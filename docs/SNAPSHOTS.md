# 더미 가격 스냅샷 저장과 멱등 처리

Node.js가 API 시작 시 읽은 것과 같은 catalog.json/prices.json의 원본 SHA-256·검색 결과를 검증한 뒤, Spring이 해당 상품의 기존 가격 근거를 Firestore Emulator에 보관한다. 실행·빌드·테스트는 아직 수행하지 않았다.

## 처리 모드

검색 화면의 더미 공급자 재확인 영역에 처리 모드를 추가했다.

- `READ_ONLY` (기본, “재확인만”): 기존 동작. Firebase 없이 Node에서 파일·검색 결과만 재확인한다.
- `STORE_SNAPSHOT` (“스냅샷 저장”): 재확인 후 에뮬레이터 저장 영수증까지 받아야 SUCCEEDED가 된다. 저장 단계 실패는 FAILED이며 캐시를 비우지 않는다.

`POST /api/v1/collection-jobs` 입력 예:

```json
{"q":"소니","categoryId":"audio","scenario":"normal","mode":"STORE_SNAPSHOT"}
```

검색어·카테고리·시나리오·모드가 같은 진행 중 작업과 완료 후 5분 작업을 재사용한다. 다른 모드는 별도 작업이다. 화면에 저장 대상 상품 수, 새로 작성한 스냅샷 문서 수, 포함된 판매처 관측/일 대표값 수와 중복 여부를 표시한다. 기존 완료 작업을 재사용하면 영수증도 그 작업의 기존 영수증이다. 이를 새로운 저장 실행으로 해석하지 않는다.

Node는 여전히 READ_ONLY 내부 응답을 반환한다. 가격 본문을 다시 보내지 않고 파일 버전 일치를 증명한다. Spring은 그 버전으로 시작 시 읽고 검증했던 동일 fixture에서 저장 내용을 구성한다. 브라우저가 임의 가격을 보내는 경로는 제공하지 않는다.

## 저장 데이터와 원자성

| 컬렉션 | 문서 ID·내용 |
| --- | --- |
| `fixturePriceSnapshots/{contentHash}` | 상품/옵션, 기존 판매처 관측 배열, 일 대표값 배열을 가진 상품별 스냅샷. 내용 해시·최초 sourceDataVersion·서버 createdAt 포함 |
| `fixtureSnapshotBatches/{keyHash}` | 요청 키의 SHA-256. payloadDigest·원본 버전·스냅샷 ID 목록·개수·서버 createdAt을 가진 저장 영수증 |

내용 해시는 수집 버전과 별개로 상품·옵션·가격·관측 시각·표본을 포함한 정렬된 JSON 내용에서 계산한다. 같은 상품 근거는 다른 검색 작업이나 파일 공백 변경 후에도 같은 스냅샷 문서를 재사용한다. 새 내용은 새 문서이며 이전 문서를 덮어쓰지 않는다. 원본 버전이 달라도 내용이 같으면 스냅샷의 최초 sourceDataVersion은 유지하고 새 영수증에서 이번 원본 버전을 기록한다.

수집 작업의 요청 키는 원본 버전과 정렬된 상품 ID 집합으로 결정한다. 기준 시각 오버라이드 자체는 가격 내용과 요청 키를 변경하지 않는다. 정확히 같은 키·내용 재전송은 `duplicate=true,newSnapshots=0`이다. 다른 키로 같은 내용을 저장해도 새 영수증만 만들고 스냅샷을 늘리지 않는다. 같은 키를 다른 유효 상품/내용에 재사용하면 409다.

Firestore 트랜잭션은 영수증과 모든 대상 스냅샷을 먼저 읽고, 새 스냅샷과 영수증을 함께 작성한다. 동시 같은 키 요청은 하나가 작성하고 다른 요청이 중복 영수증을 조회하도록 처리한다. 서로 다른 키의 겹치는 상품도 같은 내용 문서에서 충돌을 검사하여 재사용한다. 실제 에뮬레이터의 동시 요청 검증은 아래 수동 절차로 수행한다.

판매처 관측 시각·배송비 null·재고·할인·조건부 혜택은 유지한다. 관측이 있는 날짜의 일 대표값과 validSamples는 [일별 집계 규칙](DAILY-PRICES.md)에 따라 재계산한다. 관측이 없는 과거 합성 이력은 유지한다. 자동 저장 키는 fixture-snapshot-v2 접두사를 사용한다. 서버 `createdAt`은 보관 시각이며 새 가격 관측 시각이 아니다. 0원·미래 관측·상품 참조/날짜 중복 등은 기존 DataPolicy로 다시 검증한다.

한 번에 최대 20개 상품, 상품당 최대 200개 판매처 관측/90개 일 대표값, JSON 내용 256 KiB/상품·합계 4 MiB로 제한한다. empty 시나리오는 가격 문서 없이 개수 0의 영수증만 남긴다. 본문 필드 검색을 사용하지 않으므로 스냅샷 content 인덱스를 제외했다.

## 현재 조회 범위

기본 파일 모드는 시작 시 fixture 가격을 사용한다. 추가한 CATALOG_SOURCE=snapshot은 [조회 소스 안내](CATALOG-SOURCE.md)에 따라 상품/옵션별 최신 관측 묶음을 선택하여 검색·판매처·차트·분석·특가·알림에 사용한다. 여러 버전의 일 대표값을 합쳐 중복 집계하지 않는다. 모드는 API 시작 시 선택한다.

실제 Reef 수집과 실행 중 원본 파일 다시 읽기는 아직 없다. 파일의 새 더미 관측에 대한 일별 집계는 저장 단계에 연결했다. snapshot 조회 모드에서는 저장 후 조회 근거를 다시 선택하며, 파일 모드의 가격 값은 저장만으로 바뀌지 않는다. 원본 파일 변경 시 같은 파일로 API를 재시작해야 한다. 선택 근거 변경 시 상품 캐시와 분석 입력 버전이 갱신된다. 영수증과 함께 저장한 이벤트는 [저장 알림 안내](SNAPSHOT-ALERTS.md)에 따라 비동기로 평가한다. 저장 성공은 알림 평가 완료를 의미하지 않는다.

저장 영수증과 스냅샷은 공용 합성 카탈로그이므로 계정 삭제 대상이 아니다. 자동 만료는 없다. [보존·정리 안내](SNAPSHOT-RETENTION.md)의 내부 API에서 비최신 스냅샷과 오래된 완료 이벤트를 미리 확인하고 정리할 수 있다. 최신 근거·미완료 이벤트와 멱등 영수증은 유지한다. 에뮬레이터 내보내기/가져오기로 재시작 후 보존할 수 있다. 실제 클라우드 연결은 허용하지 않는다. 기존 클라이언트 Firestore 규칙은 계속 전체 거부하며 서버 Admin SDK 경로에서만 저장한다.

## 내부 API와 재전송

`POST /api/v1/internal/fixture-snapshots`에 `X-Internal-Key`를 제공한다. 현재 API에 로드된 버전/기준 시각/상품 ID와 요청 키만 받는다.

```json
{
  "idempotencyKey":"manual-demo-1",
  "dataVersion":"현재 수집 작업 result.dataVersion의 64자리 값",
  "referenceTime":"현재 수집 작업 result.referenceTime",
  "productIds":["sony-xm5-black"]
}
```

성공 응답은 `{data:{idempotencyKey,duplicate,snapshotCount,newSnapshots,offerCount,dailyPriceCount},meta}`다. snapshotCount는 포함된 상품별 스냅샷 수이고 newSnapshots만 이번 요청에서 새로 작성한 문서 수다. 내부 키 없음/오류는 403, 잘못된 목록/가격 근거는 400, 버전·기준 시각·키 충돌은 409, 에뮬레이터 오류는 503이다. 외부 배포 시 내부 경로는 기존 설계대로 공개 라우팅에서 제외한다.

작업의 저장소 오류는 `SNAPSHOT_STORE_UNAVAILABLE`, 충돌은 `SNAPSHOT_CONFLICT`, 입력 근거 오류는 `INVALID_SNAPSHOT`으로 반환한다. 저장 타임아웃 시 커밋 여부가 불확실할 수 있지만, 같은 입력을 재요청하면 동일 영수증으로 복구한다. 저장 후 응답 유실/API 재시작도 같은 요청 키를 사용한다. 메모리 작업 보관과 Firestore 영수증 보관은 별개다.

## 사용자가 직접 실행·검증

새 의존성이나 환경 변수는 없다. README와 FIREBASE.md의 Authentication/Firestore Emulator 및 기존 Spring 환경 변수를 사용한다. 저장 모드는 `FIREBASE_MODE=emulator`, `FIREBASE_PROJECT_ID=demo-prizm`, `FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099`, `FIRESTORE_EMULATOR_HOST=127.0.0.1:8085`가 필요하다. 저장 테스트 전 에뮬레이터를 먼저 실행하고, 인덱스 설정 변경을 적용하려면 에뮬레이터도 재시작한다.

별도 터미널에서 실행:

```powershell
cd D:\github\codyssey_final\backend\collector
npm start
```

API 터미널 (기존 환경 변수와 동일한 INTERNAL_API_KEY 유지):

```powershell
cd D:\github\codyssey_final\backend\api
mvn test
mvn spring-boot:run
```

프론트엔드 터미널:

```powershell
cd D:\github\codyssey_final\frontend
npm run build
npm run dev -- --host 127.0.0.1 --port 5173
```

1. 검색 화면에서 소니 검색 → “스냅샷 저장” → “재확인 후 저장”. 완료와 영수증을 확인한다. 첫 저장은 상품 스냅샷 1개·신규 1개다. 이미 보관한 근거가 있으면 신규 0개여도 정상이다.
2. 에뮬레이터 UI(`http://127.0.0.1:4000`)에서 위 두 컬렉션을 확인한다. content의 관측 시각·배송비 null·품절·일 대표값 날짜가 원본과 동일해야 한다.
3. 같은 조건을 즉시 요청하면 기존 작업을 재사용한다. 실제 재전송의 멱등성을 보려면 5분 후 새 작업을 만들거나 API만 재시작하고 같은 조건으로 저장한다. 에뮬레이터를 유지한 상태에서 duplicate=true·신규 0이고 문서 수가 늘지 않아야 한다.
4. 전체 상품으로 저장 후 소니만 별도 저장하는 등 겹치는 상품 집합을 확인한다. 영수증은 별개지만 같은 소니 내용 문서는 하나다. mode=READ_ONLY는 별도 작업이며 Firestore 문서를 추가하지 않는다.
5. API 클라이언트에서 위 내부 본문을 같은 키로 두 번 전송한다. 첫 영수증 후 두 번째는 중복이다. 키를 유지하고 productIds를 다른 유효 상품으로 바꾸면 409. 같은 키로 동시에 보내도 하나의 영수증/대상 내용만 남아야 한다.
6. empty+저장은 스냅샷 0개 영수증. Node 장애/429/미지원/버전 불일치에서는 저장되지 않아야 한다. Firestore를 내린 저장 작업은 실패하며 성공으로 표시되지 않아야 한다. 다시 실행 후 재요청하면 복구한다. “재확인만”은 에뮬레이터 없이도 동작한다.
7. 에뮬레이터 내보내기 후 재기동·가져오기 → 영수증 보존과 재전송 중복을 확인한다. 읽기 화면의 현재가/관측 시각/가격 이력이 저장만으로 바뀌지 않아야 한다.

작성한 테스트는 내부 인증·입력, 검증 실패 시 저장 차단, 시간/null 보존, 안정된 내용 해시, 같은 키 중복·충돌, 겹치는 스냅샷 재사용, 읽기 후 원자적 쓰기 순서, 저장 모드 작업 분리·저장 실패 시 완료/캐시 무효화 차단과 재시도를 확인한다. 단위 테스트는 SDK와 작업 큐를 모킹하므로 실제 Firestore 경합·보존은 위 수동 검증으로 확인한다. 테스트 실행 결과는 아직 없다.


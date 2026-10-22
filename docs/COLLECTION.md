# 상품 조회 캐시와 더미 재확인 작업

현재는 React → Spring 비동기 작업 → Node.js 로컬 HTTP 수집기 → 같은 fixture의 버전·검색 결과 검증 흐름이다. 기본 READ_ONLY는 재확인만 하며, 추가한 STORE_SNAPSHOT은 [스냅샷 저장 안내](SNAPSHOTS.md)에 따라 같은 근거를 Firestore Emulator에 보관한다. 실제 Reef 수집, 파일의 실행 중 다시 읽기, 관측 시각 변경은 하지 않는다. 조회 소스는 CATALOG_SOURCE로 선택하며 snapshot 모드의 저장 후 갱신은 [조회 소스 안내](CATALOG-SOURCE.md)를 따른다. 이 문서의 실행·테스트 명령은 사용자가 수행하며 아직 실행하지 않았다.

## 상품 조회 캐시

- 서버의 상품 요약만 캐시한다. 목록과 상세 조회가 현재가·이전가·가격 만료 표시 계산 결과를 공유한다. 판매처/가격 이력/리뷰/개인 응답 전체를 캐시하지 않는다.
- 실제 서버 시간 기준 60초, 최대 128개 LRU 항목이다. 정확히 60초부터 새로 계산하고 오류는 보관하지 않는다. 저장·반환 시 복사하여 응답 변경이 캐시를 오염시키지 않는다.
- 키는 catalog.json + prices.json 원본 바이트의 SHA-256, fixture 기준 시각, 상품 ID다. 파일 바이트를 순서대로 이어 해시하며 공백/BOM 변경도 다른 버전이다.
- Node.js 재확인 성공 시 전체 상품 요약 캐시를 비운다. 실패/버전 불일치에서는 비우지 않는다. 5분 내 완료 작업 재사용은 외부 재확인이나 캐시 비우기를 반복하지 않는다.
- 상품 상세의 `X-Product-Cache: HIT|MISS`로 확인한다. `Cache-Control: no-store`이며 브라우저 응답 캐시를 사용하는 기능은 아니다. 응답의 requestId는 항상 요청마다 새로 생성한다. 캐시는 관측 시각·fixture 기준 시각·분석 캐시를 변경하지 않는다.

## 수집 작업 API

`POST /api/v1/collection-jobs`:

```json
{"q":"소니","categoryId":"audio","scenario":"normal"}
```

HTTP 202, `Location`, `Retry-After: 1`, `{data:{jobId,status,...},meta}`를 반환한다. q는 필수이며 빈 문자열(전체 조회)을 허용하고 100자 이하, categoryId는 선택이며 영문·숫자·밑줄·하이픈 최대 128자다. scenario는 normal(기본), empty, error, rate-limit, unsupported 중 하나다.

`GET /api/v1/collection-jobs/{jobId}`는 HTTP 200으로 작업을 조회한다. 처리 실패는 HTTP 요청 실패와 구분하여 `status=FAILED`, `error={code,message,retryable}`로 반환한다. 형식이 잘못된 ID는 400, 없는 UUID/재시작 전 작업/보관 기간 경과는 404다.

| 상태 | 의미 |
| --- | --- |
| QUEUED | 대기 중 |
| RUNNING | Node 내부 요청 진행 중 |
| SUCCEEDED | 같은 fixture 버전/기준 시각과 검색 상품 ID를 검증함 |
| FAILED | 연결, 설정, 공급자 시나리오, 버전 또는 결과 검증 실패 |
| EXPIRED | 성공 후 5분 재사용 기간 경과. 결과는 null이며 다시 요청 가능 |

검색어 양끝 공백 제거·소문자 변환 후 같은 검색어/카테고리/시나리오/처리 모드 요청을 병합한다. 진행 중 작업과 성공 후 실제 시간 5분 동안은 같은 작업을 반환한다. 실패 요청은 다시 POST하면 새 작업이다. 완료 작업은 1시간 보관 후 제거한다. 최대 200개 작업, 실행 2개, 대기열 16개이며 한도 초과는 HTTP 429다. 자동 재시도는 없고 사용자가 다시 요청한다. Node 연결 제한은 2초, 요청 제한은 6초다.

처리 모드는 `READ_ONLY`(기본) 또는 `STORE_SNAPSHOT`이며, 결과에는 `{productIds,dataVersion,referenceTime}`를 표시한다. 빈 검색 결과도 성공이다. empty 시나리오는 재확인 결과만 비우고 원래 상품 목록이나 가격을 변경하지 않는다. 기존 `/internal/fixture-batches` 이벤트 CLI도 가격 저장과 분리하여 유지한다.

작업 저장소와 캐시는 API 프로세스 메모리다. API 재시작으로 초기화하며 공유 작업 저장·복구·취소는 구현하지 않았다. 상태 조회를 중단해도 서버 작업은 계속될 수 있다. 현재 공개 로컬 fixture 작업이므로 사용자 소유권을 제공하지 않는다. 실제 공급자/유료 수집 연결 전 인증·사용자별 한도·작업 소유권·공유 저장소가 필요하다.

## Node 수집기 실행

Node.js 22.12 이상, 외부 패키지 설치 없음. 별도 터미널에서:

```powershell
cd D:\github\codyssey_final\backend\collector
# .env가 없을 때만 복사
Copy-Item .env.example .env
```

기존 `.env`가 있으면 복사를 생략한다. `.env`의 `INTERNAL_API_KEY`를 Spring/FastAPI와 같게 설정하고 실행한다.

```powershell
npm start
```

127.0.0.1:4001에서 실행한다. `.env`를 수정했다면 수집기를 재시작한다. 기본 fixture는 저장소 루트의 fixtures이며 경로는 실행 위치에 영향받지 않는다. `FIXTURE_DIR`를 추가 설정하면 수집기 실행 위치 기준 경로 또는 절대 경로를 사용한다. API의 FIXTURE_DIR와 같은 실제 파일을 가리켜야 한다. 설정을 사용자 환경에 맞춰 변경하지 않은 채 다른 폴더의 fixture를 섞으면 버전 불일치가 된다.

`GET http://127.0.0.1:4001/health`는 상태만 반환한다. Spring은 `POST /internal/collect`에 X-Internal-Key를 사용한다. 이 경로를 브라우저 프록시에 추가하지 않는다. 내부 계약은 `backend/collector/openapi.json`이다. 허용 JSON 본문은 4096바이트까지이며 리다이렉트/외부 호스트로 수집 요청을 전송하지 않는다.

Spring API 터미널 (기존 Firebase/분석 환경 변수 유지):

```powershell
cd D:\github\codyssey_final\backend\api
$env:COLLECTOR_URL = 'http://127.0.0.1:4001'
$env:INTERNAL_API_KEY = 'your-local-development-key'
mvn test
mvn spring-boot:run
```

Spring은 .env를 자동 로딩하지 않는다. COLLECTOR_URL 기본값은 위 주소이므로 변경하지 않았다면 생략 가능하다. 기존 React 실행 명령은 그대로다. Node를 실행하지 않아도 기존 검색·상세는 동작하며 재확인 작업만 연결 오류로 실패한다. STORE_SNAPSHOT에는 Firebase Emulator도 필요하다.

## 직접 검증

1. Node와 API를 실행하고 검색 화면에서 “소니” 검색 → 더미 공급자 재확인 요청. 대기/진행 후 성공과 상품 수를 확인한다. UI는 1~5초 간격으로 최대 45초 조회하며, 검색 조건 변경·상세 진입·화면 이탈 시 상태 조회를 중단한다. 성공 후 기존 검색 조건으로 상품 목록을 다시 조회한다. 빠른 작업은 중간 상태가 화면에 표시되기 전에 완료될 수 있다.
2. 같은 조건으로 두 번 요청해 같은 jobId와 재사용 표시를 확인한다. 완료 후 5분이 지나면 EXPIRED, 새 요청은 새 jobId다. 소문자/양끝 공백 차이는 같은 조건으로 처리한다.
3. empty/error/rate-limit/unsupported를 각각 선택한다. 빈 결과는 성공, 나머지는 FAILED와 각각 COLLECTOR_UNAVAILABLE/PROVIDER_RATE_LIMITED/PROVIDER_UNSUPPORTED 오류다. 재요청 시 새 작업이며 error.retryable을 확인한다.
4. Node를 종료하고 요청 → 처리 실패. 다시 실행 후 재요청 → 성공. 내부 키가 다른 경우 COLLECTOR_CONFIGURATION. 원문 서버 오류/키/파일 경로가 화면에 노출되지 않아야 한다.
5. API 시작 후 원본 fixture의 공백만 수정하고 재확인 → FIXTURE_MISMATCH. 같은 파일로 API를 재시작하면 버전이 일치한다. 관측 값이나 날짜를 변경할 필요 없다. API는 파일을 실행 중 다시 읽지 않는다. `FIXTURE_REFERENCE_TIME`만 변경해도 Node는 API의 검증 기준 시각을 사용하므로 원본 관측 시각을 새로 쓰지 않는다.
6. 상품 상세 API를 직접 두 번 조회해 최초 MISS/다음 HIT를 확인한다. 이미 목록을 조회했다면 최초 상세도 HIT일 수 있다. 60초 후 직접 조회는 MISS다. 성공한 새 재확인 작업 후 다음 직접 상세 조회도 MISS지만 UI의 목록 재조회가 먼저 실행되면 HIT일 수 있다. 두 응답의 X-Request-Id와 본문 meta.requestId가 각 요청에서 일치하고 서로 다른 요청에서는 달라야 한다.
7. API 재시작 → 이전 작업 조회는 404. 빈 검색 결과와 작업 처리 실패가 서로 다른 화면 상태인지 확인한다.

단위 테스트 명령 (직접 수행):

```powershell
cd D:\github\codyssey_final\backend\collector
npm test
```

Node 테스트는 테스트 실행 시에만 임시 로컬 포트를 연다. Spring `mvn test`는 모킹한 HTTP 응답/작업 큐/시계를 사용하므로 Node 서버가 없어도 된다. 새 테스트는 캐시 복사·TTL·버전·삭제·한도, 작업 병합·실패 재시도·정리·대기열, 수집기 인증·입력·원문 오류 비노출·파일 버전, 공개 API/OpenAPI 계약을 포함한다. 프론트 빌드 확인은 `frontend/`에서 `npm run build`를 직접 수행한다.




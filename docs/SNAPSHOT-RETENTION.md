# 로컬 가격 스냅샷·완료 이벤트 보존과 정리

코드·테스트 소스·명세를 작성했다. 설치·서버 실행·빌드·테스트·실제 삭제는 수행하지 않았다. 이 API는 기존 FirebaseAccess를 통해 demo- 프로젝트의 로컬 Auth/Firestore Emulator만 사용한다. 자동 삭제 스케줄이나 Firestore TTL은 설정하지 않았다.

## 보존 정책

| 기록 | 삭제 후보 | 보호 조건 |
| --- | --- | --- |
| fixturePriceSnapshots | 생성 후 30일을 초과한 비최신 스냅샷 | 상품 ID·옵션별 최신 근거, 미완료 이벤트 대상 상품의 모든 근거, createdAt 누락 |
| snapshotAlertEvents | DONE 전환의 updatedAt으로부터 7일 초과 | PENDING·PROCESSING·RETRYING, token/availableAt 필드 잔존, updatedAt 누락 |
| fixtureSnapshotBatches | 삭제하지 않음 | 멱등 키·payloadDigest·영수증 개수와 기존 참조 유지 |
| users 및 Authentication | 이 정리에서 접근하지 않음 | 계정 삭제는 기존 PERSONAL-DATA.md 절차 |

보존 나이는 고정 fixture 관측 시각이 아닌 실제 서버 UTC 당일 00:00을 기준으로 계산한다. 정확히 30일·7일 경계는 남기므로 실제 나이는 보존 기간보다 최대 약 하루 길 수 있다. evaluatedAt은 이 기준 시각이다. 오래된 최신 가격 근거도 자동 삭제하지 않으며 가격 만료 표시는 별도 조회 규칙을 따른다.

전체 스냅샷을 기존 SnapshotSelection으로 검증하여 상품 ID·옵션별 최신 1개를 보호한다. 현재 카탈로그에서 사용하지 않는 과거 옵션의 최신 근거도 남긴다. 내용 해시·금액·관측 시각·일별 집계·카테고리 검증에 실패하면 정리 전체를 거부한다. 데이터가 잘못됐다는 이유로 삭제 후보로 삼지 않는다. 미래 관측 또는 알 수 없는 과거 카테고리는 운영자가 별도로 백업·점검해야 한다.

이벤트는 원시 관측 자체를 참조하지 않고 최신 선택 가격을 평가하므로 미완료 이벤트의 상품 전체를 보수적으로 보호한다. 임대가 끝난 PROCESSING도 정리하지 않는다. 이벤트 워커가 복구해 DONE으로 끝내야 7일 뒤 후보가 된다.

영수증의 snapshotIds는 과거 저장 감사용 식별자다. 해당 가격 본문이 정리되어도 같은 멱등 키 재전송은 기존 duplicate 영수증을 돌려주고 삭제된 근거·완료 이벤트를 재생성하지 않는다. 같은 내용을 다시 보관하려면 새 멱등 키가 필요하다. 영수증은 계속 증가하므로 크기 축소·별도 보관은 후속 범위다. 현재 API는 영수증을 일괄 조회하지 않는다.

## 내부 API

`POST /api/v1/internal/snapshot-retention`, `X-Internal-Key` 필요. 공개 라우팅에서 내부 경로를 제외한다. `{}` 또는 `{"dryRun":true}`는 읽기만 한다. 실제 적용은 `{"dryRun":false,"planId":"직전 계획의 64자리 해시"}`다.

응답 data에는 dryRun, planId, evaluatedAt, snapshotsScanned, eventsScanned, snapshotIds, eventIds, remainingCandidates가 있다. 두 ID 배열 합계는 최대 100개이며 ID순으로 스냅샷을 먼저 처리한다. 나머지가 있으면 다시 dryRun으로 다음 계획을 확인한다. 사용자 정보·키·가격 본문은 반환하지 않는다.

적용 시 같은 트랜잭션에서 두 컬렉션을 다시 읽고 검증한 후 삭제한다. 후보가 달라지거나 UTC 날짜가 바뀌면 409이고 새 계획을 확인해야 한다. 미리보기 후 생성된 스냅샷·미완료 이벤트도 적용 시 재검토한다. 이미 읽은 조회 뷰는 메모리에 고정된 내용이며, 최신 근거를 남기므로 다음 갱신에서도 사용할 수 있다. 동시 쓰기·쿼리 경합은 에뮬레이터에서 별도 검증한다.

요청 오류 400, 내부 키 오류 403, 계획 변경·데이터 검증·한도 초과 409, 저장소 장애 503이다. 한 요청은 스냅샷 최대 1000개·이벤트 500개·가격 내용 합계 8MiB·문서별 256KiB를 지원한다. 상한 초과는 일부만 골라 삭제하지 않고 중단한다. 대규모 데이터 유지보수·영수증 정리·분산 운영 자동화는 후속 작업이다. 503/연결 중단이면 커밋 여부가 불확실할 수 있으므로 dryRun으로 현재 상태를 다시 확인한다.

## 실행과 검증 (사용자 직접 수행)

1. 서버를 멈추기 전에 작업이 끝났는지 확인한다. FIREBASE.md의 --export-on-exit로 에뮬레이터를 정상 종료하고, 내보낸 firebase/emulator-data 폴더를 별도 로컬 위치에 복사한다. 백업에는 계정·개인 데이터가 포함되므로 Git에 넣지 않는다. 복구는 서버와 에뮬레이터를 종료한 상태에서 백업을 별도 import 경로로 지정하는 기존 --import 절차를 따른다. 복구 시 백업 이후의 변경은 포함되지 않는다.
2. 에뮬레이터·API를 기존 환경 변수로 재시작한다. 아래 요청의 INTERNAL_API_KEY는 API에 적용한 값과 같아야 한다. Spring은 .env를 자동으로 읽지 않는다. 요청은 사용자가 직접 수행한다.

```powershell
$headers = @{ 'X-Internal-Key' = $env:INTERNAL_API_KEY }
$uri = 'http://127.0.0.1:8080/api/v1/internal/snapshot-retention'
$preview = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -ContentType 'application/json' -Body '{}'
$preview.data
```

3. 기본 데이터는 최근 저장이므로 삭제 후보가 0개여도 정상이다. 검증용 에뮬레이터에서 같은 상품의 관측 시각이 다른 스냅샷 2개를 만든다. ALERTS.md의 fixture 변경 절차처럼 기준 시각도 함께 전진시키고 API·Node를 재시작한 뒤 각각 저장한다. 알림 이벤트들이 DONE이 될 때까지 기다린다.
4. Emulator UI에서 두 스냅샷의 createdAt을 Timestamp 타입의 2020-01-01T00:00:00Z로 변경한다. DONE 이벤트의 updatedAt도 같은 과거 Timestamp로 변경한다. 가격 content·내용 해시·관측 시각은 수정하지 않는다. dryRun에서 오래된 비최신 스냅샷과 DONE 이벤트만 후보인지 확인한다. 최신 근거는 과거 생성 시각이어도 남아야 한다.
5. 별도 실험으로 이벤트를 RETRYING 상태로 두면 해당 상품의 모든 스냅샷이 보호되어야 한다. token 또는 availableAt이 남은 DONE 이벤트, 타임스탬프가 없는 문서도 삭제되면 안 된다. 워커를 잠시 비활성화하여 상태 변경과 경합하지 않게 한다. 고의 손상은 검증용 데이터에서만 수행한다.
6. 후보 목록을 확인한 후 아래를 실행한다. 실제 삭제 명령이며 백업한 검증용 데이터에서 수행한다.

```powershell
$body = @{ dryRun = $false; planId = $preview.data.planId } | ConvertTo-Json
$applied = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -ContentType 'application/json' -Body $body
$applied.data
```

7. 다시 dryRun으로 남은 후보를 확인한다. 같은 적용 요청을 반복하면 후보 변경으로 409일 수 있다. 삭제 전 영수증 키 재전송이 duplicate를 반환하고 이벤트를 재생성하지 않는지 확인한다. 상품 조회·차트·알림 재조회와 API 재시작 후에도 최신 근거가 유지되어야 한다.
8. 미리보기 이후 보호 조건을 바꿔 후보를 변경하면 이전 planId 적용은 409여야 한다. 잘못된 내부 키, 문자열 dryRun, planId 없는 삭제도 거부해야 한다. 무결성 오류나 조회 한도 초과에서는 삭제가 없어야 한다.

테스트 소스에는 기간 경계·최신/미완료 보호·100개 제한·기본 미리보기·인증/입력·계획 불일치·전체 읽기 후 삭제 순서·영수증 무접근을 추가했다. 실제 SDK 경합·중단 복구는 위 수동 절차로 확인한다.

```powershell
cd D:\github\codyssey_final\backend\api
mvn test
```

테스트 실행 결과는 아직 없다.

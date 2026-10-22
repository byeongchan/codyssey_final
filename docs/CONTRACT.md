# 1차 fixture 계약

> 실제 상세 확장 계약 0.22.0: 공개 POST `/danawa/price_history` 및 인증 POST `/me/danawa/review-summary/{productId}`를 추가했다. 후자는 직접 입력 리뷰만 분석하며 자동 수집이 아니다. [상세 계약·제한·검증 안내](DETAIL-INSIGHTS.md)를 참고한다.

이 문서는 현재 구현된 범위만 설명한다. PRD의 전체 API는 목표이며 아직 전부 구현되지 않았다.

## 데이터

- `fixtures/catalog.json`: schemaVersion=1, 기준 시각, 카테고리, 옵션별 상품.
- `fixtures/prices.json`: 명시적인 일별 합성 가격과 판매처 fixture. 실행 중 임의 생성하지 않는다.
- 기준 시각: `2026-10-22T03:00:00Z` (한국 시각 12시). 모든 가격은 KRW 정수다.
- 상품 ID는 옵션을 구분하는 문자열이며 서로 다른 옵션을 합치지 않는다.
- 파일의 일별 샘플은 확정 비교가의 예시 최저값이다. 30일·3일·품절 후 과거 이력 시나리오가 있다. snapshot 저장에서는 [일별 집계 규칙](DAILY-PRICES.md)에 따라 관측이 있는 한국 날짜의 최저값·유효 관측 수를 재계산한다. 합성 리뷰는 `fixtures/reviews.json`에 분리하고 [리뷰·특가 계약](DISCOVERY.md)의 표본/지원/만료 기준을 적용한다.
- fixture 전체가 합성 데이터다. `source=fixture`, `synthetic=true`, 고정 referenceTime을 제공한다. 실데이터로 이관하지 않는다.

## 공개 API

기본 경로 `/api/v1`. 성공 단일 응답 `{data,meta}`, 목록 `{items,nextCursor,meta}`, 오류 `{error:{code,message,requestId,retryable}}`.

기계 판독용 명세 원본은 `backend/api/src/main/resources/static/openapi.json`이다. API 실행 후 `http://127.0.0.1:8080/openapi.json`에서 동일한 파일을 제공한다. 자동 생성 명세가 아니므로 API 변경 시 함께 수정한다. 명세 형식은 [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)을 따른다. FastAPI 명세는 `http://127.0.0.1:8000/openapi.json`, 문서 화면은 `/docs`다.

| GET 경로 | 현재 동작 |
| --- | --- |
| `/health` | API 기동 상태. 다른 서비스 연결 상태를 뜻하지 않는다. |
| `/categories` | 3개 카테고리 |
| `/products` | q 부분 검색, categoryId 필터, sort=title/priceAsc, cursor=0 이상 오프셋 문자열, limit=1~50 |
| `/products?scenario=empty` | 빈 목록 |
| `/products?scenario=error` | HTTP 503 더미 공급자 장애 |
| `/products/{id}` | 상품별 옵션·현재 확정가 |
| `/products/{id}/offers` | 품절·배송비 미확인 가격은 comparablePrice=null, 확정가 우선 정렬 |
| `/products/{id}/prices?days=90` | days=1~90, 실제 fixture가 존재하는 날짜만 오름차순 반환 |
| `/products/{id}/analysis` | 유효한 완료 캐시만 조회. 미생성·만료·입력 버전 불일치는 404, FastAPI 호출 없음 |
| `/analysis-jobs/{jobId}` | 작업 상태와 완료 결과. 기록이 없거나 보관 기간 경과 시 404 |
| `/collection-jobs/{jobId}` | 읽기 전용 Node fixture 재확인 상태. 작업 실패는 status=FAILED, 보관 만료는 404 |

`POST /collection-jobs`의 입력·202/작업 상태·실패·중복 병합·기본 재확인 모드와 상품 요약 캐시는 [수집 작업·캐시 계약](COLLECTION.md)을 따른다. Node 내부 OpenAPI는 `backend/collector/openapi.json`이며 Spring 공개 명세와 분리한다. 선택적인 STORE_SNAPSHOT과 내부 `/internal/fixture-snapshots`는 [스냅샷 계약](SNAPSHOTS.md)에 따라 같은 가격 근거를 Firestore Emulator에 원자적으로 보관한다. 기본 조회는 파일이며, 선택적 CATALOG_SOURCE=snapshot의 상태/갱신·선택·무결성·일관성 계약은 [조회 소스 안내](CATALOG-SOURCE.md)를 따른다.

`POST /products/{id}/analysis-jobs`는 HTTP 202와 `{data:{jobId,status,pollAfterSeconds,...},meta}`를 반환한다. `Location`은 작업 조회 경로이며 `Retry-After`는 1초다. 유효한 완료 캐시를 재사용해도 202로 통일한다. 분석 관련 응답은 `Cache-Control: no-store`를 사용하며 캐시는 서버에서만 관리한다.

인증·찜·목표가 설정·인앱 알림은 로컬 Firebase 에뮬레이터에 연결했다. 상품은 초기 4개 샘플을 대상으로 하며 상품 목록의 다음 페이지 UI는 미구현이다. 더미 상품별 대화는 [채팅 계약](CHAT.md)에 따라 본인 조회·전송·삭제와 요청 한도를 제공한다. 합성 리뷰 집계·평균 대비 특가는 [리뷰·특가 계약](DISCOVERY.md)에 따라 제공한다. 외부 푸시 API와 실제 Firebase 클라우드 연결은 아직 없다. 분석 생성·작업 상태·캐시 조회는 로그인 UID별로 분리하고 POST는 사용자당 60초/5회로 제한한다. 상세는 [분석 접근 안내](ANALYSIS-ACCESS.md)를 따른다. 유료 모델 비용 예산과 분산 작업 복구는 아직 없다.

## 계정·찜 API

아래 모든 API는 `Authorization: Bearer <Firebase ID token>`이 필요하다. UID를 쿼리·본문·경로로 받지 않고 검증된 토큰에서만 결정한다. 현재 토큰은 Authentication Emulator가 발행한 것이어야 하며 서버의 demo 프로젝트와 일치해야 한다.

| 경로 | 동작 |
| --- | --- |
| `GET /me` | `{data:{uid,email},meta}`. email은 null 가능 |
| `GET /me/wishlist?cursor=&limit=20` | `{items:[{productId,createdAt}],nextCursor,meta}`. 문서 ID순, 최대 50개 |
| `GET /me/wishlist/{id}` | `{data:{productId,saved},meta}`. 미등록은 saved=false |
| `PUT /me/wishlist/{id}` | 찜 등록. 본문 없음. 카탈로그 상품이어야 하며 없으면 404. 반복 등록에도 최초 createdAt 유지 |
| `DELETE /me/wishlist/{id}` | 찜 해제. 미등록·판매 종료 상품도 saved=false로 멱등 성공 |

상품 ID와 비어 있지 않은 커서는 `[A-Za-z0-9_-]{1,128}` 형식이다. Firestore 저장 경로는 `users/{verifiedUid}/wishlist/{productId}`다. 목록에 저장한 상품이 현재 카탈로그에서 없어져도 해제할 수 있다. createdAt은 서버 타임스탬프이며 기존 데이터에 없다면 null이다. 기본 프론트엔드에는 상세 화면의 찜 버튼과 헤더 하트의 목록 화면을 제공한다.

개인 데이터의 meta.source는 `firebase-emulator`, synthetic=false, referenceTime은 실제 UTC 현재 시각이며 updatedAt=null이다. 개별 항목 생성 시각은 createdAt으로 확인한다. 상품·분석의 source=fixture·synthetic=true와 구분한다. 보호된 응답은 Cache-Control: no-store, Vary: Authorization을 제공한다.

인증 헤더 없음·변조·만료·폐기·비활성 계정은 401이고 WWW-Authenticate: Bearer를 반환한다. 인증 서비스 장애/미설정이나 Firestore 장애는 503이다. 허용하지 않는 UID 형식은 403이다. 웹 SDK는 토큰을 갱신하며 보호된 API가 401이면 강제 갱신 후 한 번만 재시도한다. 요청 중 계정이 변경되면 새 계정의 토큰으로 변경 요청을 재전송하지 않는다.

FirebaseAccess는 emulator 모드, demo- 프로젝트, 고정 loopback 에뮬레이터 주소를 모두 확인하며 실제 서비스 계정/ADC로 대체하지 않는다. Firestore 클라이언트 접근 규칙은 모두 거부하고 Admin SDK 경로만 사용한다. SDK의 서버 접근은 규칙을 우회하므로 매 요청의 ID 토큰 검증과 UID 고정이 필요하다. 현재는 ID 토큰 검증 시 폐기·비활성 확인을 활성화했다.

설치·실행·계정 전환·격리 검증 방법은 `docs/FIREBASE.md`를 따른다. 최근 기록·계정 삭제 계약과 제한 사항은 [개인 데이터 안내](PERSONAL-DATA.md)를 따른다. 소셜 로그인·비밀번호 재설정·다른 기기의 실시간 알림은 미구현이다.

## 목표가·인앱 알림 API

모든 경로는 `/api/v1` 아래이며 위 계정 API와 동일한 ID 토큰·검증 UID·no-store 계약을 사용한다. alertId는 productId와 같고 사용자별 상품당 설정 하나를 둔다.

| 메서드·경로 | 동작 |
| --- | --- |
| `GET /me/alerts?cursor=&limit=20` | 설정 목록·ID 오름차순. 최대 50개 |
| `GET /me/alerts/{id}` | 본인 설정. 미등록은 404 |
| `POST /me/alerts` | `{productId,targetPrice,enabled?}` 등록/교체. enabled 기본 true, 성공 200. 등록 직후 평가 |
| `PATCH /me/alerts/{id}` | `{targetPrice?,enabled?}` 부분 수정. 하나 이상 필요. 같은 설정은 판정 상태 유지. 성공 200 |
| `DELETE /me/alerts/{id}` | 설정 삭제. 미등록도 `{data:{productId,deleted:true},meta}`로 성공 |
| `POST /me/alerts/evaluate?cursor=&limit=20` | 본인 설정 한 페이지 재평가. `{data:{checked,notified,nextCursor},meta}`. checked는 읽은 설정 수, notified는 이 요청에서 만든 알림 수 |
| `GET /me/notifications?cursor=&limit=20` | 본인 알림·createdAt 내림차순. 최대 50개 |
| `PATCH /me/notifications/{id}` | `{read:true}`만 허용. 최초 readAt 유지. `{data:{id,read:true},meta}`. 타인/미존재 알림은 404 |

설정 필드: id, productId, targetPrice, enabled, createdAt, updatedAt, lastConditionMet, lastNotifiedAt. timestamp 필드는 데이터가 없으면 null이다. 목표가는 정수 KRW 1~1,000,000,000,000원이며 문자열·소수·음수·0은 400이다. POST 상품 미존재는 404, PATCH 설정 미존재는 404다. ID·커서는 `[A-Za-z0-9_-]{1,128}` 형식이다.

알림 필드: id, productId, alertId, targetPrice, observedPrice, observedAt, createdAt, readAt, synthetic=true. 관측 시각은 fixture 기준이며 생성/읽음 시각은 실제 서버 시각이다. 개인 데이터 meta.synthetic=false와 알림의 합성 가격 근거 synthetic=true를 구분한다. 오래된 알림은 생성 당시 가격 스냅샷이며 현재 가격을 보장하지 않는다.

`users/{uid}/alerts/{productId}`에 설정과 마지막 유효 판정 상태를 보존한다. 목표가 이하 최초 충족과 미충족→충족 전환에서만 `users/{uid}/notifications/{generation-sequence}`를 생성한다. 알림 생성과 sequence·lastConditionMet·lastObservedAt·lastNotifiedAt 변경은 같은 Firestore 트랜잭션이다. 동시 실행은 문서 충돌 시 재평가하며 외부 메시지 전송을 트랜잭션 안에서 수행하지 않는다.

품절·미확인 배송비·만료/미래 시각·상품 없음은 판정 보류이며 기존 충족 상태를 보존한다. 같은/이전 관측 시각은 무시한다. 설정이 실제 변경되면 마지막 관측/충족 상태를 초기화하고 다시 평가한다. 동일 설정 재시도는 초기화하지 않는다. 삭제 후 재등록은 새 generation을 사용하여 과거 알림 ID와 충돌하지 않는다. 과거 알림은 설정 삭제 이후에도 조회/읽음 처리할 수 있다.

등록/수정의 설정 저장과 그 직후 평가는 별도 단계다. 평가 중 장애로 503을 반환해도 설정은 저장되었을 수 있으므로 재조회·멱등 재시도로 복구한다. 가격은 등록/변경 직후·본인 수동 평가·선택적인 서버 점검에서 확인한다. 자동 점검은 기본 false이며 `ALERT_SCHEDULER_ENABLED=true`일 때 시작 10초 후, 완료 기준 60초마다 활성 설정 100개씩 페이지 순회한다. 실제 가격 수집 이벤트와의 연결은 아직 없다.

설정·자동 점검·동시성/가격 전환 검증 절차는 `docs/ALERTS.md`를 따른다. 이메일·외부 푸시·클라우드 분산 큐는 미구현이다.

## 비동기 작업과 캐시

- 상태: `QUEUED` → `RUNNING` → `SUCCEEDED` 또는 `FAILED`. 조회 시 입력 버전이나 유효 기간이 달라지면 `EXPIRED`로 반환한다.
- 공통 필드: `jobId`, `productId`, `dataVersion`, `status`, `pollAfterSeconds`, `cacheHit`, `reused`, `createdAt`, `startedAt`, `finishedAt`, `analysis`, `error`.
- 작업의 접수·완료 시각은 실제 UTC 시간이다. 분석의 generatedAt과 근거 만료 판정은 고정 fixture 기준 시각을 사용한다. 캐시 보관 TTL은 실제 시간 15분이다.
- 같은 입력의 대기/실행 작업은 같은 jobId로 병합한다 (`reused=true`, `cacheHit=false`). 완료 결과는 같은 jobId로 재사용한다 (`reused=true`, `cacheHit=true`). 이 두 필드는 POST 요청의 재사용 여부다.
- 성공 결과만 캐시한다. 판단 보류도 정상 결과이며 동일한 근거에서 재사용할 수 있다. 실패한 작업에 다시 POST하면 새 jobId를 만든다. 이전 실패 작업은 보관 기간 동안 조회 가능하다.
- 키는 상품·옵션·판매처·가격·관측 시각·가격 이력·fixture 기준 시각·모델·분석 버전의 SHA-256이다. 현재 설명 모델/통합 버전은 fixture-no-gemini-call/fixture-v3이며 price-rules-v1과 explanation-v1도 키에 포함한다. 기본 설명 모델은 server-price-rules다. 실연동 모델은 키와 설정을 함께 확장해야 한다.
- 작업 계산이 끝날 때도 입력 버전을 재확인한다. 분석 중 근거가 바뀌면 결과를 저장하지 않고 FAILED로 처리한다.
- 캐시 TTL 또는 분석 expiresAt 경과 시 재사용하지 않는다. 과거 작업 조회는 EXPIRED·analysis=null을 반환해 만료된 구매 판단을 노출하지 않는다. GET은 자동 재생성하지 않는다.
- 작업 실패 응답은 HTTP 200에 `status=FAILED`, `error={code:ANALYSIS_FAILED,message,retryable:true}`다. 새 작업 POST를 통해 재시도한다. 만료 상태는 `ANALYSIS_EXPIRED`를 제공한다. 작업 접수 한도 초과는 HTTP 429다.
- 실행 스레드 2개·대기열 16개·최대 기록 1,000개. 완료/실패 후 1시간이 지나면 요청 시 정리한다. 메모리 저장이므로 재시작 시 모든 작업과 캐시는 사라진다. 사용자 소유권은 UID별로 분리한다. 분산 작업 저장·재시작 복구는 아직 없다.
- React는 완료/실패/만료 시 폴링을 중단하고, 화면 이동 시 타이머와 진행 중 네트워크 요청을 취소한다. 서버에 접수된 작업은 계속 진행하며 화면의 약 60초 대기 제한 후 재확인할 수 있다.

## 내부 API

모든 내부 요청은 환경 변수로 설정한 `X-Internal-Key`가 필요하다. Spring API의 `/api/v1/internal/fixture-batches`는 POST로 `{source:'fixture',referenceTime,idempotencyKey}`를 받는다. 시작 시 읽은 기준 시각과 일치해야 하며, 동일 키는 메모리 맵에서 원자적으로 중복 판정한다. 최대 1,000개 이벤트를 수신하며 재시작 시 초기화된다. 실제 상품을 수집·저장하는 API가 아니다.

FastAPI의 `POST /internal/analyze` 입력:

```json
{
  "productId": "sony-xm5-black",
  "observedDays": 30,
  "currentPrice": 384900,
  "averagePrice": 399400,
  "referenceTime": "2026-10-22T03:00:00Z",
  "verdict": "BUY",
  "reasonCode": "BELOW_AVERAGE",
  "ruleVersion": "price-rules-v1",
  "explanationVersion": "explanation-v1",
  "evidenceSnapshotId": "fixture:분석입력해시"
}
```

내부 출력은 productId, ruleVersion, explanationVersion, reasons, risks, generatedAt, model, evidenceSnapshotId, synthetic뿐이다. FastAPI는 판정·금액·관측 일수를 출력하지 않는다. Spring이 판단·구조화 수치·만료를 소유하며 설명 검증 실패 시 서버 기본 설명을 사용한다. FastAPI OpenAPI의 내부 키 보안과 403·422·500 공통 오류 형식은 유지한다. 상세 입력·검증·대체 동작은 [서버 판단·설명 계약](DECISION-EXPLANATION.md)을 따른다.

## 오류·요청 ID 계약

Spring과 FastAPI는 HTTP 오류에서 다음 구조를 사용한다. 구현에는 [FastAPI 공식 예외 처리 방식](https://fastapi.tiangolo.com/tutorial/handling-errors/)을 적용했다.

```json
{
  "error": {
    "code": "HTTP_400",
    "message": "요청 형식을 확인하세요",
    "requestId": "ccfe070b-9284-450c-b278-86d42cbe0101",
    "retryable": false
  }
}
```

| 상황 | Spring | FastAPI 내부 분석 |
| --- | --- | --- |
| 잘못된 파라미터·본문 | 400 / HTTP_400 | 422 / VALIDATION_ERROR |
| 개인 API 미로그인·유효하지 않은 토큰 | 401 / HTTP_401, WWW-Authenticate: Bearer | 해당 없음 |
| 내부 키 없음·불일치 | 403 / HTTP_403 | 403 / HTTP_403 |
| 리소스·API 없음 | 404 / HTTP_404 | 404 / HTTP_404 |
| 메서드 불일치 | 405 / HTTP_405, Allow 헤더 유지 | 405 / HTTP_405, Allow 헤더 유지 |
| 지원하지 않는 Accept | 406 / HTTP_406 | 별도 Accept 협상 미구현 |
| 지원하지 않는 요청 Content-Type | 415 / HTTP_415 | 기본 본문 해석/검증 경로를 따름 |
| 요청·대기열 한도 | 429 / HTTP_429, Retry-After: 5 | 현재 한도 API 미구현 |
| 예상하지 못한 오류 | 500 / HTTP_500 | 500 / HTTP_500 |
| 더미 공급자 장애 시나리오 | 503 / HTTP_503 | 해당 없음 |

- retryable은 429 또는 5xx이면 true다. 자동 재시도를 수행하라는 의미는 아니며, 입력/서비스 상태를 확인한 뒤 재요청한다.
- Spring은 요청마다 UUID를 생성하며 클라이언트가 보낸 ID를 신뢰하지 않는다. 성공 meta.requestId 또는 오류 error.requestId와 `X-Request-Id` 응답 헤더가 일치한다.
- FastAPI도 응답 헤더와 오류 본문 ID를 맞춘다. 내부 호출에서 전달된 유효한 UUID 헤더는 재사용하고, 없거나 형식이 다르면 새 UUID를 만든다. 현재 Spring 비동기 작업은 별도 스레드에서 실행되어 원래 접수 요청 ID와 내부 AI 요청 ID는 자동 연결하지 않는다. 작업 추적에는 jobId를 사용한다.
- 오류 응답에는 Cache-Control: no-store를 제공한다. 원본 요청 본문·비밀 헤더·스택·예외 메시지를 응답으로 반사하지 않는다.
- 작업 조회의 HTTP 200 + FAILED/EXPIRED는 HTTP 전송 오류가 아니다. `data.error`는 code·message·retryable만 포함하고, 조회 요청 ID는 meta에서 확인한다. 재시도는 GET 폴링이 아닌 새 POST로 수행한다.
- 서블릿 컨테이너가 애플리케이션에 전달하기 전에 거부하는 잘못된 HTTP 요청까지 JSON 형식을 보장하지는 않는다. 기동 시 fixture 검증 실패도 HTTP 오류가 아닌 시작 로그로 확인한다.

price-rules-v1 규칙: 만료·관측 7일 미만·확정가 없음·평균가 없음은 판단 보류. 그 외 현재가가 평균가의 0.98배 이하이면 BUY, 1.02배 이상이면 WAIT, 나머지는 NEUTRAL이다. 평균과 판정은 Spring이 계산하며 FastAPI는 설명만 반환한다. 통합 분석 버전은 fixture-v3, 설명 계약은 explanation-v1이다. AI_PROVIDER=gemini에서는 설정된 GEMINI_MODEL과 공급자도 키/요청에 포함한다. Gemini 설명 설정은 GEMINI.md를 따른다. 가격 근거는 계속 더미다.

## 가격 검증과 만료 계약

`DataPolicy`는 fixture 로딩 시 스키마·식별자 참조·중복·금액·날짜·시각을 검사한다. 0원 상품가와 일 대표값은 거부하고, 배송비와 할인은 0을 허용한다. 배송비는 필수 키이며 미확인은 null로 제공한다. 필드별 금액은 1조 원 이하의 정수다. 무조건 할인은 상품가 미만이어야 한다. 미래 시각·날짜와 시간대 없는 관측 시각은 거부한다. fixture가 잘못되면 API 시작을 중단하며 값을 자동 보정하지 않는다.

- 기준 시각은 기본적으로 원본 fixture 시각이다. 개발용 `FIXTURE_REFERENCE_TIME`으로 원본 이후 시각을 지정할 수 있다. 이것은 데이터의 수집 시각을 변경하지 않는다.
- 판매처 응답: `observedAt`, `expiresAt=observedAt+24h`, `stale` 추가. **정확히 24시간은 유효**, 초과하면 만료다. 만료된 `comparablePrice`는 이전 관측가로 보존하지만 현재가 산정에서 제외한다.
- 상품 응답: `currentPrice`는 만료되지 않은 판매처의 확정 비교가 중 최저값이다. 모두 만료면 null, `priceStale=true`. `lastKnownPrice`는 만료 여부와 무관한 최저 관측가다. 과거 가격이며 최신 관측 시점의 최저가라는 뜻은 아니다. `updatedAt`은 해당 상품의 가장 최근 판매처 관측 시각이다.
- 가격 이력: 일별 대표값은 한국 날짜의 종료 시점 기준으로 만료를 판정한다. 데이터가 없으면 stale=true, coverage와 updatedAt은 null이다. 당일 집계의 updatedAt은 기준 시각을 넘지 않는다.
- 상품 목록 meta.stale은 반환 페이지 중 priceStale이 있는지, 판매처 목록 meta.stale은 만료 행이 있는지를 나타낸다. 실제 개별 상태는 각 행의 필드를 따른다. 관련 조회의 meta.updatedAt에는 관측/집계 시각을 제공한다.
- 분석 입력 근거가 만료되면 Spring은 FastAPI를 호출하지 않고 `INSUFFICIENT_DATA`, `stale=true`와 갱신 필요 사유를 반환한다. 이는 새 구매 분석 결과가 아니라 판단 보류 상태다.
- 정상 분석 응답은 generatedAt·표본 수·상품 ID·상태를 검증한다. 미래 생성 시각은 502로 거부한다. 분석 expiresAt은 생성 시각+24h와 현재 최저가 근거의 만료 시각 중 빠른 값이다. 지난 결과는 상태를 판단 보류로 변경한다.

상품 저장소는 파일 로딩형이며 개인 찜·목표가 설정·알림을 Firestore Emulator에 연결했다. Firestore Emulator의 보관 스냅샷을 선택적 가격 조회 소스로 연결했다. 클라우드 연결·실제 공급자 정규화·수집 스케줄·분산 락은 미구현이다. 실제 공급자 입력도 향후 같은 도메인 검증을 거치도록 연결해야 한다.







## 로컬 가격 근거 보존·정리

POST /api/v1/internal/snapshot-retention은 내부 키로 보호한다. 기본 dryRun은 읽기만 하고, 실제 적용은 확인한 planId가 필요하다. 30일 초과 비최신 스냅샷·7일 초과 DONE 이벤트를 최대 합계 100개 처리하며 최신/미완료 보호와 멱등 영수증 유지를 적용한다. 범위·한도·백업과 사용자 검증은 [보존·정리 안내](SNAPSHOT-RETENTION.md)를 따른다. 실행·테스트·실제 삭제는 수행하지 않았다.

## 로컬 운영 상태 조회

GET /api/v1/me/operations는 Firebase ID 토큰과 서버 OPERATIONS_UIDS의 UID 일치를 요구한다. 기본 허용 목록은 비어 있다. 응답은 현재 프로세스의 수집 요약·조회 뷰·각 최대 500개 저장소 메타데이터 표본·처리 설정이다. 저장소 부분 장애는 storage.state=UNAVAILABLE로 표시하며 전체 상태를 정상으로 간주하지 않는다. no-store와 Vary: Authorization을 적용한다. 자세한 범위·한계·사용자 실행 절차는 [로컬 운영 안내](OPERATIONS.md)를 따른다.

## 다나와 실제 조회

POST /api/v1/me/danawa/search, /product, /offers는 로그인과 OPERATIONS_UIDS를 검사하고 Node의 별도 실조회 경로를 호출한다. source=reef-danawa, synthetic=false이며 정규화된 검색/상품/판매처와 원본 수집 시각·수신 시각·캐시/페이지 정보를 반환한다. 배송비·재고 미확인은 유지하며 더미 저장소나 분석/알림으로 연결하지 않는다. 입력·비용·시각·권한·한도와 사용자 실행은 [다나와 안내](DANAWA.md)를 따른다.

## 실제 다나와 관측 저장·이력

POST /me/danawa/observations는 서버에서 다시 조회한 가격만 실제 전용 경로에 기록한다. GET /me/danawa/history/{productId}는 유료 공급자 호출 없이 최대 90개 관측 날짜의 카드 표시가 집계와 보류 분석을 반환한다. 원본 시각 없는 관측은 일 집계에서 제외하며 재고/옵션 미확인으로 구매 판단은 INSUFFICIENT_DATA다. [실제 관측 저장 안내](DANAWA-STORAGE.md)의 멱등·한도·개인/더미 분리·검증 절차를 따른다.

## 0.19.0 실제 연결
DATA_PROVIDER=reef-danawa에서 허용되는 API는 health, 본인 me/account, me/danawa/*와 0.20.0의 공개 danawa/* 조회다. 나머지 레거시 더미 경로는 404 DEMO_DISABLED다. POST /me/danawa/explanation/{productId}는 운영 UID 인증, 저장 관측 존재, 사용자 분당 5회 한도를 확인하고 FastAPI Gemini 설명을 요청한다. 외부 Reef 호출은 하지 않는다. 응답의 synthetic=false이며 실패 시 더미 설명으로 대체하지 않는다. 상세 설정은 LIVE-CONNECTION.md 참고.

## 0.20.0 공개 조회
POST /danawa/search, /danawa/product, /danawa/offers는 인증 없이 접근 가능하다. 기존 /me/danawa/* 권한은 유지한다. 공개 조회도 기존 입력 검증, 수집기 캐시와 요청 한도를 적용한다. Firestore 저장 및 Gemini는 공개 경로에서 수행하지 않는다.

## 다나와 이미지 전달
수집기 검색 items[].image 및 상세 product.image / product.images[]는 공개 문서의 image / images 필드를 HTTPS URL로 정규화한 값이다. 없거나 안전하지 않으면 image=null, images=[]로 전달한다. Spring은 이 정규화 응답을 그대로 전달한다. 카드의 최저 표시는 프론트엔드가 본 상품 및 variants의 확인된 listedPrice 중 최소값과 해당 productId를 선택한다. 현금가/배송 포함가를 임의로 혼합하지 않는다.

## 0.21.0 실제 공유 캐시 및 작업
/danawa/search, /product, /offers 및 설명 요청은 저장된 결과를 우선한다. 최초 수집 시 202와 data=null, meta.collectionJobId/jobStatus/pollAfterSeconds를 반환한다. 만료 자료가 있으면 이전 data와 stale/진행 상태를 제공한다. GET /danawa/jobs/{jobId}는 상태 조회만 하며 설명 작업은 공개 경로에서 404다. 설명은 GET /me/danawa/jobs/{jobId}에서 운영 UID 권한을 확인한다.

GET /danawa/catalog는 Firestore의 실제 저장 상품 최대 40개를 읽으며 수집을 시작하지 않는다. GET /danawa/categories는 탐색 그룹 설정을 제공한다. 이 그룹은 공급자의 실제 분류 코드가 아니라 수집 시 연결한 탐색 범위다. 관측 저장은 인증 후 공유 판매처 자료의 원본 시각을 보존하며, 캐시 만료/수집 중이면 409로 보류한다.

history는 currency/observedDays/coverageStart/coverageEnd/dataVersion을 포함한다. 표시 캐시 버전과 관측 이력 버전은 다르다. explanation은 옵션·관측 버전·모델·규칙/설명 버전 키를 사용하고, 동일 키의 성공 설명은 시간만으로 재생성하지 않는다. 실패 시 SERVER_FALLBACK을 명시할 수 있다. GET /me/danawa/live-metrics는 운영 UID 전용이며 내부 키를 반환하지 않는다. 정책과 제약은 EXTERNAL-CALL-REDUCTION.md 참고.

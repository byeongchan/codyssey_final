# PRIZM 제품 요구사항 정의서

- 작성일: 2026-10-22
- 제품: AI 가격 변동·구매 타이밍 분석 서비스
- 기준 목업: `사이트/src/App.tsx`, `사이트/src/index.css`
- 문서 상태: 더미 데이터 우선 개발 명세. 실제 Reef API·Gemini 연결은 후속 단계다.

## 1. 목표와 개발 원칙

사용자가 동일 상품의 판매처별 가격과 수집 기간 내 가격 변동을 비교하고, Gemini가 설명하는 구매 판단 근거를 확인해 구매 시점을 결정하도록 돕는다.

React, Spring Boot, FastAPI, Node.js, Firebase Cloud Firestore를 사용한다. 사용자가 말한 “firebase store”는 Cloud Firestore로 해석한다. 인증은 Firebase Authentication을 추가 사용하는 설계를 제안한다.

기존 목업을 화면 기준으로 삼고 필요한 부분만 변경한다. 문서 작성 후 사용자의 순차 진행 요청에 따라 더미 기반 1차 코드를 구현했다. 설치·실행·테스트는 사용자가 직접 수행한다. 코드 작성 상태와 사용자 검증 완료 상태는 구분한다.

## 2. 현재 저장소 상태

- `사이트/`: React 19, TypeScript, Vite, Tailwind CSS 기반 목업.
- `frontend/`: 목업 스타일을 재사용한 더미 조회·상세·차트·예시 분석 및 로컬 Firebase 로그인·찜 화면.
- `backend/api/`, `backend/ai/`, `backend/collector/`: Spring Boot 조회/작업 API, FastAPI fixture 분석, Node.js 읽기 전용 더미 재확인 HTTP 서버와 이벤트 CLI.
- `fixtures/`: 고정 기준 시각의 상품·판매처·합성 가격 이력. 실제 외부 연동은 미구현이다.
- `firebase/`: demo-prizm Authentication/Firestore Emulator 설정·접근 규칙·알림 점검 인덱스. 계정·찜·목표가·인앱 알림은 에뮬레이터로 연결하고 상품/분석 fixture는 유지한다. 실행 검증은 사용자가 수행한다.
- 목업 화면: 홈, 검색 결과, 카테고리, 특가, 상품 상세, 찜, 로그인.
- 상품, 판매처 가격, 가격 차트, 리뷰 비율, AI 점수와 채팅 답변은 고정 데이터다.
- `사이트/`는 참고 원본으로 유지한다. 현재 구현 범위와 실행 절차는 README와 `docs/CONTRACT.md`에 기록한다.

## 3. 대상 사용자와 핵심 흐름

1. 사용자가 상품명으로 검색하거나 카테고리를 선택한다.
2. 상품 상세에서 옵션이 일치하는 판매처 가격과 배송비를 비교한다.
3. 실제 확보한 기간의 가격 추이와 구매 판단 근거를 확인한다.
4. 상품에 대해 AI 비서에게 질문하고 판매처 링크로 이동한다.
5. 로그인 후 상품을 찜하고 목표 가격 알림을 설정한다.

## 4. 범위와 우선순위

| 우선순위 | 기능 | 완료 조건 |
| --- | --- | --- |
| 상 | 상품 검색·카테고리·목록 | Reef 결과를 공통 상품 모델로 변환하고 페이지네이션·빈 결과·오류를 처리한다. |
| 상 | 상품 상세·판매처 비교 | 옵션·통화·수집 시각·배송비 확인 여부를 표시한다. 유효한 판매처 링크로 이동한다. |
| 상 | 가격 수집·추이 | 수집 데이터만 차트에 표시하고 실제 관측 기간·표본 수·갱신 시각을 제공한다. |
| 상 | AI 구매 타이밍 분석 | 근거 데이터, 판단 상태, 생성 시각을 반환하고 데이터 부족 시 판단을 보류한다. |
| 상 | 프론트엔드 연동 | 목업의 핵심 흐름을 API에 연결하고 로딩·빈 값·실패·오래된 데이터 상태를 제공한다. |
| 중 | Firebase 로그인·찜 | 로그인·로그아웃 및 사용자별 찜 등록·해제·재조회가 가능하다. 타인 데이터 접근을 거부한다. |
| 중 | 상품별 AI 채팅 | 해당 상품의 검증된 정보와 분석 결과를 사용해 답하고 대화 접근 권한을 검증한다. |
| 중 | 목표 가격·인앱 알림 | 목표가 이하의 유효 가격 관측 시 알림을 저장하며 같은 조건에서 중복 생성하지 않는다. |
| 중 | 특가·최근 비교 기록 | 관측 기간과 산식이 명확한 상품만 특가로 노출하고 개인 기록은 본인만 조회한다. |
| 중 | 리뷰 요약 | 원문 수집·이용이 허용될 때만 제공하며 실제 분석한 리뷰 수와 출처를 표시한다. |
| 하 | 외부 푸시·개인화·유사 상품 | MVP 안정화 후 별도 요건과 동의를 정해 확장한다. |

MVP는 ‘상’ 항목이다. 결제·주문·자동 구매, 미래 최저가 보장, 확인되지 않은 할인·재고·파트너 제휴 표시는 범위에서 제외한다. 목업의 로그인·찜 등 미구현 기능은 준비 중 상태를 명시한다.

## 5. 외부 API 확인 사항과 진행 조건

사용할 공급자는 [ReefAPI E-commerce & Marketplaces](https://reefapi.com/apis/ecommerce)로 확정한다. 해당 페이지는 쇼핑몰별 API 카탈로그이며 단일 호출 엔드포인트가 아니다. 카탈로그에서 11번가·쿠팡·다나와·Gmarket 항목을 확인했다. 개별 API의 지원 필드·인증·한도·이용 조건은 실연동 단계에서 확인한다. 다른 API 서비스로 임의 대체하지 않는다.

### 현재 개발 단계: 더미 데이터 우선

- 기본 설정은 `DATA_PROVIDER=fixture`, `AI_PROVIDER=fixture`로 정한다. Reef API와 Gemini를 호출하지 않으며 키를 요구하지 않는다. Gemini 분석 어댑터의 실연동은 이후 진행한다.
- 더미 상품·판매처·가격 이력·리뷰·분석·채팅 응답을 공통 계약에 맞는 fixture 파일로 분리한다. React 컴포넌트에 샘플 값을 추가로 하드코딩하지 않는다.
- Spring Boot는 fixture 저장소를 사용하고, Node.js는 fixture 수집 공급자, FastAPI는 fixture 분석 공급자를 사용한다. Firestore는 저장소 인터페이스로 분리하며 초기 기본 모드에서는 클라우드 자격 증명을 요구하지 않는다. 재시작 시 초기화되는 개발용 메모리 저장을 허용한다.
- 합성 90일 이력·리뷰·분석 응답은 데모에서 사용할 수 있지만 `meta.source=fixture`, `meta.synthetic=true`와 화면의 ‘더미 데이터 / AI 예시 응답’ 표시로 구분한다. 합성 이력을 실제 수집 이력으로 저장하거나 이관하지 않는다.
- fixture 기준 시각을 고정하고 `meta.referenceTime`으로 제공한다. 오래된 데이터 판정도 이 기준 시각을 사용해 실행 날짜에 따라 데모 결과가 바뀌지 않게 한다. 실연동에서는 실제 현재 시각으로 판정한다.
- 정상·빈 검색·데이터 부족·품절·배송비 미확인·가격 하락·오류 시나리오를 준비한다. 외부 장애 예시는 fixture 설정으로 선택한다.
- 더미 구매 버튼은 ‘데모에서는 구매 이동을 제공하지 않습니다’ 상태를 표시한다. 임의 상품 URL을 실제 구매 링크처럼 제공하지 않는다.
- 더미 모드는 개발 환경에서만 허용하고 운영 환경에서는 시작을 거부한다. 샘플 데이터와 실제 데이터는 저장 위치도 분리한다.
- 로그인·찜·알림은 중 우선순위를 유지한다. 인증 구현 전에는 보호된 API를 공개하지 않고 준비 중 상태로 제공한다.

### 후속 실제 연동 확인 사항

아래 항목은 더미 기반 핵심 흐름을 구현한 뒤 확인하고 어댑터에 반영한다. 초기 개발을 막는 선행 조건으로 삼지 않는다.

- 검색·상품 상세·판매처·상품 옵션 식별자와 국내 쇼핑몰 및 원화 지원 여부.
- 가격 이력, 리뷰 원문, 재고, 배송비, 쿠폰·카드 혜택 제공 여부.
- 호출 한도, 요금, 재시도 정책, 페이지네이션, 데이터 갱신 주기.
- 데이터 저장·캐시·리뷰 AI 처리·이미지 표시·외부 링크 이용 허용 조건.

가격 이력이 없으면 서비스 수집 시작 시점부터 축적한다. 90일치 데이터를 생성하거나 보간해 관측값처럼 표시하지 않는다. 리뷰 미제공 시 요약은 비활성화한다. 배송비 미확인 시 상품가만 비교하고 총 결제액 순위와 구분한다. 지원 쇼핑몰·혜택만 노출한다.

Gemini 설명 모드는 AI_PROVIDER/GEMINI_MODEL로 선택하며 REST 구조화 JSON 어댑터를 작성했다(미실행). 키는 FastAPI에만 두며 설정과 사용자 검증은 docs/GEMINI.md를 따른다. 실제 데이터·비용 예산·설명 품질 검증은 별도 단계다. 모델 변경 시 분석 버전을 함께 변경한다.

## 6. 서비스 구조와 책임

```text
React → Spring Boot → Firestore
                  → FastAPI → Gemini
Node.js 수집 워커 → Reef API
                 → Spring Boot 내부 수집 API → Firestore
```

| 구성 | 책임 |
| --- | --- |
| React (`frontend/`) | 화면·라우팅·상태·차트, Firebase 로그인, Spring Boot API 호출 |
| Spring Boot (`backend/api/`) | 공개 API, 인증·인가, 상품 정규화, 가격 계산, Firestore 쓰기, 분석 요청·캐시, 알림 판정 |
| FastAPI (`backend/ai/`) | Gemini 호출, 입력·출력 스키마 검증, 근거 기반 분석·채팅. Firestore 직접 쓰기 금지 |
| Node.js (`backend/collector/`) | 수집 스케줄, Reef 어댑터, 호출 제한·재시도, 수집 결과 전달. 동일 작업 중복 실행 방지 |
| Firestore | 상품·판매처·가격 이력·분석·사용자 데이터 저장 |

상품 조회는 저장된 데이터를 우선 반환한다. 검색 캐시가 없으면 Spring Boot가 인증된 내부 수집 요청을 Node.js에 전달하고, 프론트엔드에는 진행 상태와 재조회 방법을 반환한다. 정기 수집도 동일한 정규화·저장 경로를 사용한다. 외부 API를 호출하는 동안 DB 트랜잭션을 유지하지 않는다.

현재 fixture 구현은 목록/상세 상품 요약을 서버 실제 시간 60초·최대 128개로 캐시한다. 검색 화면에서 명시적으로 요청하면 Node HTTP 서버가 같은 파일의 SHA-256·기준 시각·검색 결과를 재확인하고, 성공 시 캐시를 비운 뒤 기존 검색 목록을 다시 조회한다. STORE_SNAPSHOT 모드와 내부 저장 API는 검증된 같은 근거를 에뮬레이터에 보관하며 영수증·내용 해시로 중복과 충돌을 처리한다. 상세 계약은 `docs/SNAPSHOTS.md`를 따른다. CATALOG_SOURCE=snapshot으로 검증된 저장 근거를 선택하고 가격 조회·분석·특가·알림에 사용한다. 요청/계산 중의 조회 뷰를 고정하며 상세는 `docs/CATALOG-SOURCE.md`를 따른다. 저장 영수증과 원자적으로 생성하는 알림 이벤트·임대 기반 페이지 평가·재시도를 작성했다(미실행). snapshot 조회 모드의 선택된 최신 가격만 사용하며 상세는 `docs/SNAPSHOT-ALERTS.md`를 따른다. 새 더미 관측의 한국 날짜별 일 최저가·유효 표본 집계를 저장 단계에 연결했다(미실행, docs/DAILY-PRICES.md). 다나와 검색·상품·판매처의 실조회 어댑터/API/화면은 별도 경로로 작성했다(미실행, docs/DANAWA.md). 실제 다나와 전용 관측 저장·일별 카드 표시가 집계·차트·재고/옵션 미확인 보류 분석을 작성했다(미실행, docs/DANAWA-STORAGE.md). 검증된 실제 가격의 구매 판단/알림·캐시 미스 자동 수집·정기 수집은 아직 없다. 작업 병합·기간·에러·실행 방법은 `docs/COLLECTION.md`를 따른다.

프론트엔드가 FastAPI·Node.js에 직접 접근하지 않도록 한다. 서버 간 API는 별도 자격 증명으로 인증하며 사용자 ID 토큰을 내부 서비스 인증으로 대체하지 않는다. Firestore 접근은 Spring Boot를 통해서만 제공한다.

## 7. 가격·분석 데이터 규칙

- 상품 ID는 문자열이며 공급자 식별자를 별도 매핑한다. 모델·용량·색상· 상품 여부가 다른 옵션은 합치지 않는다.
- KRW 금액은 정수로 저장한다. 상품가, 배송비, 무조건 할인, 조건부 혜택을 분리한다.
- 확정 비교가 = 상품가 + 확인된 배송비 - 무조건 할인. 조건부 혜택은 자격과 금액을 별도 표시한다.
- 배송비가 불명확하면 확정 비교가를 `null`로 제공한다. 품절·무효·옵션 불일치 판매처는 최저가 계산에서 제외한다.
- 시각은 UTC로 저장하고 화면에서는 Asia/Seoul로 표시한다. 공급자 기준 시각과 실제 수집 시각을 구분한다.
- 하루 대표 가격은 동일 옵션·동일 통화의 유효 확정 비교가 중 일 최저가로 정한다. 일 평균은 일 대표값들의 평균으로 계산하며 관측 일수와 결측 일수를 반환한다.
- 하락률 = `(이전 비교가 - 현재 비교가) / 이전 비교가 × 100`. 비교 시점과 기준가를 표시하고 기준 부재·0원은 계산하지 않는다.
- 초기 분석 기준 제안: 최근 30일 중 관측 일수가 7일 미만이거나 최신 유효 관측이 24시간보다 오래되면 `INSUFFICIENT_DATA`. 화면에 부족 사유를 표시한다. 수집 주기와 함께 구현 전에 확정한다.
- 구매 상태는 `BUY`, `WAIT`, `NEUTRAL`, `INSUFFICIENT_DATA`. 가격 통계는 서버에서 계산하고 Gemini는 그 근거를 설명한다. 가격 판단 규칙은 버전 관리한다.
- 미래 가격 확률·보장 문구는 제공하지 않는다. 목업의 92점·리뷰 91%·‘최저가 보장’ 등 고정 수치와 문구는 실제 근거가 없으면 제거한다.
- 현재 서버 규칙 price-rules-v1이 판단하고 FastAPI는 explanation-v1 설명만 반환한다. 설명 실패 시 서버 기본 설명을 제공하며 상세는 docs/DECISION-EXPLANATION.md를 따른다(미실행). 공개 분석 출력은 `verdict`, `reasons`, `risks`, `evidenceSnapshotId`, `observedDays`, `generatedAt`, `model`, `analysisVersion`을 포함한다. 분석 근거에 없는 가격·재고·할인 주장은 거부한다.
- 분석 키는 상품 옵션·가격 데이터 버전·모델·분석 버전으로 구성한다. 동일 키 요청은 재사용하며 데이터 변경 시 무효화한다.

## 8. 제안 데이터 모델

| Firestore 경로 | 주요 필드 |
| --- | --- |
| `products/{productId}` | title, categoryId, optionKey, imageUrl, providerIds, updatedAt |
| `offers/{offerId}` | productId, store, productPrice, shippingFee, unconditionalDiscount, conditionalBenefits, comparablePrice, currency, availability, purchaseUrl, observedAt |
| `products/{productId}/priceSnapshots/{snapshotId}` | offerId, optionKey, source, productPrice, comparablePrice, sourceTimestamp, collectedAt, idempotencyKey |
| `products/{productId}/dailyPrices/{date}` | dateKst, minimumPrice, validSamples, sourceSnapshotId |
| `analyses/{analysisId}` | productId, dataVersion, status, verdict, reasons, risks, evidenceSnapshotId, model, analysisVersion, generatedAt, expiresAt |
| `users/{uid}/wishlist/{productId}` | createdAt |
| `users/{uid}/alerts/{alertId}` | productId, targetPrice, enabled, lastConditionMet, lastNotifiedAt |
| `users/{uid}/notifications/{notificationId}` | alertId, productId, observedPrice, createdAt, readAt, dedupKey |
| `users/{uid}/history/{entryId}` | productId, viewedAt |
| `users/{uid}/conversations/{productId}` | productId, turns(최대 20쌍: requestId, question, answer, createdAt, referenceTime), updatedAt |
| `collectionJobs/{jobId}` | status, trigger, queryOrProductId, attempt, leaseUntil, errorCode, updatedAt |

리뷰는 제공 조건 확인 후 별도 컬렉션과 보존 기간을 정의한다. 배열 하나에 무제한 이력·채팅을 저장하지 않는다. 조회에 필요한 복합 인덱스와 보존·삭제 정책을 코드로 관리한다.

현재 fixture 채팅은 Spring에서 규칙 기반 답변을 만들고 상품당 질문·답변 20쌍만 저장한다. 짧은 후속 질문의 이전 질문은 최대 2개, 사용자당 새 질문은 분당 10회다. 실모델 채팅은 FastAPI 공급자 계층으로 연결할 예정이며 현재 Gemini를 호출하지 않는다. 세부 계약과 제한은 `docs/CHAT.md`를 따른다.

## 9. 제안 API 계약

공개 API 기본 경로는 `/api/v1`. 검색·상품·추이는 공개 조회, `/me/**`와 채팅·분석 생성은 로그인 필요로 설계한다. 목록은 커서 기반이며 최대 조회 수를 제한한다.

| 메서드·경로 | 용도 |
| --- | --- |
| `GET /catalog-source` / `POST /catalog-source/refresh` | 조회 모드·버전·선택 근거 상태 / 저장 근거 재선택 |
| `GET /categories` | 지원 카테고리 |
| `GET /products?q=&categoryId=&sort=&cursor=&limit=` | 검색·상품 목록 |
| `GET /products/{id}` | 상품 상세 |
| `GET /products/{id}/offers` | 판매처 비교 |
| `GET /products/{id}/prices?days=90` | 확보된 기간의 가격 추이·관측 범위·통계 |
| `GET /products/{id}/reviews` | 합성 리뷰 예시·표본/별점·수작업 태그 집계·부족/미지원/만료 상태 |
| `GET /products/{id}/analysis` | 최신 분석·부족/만료 상태 |
| `POST /products/{id}/analysis-jobs` | 분석 생성 요청, 중복 요청 병합 |
| `GET /analysis-jobs/{jobId}` | 분석 진행·결과 조회 |
| `GET /collection-jobs/{jobId}` | 검색 수집 진행 조회 |
| `POST /collection-jobs` | 검색 조건의 Node fixture 재확인·선택적 STORE_SNAPSHOT 보관 작업 요청 |
| `POST /internal/fixture-snapshots` | 내부 키·시작 시 fixture 버전 검증 후 동일 가격 근거의 에뮬레이터 원자적 보관 |
| `GET /deals` | 산식과 표본 기준을 만족하는 특가 |
| `GET /me/conversations/{id}` | 본인 상품별 대화 조회, 미생성이면 빈 목록 |
| `POST /me/conversations/{id}/messages` | 본인 상품별 대화 생성 또는 질문·더미 답변 추가 |
| `DELETE /me/conversations/{id}` | 본인 상품별 대화 삭제 |
| `GET /me` | 로그인 사용자 정보 |
| `GET /me/wishlist` | 찜 조회 |
| `PUT /me/wishlist/{productId}` / `DELETE /me/wishlist/{productId}` | 찜 등록·해제 |
| `GET /me/alerts` / `POST /me/alerts` | 목표 가격 알림 조회·등록 |
| `PATCH /me/alerts/{id}` / `DELETE /me/alerts/{id}` | 본인 알림 수정·삭제 |
| `GET /me/notifications` | 인앱 알림 조회 |
| `PATCH /me/notifications/{id}` | 읽음 처리 |
| `GET /me/history` | 최근 비교 기록 |
| `POST /me/history/{id}` / `DELETE /me/history/{id}` | 본인 상품 방문 기록 갱신·삭제 |
| `DELETE /me/account` | 최근 재인증·명시 동의 후 개인 데이터와 인증 계정 단계별 삭제 |
| `GET /health` | 공개 최소 상태 확인 |

비동기 작업은 HTTP 202와 `jobId`, `status`, `pollAfterSeconds`를 반환한다. 작업 조회도 요청자 권한을 검사한다. 검색 수집 대기 중에는 `items: []`와 `collectionJobId`를 제공해 단순 검색 결과 없음과 구분한다.

목록 응답은 `{ items, nextCursor, meta }`, 단일 응답은 `{ data, meta }`. `meta`에는 `requestId`, `updatedAt`, `stale`, `source`를 포함한다. 가격 응답에는 `currency`, `observedDays`, `coverageStart`, `coverageEnd`를 추가한다. 오류는 `{ error: { code, message, requestId, retryable } }`로 통일한다.

주요 오류: 400 잘못된 입력, 401 인증 필요, 403 권한 없음, 404 상품/작업 없음, 429 요청 한도, 502 공급자 응답 오류, 503 서비스 사용 불가. 데이터 부족은 정상 상태로 반환한다.

내부 계약은 별도 OpenAPI로 관리한다: Spring Boot → Node.js 수집 요청, Node.js → Spring Boot 멱등 수집 결과 저장, Spring Boot → FastAPI 분석·채팅 요청. 내부 경로는 외부 라우팅에서 차단한다.

## 10. 화면 연동 기준

| 목업 영역 | 구현 시 요구사항 |
| --- | --- |
| 홈·검색·카테고리 | 고정 상품을 API 데이터로 치환하고 지원 범위·검색 진행·빈 결과를 표시 |
| 상품 상세 | 문자열 ID 사용, 상품별 옵션·이미지·가격 연결, 찜 상태 유지 |
| 가격 차트 | SVG 고정 경로를 실제 시계열로 치환, 결측 구간과 실제 기간 표시 |
| 쇼핑몰 가격 | 조건부 할인과 배송비 미확인 상태 구분, 검증한 외부 URL 연결 |
| AI 판단·채팅 | 고정 점수·답변 제거, 생성 중·만료·실패·판단 보류 상태 표시 |
| 리뷰 요약 | 실제 분석한 표본·기간·출처 제공, 데이터 미지원 상태 표시 |
| 프로필·찜 | 인증 흐름과 접근 제한, 로그인 전후 이동 경로 제공 |

다크 모드와 반응형 레이아웃은 유지한다. 상품·검색 화면은 URL로 직접 진입하고 새로고침해도 같은 맥락을 복원할 수 있어야 한다.

## 11. 보안·신뢰성·운영 요구사항

- Spring Boot에서 Firebase ID 토큰과 UID를 검증하고 사용자 경로의 소유권을 검사한다. Firestore 클라이언트 직접 접근은 차단한다. 서버 SDK의 IAM 권한은 별도로 최소화한다.
- Reef·Gemini 키와 서비스 계정은 서버 환경에만 저장한다. 프론트엔드 번들·Git·로그에 포함하지 않는다. Firebase 웹 공개 설정과 서버 비밀키를 구분한다.
- 외부 텍스트는 분석 데이터로만 취급한다. 프롬프트의 명령으로 실행하지 않고 AI 출력은 스키마와 수치 근거를 검증한다.
- 외부 URL은 허용한 HTTPS 판매처 도메인으로 제한한다. 서버가 임의 사용자 URL을 가져오지 않는다.
- 외부 호출 타임아웃, 최대 재시도, 지수 백오프, 429 대기, 실패 격리를 구현한다. 공급자 실패 시 마지막 데이터와 갱신 시각을 표시한다.
- 스케줄 초기 제안은 활성 상품 6시간 간격이며 공급자 한도·비용 확인 후 조정한다. 분산 락과 멱등 키로 중복 수집·저장을 방지한다.
- 현재 로컬 구현은 사용자별 분석 생성 POST를 60초/5회, 채팅을 분당 10회로 제한한다. 분석 작업·캐시는 UID별이며 상세는 docs/ANALYSIS-ACCESS.md를 따른다(미실행). 일일 예산 도달 시 재사용 가능한 캐시를 제공하거나 요청을 중단한다.
- 로그는 requestId와 jobId로 연결하고 토큰·키·개인 질문 원문은 기본적으로 기록하지 않는다. 수집 성공률·지연·AI 호출 비용·캐시 적중률을 관측한다. 현재는 UID 허용 목록으로 보호하는 로컬 운영 화면에서 현재 프로세스 수집 상태·최대 500개 저장/이벤트 표본·조회 근거와 설정을 확인한다(미실행, docs/OPERATIONS.md). 실제 비용·장기 시계열은 아직 측정하지 않는다.
- 캐시 조회 API p95 1초 이하를 초기 목표로 한다. 외부 호출·AI 작업은 비동기 상태로 분리한다. 실제 달성 여부는 사용자 측 부하 검증으로 판단한다.
- 계정 삭제 시 개인 찜·알림·이력·대화를 삭제한다. 가격 데이터와 개인 데이터를 구분해 보존 정책을 작성한다. 로컬 가격 스냅샷·DONE 이벤트의 30일/7일 정리 후보와 최신/미완료 보호, 계획 확인 후 수동 적용을 작성했다(미실행). 멱등 영수증은 유지하며 상세는 docs/SNAPSHOT-RETENTION.md를 따른다.
  현재 에뮬레이터 구현은 찜·목표가 설정·인앱 통지·방문 기록·구매 비서 대화와 채팅 카운터를 정리한 뒤 인증 계정을 삭제한다. 늦은 쓰기 차단용으로 UID 경로와 삭제 상태만 남기며, 중단 후 재개·응답 유실·로컬 백업 제한은 `docs/PERSONAL-DATA.md`를 따른다.

## 12. 완료 기준

현재 단계는 더미 상품 검색 → 상세 → 판매처·가격 추이 → 예시 분석까지 공통 API로 연결되고, 데이터 출처와 예시 응답이 명확하면 완료로 본다. API 키·클라우드 자격 증명 없이 사용자가 실행할 수 있어야 한다. 이 단계 완료는 실제 Reef 수집·Gemini 분석·Firestore 저장 완료를 의미하지 않는다.

실연동 MVP는 실제 지원 상품 검색 → 상세 → 판매처·가격 추이 조회 → 근거 있는 AI 분석 → 판매처 이동까지 연결되면 완료로 본다. 데이터 부족·Reef 실패·Gemini 실패에서도 조작된 값 없이 화면이 동작해야 한다. fixture 데이터는 운영 데이터와 혼합하지 않는다.

세부 작업 순서와 사용자가 수행할 실행·검증 절차는 `TASKS.md`를 따른다.



## 실제 연결 모드 전환 (2026-10-22)
기본 실행은 DATA_PROVIDER=reef-danawa / FIREBASE_MODE=cloud / AI_PROVIDER=gemini / VITE_DATA_MODE=live다. 기존 더미 카탈로그·리뷰·채팅 화면과 API는 실제 모드에서 제공하지 않는다. 실제 Firestore 관측과 Gemini 설명을 연결하는 코드를 작성했다. 실제 공급자 호출·실행·테스트는 미수행이다. 재고·판매처 옵션 검증 전에는 구매 판단 보류를 유지한다. 설정은 docs/LIVE-CONNECTION.md를 기준으로 한다.

## 공개 검색 결과의 브라우저 보관
실제 화면은 동일 검색어·페이지의 공개 결과를 브라우저에서 15분간 재사용한다. 최근 검색어는 최대 10개 표시하고 기록/결과 삭제를 제공한다. 보관 결과는 상품의 새로운 관측 시각이나 구매 판단 근거를 생성하지 않으며 Firestore의 실제 관측과 구분한다. 상세·판매처·Gemini·관측 저장은 기존 서버 경로를 유지한다.

## 실제 상품 찜 (2026-10-23)
로그인한 사용자가 실제 다나와 상품을 최대 100개 찜하고, 카드/상세에서 해제하거나 상단 찜 목록에서 상세로 이동한다. Firestore에 사용자별로 저장하고 목록은 페이지당 12개를 표시한다. 가격은 저장 당시 표시가이며 찜 조작으로 외부 수집·분석을 시작하지 않는다. 계정 삭제 시 해당 자료도 정리한다. 적용·검증 절차는 docs/LIVE-WISHLIST.md를 따른다.

## 상세 페이지 확장 (2026-10-23)
사이트2 디자인의 가격 흐름·리뷰 요약 카드를 추가한다. 가격 흐름은 Reef price_history의 실제 주간/월간 최저가이며 기존 일별 관측이나 현재가로 변환하지 않는다. 다나와 리뷰 본문 API가 없어 자동 요약은 지원하지 않으며, 로그인 사용자가 직접 입력한 리뷰 표본의 Gemini 요약을 제공한다. 평점/리뷰 수만으로 내용을 추측하지 않는다. 실제 실행 검증 전이며 docs/DETAIL-INSIGHTS.md를 따른다.

## 실제 외부 API 호출 최소화 (2026-10-23)
Firestore 공유 표시 캐시, 서버 관리 탐색 그룹과 저장 카탈로그, 수집 작업의 공유 임대/재시도 상태, 데이터 버전 기반 Gemini 설명 재사용을 추가했다. 표시 자료가 실제 관측으로 자동 전환되지 않도록 분리한다. 기본 정기 수집은 꺼져 있다. 기존 fixture CATALOG_SOURCE=snapshot은 실제 경로의 저장소가 아니며 그대로 비활성 경로로 둔다. 구현/제약/사용자 검증은 docs/EXTERNAL-CALL-REDUCTION.md를 따른다. 실행·성능 및 과금 감소는 아직 검증하지 않았다.

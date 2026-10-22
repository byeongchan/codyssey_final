# 실제 Firebase · Reef · Gemini 연결

이 문서는 현재 실제 연결 모드의 실행 안내다. 이전 문서의 fixture/에뮬레이터 실행 명령 대신 아래 설정을 사용한다. 설치·실행·테스트·실제 API 호출은 수행하지 않았다. 기존 `.env`는 덮어쓰지 않았으므로 각 파일의 값을 직접 변경해야 한다.

## 변경된 동작

- 기본 React 화면은 실제 다나와 조회다. 더미 상품, 목업 상품 사진, 리뷰, 특가, 채팅, 시나리오 선택은 표시하지 않는다.
- `DATA_PROVIDER=reef-danawa`인 API는 fixture JSON을 읽지 않는다. 기존 더미 API와 수집·스냅샷·운영 화면 경로는 404로 차단한다. 더미 알림 스케줄러도 실행하지 않는다.
- `fixtures/`와 예전 화면·서비스 코드는 회귀 테스트 및 명시적인 fixture 개발 모드용으로 남겼다. 실제 모드에는 사용되지 않는다. 기존 DB나 에뮬레이터 데이터는 삭제하지 않았다.
- Firebase Cloud Auth로 로그인하고, Admin SDK로 서버에서 토큰 검증과 Firestore 읽기/쓰기를 수행한다. 클라이언트는 Firestore에 직접 접근하지 않는다.
- Reef 호출은 검색·상세·판매처 조회·현재 페이지 조회/저장 버튼에서만 발생한다. 기존 캐시·한도·타임아웃은 유지한다.
- 저장 이력을 조회한 뒤 **Gemini로 관측 한계 설명하기**를 누르면 실제 관측의 가격 요약을 전송한다. 사용자 이메일·UID·상품명·판매처 텍스트는 Gemini에 보내지 않는다. 사용자당 분당 5회 제한이며 실패 시 오류를 표시한다.
- 재고·판매처 옵션 미확인으로 구매 판단은 계속 보류한다. Gemini는 보류 사유와 관측 한계를 설명하며 실제 가격 예측이나 매수 신호를 만들지 않는다. 무신사는 아직 미연결이다.

## 1. Firebase 프로젝트 준비

1. Firebase 콘솔에서 실제 프로젝트와 웹 앱을 만든다. Authentication → Sign-in method에서 이메일/비밀번호 로그인을 활성화한다.
2. Authentication의 승인된 도메인에 접속 주소의 호스트를 등록한다. 개발 시 `localhost`와 사용하는 경우 `127.0.0.1`을 확인한다.
3. Firestore Database의 기본 데이터베이스를 생성한다. 웹 SDK의 직접 접근은 필요 없으므로 `firebase/firestore.rules`의 전체 거부 규칙을 적용한다. Admin SDK는 규칙을 우회하므로 서버 서비스 계정에 필요한 Firestore 및 Firebase Auth 권한이 있어야 한다. 규칙 파일은 프로젝트에 자동 배포되지 않는다.
4. 서버용 서비스 계정 JSON은 저장소 밖에 보관한다. 아래 `GOOGLE_APPLICATION_CREDENTIALS`에는 그 파일의 절대 경로를 넣는다. 프론트엔드에는 전달하지 않는다.

Firebase 공식 문서: [Admin SDK 설정](https://firebase.google.com/docs/admin/setup), [이메일/비밀번호 로그인](https://firebase.google.com/docs/auth/web/password-auth).

`frontend/.env`에 웹 앱 설정을 입력한다. 기존 파일에 값이 있다면 아래 항목만 수정한다.

```dotenv
VITE_API_BASE_URL=/api/v1
VITE_DATA_MODE=live
VITE_FIREBASE_MODE=cloud
VITE_FIREBASE_PROJECT_ID=실제-프로젝트-ID
VITE_FIREBASE_API_KEY=Firebase-웹-앱-apiKey
VITE_FIREBASE_AUTH_DOMAIN=Firebase-웹-앱-authDomain
```

Firebase 웹 앱 API 키는 브라우저 설정이다. Reef/Gemini 비밀 키나 서비스 계정 JSON은 `VITE_` 변수에 넣지 않는다.

## 2. Spring Boot

새 PowerShell 터미널에서 다음을 설정한다. Spring Boot는 `.env`를 자동으로 읽지 않는다.

```powershell
cd D:\github\codyssey_final\backend\api
Remove-Item Env:FIREBASE_AUTH_EMULATOR_HOST -ErrorAction SilentlyContinue
Remove-Item Env:FIRESTORE_EMULATOR_HOST -ErrorAction SilentlyContinue
$env:APP_ENV = 'development'
$env:DATA_PROVIDER = 'reef-danawa'
$env:CATALOG_SOURCE = 'fixture'
$env:FIREBASE_MODE = 'cloud'
$env:FIREBASE_PROJECT_ID = '<실제 Firebase 프로젝트 ID>'
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\private\firebase-service-account.json'
$env:INTERNAL_API_KEY = '<세 서버에서 동일한 내부 통신 비밀 키>'
$env:AI_URL = 'http://127.0.0.1:8000'
$env:COLLECTOR_URL = 'http://127.0.0.1:4001'
$env:AI_PROVIDER = 'gemini'
$env:GEMINI_MODEL = '<사용 가능한 Gemini 모델 ID>'
$env:OPERATIONS_UIDS = '<Firebase Authentication 사용자 UID>'
$env:ALERT_SCHEDULER_ENABLED = 'false'
$env:SNAPSHOT_ALERT_EVENTS_ENABLED = 'false'
mvn spring-boot:run
```

`CATALOG_SOURCE=fixture`는 비활성 레거시 어댑터 설정값이다. 실제 모드에서는 파일을 읽지 않는다. `snapshot`으로 바꾸지 않는다. 클라우드 모드에서 에뮬레이터 환경 변수가 남아 있거나 `DATA_PROVIDER=fixture`이면 Firebase 접근을 거부한다.

처음에는 Firebase 콘솔 → Authentication에서 이메일 사용자를 만들고 UID를 복사해도 된다. 또는 웹에서 먼저 회원가입하고 콘솔에서 UID를 확인한 뒤 API의 `OPERATIONS_UIDS`를 설정하고 API를 재시작한다. 비어 있으면 로그인 전용 저장·이력 조회·설명은 403이다. 공개 상품 검색은 운영 UID 없이 사용할 수 있다.

## 3. Node 수집기 · Reef

`backend/collector/.env`를 아래처럼 수정한다. 파일이 없을 때만 `.env.example`을 복사한다.

```dotenv
APP_ENV=development
DATA_PROVIDER=reef-danawa
INTERNAL_API_KEY=위와-동일한-내부-키
REEF_DANAWA_ENABLED=true
REEF_API_KEY=본인-Reef-API-키
```

```powershell
cd D:\github\codyssey_final\backend\collector
npm start
```

Node 22.12 이상이 필요하다. Reef 키는 이 수집기에만 설정한다. 기존 `collect:once` 더미 CLI는 실제 모드에서 사용하지 않는다. 다나와 엔드포인트·매핑은 기존 [다나와 안내](DANAWA.md)를 참고한다. 실제 호출 성공 여부는 아직 확인하지 않았다.

## 4. FastAPI · Gemini

별도 PowerShell에서 실행한다. `.env.example`은 참고 파일이며 이 명령은 셸 환경 변수를 사용한다.

```powershell
cd D:\github\codyssey_final\backend\ai
# 가상환경이 없을 때만 생성
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pip install -r requirements-gemini.txt
$env:APP_ENV = 'development'
$env:AI_PROVIDER = 'gemini'
$env:GEMINI_API_KEY = '<본인 Gemini API 키>'
$env:GEMINI_MODEL = '<Spring과 같은 모델 ID>'
$env:INTERNAL_API_KEY = '<위와 동일한 내부 키>'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

구조화 JSON 출력을 지원하고 계정에서 사용할 수 있는 모델을 선택한다. [Gemini 구조화 출력 공식 문서](https://ai.google.dev/gemini-api/docs/structured-output)를 참고한다. 키는 FastAPI에만 입력한다. 모델 이름 누락·잘못된 키·할당량 초과·응답 검증 실패 시 정상 연결로 간주하지 않는다.

## 5. 프론트엔드와 직접 확인

```powershell
cd D:\github\codyssey_final\frontend
npm install
npm run dev
```

`.env` 변경 후 Vite를 재시작한다. `http://127.0.0.1:5173`에서 다음 순서로 확인한다.

1. 첫 화면에 더미 상품 카드가 없고 실제 다나와 화면이 보이는지 확인한다.
2. Firebase 계정으로 로그인한다. 콘솔 Authentication과 동일 UID인지 확인한다.
3. 검색 → 상품 상세 → 판매처 조회를 한 번씩 누른다. 실제 Reef 크레딧을 사용할 수 있다. 공개 검색에는 운영 UID가 필요 없다. 저장·이력·설명의 403이면 운영 UID, 503이면 Node 실행/내부 키/활성 설정, 502이면 Reef 응답·키·외부 연결을 확인한다.
4. 현재 페이지 조회·저장 → 저장 이력 조회를 누른다. 실제 Firestore의 `liveDanawaProducts/{상품ID}` 아래 관측/일별 집계를 확인한다. 원본 수집 시각이 없으면 관측만 저장하고 일 집계/차트는 비어 있을 수 있다.
5. Gemini 설명 버튼을 누른다. 모델명과 이유·주의사항을 확인한다. 이 요청은 Gemini 비용이 발생할 수 있다. 서버 구매 판단 보류가 유지되어야 한다.
6. API `/api/v1/health`의 `data.mode=reef-danawa`를 확인한다. 이 값은 프로세스 설정 확인용이며 외부 서비스 연결 성공을 뜻하지 않는다. `/api/v1/products`는 실제 모드에서 404여야 한다.

각 서버는 현재 로컬 주소에 바인딩한다. 이 안내는 실제 공급자를 사용하는 로컬 통합 개발용이며 배포 완료를 의미하지 않는다.

선택적 회귀 검증 명령(개발자가 직접 실행, 외부 호출은 모킹):

```powershell
# backend/api
mvn test
# backend/collector
npm test
# backend/ai (테스트는 fixture 환경을 명시)
$env:AI_PROVIDER = 'fixture'
.\.venv\Scripts\python.exe -m unittest discover -s tests
# frontend
npm run build
```

FastAPI를 다시 실제 모드로 실행할 때는 `AI_PROVIDER=gemini`로 되돌린다. 테스트 명령은 서비스별 작업 디렉터리에서 실행한다.

## 홈페이지 디자인 및 비로그인 조회 복원
기본 실제 화면에도 기존 PRIZM 헤더 검색창, 추천 검색어, 카테고리 SVG 아이콘과 선택 스타일, 메인 배너, 반응형 상품 카드, 다크 모드와 푸터를 적용했다. 더미 상품·가격을 다시 표시하지 않는다. 배너 사진은 장식 이미지이며 API에 상품 이미지가 없으면 카드에 아이콘을 표시한다. 카테고리는 실제 공급자 카테고리 필터가 아닌 관련 키워드 검색으로 동작한다.

로그인 없이 홈페이지와 POST /api/v1/danawa/search, /product, /offers를 사용할 수 있다. 로그인 설정 실패가 공개 검색을 막지 않는다. 저장·이력 조회·Gemini 설명은 기존 로그인 및 운영 UID 권한을 유지한다. 공개 조회도 Reef 크레딧을 사용할 수 있으며 기존 Node 캐시/프로세스당 분당 20회 외부 요청 한도를 공유한다. 첫 진입에는 자동 외부 호출이 없다.

직접 확인: API와 프론트엔드를 재시작한 뒤 로그아웃 또는 시크릿 창에서 홈페이지의 헤더·카테고리·배너·푸터를 확인한다. 추천 검색어와 상단 검색, 카테고리 관련 검색이 실제 결과를 표시하는지 확인한다. 다크 모드 및 모바일 가로 스크롤 메뉴를 확인한다. 비로그인 상태의 저장 버튼은 로그인 창을 열고 저장 요청을 보내지 않아야 한다. 이 변경의 실행·빌드·테스트와 브라우저 렌더링 검증은 수행하지 않았다.

## 상품 상세·이미지·카드 가격 수정
- 검색 카드는 옵션별 가격 버튼을 표시하지 않고, 검색 응답의 본 상품 및 옵션 중 확인된 최저 표시가 하나만 표시한다. 카드 클릭은 해당 최저가의 productId로 이동한다. 배송비 포함 확정 최저가라는 의미는 아니다.
- 상품 클릭 시 `?product=다나와상품ID` URL의 상세 화면으로 전환한다. 검색 목록·홈 배너·카테고리는 상세에서 숨긴다. 목록으로 돌아가기, 브라우저 뒤로/앞으로, 상세 URL 직접 접속 및 새로고침을 지원한다. 직접 접속 후 목록으로 돌아가면 이전 검색 메모리가 없어 검색부터 다시 시작할 수 있다.
- Reef 문서의 검색 `results[].image`, 상세 `product.image` / `product.images[]`를 수집기에서 전달한다. 프로토콜 생략/HTTP 이미지 주소는 HTTPS로 정규화하며 안전하지 않은 주소는 제외한다. 이미지가 없거나 로드에 실패하면 대체 아이콘을 표시한다. 별도의 상품 이미지 크롤링이나 유료 이미지 API 호출은 추가하지 않았다.
- 로그인 상태 초기화가 공개 상세 요청을 취소하지 않도록 조정했다. 계정 변경 시 로그인 전용 요청은 취소하고 개인 화면 상태를 지운다.

직접 확인 순서:
1. Node 수집기를 종료 후 `backend/collector`에서 `npm start`로 재시작한다. 이미지 필드를 추가한 매핑을 반영하고 이전 캐시를 비운다.
2. 프론트엔드는 변경 반영 후 새로고침한다. 필요하면 `frontend`에서 `npm run dev`를 재시작한다.
3. 다시 검색해 카드 이미지와 최저가 하나가 표시되는지 확인한다. 실제 응답에 이미지가 없으면 아이콘이 정상 대체 표시다.
4. 카드 클릭 시 주소에 `?product=`가 붙고 목록 대신 상세가 나타나는지 확인한다. 목록 복귀·브라우저 뒤로/앞으로·새로고침도 확인한다. 상세 조회 실패 시 화면 오류와 재시도 버튼을 확인한다.
5. 사용자 검증 명령: `backend/collector`에서 `npm test`, `frontend`에서 `npm run build`.

이번 수정에서는 공개 Reef 문서만 조회했으며 서비스 실행·빌드·테스트·유료 Reef/Gemini 호출은 수행하지 않았다.

## 검색 기록 및 브라우저 결과 캐시
같은 카테고리/검색어를 다시 누를 때 매번 서버를 요청하지 않도록 공개 검색 결과를 브라우저 localStorage에 보관한다.

- 키: API 기본 주소 + 앞뒤 공백을 제거한 검색어 + 페이지. 카테고리 관련 키워드 검색에도 적용한다.
- 검색 결과: 보관 시각부터 15분간 재사용, 최대 20개 페이지. 동일 항목 재사용은 만료 시각을 연장하지 않는다. 전체 저장 문자열 200만 자, 단일 결과 20만 자를 초과하면 오래된 항목 제거 또는 해당 결과 저장을 생략한다.
- 최근 검색어: 성공한 검색의 중복을 합쳐 최대 10개, 최근 30일 이내 기록을 표시한다. 실패 요청은 기록하지 않는다. 결과가 만료되어도 검색어 버튼은 남을 수 있으며 이때 클릭하면 다시 조회한다.
- 저장 결과 적중 시 서버/수집기/Reef 요청을 모두 생략한다. 원래 수집/수신 시각을 바꾸지 않고 화면에 브라우저 저장 결과임을 표시한다. 원본 수집 시각이 없는 경우에도 만들어내지 않는다.
- ‘결과 다시 조회’는 브라우저 캐시를 건너뛴다. 수집기의 기존 60초 캐시는 유지하므로 Reef 유료 호출을 항상 강제로 발생시키는 버튼은 아니다.
- ‘기록 지우기’는 이 브라우저의 검색어와 저장 결과를 함께 제거한다. 로그인 여부와 무관하며 브라우저 사용자가 공유하는 기록이다. Firestore에 저장하거나 다른 브라우저/기기에 동기화하지 않는다. 토큰, 계정 정보, AI 설명, 개인별 저장 내역은 이 캐시에 넣지 않는다.
- 브라우저 저장소 사용이 불가능하면 현재 페이지 메모리로 대체한다. 이때 새로고침 후 유지되지 않을 수 있다. 만료 결과는 재사용하지 않으며 새 검색/기록 저장 시 보관 목록에서 제외한다.
- 상품 상세, 판매처, 관측 저장, Gemini 설명은 이번 캐시 대상이 아니다.

사용자 직접 확인:
1. 프론트엔드를 새로고침한다. 카테고리 하나를 검색하고 다른 카테고리로 이동했다가 원래 카테고리를 선택한다.
2. 15분 이내에는 ‘브라우저에 저장된 검색 결과 · 추가 조회 없음’ 표시와 브라우저 Network 탭에서 /danawa/search 요청이 없는지 확인한다.
3. 브라우저 새로고침 뒤 최근 검색어를 눌러도 유효한 저장 결과를 사용하는지 확인한다. 처음 홈페이지 진입만으로 API를 자동 호출하지 않는다.
4. ‘결과 다시 조회’로 서버 요청을 보내는지 확인한다. 오류 응답은 캐시나 최근 검색어에 새로 저장하지 않는다.
5. ‘기록 지우기’로 최근 검색 버튼이 사라지고 이후 같은 검색이 다시 서버에 요청되는지 확인한다.
6. 필요 시 frontend에서 npm run build로 타입/빌드를 확인한다.

이번 변경의 실행·빌드·테스트 및 외부 API 호출은 수행하지 않았다.

## 검색 이미지 누락 시 상세 이미지 재사용
검색 응답의 image가 비어 있어도 상세 응답에 image가 있을 수 있다. 상품 상세를 조회할 때 확인한 이미지 URL을 상품 ID별로 브라우저에 보관하고 검색 카드에서도 사용한다. 최저 표시가 옵션 ID로 상세를 조회한 경우 해당 ID의 이미지를 그 카드에 재사용한다. 상품명 추정이나 URL 조합으로 다른 상품 사진을 만들어내지 않는다.

검색 목록의 ‘누락 사진 불러오기 · 최대 4개’는 사진이 없는 상품만 명시적으로 상세 조회한다. 한 번 클릭당 최대 4개를 순차적으로 요청하고 첫 오류 발생 시 중단한다. 실제 Reef 크레딧이 사용될 수 있으며, 자동으로 전체 목록의 유료 조회를 실행하지 않는다. 수집기의 기존 캐시와 요청 한도는 유지한다. 이 버튼은 사진 보완만 수행하고 화면을 상세로 이동시키거나 가격·관측 저장을 갱신하지 않는다.

이미지 URL은 최대 100개, 7일간 재사용한다. 상세에도 이미지가 없는 것으로 확인된 ID는 15분간 추가 사진 보완에서 제외한다. 검색 기록의 ‘기록 지우기’는 별도 이미지 보관도 함께 지운다. 이미지 주소가 없을 때와 다운로드가 실패했을 때의 안내 문구를 구분한다. 이미지 보관 역시 현재 브라우저 전용이며 저장소를 사용할 수 없으면 메모리로 대체한다.

직접 확인: 프론트엔드를 새로고침한 뒤 검색 → 사진이 없는 상품 상세 → 목록 복귀 시 사진이 나타나는지 확인한다. 나머지는 누락 사진 불러오기 버튼으로 최대 4개씩 확인한다. 한 번 확인한 사진은 새로고침 후 최근 검색 결과에서도 재사용되어야 한다. 실제 호출과 실행·빌드·테스트는 수행하지 않았다.

홈·카테고리·검색·상세 화면을 사이트2 원본 디자인에 맞게 수정했다. 적용 범위와 사용자 확인 순서는 [SITE2-DESIGN.md](SITE2-DESIGN.md)를 참고한다. 외부 서비스 자동 호출과 더미 가격/분석 수치는 추가하지 않았다.

## 2026-10-23 공유 캐시·수집 작업 반영
실제 조회 경로를 Firestore 저장 우선으로 변경했다. 적용 전 [EXTERNAL-CALL-REDUCTION.md](EXTERNAL-CALL-REDUCTION.md)의 인덱스 반영·서버 재시작·직접 검증 절차를 따른다. 이전의 ‘공개 조회는 즉시 Node를 호출’ 설명은 이 변경으로 대체한다. 정기 수집은 기본 비활성이고 성능/실제 과금 감소는 미검증이다.

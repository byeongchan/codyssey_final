# 줍줍각

**AI SMART PRICE PICK**

줍줍각은 다나와·무신사 상품과 중고나라·당근 매물을 탐색하고, 가격과 구매 조건을 비교하는 서비스입니다. 가격 이력과 검색 결과를 서버에서 분석하고, Gemini 기반 AI 구매 비서가 확인된 정보를 바탕으로 구매 판단을 돕습니다.

프론트엔드는 **Vercel**, 백엔드는 **Render**에 배포합니다. 실제 구매·결제·거래는 원본 판매처와 플랫폼에서 진행합니다.

## 주요 기능

- **상품 탐색:** 검색·카테고리 조회, 최근 검색·최근 본 상품
- **무신사 패션·스포츠:** 해당 카테고리에서 다나와·무신사 선택, 무신사 분류 검색·옵션 재고·사이즈표·실제 리뷰·찜 ([설정 안내](docs/MUSINSA.md))
- **중고 최저가:** 중고나라·당근 통합 검색, 동네 선택·매물 상세·가격 요약·찜·최근 본 상품 ([설정 안내](docs/USED-MARKET.md))
- **상세 검색:** 선택한 카테고리에 맞는 상품 종류·기종·제품군·브랜드 드롭다운 ([사용 안내](docs/PRODUCT-FILTERS.md))
- **상품 상세:** 상품 정보, 쇼핑몰별 최저가, 기간별 가격 흐름과 지점별 가격 확인
- **AI 구매 비서:** 로그인 회원 전용 상담, 구매 판단·가격 흐름·뉴스·사양 등 질문에 맞춘 답변
- **뉴스 근거:** NAVER API HUB에서 최근 30일 기사를 검색하고 상품군·가격 영향 관련성을 평가
- **회원 기능:** 이메일 인증 가입, 비밀번호 재설정, 내 정보, 찜, 표시 이름 변경
- **관리자 권한:** Firestore에서 관리자를 지정하고 내 정보에서 다른 회원의 권한 부여·해제

커뮤니티에서 일반 게시글과 공지사항을 조회할 수 있습니다. 이메일 인증 회원은 표시 이름 등록 후 글과 댓글을 작성할 수 있으며, 공지사항은 관리자만 작성하고 댓글은 허용하지 않습니다. 작성자는 자신의 글·댓글을 수정할 수 있고, 작성자와 관리자는 삭제할 수 있습니다. 삭제한 글은 목록에 남기지 않으며, 탈퇴 시 삭제하지 않은 게시글은 유지합니다. 이미지 업로드는 지원하지 않습니다. 설정과 확인 절차는 [커뮤니티 안내](docs/COMMUNITY.md)를 참고하세요. 다나와 연동에서는 리뷰 본문을 제공받지 못하면 직접 확인 안내를 표시합니다.

## 시스템 구성

Render 백엔드·Vercel 프론트엔드 배포는 [배포 안내](docs/DEPLOYMENT.md)를 참고하세요. 저장소 루트 Dockerfile은 API·수집기·AI를 하나의 Render 서비스에서 실행하며, 로컬 실행 방법은 아래와 같습니다.

| 구성 요소 | 역할 | 로컬 포트 |
| --- | --- | --- |
| React · TypeScript · Vite | 사용자 화면, Firebase 로그인 | 5173 |
| Spring Boot · Java 17 | 공개·회원 API, 가격 판단, DB 조회·저장, 권한 검사 | 8080 |
| Node.js | Reef 다나와·무신사·중고나라·당근 API 수집 | 4001 |
| FastAPI · Python | Gemini 상담, 뉴스 수집·선별 | 8000 |
| Firebase Authentication · Cloud Firestore | 인증, 상품·가격 이력·개인 데이터·관리자 권한 저장 | 클라우드 |

로컬에서는 Vite가 `/api` 요청을 Spring Boot로 전달하고, 배포 환경에서는 프론트엔드가 `VITE_API_BASE_URL`에 지정된 Render HTTPS API를 직접 호출합니다. Spring Boot가 수집기와 AI 서버를 호출하며, 외부 API 비밀 키와 Firebase 서비스 계정은 서버에서만 사용합니다.

```text
frontend/           React 화면과 정적 자산
backend/api/        Spring Boot API
backend/collector/  Reef 수집기
backend/ai/         FastAPI·Gemini·뉴스 처리
firebase/           Firestore 규칙·인덱스·에뮬레이터 설정
deploy/             Render 컨테이너 시작·프로세스 관리
scripts/            PowerShell 환경변수 로더
docs/               기능별 설정·설계·확인 방법
Dockerfile          백엔드 통합 이미지 빌드
render.yaml         Render 무료 Web Service 설정
PRD.md              제품 요구사항
TASKS.md            개발 체크리스트
```

## 개발 환경 준비

배포된 서비스를 이용하는 데 로컬 개발 환경은 필요하지 않습니다. 아래 절차는 개발·유지보수를 위한 설정입니다. 재배포는 [배포 및 운영](#배포-및-운영)과 [상세 배포 안내](docs/DEPLOYMENT.md)를 참고하세요.

- Java 17, Maven
- Node.js 22.12 이상, npm
- Python 3.11 권장
- Firebase 프로젝트와 서버 서비스 계정 JSON
- Reef API 키, Gemini API 키
- 뉴스 사용 시 NAVER API HUB Client ID·Client Secret

아래 명령은 **Windows PowerShell**, 저장소 위치는 `E:\github\codyssey_final` 기준입니다. 다른 위치에 내려받았다면 경로를 변경하세요.

### 1. Firebase 설정

1. Firebase Console → **Authentication → Sign-in method**에서 이메일/비밀번호 로그인을 활성화합니다.
2. **Firestore Database**에 `(default)` 데이터베이스를 생성합니다.
3. **프로젝트 설정 → 서비스 계정**에서 서버용 JSON 키를 발급합니다. 예: 저장소의 `secrets/firebase-service-account.json`에 보관합니다.
4. 프론트엔드용 웹 앱을 등록하고 Firebase 설정 값을 확인합니다. 로그인 도메인 문제 발생 시 Authentication의 승인된 도메인도 확인합니다.

Firestore 규칙과 조회 인덱스를 배포합니다. 이미 동일한 설정을 배포했다면 생략할 수 있습니다. 브라우저의 Firestore 직접 접근은 차단하고 서버를 통해 이용하는 구조입니다.

```powershell
cd E:\github\codyssey_final\firebase
npx.cmd --package=firebase-tools firebase login
npx.cmd --package=firebase-tools firebase deploy --only firestore:rules,firestore:indexes --project 실제-프로젝트-ID
```

인덱스가 생성 중이면 완료될 때까지 기다립니다. 서비스 계정의 프로젝트와 Firestore 프로젝트가 같아야 하며 서버 계정에 필요한 접근 권한이 있어야 합니다.

### 2. 환경변수 설정

아래 네 폴더에서 `.env.example`을 `.env`로 복사하고 값을 입력합니다. **기존 `.env`가 있다면 덮어쓰지 마세요.**

| 파일 | 주요 설정 |
| --- | --- |
| `frontend/.env` | `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN` |
| `backend/api/.env` | `FIREBASE_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS`, `INTERNAL_API_KEY`, `GEMINI_MODEL` |
| `backend/collector/.env` | `REEF_API_KEY`, `INTERNAL_API_KEY` |
| `backend/ai/.env` | `GEMINI_API_KEY`, `GEMINI_MODEL`, `INTERNAL_API_KEY`, 뉴스 설정 |

- `INTERNAL_API_KEY`: 직접 생성한 충분히 긴 임의 문자열을 **API·수집기·AI 서버에 동일하게** 설정합니다. 프론트엔드에는 넣지 않습니다.
- `GEMINI_MODEL`: **Spring Boot와 FastAPI 양쪽에 같은 모델 ID**를 입력합니다. 예제는 `gemini-3.5-flash-lite`이며, 실제 사용 가능한 모델 ID는 해당 API 프로젝트에서 확인하세요.
- `GOOGLE_APPLICATION_CREDENTIALS`: 서비스 계정 JSON의 **절대 경로**를 입력합니다.
- 실제 연결 모드는 예제의 `DATA_PROVIDER=reef-danawa`, `FIREBASE_MODE=cloud`, `VITE_DATA_MODE=live`를 유지합니다. API의 `CATALOG_SOURCE=fixture`는 현재 실제 연결 경로에서 사용하지 않는 기존 설정입니다.
- 클라우드 연결에서는 `FIREBASE_AUTH_EMULATOR_HOST`, `FIRESTORE_EMULATOR_HOST`를 제거합니다.
- `OPERATIONS_UIDS`는 기존 수집·관측 운영 API 권한입니다. 일반 검색·찜·AI 채팅이나 커뮤니티 관리자 지정용이 아닙니다.
- `.env`는 `이름=값` 형식으로 작성하고 주석은 별도 줄에 씁니다. 설정 변경 후 해당 서버를 재시작합니다.

Spring Boot와 Gemini 설정은 아래 실행 명령처럼 `Import-Env.ps1`로 현재 터미널에 불러옵니다. Node 수집기는 `npm start`에서, Vite는 시작 시 `.env`를 읽습니다.

**뉴스 설정** — `backend/ai/.env`:

```dotenv
NEWS_PROVIDER=naver
NAVER_CLIENT_ID=발급받은_Client_ID
NAVER_CLIENT_SECRET=발급받은_Client_Secret
```

**NAVER Cloud의 NAVER API HUB**에서 발급받은 같은 애플리케이션의 키 쌍을 사용합니다. 기존 네이버 개발자 센터 검색 Open API 키와 구분해야 합니다. RSS를 선택하려면 `NEWS_PROVIDER=rss`와 예제의 `RSS_FEEDS_JSON`, `RSS_ALLOWED_HOSTS`를 설정합니다. 네이버 검색 실패 시 RSS로 자동 전환하지 않습니다.

### 3. 의존성 설치

최초 설정 또는 의존성 변경 시 실행합니다.

```powershell
cd E:\github\codyssey_final\frontend
npm install

cd E:\github\codyssey_final\backend\collector
npm install

cd E:\github\codyssey_final
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend\ai\requirements-gemini.txt
```

기존 가상환경이 있다면 재생성하지 않고 해당 환경을 사용하세요. Gemini용 의존성 파일에는 `httpx`와 뉴스 파서가 포함됩니다. Maven 의존성은 Maven 실행 시 내려받습니다.

## 로컬 실행

각각 별도 터미널에서 실행합니다.

**1. Node 수집기**

```powershell
cd E:\github\codyssey_final\backend\collector
npm start
```

**2. FastAPI · Gemini**

```powershell
cd E:\github\codyssey_final\backend\ai
& ..\..\scripts\Import-Env.ps1 -Path .env
..\..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

**3. Spring Boot**

```powershell
cd E:\github\codyssey_final\backend\api
& ..\..\scripts\Import-Env.ps1 -Path .env
mvn spring-boot:run
```

**4. React**

```powershell
cd E:\github\codyssey_final\frontend
npm run dev
```

접속: [줍줍각 로컬 화면](http://127.0.0.1:5173). 각 서버는 해당 터미널에서 `Ctrl+C`로 종료합니다. 로컬은 `APP_ENV=development`를 사용하며 배포 설정과 구분합니다.

## 데이터 저장·가격 갱신 정책

검색 결과, 상품 상세, 판매처별 가격, 주간·월간 가격 이력을 Firestore에 저장합니다.

- 기본 최신성 기준은 **1일**입니다. 1일이 지나도 저장 자료를 유지하며 자동 재수집하지 않습니다.
- 저장되지 않은 검색 조건·상품·판매처 페이지·가격 이력은 최초 조회 시 수집하므로 Reef 사용량이 발생할 수 있습니다.
- 상세의 **최신 가격 갱신**은 상품·판매처·가격 이력을 갱신합니다. 여러 Reef 요청이 발생할 수 있습니다.
- 가격 흐름의 새로고침은 가격 이력을 갱신합니다. 검색 결과의 새로고침은 저장 자료를 다시 읽습니다.
- 동일 자료는 마지막 성공 저장 후 60초 이내 중복 갱신을 제한하고 진행 중인 수집 작업을 공유합니다.
- 일별 기록이 부족하면 확보한 주간·월간 자료를 활용하되 일별 통계로 꾸미지 않습니다. 저장 자료의 시각과 비교 조건은 AI 판단에 반영합니다.

설정값과 확인 절차는 [수동 가격 갱신 안내](docs/MANUAL-PRICE-REFRESH.md)를 기준으로 합니다. 기존 문서의 자동 갱신 설명보다 이 정책이 우선합니다.

중고 검색의 AI 분석은 조회한 매물만 사용합니다. 같은 모델명으로 묶고, 모델명이 없으면 상품 종류로 분류합니다. 용량·색상까지 나누지 않은 참고 가격이며, 최저가·평균가·최고가·가격 분포와 지역 정보 제공·미제공 비율을 확인할 수 있습니다. 명확한 비정상 저가·분석 제외 조건을 적용하더라도 허위 매물 여부를 확정하는 기능은 아닙니다.

중고 상세 비서는 API의 제목·설명·가격·지역 등과 연결된 검색 근거를 함께 참고합니다. 브라우저에는 상품별 검색 근거의 ID와 후보 ID만 최대 24시간 저장하고 서버가 실제 자료를 검증합니다. 최근 본 상품은 이 기기에서 최대 5개·30일 보관하므로, 상품 기록이 남아 있어도 검색 비교 근거는 먼저 만료될 수 있습니다. 이때는 상세 정보로 분석하고 재검색을 안내합니다.

AI 답변은 확인된 근거와 추가 검토 사항을 구분하는 참고 정보입니다. 거래 안전·실물 상태·미래 가격을 보증하지 않습니다. 현재 AI 상담은 다나와 상품과 중고 매물에 연결되어 있으며 무신사 전용 화면에는 연결되어 있지 않습니다.

## 배포 및 운영

```text
브라우저 → Vercel (React 정적 빌드)
              ↓ HTTPS · Firebase 사용자 토큰
           Render (Docker Web Service)
              ├─ Spring Boot : 공개 API
              ├─ Node 수집기 : 127.0.0.1:4001
              └─ FastAPI    : 127.0.0.1:8000
                   ↓
           Firebase / Reef / Gemini / NAVER
```

Render는 세 백엔드 프로세스를 하나의 컨테이너에서 실행합니다. 수집기와 AI 서버는 외부에 공개하지 않습니다. 시작 스크립트는 필수 설정과 내부 서버 준비 상태를 확인하고, 프로세스 하나가 종료되면 나머지도 종료합니다.

| 항목 | 설정 |
| --- | --- |
| Vercel Root Directory | `frontend` |
| 프론트 빌드 / 출력 | `npm run build` / `dist` |
| Render 배포 정의 | 루트 `render.yaml` 및 `Dockerfile` |
| Render 상태 확인 | `https://<Render호스트>/api/v1/health` |
| 내부 통신 | 동일한 `INTERNAL_API_KEY`, loopback 전용 |

**Vercel 환경변수**는 아래 6개이며, 브라우저용 설정으로 등록합니다. API 주소에는 실제 Render 호스트를 사용합니다.

```dotenv
VITE_API_BASE_URL=https://<Render호스트>/api/v1
VITE_DATA_MODE=live
VITE_FIREBASE_MODE=cloud
VITE_FIREBASE_PROJECT_ID=<Firebase 프로젝트 ID>
VITE_FIREBASE_API_KEY=<Firebase 웹 앱 apiKey>
VITE_FIREBASE_AUTH_DOMAIN=<Firebase 웹 앱 authDomain>
```

`VITE_*` 값은 빌드에 포함됩니다. Firebase 웹 앱 키와 서버 서비스 계정의 비공개 키를 구분하고, Reef·Gemini·NAVER·내부 키는 Render에만 등록합니다. Vercel 환경변수 변경 후에는 재배포해야 합니다.

**Render**에는 실제 Vercel origin을 `CORS_ALLOWED_ORIGINS`로 등록합니다. 예: `https://<Vercel호스트>`처럼 마지막 슬래시·경로 없이 지정합니다. 서비스 계정 JSON은 Secret File `firebase-service-account.json`으로 등록하고 `GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-service-account.json`으로 연결합니다. Firebase Authentication 승인된 도메인에도 프론트 호스트를 추가합니다.

현재 배포 정의는 **Render 무료 플랜**입니다. 비활성 상태 이후 첫 요청은 서버 재시작으로 지연될 수 있으며, 세 프로세스가 메모리를 공유합니다. 외부 API 사용량과 비용은 별도로 관리합니다. 설정 전체·검증 순서는 [배포 안내](docs/DEPLOYMENT.md)를 참고하세요. 정책 초안의 미정 운영 정보는 [운영 전 확인 사항](docs/LEGAL-DOCUMENTS.md)에 정리되어 있습니다.

## 회원·관리자 설정

회원가입은 인증 메일 보내기 → 메일 링크 인증 → 인증 확인 → 가입 완료 순서입니다. 표시 이름은 서버에서 관리하며 등록·변경 후 7일에 한 번 변경할 수 있습니다. 관리자 사칭에 사용될 수 있는 예약 이름은 제한합니다.

최초 관리자는 Firebase Console에서 등록합니다.

1. Authentication → Users에서 이메일 인증된 본인 UID를 복사합니다.
2. Firestore에 컬렉션 `serviceAccess`, 문서 `administrators`를 만듭니다.
3. 필드 `uids`를 **배열(array)**로 만들고 UID를 문자열 항목으로 추가합니다.
4. 서비스의 내 정보를 다시 엽니다. 이후 **관리자 권한 관리**에서 다른 회원의 권한을 부여·해제할 수 있습니다.

```json
{ "uids": ["최초_관리자의_Firebase_UID"] }
```

`ADMIN_UIDS` 환경변수는 사용하지 않습니다. 마지막 관리자 해제는 차단되며, 관리자 탈퇴 전에는 다른 관리자에게 권한 해제를 요청해야 합니다. 자세한 내용은 [표시 이름·관리자 권한](docs/MEMBER-NAMES-AND-ADMINS.md)을 참고하세요.

## 실행 후 확인·테스트

다음 순서로 화면을 확인합니다. 최초 수집·명시적 갱신과 AI·뉴스 호출은 외부 서비스 사용량이 발생할 수 있습니다.

1. 비로그인 상태에서 검색·카테고리·상품 상세·가격 흐름·판매처 가격 확인
2. 이메일 인증 가입과 로그인, 찜 추가·해제, 내 정보 확인
3. 상품 상세에서 구매 시점·가격 흐름·뉴스·사양 질문을 각각 보내 응답 확인
4. 저장된 상품 재방문과 명시적 갱신 시 수집 횟수·자료 시각 확인
5. 일반 회원·관리자 계정의 권한 차이 확인

프론트엔드 타입 검사·빌드와 Java 테스트를 확인한 뒤 변경사항을 배포합니다. 아래 명령은 로컬 검증용이며 외부 서비스 연결 검증과는 별개입니다.

```powershell
# Spring Boot
cd E:\github\codyssey_final\backend\api
mvn test

# Node 수집기
cd E:\github\codyssey_final\backend\collector
npm test

# FastAPI
cd E:\github\codyssey_final\backend\ai
..\..\.venv\Scripts\python.exe -m unittest discover -s tests

# 프론트엔드 타입 검사·빌드
cd E:\github\codyssey_final\frontend
npm run build
```

## 자주 확인할 문제

| 증상 | 확인할 항목 |
| --- | --- |
| 배포 후 `Failed to fetch` | Render 상태 API, Vercel `VITE_API_BASE_URL`, 현재 프론트 origin과 CORS 설정 일치, 환경변수 변경 후 재배포 |
| `Firebase secret file is missing` | Render Secret File의 이름과 `GOOGLE_APPLICATION_CREDENTIALS` 경로 일치 |
| `No module named 'httpx'` | 실행에 사용하는 가상환경에 `requirements-gemini.txt` 설치 |
| Firebase 서버 인증·접근 오류 | 서비스 계정 절대 경로, 프로젝트 ID, Firestore 생성·IAM 권한, 에뮬레이터 환경변수 |
| 뉴스 인증·권한 오류 | NAVER API HUB 키 쌍과 해당 애플리케이션의 API 사용 권한 |
| AI 연결 실패·503·시간 초과 | FastAPI 로그의 오류 코드·실제 모델, 양쪽 `GEMINI_MODEL`, API 키·사용량·연결 상태 |
| 서버 간 인증 실패 | 세 서버의 `INTERNAL_API_KEY` 일치 여부, 환경변수 재적용·재시작 |
| Firestore 인덱스 오류 | 인덱스 배포 대상 프로젝트와 생성 완료 여부 |

## 저장소에 올리기 전

실제 `.env`, 서비스 계정 JSON, 인증서·개인 키, `secrets/`, `credentials/`는 Git 제외 대상입니다. `.env.example`과 `.env.production.example`만 실제 키 없이 공유합니다. 프론트엔드 `VITE_*` 값은 브라우저에 포함되므로 서버용 비밀을 넣지 마세요.

```powershell
cd E:\github\codyssey_final
git status --short
git check-ignore backend/collector/.env frontend/.env secrets/firebase-service-account.json
```

`.gitignore`는 이미 추적·공개된 비밀을 제거하지 않습니다. 공개된 키는 폐기·재발급해야 합니다. Firebase 규칙·인덱스는 코드로 포함하며 `사이트/`, `사이트2/` 목업은 현재 제외 설정을 유지합니다.

## 상세 문서

| 주제 | 문서 |
| --- | --- |
| Render·Vercel 배포 | [배포 및 운영 설정](docs/DEPLOYMENT.md) |
| 중고 검색·AI 분석 | [중고 최저가](docs/USED-MARKET.md), [중고 구매 비서](docs/USED-AGENT.md) |
| 패션·상세 검색·커뮤니티 | [무신사](docs/MUSINSA.md), [상세 검색](docs/PRODUCT-FILTERS.md), [게시판](docs/COMMUNITY.md) |
| 제품 요구사항·작업 목록 | [PRD](PRD.md), [TASKS](TASKS.md) |
| 외부 서비스 연결 | [Firebase·Reef·Gemini](docs/LIVE-CONNECTION.md) |
| 저장·수동 가격 갱신 | [가격 갱신 정책](docs/MANUAL-PRICE-REFRESH.md) |
| 가격 이력·상세 정보 | [기간별 이력](docs/PERIOD-HISTORY.md), [상세·리뷰](docs/DETAIL-INSIGHTS.md) |
| AI 구매 판단·채팅 | [판단 엔진](docs/PURCHASE-DECISION-V3.md), [구매 비서](docs/PRICE-AGENT.md) |
| 뉴스 설정·가격 영향 평가 | [NAVER API HUB](docs/NAVER-NEWS.md), [뉴스 영향 평가](docs/NEWS-PRICE-IMPACT.md) |
| 계정·관리자·찜 | [회원 인증](docs/ACCOUNT-AUTH.md), [이름·권한](docs/MEMBER-NAMES-AND-ADMINS.md), [찜](docs/LIVE-WISHLIST.md) |
| 약관·개인정보 문서 준비 | [운영 전 확인 사항](docs/LEGAL-DOCUMENTS.md) |

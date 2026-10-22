# PRIZM

AI 가격 변동·구매 타이밍 분석 서비스입니다. React, Spring Boot, Node.js, FastAPI, Firebase를 사용하며 Reef 다나와 API와 Gemini를 연결합니다.

## 준비

- Java 17, Maven, Node.js 22.12 이상, Python 3.11 권장
- Firebase 프로젝트: Authentication 이메일/비밀번호 로그인 활성화, Firestore `(default)` 데이터베이스, 서버 서비스 계정 JSON
- Reef API 키, Gemini API 키

아래 명령은 Windows PowerShell 기준입니다. 예시 경로는 `D:\github\codyssey_final`이며 다른 위치에 받았다면 바꾸세요.

## 1. 환경 설정 — 최초 한 번

각 폴더의 `.env.example`을 `.env`로 복사하고 값을 입력합니다. **기존 `.env`가 있다면 덮어쓰지 마세요.**

| 파일 | 입력할 주요 값 |
|---|---|
| `backend/api/.env` | `FIREBASE_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS`, `INTERNAL_API_KEY`, `GEMINI_MODEL` |
| `backend/collector/.env` | `REEF_API_KEY`, `INTERNAL_API_KEY` |
| `backend/ai/.env` | `GEMINI_API_KEY`, `GEMINI_MODEL`, `INTERNAL_API_KEY` |
| `frontend/.env` | `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN` |

- `INTERNAL_API_KEY`는 직접 생성한 충분히 긴 임의 문자열을 **세 서버에 동일하게** 입력합니다. 예제에는 키가 들어 있지 않습니다.
- `GEMINI_MODEL`은 Spring과 FastAPI에서 같은 모델 ID를 사용합니다.
- 서비스 계정 파일은 프로젝트 루트의 `secrets/` 안에 두고, `GOOGLE_APPLICATION_CREDENTIALS`에는 그 파일의 **절대 경로**를 입력합니다.
- 기존 `.env.example`의 실제 연결 모드 값을 유지합니다. `OPERATIONS_UIDS`에는 관측 저장·기존 분석을 사용할 Firebase 사용자 UID를 설정합니다. 일반 조회·찜에는 운영 UID가 필요 없습니다.
- 실제 클라우드 연결 시 `FIREBASE_AUTH_EMULATOR_HOST`, `FIRESTORE_EMULATOR_HOST` 환경 변수는 제거합니다.
- `.env`는 `이름=값` 형식이며 주석은 별도 줄에 씁니다. 아래 스크립트는 값을 코드로 실행하지 않고 현재 터미널의 환경 변수로 읽습니다. Spring과 FastAPI는 `.env`를 자동으로 읽지 않으므로 실행 전 이 단계가 필요합니다.

처음 프로젝트를 설정하는 경우 카테고리 조회용 Firestore 인덱스도 배포합니다. 기존 배포가 완료되어 있다면 생략합니다.

```powershell
cd D:\github\codyssey_final\firebase
npx.cmd --package=firebase-tools firebase login
npx.cmd --package=firebase-tools firebase deploy --only firestore:indexes --project 실제-프로젝트-ID
```

## 2. 의존성 설치 — 최초 또는 변경 시

```powershell
cd D:\github\codyssey_final\frontend
npm install

cd D:\github\codyssey_final\backend\collector
npm install

cd D:\github\codyssey_final
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend\ai\requirements-gemini.txt
```

기존에 사용하는 가상환경이 있다면 재생성하지 않고 해당 Python 경로를 사용하세요. Maven 의존성은 서버 실행 시 내려받습니다.

## 3. 실행 — 터미널 4개

**Node 수집기**

```powershell
cd D:\github\codyssey_final\backend\collector
npm start
```

**FastAPI / Gemini**

```powershell
cd D:\github\codyssey_final\backend\ai
& ..\..\scripts\Import-Env.ps1 -Path .env
..\..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

**Spring Boot**

```powershell
cd D:\github\codyssey_final\backend\api
& ..\..\scripts\Import-Env.ps1 -Path .env
mvn spring-boot:run
```

**React**

```powershell
cd D:\github\codyssey_final\frontend
npm run dev
```

브라우저에서 **http://127.0.0.1:5173**으로 접속합니다. 각 서버는 해당 터미널에서 `Ctrl+C`로 종료합니다. 이 실행 설정은 로컬 개발용이며 인터넷 공개 배포 설정은 별도로 필요합니다.

## 4. 확인

- 로그인 없이 검색·상품 상세·주간/월간 가격 흐름 확인
- 로그인 후 카드/상세 하트로 찜 → 상단 하트에서 목록 확인·해제
- Reef 다나와는 리뷰 본문을 제공하지 않으므로 직접 확인 안내 표시
- 실제 검색·상세·가격 이력 갱신에는 Reef 사용량이 발생할 수 있음. 정기 수집은 기본 꺼짐

빌드·테스트는 사용자가 각 폴더에서 직접 실행합니다.

```powershell
# backend/api
mvn test
# backend/collector
npm test
# backend/ai — 위에서 사용한 가상환경
..\..\.venv\Scripts\python.exe -m unittest discover -s tests
# frontend
npm run build
```

## GitHub에 올릴 때

- 실제 `.env`, 서비스 계정 JSON, 인증서/개인 키, `secrets/`, `credentials/`는 `.gitignore`로 제외합니다. `.env.example`만 키 값 없이 공유합니다.
- Firebase 설정·Firestore 규칙/인덱스는 코드이므로 저장소에 포함합니다. `사이트/`, `사이트2/` 목업 폴더는 기존 제외 설정을 유지합니다.
- `.gitignore`는 이미 커밋한 비밀을 삭제하지 않습니다. 이전에 공개한 키가 있다면 해당 공급자에서 폐기·재발급해야 합니다.
- 업로드 전 아래 명령으로 제외·추적 상태를 확인하세요. 키 값이 들어 있는 파일 내용은 출력하지 않습니다.

```powershell
cd D:\github\codyssey_final
git status --short
git check-ignore backend/collector/.env frontend/.env secrets/firebase-service-account.json
```

## 상세 안내

- [Firebase·Reef·Gemini 연결](docs/LIVE-CONNECTION.md)
- [공유 캐시·수집 주기](docs/EXTERNAL-CALL-REDUCTION.md)
- [가격 흐름·리뷰 제공 범위](docs/DETAIL-INSIGHTS.md)
- [실제 상품 찜](docs/LIVE-WISHLIST.md)
- [개발 작업 목록](TASKS.md)

서비스 실행·테스트·실제 외부 연결은 사용자 환경에서 확인해야 합니다.

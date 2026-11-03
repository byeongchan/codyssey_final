# Render + Vercel 배포

## 배포 구조

- Vercel: `frontend/` Vite 정적 빌드
- Render: 저장소 루트 Dockerfile로 Spring Boot + Node 수집기 + FastAPI를 한 서비스에 실행
- 공개 포트는 Spring Boot의 `$PORT` 하나. 수집기 4001·AI 8000은 loopback 전용이며 기존 내부 키 검사와 URL 제한을 유지한다.
- Firebase/Firestore는 기존 클라우드 서비스를 사용한다. 로컬 데이터 디스크는 필요하지 않다.

첫 배포의 설정·서비스 수를 줄이기 위한 구성이다. 세 프로세스가 메모리와 재시작을 공유하며, 하나가 종료되면 전체 컨테이너를 종료한다. 개별 확장이 필요해지면 내부 서비스로 분리해야 한다.
`render.yaml`은 **무료 Free 플랜**을 명시했다. Java·Python·Node를 함께 실행하므로 무료 인스턴스의 메모리에서 정상 동작하는지 검증해야 한다. 메모리 부족으로 종료되면 유료 플랜 전환 또는 실행 구조 조정이 필요하다. 무료 서비스는 15분 동안 요청이 없으면 중지되며 다음 요청에서 재시작하므로 첫 응답이 늦을 수 있다. Reef·Gemini·Firebase 사용 요금은 별도이다. 실제 클라우드 리소스는 이 코드 변경으로 생성되지 않는다.

## 1. 저장소 준비

Render 자동 배포는 `buildFilter`로 `backend/**`, `deploy/**`, `Dockerfile`, `.dockerignore`, `render.yaml` 변경에 한정한다. README·docs·프론트만 변경하면 백엔드 자동 빌드를 실행하지 않는다. 백엔드 폴더의 Markdown 문서도 제외한다. 단, Blueprint YAML 변경·수동 배포·서비스 설정 변경은 별도로 배포될 수 있다.
Blueprint로 관리 중이면 이 변경을 푸시하고 동기화한다. 수동 생성한 서비스는 Render → Settings → Build & Deploy → Build Filters에 동일한 Included Paths와 Ignored Paths를 등록해야 한다. Root Directory는 계속 비워 둔다.

새 Dockerfile, `.dockerignore`, `deploy/start.py`, `render.yaml`, Java CORS 설정, 프론트 소스·CSS, `frontend/vercel.json`, `.env.production.example` 등을 함께 커밋한다. 실제 `.env`와 서비스 계정은 커밋하지 않는다. 참고 디자인 폴더는 배포에 필요하지 않으며 실제 사용 이미지·당근 지역 JSON은 `frontend/public`에 있다.

로컬 명령은 기존 README대로 사용한다. Docker 실행은 Render 전용 시작 스크립트를 사용하므로 PowerShell·Windows 경로·로컬 `.env`에 의존하지 않는다. Docker 빌드는 Java 패키징 시 테스트를 생략하므로 아래 검증을 별도로 진행해야 한다.

## 2. Vercel 프로젝트 주소 확보

Git 저장소를 연결하고 Root Directory를 **frontend**, Framework를 **Vite**, Node.js를 **22.x**로 설정한다.
빌드는 `npm run build`, 출력은 `dist`이다. 현재 상세 주소는 쿼리스트링 기반이므로 임의의 catch-all rewrite는 추가하지 않았다.

먼저 운영 프로젝트 도메인(예: `https://YOUR-SITE.vercel.app`)을 확인한다. API 주소를 등록하기 전의 프론트는 정상 서비스 상태가 아니다.

## 3. Render 생성·설정

Render Blueprint에서 저장소 루트 `render.yaml`을 선택한다. 또는 Docker Web Service를 만들고 Dockerfile 경로 `./Dockerfile`, Context `.`로 지정한다. Root Directory는 비운다.
Health Check Path는 `/api/v1/health`이다. 내부 서버가 준비된 뒤 API가 시작된다. 이 상태 확인은 외부 Reef·Gemini·Firestore 기능 전체를 검증하는 호출은 아니다.

환경변수:

| 이름 | 입력값 |
| --- | --- |
| FIREBASE_PROJECT_ID | 기존 실제 Firebase 프로젝트 ID |
| GOOGLE_APPLICATION_CREDENTIALS | `/etc/secrets/firebase-service-account.json` |
| CORS_ALLOWED_ORIGINS | 실제 Vercel origin. 예: `https://YOUR-SITE.vercel.app` |
| INTERNAL_API_KEY | Blueprint 자동 생성. 수동 생성 시 임의 문자열 32자 이상 |
| REEF_API_KEY | Reef 키 |
| GEMINI_API_KEY | Gemini 키 |
| GEMINI_MODEL | 해당 키에서 사용 가능한 정확한 모델 ID |
| NEWS_PROVIDER | `naver` |
| NAVER_CLIENT_ID / NAVER_CLIENT_SECRET | NAVER API HUB 키 쌍 |
| OPERATIONS_UIDS | 기존 운영 API가 필요할 때만 설정. 커뮤니티 관리자 설정과 별개 |

`CORS_ALLOWED_ORIGINS`는 쉼표로 복수 지정할 수 있다. 각 값은 경로·마지막 슬래시 없이 정확한 origin을 쓴다. `*`와 `*.vercel.app` 전체 허용은 지원하지 않는다. 필요한 Preview 주소도 개별 등록한다.

Render **Secret Files**에 `firebase-service-account.json`이라는 이름으로 실제 서비스 계정 JSON을 등록한다. 파일 내용을 환경변수나 저장소에 붙여넣지 않는다. 이 파일이 없으면 시작이 실패한다. Blueprint 생성 직후 Secret File 추가 및 재배포가 필요할 수 있다.

Docker 기본값은 `APP_ENV=production`, `DATA_PROVIDER=reef-danawa`, `AI_PROVIDER=gemini`, `FIREBASE_MODE=cloud`, `SERVER_ADDRESS=0.0.0.0`, 기본 `PORT=10000`이다. Render가 지정한 PORT가 우선한다.
`AI_URL=http://127.0.0.1:8000`, `COLLECTOR_URL=http://127.0.0.1:4001`은 같은 컨테이너 내부 주소이므로 외부 도메인으로 변경하지 않는다.

Blueprint는 다나와·무신사·중고 수집을 모두 켜고, 예약 자동 수집·알림은 끈다. 실제 최초 조회·갱신·AI 질문에는 외부 API 사용량이 발생할 수 있다. Firebase 에뮬레이터 변수는 등록하지 않는다.

## 4. Vercel 환경변수 등록·재배포

`frontend/.env.production.example`을 참고하여 Vercel의 Production 환경변수에 등록한다.

```dotenv
VITE_API_BASE_URL=https://YOUR-BACKEND.onrender.com/api/v1
VITE_DATA_MODE=live
VITE_FIREBASE_MODE=cloud
VITE_FIREBASE_PROJECT_ID=실제프로젝트ID
VITE_FIREBASE_API_KEY=Firebase웹앱설정값
VITE_FIREBASE_AUTH_DOMAIN=Firebase웹앱설정값
```

VITE 값은 빌드에 포함되므로 환경변수를 변경한 뒤 다시 배포한다. 서버 서비스 계정·Reef·Gemini·내부 키는 Vercel의 VITE 변수에 넣지 않는다. Preview를 사용할 때도 API·Firebase 설정 및 허용 도메인을 별도로 확인한다.
프론트가 Render의 HTTPS API를 직접 호출한다. Vercel Functions/외부 프록시를 거치지 않으므로 AI 응답이 Vercel 프록시의 제한에 걸리지 않는다. 실제 Render·브라우저·네트워크에서 긴 응답 검증은 필요하다.

## 5. Firebase 및 운영 정보

- Authentication 승인된 도메인에 실제 Vercel 호스트명과 자체 도메인을 추가한다.
- 서비스 계정과 프로젝트 ID 일치, 필요한 IAM 권한, Firestore 규칙·인덱스 적용을 확인한다.
- 관리자 설정은 기존 `serviceAccess/administrators` 문서를 사용한다.
- 약관·개인정보처리방침의 운영자명·문의처·시행일 등 미정 항목을 확정한다. 임의의 운영 정보를 만들지 않는다.
- Firestore 분석 캐시의 TTL 물리 삭제 정책은 별도 Firebase 설정이다. 코드의 만료 판단만으로 문서가 즉시 삭제되는 것은 아니다.

## 6. 사용자 검증

실행·테스트는 사용자가 진행한다. 이번 배포 준비에서 Docker 이미지 빌드·실서비스 생성·실제 유료 API 호출은 수행하지 않았다.

1. `frontend`에서 `npm run build`
2. `backend/api`에서 `mvn test`
3. `backend/collector`에서 `npm test`
4. 기존 Python 환경에서 `python -m unittest discover -s backend/ai/tests`
5. Docker 사용 가능 환경에서 저장소 루트 `docker build -t joopjoop-backend .`
6. 배포 후 상태 확인, CORS preflight, 이메일 인증 로그인·내정보·찜·커뮤니티·탈퇴 흐름 확인. 탈퇴 검증은 폐기 가능한 테스트 계정만 사용한다.
7. 다나와·무신사·중고 검색/상세, 당근 동네, 최근 본 상품의 검색 근거 복원, AI 질문과 로딩·실패 안내 확인
8. Render 메모리·재시작 로그·실제 AI 응답 시간 확인

상태 API 성공만으로 Firebase와 외부 API 설정이 모두 정상이라고 판단하지 않는다. 로그에는 키·서비스 계정·회원 토큰을 출력하지 않는다.

## 공식 문서

- https://render.com/docs/docker
- https://render.com/docs/docker-secrets
- https://render.com/docs/configure-environment-variables
- https://vercel.com/docs/frameworks/frontend/vite
- https://vercel.com/docs/environment-variables

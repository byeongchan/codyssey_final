# 실제 Firebase · Reef · Gemini 연결

2026-11-03 코드 기준 운영 연결 안내입니다. 배포 URL·증거는 [README](../README.md), 실제 등록값은 [배포 안내](DEPLOYMENT.md)를 참고합니다. 빌드·테스트·실사용 검증 상태는 [TASKS](../TASKS.md)로 분리합니다. 이전 설명은 [개발 이력](archive/LIVE-CONNECTION-HISTORY.md)에 보관합니다.

## 현재 경로

- `frontend/src/main.tsx`는 `VITE_DATA_MODE=fixture`일 때만 App을 선택하고, 그 외에는 LiveApp을 표시합니다.
- 배포 프론트는 Vercel에서 Render의 HTTPS API를 직접 호출합니다. 로컬은 Vite `/api` 프록시를 사용합니다.
- Render 컨테이너는 Spring Boot 공개 API, loopback Node 수집기(4001), FastAPI(8000)를 실행합니다. API는 PORT를 사용하며 내부 키·주소 검증을 유지합니다.
- `DATA_PROVIDER=reef-danawa`, `FIREBASE_MODE=cloud`, `AI_PROVIDER=gemini`를 사용합니다. production에서 fixture 공급자를 사용하지 않습니다.
- `LiveModeFilter`는 과거 fixture 상품/채팅/수집/스냅샷/알림/운영 API를 차단합니다. 실제 다나와·무신사·중고·커뮤니티·회원·관리자 경로만 허용합니다.
- Firebase 웹 SDK는 로그인에 사용합니다. Firestore 직접 접근 대신 서버가 ID 토큰·권한을 검사하고 Admin SDK로 읽고 씁니다.
- Reef 수집은 저장 자료 최초 조회와 명시적 갱신 정책을 따릅니다. [갱신 정책](MANUAL-PRICE-REFRESH.md)이 이전 자동 만료 재수집 설명보다 우선합니다.

## 설정

로컬 `.env` 예제 4개와 PowerShell 실행 명령은 [README](../README.md#개발-환경-준비)를 사용합니다. 실제 `.env`는 덮어쓰지 않습니다.

| 위치 | 필수 설정 |
| --- | --- |
| 프론트 | VITE_API_BASE_URL, VITE_DATA_MODE=live, Firebase 웹 설정 |
| Spring API | FIREBASE_PROJECT_ID, GOOGLE_APPLICATION_CREDENTIALS, INTERNAL_API_KEY, AI_URL, COLLECTOR_URL, GEMINI_MODEL |
| Node | REEF_API_KEY, INTERNAL_API_KEY, 플랫폼별 REEF_*_ENABLED |
| FastAPI | GEMINI_API_KEY, GEMINI_MODEL, INTERNAL_API_KEY, NEWS_PROVIDER 및 NAVER/RSS 설정 |

내부 키는 세 서버에서 일치시키며 API와 AI의 모델 ID도 같아야 합니다. 서비스 계정은 서버에서만 읽습니다. 클라우드 모드에서는 Firebase Emulator 환경변수를 제거합니다.

## 기능별 실제 사용

- [다나와 가격 이력](PERIOD-HISTORY.md)·[구매 판단](PURCHASE-DECISION-V3.md)·[가격 상담](PRICE-AGENT.md)
- [무신사 분류·상세·리뷰](MUSINSA.md) — 무신사 전용 AI 상담은 현재 연결하지 않음
- [중고 통합 검색](USED-MARKET.md)·[가격/지역/모델 분석과 상세 상담](USED-AGENT.md)
- [인증](ACCOUNT-AUTH.md)·[찜](LIVE-WISHLIST.md)·[커뮤니티](COMMUNITY.md)

실제 상담은 질문·최근 대화·상품/API 정보·서버 계산 근거를 목적에 맞게 전달합니다. 초기 문서의 “질문·상품명을 보내지 않는 단순 설명만 제공”은 현재 상담 경로 설명이 아닙니다. 개인정보 처리 범위는 실제 정책과 함께 확인합니다.

## 검증 상태

배포·헬스체크 증거와 사용자 보고 빌드/Java 테스트 성공을 확인했습니다. Node/Python 테스트, 모든 플랫폼의 실제 응답, Emulator 통합 테스트와 외부 사용자 테스트는 별도 결과 제출이 필요합니다. 상태 API 성공은 외부 API 전체의 성공을 뜻하지 않습니다.

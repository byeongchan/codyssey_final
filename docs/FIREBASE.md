> 실제 Firebase Cloud · Reef · Gemini 통합 연결은 [LIVE-CONNECTION.md](LIVE-CONNECTION.md)를 먼저 참고하세요. 이 문서의 에뮬레이터/더미 실행 절차는 이전 개발 모드 안내입니다.

# Firebase 로컬 로그인·찜 실행 안내

현재는 **Authentication Emulator + Firestore Emulator**만 지원한다. 상품·가격·분석은 기존 fixture이고, 계정·찜은 로컬 에뮬레이터에 저장한다. Firebase 콘솔 프로젝트·결제·서비스 계정 파일 없이 `demo-prizm` 프로젝트로 개발한다. 실제 클라우드 Firebase 연결은 아직 구현하지 않았다.

설치·실행·테스트는 수행하지 않았다. 아래 명령은 사용자가 직접 실행한다. 공식 연결 방식: [Authentication Emulator](https://firebase.google.com/docs/emulator-suite/connect_auth), [Firestore Emulator](https://firebase.google.com/docs/emulator-suite/connect_firestore), [Java Admin SDK](https://firebase.google.com/docs/admin/setup).

## 1. 준비

- Node.js 22.12 이상과 JDK 21 이상을 권장한다. Java 소스의 컴파일 기준은 기존 17을 유지하지만 최신 Firestore 에뮬레이터 사용을 위해 Java 21을 권장한다.
- 포트: Auth 9099, Firestore 8085, Emulator UI 4000. Spring 8080과 충돌하지 않도록 구성했다.
- 실제 계정의 비밀번호를 사용하지 말고 `alice@example.test`, `bob@example.test` 등 로컬 테스트 계정을 사용한다.
- 웹·서버·에뮬레이터의 프로젝트 ID는 모두 `demo-prizm`으로 맞춘다.

## 2. 에뮬레이터 시작 — 별도 PowerShell

```powershell
cd D:\github\codyssey_final\firebase
npx firebase-tools@latest emulators:start --project demo-prizm --only "auth,firestore" --export-on-exit ./emulator-data
```

최초 실행에는 CLI와 에뮬레이터 다운로드가 필요하다. Firebase 계정에 로그인하거나 실제 프로젝트를 만들 필요는 없다. UI는 `http://127.0.0.1:4000`이다. 종료 시 Ctrl+C로 정상 종료하면 로컬 상태를 `firebase/emulator-data/`에 내보낸다. 해당 폴더는 Git에서 제외했다.

내보낸 폴더가 생성된 이후에는 다음 명령으로 기존 계정·찜을 복원할 수 있다.

```powershell
npx firebase-tools@latest emulators:start --project demo-prizm --only "auth,firestore" --import ./emulator-data --export-on-exit ./emulator-data
```

에뮬레이터 데이터를 초기화하면 브라우저에 남은 로그인 세션은 무효화될 수 있다. UI에서 로그아웃 후 다시 가입/로그인한다.

## 3. Spring Boot 환경 변수 추가 후 재시작

기존 API 실행 터미널에서 서버를 종료한 뒤 아래 환경 변수를 **같은 터미널**에 추가한다. 기존 Reef fixture·AI URL·내부 서비스 키 설정은 유지한다.

```powershell
cd D:\github\codyssey_final\backend\api
$env:FIREBASE_MODE = 'emulator'
$env:FIREBASE_PROJECT_ID = 'demo-prizm'
$env:FIREBASE_AUTH_EMULATOR_HOST = '127.0.0.1:9099'
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8085'
mvn spring-boot:run
```

HOST 환경 변수에는 `http://`를 붙이지 않는다. 현재 코드는 명시한 로컬 주소와 `demo-` 프로젝트만 허용한다. 설정이 없으면 공개 상품 조회는 유지하고 인증이 필요한 기능은 503을 반환한다. 미로그인 요청은 설정 검사에 앞서 401을 반환한다. 에뮬레이터가 꺼지면 인증/저장소 오류를 반환하며 메모리 찜으로 대체하지 않는다.

## 4. 프론트엔드 의존성 설치·재시작

Firebase Web SDK를 새 의존성으로 추가했으므로 `npm install`을 다시 수행한다. 설치 시 사용 중인 잠금 파일도 갱신된다.

```powershell
cd D:\github\codyssey_final\frontend
npm install
```

기존 `.env`에 다음 값을 추가한다. `.env.example` 전체를 복사해 기존 설정을 덮어쓰지 않아도 된다.

```dotenv
VITE_FIREBASE_MODE=emulator
VITE_FIREBASE_PROJECT_ID=demo-prizm
```

```powershell
npm run dev
```

`http://127.0.0.1:5173`에서 사용자 아이콘 → 회원가입/로그인으로 진입한다. 로컬 인증을 끄려면 `VITE_FIREBASE_MODE=disabled`로 바꾸고 Vite를 재시작한다. 현재 실제 Firebase API 키는 필요하지 않다. 프론트엔드는 Firestore SDK를 직접 사용하지 않고 `/me/**` API로만 찜을 조회·변경한다.

## 5. 직접 확인할 흐름

1. 미로그인에서 상품 상세의 찜하기를 누르면 로그인 패널이 열리는지 확인한다.
2. Alice로 회원가입하고 소니 상품 상세에서 찜하기를 누른다. 성공 후 찜 해제로 바뀌어야 한다.
3. 헤더의 하트 → 찜 목록에 해당 상품이 표시되는지, 새로고침 후에도 유지되는지 확인한다.
4. 다른 브라우저에서 같은 계정으로 로그인해 같은 목록을 확인한다. 현재 목록은 조회 시 갱신하며 실시간 푸시 동기화는 제공하지 않는다.
5. 로그아웃 후 Bob으로 가입/로그인한다. Alice의 찜 목록이 보이면 안 된다. Bob이 같은 상품을 찜해도 서로의 저장 문서는 분리되어야 한다.
6. Bob의 찜을 해제한 뒤 Alice로 돌아오면 Alice의 찜은 그대로여야 한다.
7. 동일 상품을 여러 번 PUT해도 문서가 하나이고 최초 createdAt이 유지되는지 Emulator UI에서 확인한다. DELETE는 미등록 상품에도 정상 응답한다.
8. 로그인 토큰 없이 `/api/v1/me/wishlist`에 요청하면 401과 WWW-Authenticate: Bearer가 반환되어야 한다. 변조 토큰도 401이다.
9. Alice 토큰의 요청에 `?uid=bob`를 추가해도 Alice의 경로만 사용해야 한다. `/users/{uid}/wishlist` 형태의 공개 API는 없다.
10. Emulator UI에서 계정을 비활성화하거나 삭제한 뒤 해당 세션으로 요청한다. 서버의 토큰 검증·사용자 상태 확인에서 거부되어야 한다.
11. 에뮬레이터를 종료하면 기존 상품/가격 조회는 유지되고 로그인·찜에서 오류가 표시되어야 한다. 데이터가 저장됐다는 성공 표시로 바꾸면 안 된다.

Firestore 경로는 `users/{verifiedUid}/wishlist/{productId}`다. 저장 필드는 productId·createdAt이며 상품 스냅샷을 복제하지 않는다. 목록은 문서 ID 오름차순이며 기본 20개·최대 50개, nextCursor로 다음 페이지를 조회한다. 삭제된 상품도 목록에서 해제할 수 있다.

Firestore 규칙은 클라이언트 직접 접근을 모두 거부한다. Admin SDK는 규칙을 우회하므로 서버의 ID 토큰 검증과 검증 UID 경로 고정이 접근 통제를 담당한다. 현재 서버는 매번 `verifyIdToken(token, true)`로 폐기·비활성 여부까지 확인한다. 보호된 응답은 no-store이며 계정 변경 시 화면의 목록/찜 상태를 초기화한다.

## 6. 작성된 테스트 실행

```powershell
cd D:\github\codyssey_final\backend\api
mvn test
```

미로그인 차단, 검증 UID만 사용, 사용자별 목록 분리, 만료·폐기 토큰, 클라우드 연결 거부, OpenAPI 경로/참조 검사를 포함한다. 단위 테스트는 Firebase SDK·저장소를 모킹하므로 에뮬레이터가 필요 없다. 실제 Firestore 저장·보존·규칙 적용은 위 수동 흐름으로 별도 확인한다.

목표 가격 설정·인앱 알림을 추가했으며 실행/검증 방법은 [알림 안내](ALERTS.md)를 따른다. 최근 비교 기록과 계정 삭제는 [개인 데이터 안내](PERSONAL-DATA.md), 상품별 더미 구매 비서 대화는 [채팅 안내](CHAT.md)를 따른다. 클라우드 배포·이메일 인증/비밀번호 재설정·소셜 로그인·유료 분석 작업의 사용자 소유권 처리는 남아 있다.


공용 더미 가격 스냅샷과 저장 영수증의 원자적 보관은 [스냅샷 안내](SNAPSHOTS.md)를 따른다. 이 모드에서도 동일한 로컬 에뮬레이터 설정을 사용하며, 클라이언트 규칙을 허용하지 않는다.


CATALOG_SOURCE=snapshot으로 저장 근거를 조회에 사용하는 설정·검증은 [조회 소스 안내](CATALOG-SOURCE.md)를 따른다. 기본 fixture 모드는 기존대로 조회 시 DB 연결을 요구하지 않는다.


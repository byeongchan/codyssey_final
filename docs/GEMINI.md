> 실제 Firebase Cloud · Reef · Gemini 통합 연결은 [LIVE-CONNECTION.md](LIVE-CONNECTION.md)를 먼저 참고하세요. 이 문서의 에뮬레이터/더미 실행 절차는 이전 개발 모드 안내입니다.

# Gemini 설명 연동

코드·모킹 테스트·명세를 작성했다. 설치·서버 실행·빌드·테스트·실제 Gemini 호출은 수행하지 않았다. 기본값은 fixture다. 상품·가격·리뷰·채팅은 계속 더미이며 Gemini는 서버 구매 판단의 설명만 생성한다.

## 구현 범위

FastAPI가 Google의 generateContent REST API를 호출한다. SDK 대신 httpx를 선택하여 자동 재시도 없이 요청을 한 번만 보낸다. 고정 Google HTTPS 주소와 x-goog-api-key 헤더를 사용하며 리다이렉트·환경 프록시를 사용하지 않는다. 응답은 responseMimeType=application/json, responseJsonSchema로 이유·주의사항만 요청한다. 검색·도구·대화 이력은 보내지 않는다.

Gemini에 전달하는 내용은 서버 verdict·reasonCode·합성 현재가·평균가·관측 일수뿐이다. UID·이메일·사용자 질문·토큰·내부 키·상품 ID·근거 ID는 프롬프트에 넣지 않는다. 상품·근거·버전·시각은 FastAPI가 원래 요청에서 응답에 붙인다. generatedAt은 실제 모델 생성 시각이 아니라 기존 더미 기준 시각이며 synthetic=true를 유지한다.

출력 토큰 상한 768, 응답 본문 64KiB, 설명 JSON 6000자, 이유/주의사항 각 1~3개·문장별 200자를 제한한다. 숫자·일부 보장 문구도 검사한다. STOP 완료만 허용하며 차단·토큰 소진·빈 응답·형식 오류·429·서버 오류는 재시도 없이 실패시킨다. httpx 단계별 타임아웃 4초·연결 2초와 읽는 동안 경과 시간 6초 검사를 사용한다. 엄밀한 전체 6초 마감이나 취소 전파는 아니며 Spring은 기존 8초 요청 타임아웃을 사용한다.

Spring은 설정된 공급자·정확한 모델명·가격 규칙/설명 버전·근거 ID·문장을 검증한다. 실패는 SERVER_FALLBACK으로 기본 설명을 제공하며 가격 판단은 바꾸지 않는다. 데이터 부족/만료는 AI 없이 SERVER_RULE_ONLY다. 성공하면 GEMINI_EXPLANATION과 모델명을 표시한다. 자연어의 모든 의미적 모순을 검출하지는 않는다.

## 사용자 직접 설정·실행

먼저 기존 Firebase 에뮬레이터·로그인·프론트엔드 설정을 유지한다. 실제 요청은 설정 후 사용자가 분석을 생성할 때 발생하며 계정의 모델 사용량이 소비될 수 있다.

1. Google AI Studio에서 API 키와 계정에서 사용 가능한 구조화 출력 지원 모델 ID를 확인한다. 모델을 코드에 고정하지 않았다. 아래 gemini-3.8-flash는 공식 문서 예시이며 계정에서 지원하는 ID로 변경한다.
2. FastAPI 터미널에서 옵션 의존성을 설치하고 환경 변수를 설정한다. 아래 키 입력은 PowerShell 7의 마스킹 입력이다. 키를 .env.example·채팅·프론트엔드·Spring에 기록하지 않는다.

```powershell
cd D:\github\codyssey_final\backend\ai
.\.venv\Scripts\python.exe -m pip install -r requirements-gemini.txt
$env:APP_ENV = 'development'
$env:AI_PROVIDER = 'gemini'
$env:GEMINI_MODEL = 'gemini-3.8-flash'
$env:GEMINI_API_KEY = Read-Host 'Gemini API 키' -MaskInput
$env:INTERNAL_API_KEY = 'your-local-development-key'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

3. Spring 터미널에서는 키 없이 같은 공급자·모델명을 설정하고 재시작한다. 기존 Firebase·fixture·내부 키 환경 변수도 유지한다.

```powershell
cd D:\github\codyssey_final\backend\api
$env:AI_PROVIDER = 'gemini'
$env:GEMINI_MODEL = 'gemini-3.8-flash'
mvn spring-boot:run
```

AI_PROVIDER나 모델명이 두 서버에서 다르면 FastAPI가 외부 호출 전에 503으로 거부하며 Spring은 기본 설명을 사용한다. 공급자·모델명은 분석 캐시 키에 포함하며 통합 분석 버전은 fixture-v3다. 기존 price-rules-v1과 explanation-v1은 유지한다.

FastAPI는 .env를 자동으로 읽지 않는다. 키 또는 모델이 누락되거나 httpx 의존성이 없으면 Gemini 모드 시작을 거부한다. Spring은 잘못된 공급자/모델 형식이면 시작을 거부한다. /health의 provider=gemini는 설정 상태이며 키 유효성·외부 연결 성공을 검사하지 않는다.

4. 로그인 후 소니처럼 근거가 충분한 상품의 분석을 요청한다. 화면에서 서버 규칙 판단, Gemini 설명·모델명, 가격 근거는 더미라는 표시를 확인한다. 판단 보류 상품은 Gemini를 호출하지 않는다.
5. 키 변경·오류·복구 검증은 새 캐시가 없는 상태에서 수행한다. 기본 설명도 최대 15분 재사용하므로 즉시 재요청하면 외부 호출을 하지 않을 수 있다. 캐시 만료 또는 API 재시작으로 새 작업을 만들되 사용자 POST 한도는 재시작해도 남는다.

더미 모드로 복원하려면 두 서버에서 AI_PROVIDER=fixture로 바꾸고 재시작한다. FastAPI 터미널의 Gemini 키는 더 이상 필요하지 않다.

```powershell
$env:AI_PROVIDER = 'fixture'
Remove-Item Env:GEMINI_API_KEY -ErrorAction SilentlyContinue
```

## 사용자 검증

- fixture 모드에서는 키가 있어도 Gemini 요청이 없어야 한다.
- Gemini 모드 성공 결과는 model이 설정값과 같고 explanationSource=GEMINI_EXPLANATION이어야 한다. 서버 verdict가 모델 응답으로 바뀌면 안 된다.
- 잘못된 키·모델 불일치·네트워크 장애·응답 차단·잘린 JSON은 내부 503 또는 Spring 요청 실패 후 서버 기본 설명으로 대체되어야 한다. 원본 Google 오류나 API 키가 응답에 나오면 안 된다.
- 맥북 부족 이력·만료 근거는 SERVER_RULE_ONLY이며 외부 요청이 없어야 한다.
- 응답에 verdict·가격 추가, 숫자·보장 문구, 모델명 불일치가 있으면 검증에서 거부해야 한다.
- 사용자별 작업/캐시·POST 60초/5회 제한은 기존대로 동작해야 한다. 일일 비용 예산·분산 작업 큐·공급자 공통 rate limit은 아직 없다.

모킹 테스트는 정상 구조·프롬프트 비밀정보 제외·완료 상태·잘린 출력·문구 검사·설정 불일치·예외 비노출·Spring 모델 검증을 다룬다. 실제 API 키나 네트워크는 사용하지 않도록 작성했다. 사용자가 직접 실행한다.

```powershell
cd D:\github\codyssey_final\backend\ai
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

```powershell
cd D:\github\codyssey_final\backend\api
mvn test
```

```powershell
cd D:\github\codyssey_final\frontend
npm run build
```

공식 참고: [generateContent API](https://ai.google.dev/api/generate-content), [구조화 출력](https://ai.google.dev/gemini-api/docs/structured-output). 구조화 JSON은 의미적 정확성까지 보장하지 않으므로 실제 응답 평가는 별도로 수행한다.

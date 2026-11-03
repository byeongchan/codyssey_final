> 검증 상태(2026-11-03): 아래의 “미실행” 표현은 작성 당시 기록입니다. 현재 프론트 빌드·Java 테스트 성공 보고와 배포 증거는 [TASKS](../TASKS.md)에 구분해 기록했습니다. 개별 실제 API·계정 시나리오와 Node/Python 테스트까지 통과했다는 의미는 아닙니다.

> 최신 구현: [뉴스 가격 영향 평가·대화형 구매 비서](NEWS-PRICE-IMPACT.md). 상품군별 다중 검색과 별도 인과 경로 평가를 사용합니다. 아래 단일 카테고리 검색 설명은 이전 동작이며 최신 문서가 우선합니다. API HUB 주소·인증 설정은 동일합니다.

# NAVER API HUB 뉴스 검색 연결

공식 기준: [NAVER API HUB 뉴스 검색 결과 조회](https://api.ncloud-docs.com/docs/naver-api-hub-search-news).

## 설정

1. NAVER Cloud의 **NAVER API HUB** 이용 설정과 해당 검색 API 이용 권한을 확인합니다. 기존 네이버 개발자 센터 검색 Open API와 인증 주체가 다릅니다.
2. NAVER API HUB에서 발급된 Client ID / Client Secret을 `backend/ai/.env`에 입력합니다.

```dotenv
NEWS_PROVIDER=naver
NAVER_CLIENT_ID=API_HUB_Client_ID
NAVER_CLIENT_SECRET=API_HUB_Client_Secret
```

실제 값은 `.env.example`이나 프론트엔드에 기록하지 않습니다. `.env`는 기존 `.gitignore`로 제외됩니다. 이번 변경은 실제 `.env`를 읽거나 수정하지 않습니다.

`rss_settings.local_settings`는 요청한 이름만 읽으며 로컬 `backend/ai/.env` > 프로세스 환경 변수 > 기본값 순입니다. 네이버 설정은 서버 내부에서만 사용하고 로그·응답·Firestore·Gemini 입력에 포함하지 않습니다. 키가 바뀌면 프로세스 내 검색 캐시 키도 바뀝니다. 추가 라이브러리는 필요하지 않으며 기존 `requirements-gemini.txt`의 httpx를 사용합니다.

FastAPI와 Spring Boot를 재시작하고 프론트엔드를 새로고침한 후, 로그인한 상품 상세에서 ‘관련 뉴스가 있나요?’ 또는 구매 시점을 질문합니다. 모델 호출과 검색을 원하지 않는 사양/판매처 질문은 기존대로 뉴스 검색을 생략합니다. 키 미설정 시 RSS로 자동 대체하지 않습니다. RSS를 선택하려면 명시적으로 `NEWS_PROVIDER=rss`로 설정합니다.

## 처리 방식

- FastAPI의 기존 `/internal/price-agent/news`에서 `news_sources.py`가 공급자를 선택합니다. 네이버 연결은 `naver_news.py`에 분리했습니다. Spring API 경로와 Firebase 권한은 그대로입니다.
- 고정 주소 `https://naverapihub.apigw.ntruss.com/search/v1/news`에 GET, 서버 헤더 `X-NCP-APIGW-API-KEY-ID` / `X-NCP-APIGW-API-KEY`으로 인증합니다. 분류된 카테고리명(예: 노트북, 스마트폰)을 query로 보내고 `sort=date`, `display=100`, `start=1`, `format=json`을 사용합니다. 페이지 추가 요청과 자동 재시도는 없습니다.
- API에 기간 필터 파라미터가 없으므로 반환된 `pubDate`를 서버가 파싱해 조회 시각 기준 최근 30×24시간 안의 결과만 유지합니다. 날짜 누락·파싱 오류·미래 날짜는 제외합니다.
- 공식 문서에서 `pubDate`는 **네이버에 제공된 시각**이며, 비제휴 기사는 **원문이 제공된 시각**입니다. 원문 최초 발행일 검증을 의미하지 않습니다. 기존 호환 필드 `publishedAt`에 저장하되 `dateBasis=NAVER_PROVIDED_AT`을 함께 전달하고 화면과 AI 설명에서도 ‘네이버 제공일’로 표시합니다.
- 서버에서 전달한 카테고리 키워드·별칭 중 하나 이상이 제목·검색 요약에 있으면 선별합니다. 같은 카테고리의 다른 브랜드·모델도 시장 배경 정보로 참고합니다. 선택 상품의 할인이나 가격 변화를 직접 입증하는 것은 아닙니다.
- HTML 표시 태그를 제거하고 원문 링크(없으면 뉴스 링크)를 사용합니다. 중복 URL/동일 도메인의 같은 제목을 제거한 뒤 최신순 최대 5건만 AI와 화면에 전달합니다. `publisher`는 API에 없는 언론사 이름을 추측하지 않고 링크 도메인으로 표시합니다.
- 기사 본문은 수집하지 않습니다. AI는 API의 제목·검색 요약·출처·제공일만 참고합니다. 별도 벡터 DB 없이 검색 결과를 답변 근거로 주입하는 retrieval 방식입니다. 뉴스만으로 서버의 BUY/WAIT나 확인 행사를 임의 확정하지 않습니다.
- 검색 범위는 최신순 첫 100건입니다. 결과가 없어도 웹 전체에 관련 기사가 없다는 뜻은 아닙니다. 일별 가격·구매 판단의 필수 자료로 강제하지 않습니다.
- 카테고리·별칭 검색별 성공 결과는 프로세스 내 최대 128개/15분, 오류·미설정은 1분 재사용합니다. 캐시를 읽을 때도 30일 경계를 다시 확인합니다. 여러 FastAPI 인스턴스 간에 검색 캐시는 공유하지 않습니다. 최종 AI 응답은 기존 Spring/Firestore 공유 캐시를 이용합니다.

## 확인 방법

이번 변경에서는 API 호출·테스트·서버 실행을 하지 않습니다. 사용자가 실행할 테스트:

```powershell
cd E:\github\codyssey_final\backend\ai
..\..\.venv\Scripts\python.exe -m unittest discover -s tests -p test_naver_news.py
..\..\.venv\Scripts\python.exe -m unittest discover -s tests -p test_rss_settings.py
```

기존 환경 변수 설정과 FastAPI 실행 방법은 README를 따릅니다. 실행 후 Network에서 agent 응답의 `data.news.provider=naver`, `apiVariant=NAVER_API_HUB`, `state`, `articles`를 확인합니다. 관련 뉴스 근거 영역에 검색 상태를 표시합니다. 검색 성공 후 조건에 맞는 기사가 없으면 ‘최근 관련 뉴스 없음’, 있으면 기사 개수와 제목·검색 요약·출처·제공일을 표시합니다. 검색 실패를 기사 없음으로 표시하지 않습니다.

| state | 의미/확인 항목 |
|---|---|
| READY | 검색 성공. articles가 비면 해당 검색 범위에서 조건에 맞는 기사 없음 |
| NOT_CONFIGURED | backend/ai/.env의 Client ID와 Secret 입력 여부 확인 |
| AUTH_FAILED | 인증 또는 권한 오류. NAVER API HUB 키 값과 해당 API 이용 권한 확인 |
| RATE_LIMITED | 호출 제한 응답. 자동 재시도하지 않으며 API 사용량 확인 |
| FAILED | 네트워크·시간 제한·응답 형식 등을 확인 |

검색 상태는 근거 영역에 짧게 안내합니다. 가격 흐름 질문도 뉴스를 검색하며, 사양·판매처 질문은 검색 생략을 표시합니다. 키 값이나 공급자 원본 오류는 반환하지 않습니다.

## 인증 오류 진단

기존에는 401/403을 모두 인증 오류로 묶었지만 이제 HTTP 상태와 제한된 형식의 공급자 오류 코드를 표시합니다. 예: `HTTP 401 / 코드 200`. 게이트웨이 `error.errorCode` 또는 Search API `errorCode`를 구분해 읽으며 원본 메시지·details·키는 로그나 응답에 넣지 않습니다.

[공통 문서](https://api.ncloud-docs.com/docs/naver-api-hub-overview)에 따르면 401은 Client ID/Secret 또는 애플리케이션 API 권한을 확인해야 하며 403은 요청 형식도 확인해야 합니다. 콘솔의 All Services > Application Services > NAVER API HUB > Application에서 해당 앱의 API 선택과 인증 정보를 확인하세요. 같은 앱의 Client ID/Secret 쌍을 backend/ai/.env에 입력해야 합니다.

첨부된 NAVER API HUB 개요와 대조해 HTTPS 주소, Client ID/Secret 헤더, GET 본문·Content-Type 미전송, 쿼리 UTF-8 인코딩을 확인했습니다. HTTP 300/404는 API_NOT_FOUND, 400은 INVALID_REQUEST로 구분하고 300도 화면의 HTTP 코드에 포함합니다. 본문이 있는 POST 전용 errMsg/errId/body 오류 형식은 뉴스 GET 연결의 처리 대상이 아닙니다. 실제 인증·API 호출은 수행하지 않았습니다.

## 카테고리 기준 검색 (v10)

상품명·모델명을 네이버 검색어로 보내지 않습니다. Spring의 NewsCategory가 `liveDanawaCatalog/{id}.summaryJson.category`를 우선하고, 없으면 상품명에서 알려진 카테고리를 규칙으로 분류합니다. 그마저 없으면 저장된 발견 카테고리 ID가 하나로 명확할 때만 넓은 분류를 사용합니다. 분류 실패 시 CATEGORY_UNKNOWN으로 구분하고 상품명으로 대체 검색하지 않습니다.

분류와 별칭의 원본은 `backend/api/src/main/resources/news-categories.json`입니다. FastAPI는 분류를 중복 추측하지 않고 `category`, `keywords`, `categoryBasis`를 전달받아 검색합니다. 뉴스 응답에 `searchBasis=CATEGORY`와 카테고리를 담고 화면에는 '검색 카테고리: 노트북'처럼 표시합니다. 같은 카테고리와 별칭을 쓰는 상품끼리는 검색 캐시를 재사용합니다.

분류 규칙은 주요 상품군부터 지원하며 자동 분류 오류나 미지원 상품군이 있을 수 있습니다. 표시된 카테고리를 확인해 별칭을 조정하세요. 추가 API 호출이나 Firebase 인덱스는 필요하지 않습니다.

사용자 실행 검증: `backend/api`에서 `mvn "-Dtest=NewsCategoryTest" test`, `backend/ai`에서 기존 `test_naver_news.py` 명령을 실행하세요. 이번 작업에서 테스트와 실제 API 호출은 수행하지 않았습니다.

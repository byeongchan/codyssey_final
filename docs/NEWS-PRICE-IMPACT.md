# 뉴스 기반 가격 영향 평가

## 변경 전 코드 확인

- `backend/ai/app/naver_news.py::_fetch`: NAVER API HUB 뉴스 GET `/search/v1/news`, Client ID/Secret 헤더, `query/display/start/sort/format`을 사용합니다. 응답은 `items.title/description/pubDate/originallink/link`를 읽습니다. 이번 작업에서 실제 API 호출은 하지 않았으며 제공된 공식 문서·응답 예시와 코드·테스트 입력을 대조했습니다. live 응답이나 기사 전문을 확인했다고 간주하지 않습니다.
- `NewsCategory.java`와 `news-categories.json`: 저장된 카테고리 또는 상품 텍스트의 로컬 분류를 사용해 카테고리 검색어 하나를 생성합니다. 상세의 maker/brand/intro는 기존에 정규화되어 있지만 뉴스 요청에는 충분히 전달되지 않았습니다.
- `naver_news.py::news_for`: 카테고리별 검색 결과를 프로세스 메모리에서 15분 재사용합니다. `select_articles`는 카테고리·모델 키워드와 가까운 가격 문장 위주라 간접 공급망 기사를 놓칩니다. 직전 중단 작업에 RSS의 product_title 인자 불일치도 남아 있었습니다.
- `PriceAgentController`: 뉴스/구매시점/가격 질문에서 근거를 구성해 Gemini에 전달합니다. 최종 답변은 Firestore 공유 캐시를 사용합니다. 원문 후보나 평가 탈락 기사는 따로 Firestore에 저장하지 않습니다.
- `app/prompts/price_agent.txt`가 실제 단일 시스템 프롬프트입니다. 서버의 구매 판단을 변경할 수 없고 제목·검색 요약만 사용합니다.
- `frontend/src/PriceAgentChat.tsx`: 상태, 카테고리, 기사 제목·요약·제공일·링크를 표시합니다. 관련 근거 없음과 검색 실패를 구분합니다.
- 관련 테스트: `test_naver_news.py`, `test_price_agent.py`, `test_rss_settings.py`, `NewsCategoryTest`, `PriceAgentControllerTest`. 설정: NEWS_PROVIDER, NAVER_CLIENT_ID, NAVER_CLIENT_SECRET. 실제 .env 값은 읽거나 수정하지 않습니다.

## 목표 파이프라인

정규화된 상품 → 상품군별 제한된 검색 주제 → 쿼리별 네이버 수집·캐시 → 날짜/설명/중복 정리 → 관련성 및 가격 영향 경로 평가 → 근거/참고 기사 구분 → 최종 선별 기사만 Gemini 전달.

정책·가중치·임계값은 `backend/ai/app/news-impact-policy.json`의 검증 전 초기 설정입니다. 검색과 평가는 분리하며 평가에 추가 AI 호출은 사용하지 않습니다. 기사 요약의 표현을 바탕으로 한 규칙 평가이므로 인과관계의 실증이나 전문 검증을 대신하지 않습니다.

## 구현과 비용 상한

`news_impact.normalize_product/search_plan/evaluate/rank_articles`가 정규화·검색 주제·가격 경로·최종 선별을 담당합니다. 기존 `_fetch`의 API 주소·헤더·응답 필드는 유지합니다. 기본값은 검색 최대 4개, 각 최신순 100건, 날짜/설명/중복 제거 후 최대 60건 평가, 최종 5건이며 그중 참고 기사는 최대 2건입니다. 공급자 자동 재시도와 기사 본문 크롤링은 없습니다.

검색 목록은 그래픽카드, 스마트폰, 노트북/PC, 메모리, 생활가전, 커피, 쌀, 일반 식품, 기타로 나뉩니다. 직접 모델 가격, 기업/생산, 부품·원자재, 정책 등을 순서대로 선택하며 경쟁 신제품 주제는 프로필의 후보 목록에 포함합니다. 상한을 넘는 후보는 실행하지 않습니다. 순서와 상한은 설정으로 조정합니다. 식품에 GPU 검색어를 적용하지 않습니다.

GDDR7 같은 부품은 상품명·소개에 실제 언급된 경우에만 `PRODUCT_TEXT`로 기록합니다. 이는 원본 텍스트의 주장이지 독립 검증은 아닙니다. 미지원/제외 문맥은 건너뜁니다. 일반 GPU 프로필의 GDDR 계열은 `possibleInputs`로만 존재하고 실제 상품 사양에 저장하지 않습니다. 수동 보완은 `productMappings[상품ID].components`에 name, aliases, verified=true, source를 명시할 때만 사용합니다. 이 매핑은 운영자가 확인한 출처를 기록해야 하며 코드가 source의 내용을 검증한 것으로 취급하지 않습니다. 어떤 경우에도 상품 카탈로그를 추측한 사양으로 갱신하지 않습니다.

쿼리별 원본 응답은 프로세스 내 96개/15분, 오류는 1분 재사용합니다. 같은 쿼리의 동시 요청을 묶고, 전체 네이버 호출 동시성도 기본 2개로 제한합니다. 자리가 부족하면 검색 실패로 구분하며 기사 없음으로 바꾸지 않습니다. 다른 상품도 같은 산업 검색을 재사용할 수 있습니다. 정규화 상품·후보 기사·정책 해시별 평가 결과는 별도 128개/15분 캐시로 재사용합니다. 30일 경계와 보완 근거의 오래됨 기준은 캐시를 읽을 때도 반영합니다. 여러 FastAPI 인스턴스 사이의 검색 캐시는 공유하지 않습니다.

## 점수와 선별

초기 가중치: 모델 힌트 15, 기업 8, 카테고리 12, 공급망 14, 정책 10, 영향 경로 20, 최신성 7, 출처 6, 설명 충분성 8입니다. 각 차원 0~1에 가중치를 곱하고 총 가중치로 나눈 뒤 100점으로 환산합니다. **점수는 관련성 규칙 점수이며 가격 변동 확률이나 기사 진실성 점수가 아닙니다.**

최신성은 `max(0,1-경과일/30)`, 설명 충분성은 초기 설명 길이 기준 대비 비율입니다. 도메인 목록에 있으면 출처 차원 1, 그 외 공개 도메인은 0.25라는 초기 가설을 사용합니다. 이것은 실제 신뢰도 검증 결과가 아니므로 `sourceAssessment`와 한계에 명시합니다. 목록에 있어도 전문·사실 검증을 의미하지 않습니다.

| 단계 | 초기 기준 | AI 전달 |
|---|---|---|
| IRRELEVANT | 관련 연결 또는 설명 부족 | 제외 |
| RELATED_ONLY | 관련성이 있고 20점 이상이지만 가격 경로 부족 등 | 제외·집계만 |
| REFERENCE | 가격 영향 경로 후보가 있고 35점 이상 | 참고 한도를 적용해 선별 |
| EVIDENCE | 60점 이상 + 지정 출처 + 충분한 설명 + 최근 14일 + 방향 근거 | 보완 근거로 선별 |

EVIDENCE는 전망/부정 표현, 상품 탑재가 확인되지 않은 간접 부품만의 연결, 범위 미확인 정책 기사, AI 추정 분류에서는 허용하지 않습니다. 이 단계도 최종 BUY/WAIT를 결정하는 확정 근거가 아닙니다. 모델·브랜드가 다른 단일 할인 기사는 경쟁 효과가 명시되지 않으면 제외합니다. 단순 기업 실적·주가·제품 소개에는 가격 경로를 자동 부여하지 않습니다.

상승/하락 신호를 동시에 검토해 UPWARD_PRESSURE / DOWNWARD_PRESSURE / MIXED / UNCERTAIN / IRRELEVANT를 부여합니다. 정책 범위만 언급되고 방향을 신뢰하기 어려우면 UNCERTAIN으로 둡니다. 공급 해소 문맥은 과거 공급 부족에 따른 상승 경로와 구분합니다. 부인된 주장도 상승 사실로 취급하지 않습니다.

같은 URL·원문 URL·정규화 제목과 유사 제목을 묶고 eventId, duplicateCount를 제공합니다. 쿼리별로 후보를 고르게 평가하고, 최종 선별에서는 상반된 유효 방향을 보존합니다. 유사 제목 기준도 조정 가능하며 서로 다른 제목으로 보도된 같은 사건을 완벽히 식별하지는 못합니다. 따라서 AI도 같은 사건을 여러 독립 근거로 세지 않도록 지시합니다.

## AI와 화면 계약

선별 기사에 기존 title/summary/url/publisher/publishedAt 외에 originalUrl, naverUrl, relevanceScore, scoreDimensions, assessmentStage, priceImpact, relevanceReasons, reportedFacts, impactPath, uncertainties, sourceAssessment, eventId를 전달합니다. `reportedFacts`는 **기사 요약에 기술된 주장**이고 `impactPath`는 **시스템 추론**입니다. `pubDate`는 기존 공식 문서대로 네이버 제공 시각이며 원문 최초 발행일 확인이 아닙니다.

제외된 후보의 내용은 AI에 전달하지 않고 평가 개수만 제공합니다. 최종 구매 판단은 서버 가격 이력 엔진의 decision/wait_condition을 보존합니다. 프롬프트는 실제 전송 파일 `app/prompts/price_agent.txt` 한 곳에서 관리합니다. 화면에서는 보완 근거/참고 기사, 압력 방향, 근거 문장, 추론 경로, 출처/제공일/링크, 한계와 실제 검색 주제를 볼 수 있습니다. 성공한 검색에서 선별 기사가 없으면 ‘최근 관련 뉴스 없음’이며 검색 실패/일부 실패와 구분합니다.

## 대화형 구매 비서와 자동 분류

기존에는 질문 유형만 모델에 전달해 서로 다른 질문이 같은 구매 판단 요약을 받았습니다. 이제 question 원문과 최근 6개 user/assistant 메시지(각 1000자)를 전달합니다. 상품 소개·용도·사양·가격·뉴스·구매 시점과 후속 질문에 맞춰 answer를 작성하고 구매 판단은 관련 질문에만 앞에 표시합니다. 상세 가격 표는 접어서 볼 수 있습니다. 모델 연결 실패는 별도 안내합니다.

개인 대화가 섞인 최종 답변은 공유 Firestore `liveDisplayCache`에 쓰지 않습니다. 사용자·상품·질문·문맥·근거·모델·프롬프트별 해시를 키로 프로세스 내 128개/5분 캐시만 사용합니다. 질문과 전체 대화는 Firestore에 저장하지 않으며 기존 사용자별 lastIntent만 남깁니다. 상품/계정 변경 시 브라우저 대화도 초기화합니다. 기존 공개 가격·뉴스 캐시는 계속 재사용합니다. 서버 재시작·다중 인스턴스에서는 개인 답변 캐시를 공유하지 않습니다. 동시에 같은 개인 질문이 들어오는 경우 각각 모델을 호출할 수 있으며 기존 사용자별 할당량이 적용됩니다.

저장된 카테고리 → 상품명/소개의 로컬 분류 → 명확한 발견 카테고리를 먼저 사용합니다. 그래도 분류가 없으면 `product_identity.identify`가 상품 정보로 재시도하고, 필요할 때 기존 Gemini 키/모델로 한 번 분류합니다. 성공한 분류는 최대 128개/8시간, 실패는 1분 재사용합니다. 일반 뉴스 평가는 AI를 추가 호출하지 않지만 이 예외 분류에는 모델 비용이 발생할 수 있습니다. AI_INFERRED는 추정임을 표시하고 기사도 참고 수준으로 제한하며 사양을 카탈로그에 저장하지 않습니다. 완전히 불명확한 모델은 강제로 분류하지 않으며, 뉴스 검색이 생략되어도 상품 대화 자체는 계속할 수 있습니다.

## 검증과 조정

테스트 소스에 GPU 생산 차질·GDDR 가격·관세 범위·반대 방향·공급 정상화·실적 전용·경쟁 제품 할인·중복·시급성 외 대화·AI 분류·캐시 재사용 사례를 작성했습니다. 이는 합성 입력의 기대 동작이며 실제 기사 성능 검증 결과가 아닙니다. 이번 작업에서 테스트/서버/빌드/실제 API는 실행하지 않았습니다.

실제 네이버 결과를 기간·상품군·검색 주제별로 수집한 뒤 사람이 관련성/가격 경로/방향/근거 단계를 라벨링하세요. 선별된 기사만 모으면 선택 편향이 생기므로 탈락한 후보도 포함합니다. 중복 사건은 학습·검증에 나눠 넣지 말고 날짜와 사건 단위로 분리합니다. 근거 없는 포함(precision), 유효 공급망 기사 누락(recall), 방향 오판, 참고→보완 근거의 잘못된 승격을 따로 확인하고 weights/thresholds/sourceDomains/signals를 조정합니다. 다른 기간의 홀드아웃 기사로 재검증하며 점수를 확률처럼 해석하지 않습니다.

`evaluate_news_impact.py`는 저장한 실제 API 응답을 사용하는 오프라인 평가 도구이며 네이버/Gemini를 호출하지 않습니다. JSONL의 각 행 형식은 다음과 같습니다. item은 원래 응답의 title/description/pubDate/originallink/link 객체를 그대로 사용하고 API 키는 저장하지 마세요.

```json
{"asOf":"2026-10-26T06:00:00+00:00","product":{"productId":"123","title":"NVIDIA RTX 5070","category":"그래픽카드","keywords":["그래픽카드","GPU"]},"item":{"title":"실제 수집 제목","description":"실제 수집 설명","pubDate":"원본 pubDate","originallink":"원문 URL","link":"네이버 URL"},"expectedStage":"REFERENCE","expectedImpact":"UPWARD_PRESSURE"}
```

위 블록은 구조 설명이며 실행 가능한 실제 뉴스 표본이 아닙니다. 파일 경로를 지정하면 선별 precision/recall과 단계·방향 혼동 집계를 출력합니다.

## 사용자 실행 방법

추가 패키지와 Firebase 인덱스 배포는 필요하지 않습니다. 기존 Gemini·네이버 환경 변수를 유지하고 Spring Boot/FastAPI를 모두 재시작한 뒤 프론트엔드를 새로고침합니다. 로그인 후 ‘이건 무슨 상품이야?’ → ‘게임용으로 괜찮아?’ → ‘관련 산업 뉴스가 가격에 어떤 영향을 줄까?’처럼 연속 질문을 확인하세요.

```powershell
cd E:\github\codyssey_final\backend\ai
..\..\.venv\Scripts\python.exe -m unittest discover -s tests -p "test_*news*.py"
..\..\.venv\Scripts\python.exe -m unittest discover -s tests -p test_product_identity.py
..\..\.venv\Scripts\python.exe -m unittest discover -s tests -p test_price_agent.py
..\..\.venv\Scripts\python.exe evaluate_news_impact.py --cases saved-news-cases.jsonl
cd E:\github\codyssey_final\backend\api
mvn "-Dtest=NewsCategoryTest,PriceAgentPolicyTest,PriceAgentControllerTest" test
cd E:\github\codyssey_final\frontend
npm run build
```

오프라인 평가 명령은 실제 라벨 파일을 만든 뒤 실행하세요. 위 명령은 안내이며 실행 결과가 아닙니다. 분류/다중 검색/대화 시간을 고려해 비서 요청 제한 시간만 늘렸습니다. 상품/계정이 바뀌면 진행 중 요청은 기존대로 취소됩니다.

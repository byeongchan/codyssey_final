> 실제 Firebase Cloud · Reef · Gemini 통합 연결은 [LIVE-CONNECTION.md](LIVE-CONNECTION.md)를 먼저 참고하세요. 이 문서의 에뮬레이터/더미 실행 절차는 이전 개발 모드 안내입니다.

# Reef 다나와 검색·상품·판매처 연동

> 2026-10-23: 상세 페이지의 실제 price_history 주간·월간 차트와 직접 입력 리뷰 Gemini 요약을 추가했다. 아래의 과거 미연결 설명보다 [최신 상세 확장 안내](DETAIL-INSIGHTS.md)를 우선한다. 리뷰 본문 자동 수집은 현재 Reef 다나와 미지원이다.

사용자가 제공한 [다나와 Markdown](https://reefapi.com/docs/danawa.md)과 [무신사 Markdown](https://reefapi.com/docs/musinsa.md)을 확인하고 진행 중이던 다나와 연동을 작성했다. 문서 HTTP 조회만 했으며 설치·서버 실행·빌드·테스트·실제 Reef API 호출은 수행하지 않았다.

## 현재 동작

화면 하단 ‘다나와 실조회’ 또는 `http://127.0.0.1:5173/?view=danawa`에서 검색한다. 별도 실제 조회 화면이며 기존 더미 카탈로그를 교체하지 않는다. 로그인과 OPERATIONS_UIDS의 UID 일치가 필요하다. 브라우저는 Spring의 POST /api/v1/me/danawa/search, /product, /offers를 호출한다. Spring이 내부 키로 Node를 호출하고 Node만 Reef 키를 사용한다.

| 동작 | Reef 요청 | 사용 필드 |
| --- | --- | --- |
| 검색 | POST /danawa/v1/search, query·scope=catalog·sort=popular·page | data.results 중 row_type=product, product_id, title, price, maker, category, variants |
| 상세 | POST /danawa/v1/product, product_id | data.product의 상품 ID·명칭·카드/현금/배송 포함 표시 최저가·평점·리뷰 개수·관련 옵션 ID·가격 불일치 |
| 판매처 | POST /danawa/v1/offers, product_id·page | data.product의 ID 확인 후 data.offers의 mall·title·group·price·payment·shipping_cost·shipping_text·price_with_shipping·coupon·url |

원본 product 포장을 제거하고 화면용 product로 변환한다. 다른 상품 ID를 반환하면 거부한다. 상품군·색상·용량·통신 요금제 옵션을 하나로 합치지 않고 각 product_id로 조회한다. 검색 가격은 배송비 포함 확정 최저가가 아니다. 상세의 카드 결제 가능 가격과 현금 전용 가격도 구분한다.

판매처 quotedTotal은 정수 KRW 상품가와 확인된 shipping_cost의 합이다. 배송비 누락·null은 0으로 바꾸지 않는다. price_with_shipping도 제공됐는데 합계와 다르면 quotedTotal=null, totalMismatch=true로 표시한다. 쿠폰은 자동 차감하지 않는다. payment 미상은 UNKNOWN, 판매처 재고는 API에 확정 필드가 없어 모두 UNKNOWN으로 표시한다. 이 값은 기존 DataPolicy의 확정 현재가로 사용하지 않는다. 판매처 옵션 일치도 수동 확인 대상이다.

상품 원본 링크는 상품 ID로 생성한 Danawa HTTPS URL이다. 판매처 링크는 HTTPS·사용자정보/비표준 포트가 없는 URL만 허용하고 새 탭·noopener/noreferrer를 사용한다. Node가 그 링크에 접속하지 않는다. 원본 seller 문자열은 React 텍스트로 표시하고 HTML로 삽입하지 않는다.

Markdown의 공통 meta는 확장 필드 전체를 보장하지 않으므로 fetched_at은 선택적이다. 있으면 시간대·유효 시각·미래 여부를 검사하고 fetchedAt으로 유지한다. 없으면 fetchedAt=null, 화면에 ‘수집 시각 미제공·수집기 수신 시각’을 표시한다. 수신 시각으로 관측 시각을 만들지 않는다. Spring meta.updatedAt=null이며 관측 신선도를 확인할 수 없다는 뜻으로 stale=true다. 관측 시각이 있으면 기존 24시간 규칙을 적용한다.

실제 출처는 reef-danawa, synthetic=false다. 실제 다나와 전용 관측 저장·표시가 이력·보류 분석은 [실제 관측 저장 안내](DANAWA-STORAGE.md)에 연결했다. 더미 스냅샷·기존 AI·찜·목표가 알림에는 아직 연결하지 않았다. 주별/월별 price_history도 이번에 호출하지 않는다. 이를 일별 관측으로 바꾸거나 관측 일수를 부풀리면 안 된다.

## 호출·캐시·한도

자동 요청·다음 페이지 순회·공급자 재시도는 없다. 클릭한 동작 하나당 해당 엔드포인트 하나만 호출한다. 검색→상세→판매처 첫 페이지를 각 한 번 누르면 문서 기준 3+2+3 크레딧의 호출 후보이며 실제 청구는 Reef 정책과 응답에 따른다. 재검색·판매처 페이지 이동도 호출 대상이다.

Node 프로세스 내부에서 같은 동작/입력은 60초·최대 64개 캐시를 재사용하고 진행 중 요청도 병합한다. 캐시의 원본 시각은 유지한다. 동시에 최대 4개, 캐시가 아닌 외부 요청 시도는 전체 프로세스에서 60초에 최대 20회다. 오류도 시도 수에는 포함되며 재시작 시 한도·캐시는 초기화된다. 이 제한은 사용자별/여러 서버 공통 한도나 일일 크레딧 예산이 아니다.

고정 https://api.reefapi.com 주소, x-api-key 헤더, 8초 타임아웃, 리다이렉트 차단, 응답 1MiB 한도를 사용한다. Spring은 로컬 Node 주소만 허용하고 10초 요청 타임아웃을 적용한다. 브라우저의 기존 요청 제한 시간은 12초다. 사용자가 화면을 닫아도 이미 접수된 유료 호출은 완료될 수 있다.

검색은 최대 50페이지, 판매처는 로컬에서 최대 20페이지를 허용한다. 각 페이지는 현재 목록/표를 대체하며 수동으로 조회한다. 전체 판매처를 모두 받았다는 표시를 하지 않는다. 공개/Node 내부 OpenAPI를 함께 갱신했다.

## 사용자 직접 실행

1. 기존 FIREBASE.md 절차로 Auth·Firestore Emulator와 프론트엔드를 실행한다. 운영 계정으로 가입한 후 Emulator UI에서 UID를 확인한다.
2. 기존 Node .env에 아래 설정을 추가한다. 기존 INTERNAL_API_KEY는 Spring과 같게 유지한다. DATA_PROVIDER는 fixture를 유지한다. 이 값은 기존 더미 경로의 설정이며 실제 조회는 별도 REEF_DANAWA_ENABLED로 켠다.

```dotenv
APP_ENV=development
DATA_PROVIDER=fixture
REEF_DANAWA_ENABLED=true
```

3. Node 터미널에서 키를 설정하고 실행한다. PowerShell 7의 마스킹 입력을 사용한다. Node 내장 fetch를 사용하므로 새 외부 패키지는 없다. 키를 .env.example·브라우저·Spring·공유 문서에 넣지 않는다. 기존 .env는 덮어쓰지 않는다.

```powershell
cd D:\github\codyssey_final\backend\collector
$env:REEF_API_KEY = Read-Host 'Reef API 키' -MaskInput
npm start
```

4. Spring 터미널에 기존 Firebase·내부 키 설정과 아래 운영 UID를 적용하고 재시작한다. 쉼표로 여러 UID를 지정할 수 있으며 기본 빈 목록은 모두 거부한다.

```powershell
cd D:\github\codyssey_final\backend\api
$env:COLLECTOR_URL = 'http://127.0.0.1:4001'
$env:OPERATIONS_UIDS = 'Emulator에서 확인한 본인 UID'
mvn spring-boot:run
```

5. 프론트엔드를 기존 npm run dev로 실행/재시작하고 로그인한다. 하단 링크에서 ‘SSD 2TB’ 등을 검색 → 옵션 상품 선택 → 상세 → 판매처 가격 조회를 확인한다. 실조회 활성화 시에만 키가 필요하고 실제 API 사용량이 발생할 수 있다. 403이면 운영 UID·로그인 계정을, 502이면 Node 활성화·키·응답·연결을 확인한다.

끄려면 Node .env의 REEF_DANAWA_ENABLED=false로 바꾸고 Node를 재시작한다. 환경 변수로 같은 플래그를 지정해뒀다면 그 값도 함께 바꾼다. 키만 있어도 플래그가 꺼져 있으면 실제 공급자를 생성하지 않는다. /health의 기존 fixture 상태는 더미 수집기 상태이며 Reef 키 유효성 확인이나 실조회 성공을 의미하지 않는다.

## 사용자 검증

- 미로그인은 401, 일반 계정·비어 있는 운영 UID 목록은 403이며 외부 요청이 없어야 한다.
- 검색은 카탈로그 상품만 반환하고 variants의 서로 다른 ID를 유지해야 한다. 클릭 없이 자동으로 가격 조회를 하면 안 된다.
- 상세·판매처 응답의 상품 ID 불일치는 거부해야 한다. 누락된 필드로 목업 숫자를 생성하면 안 된다.
- 배송비 null, 현금 전용, 합계 불일치, 쿠폰, 재고 미확인이 구분되어야 한다. 반환 가격을 기존 더미 차트·AI·알림이 사용하면 안 된다.
- 같은 요청을 60초 내 반복하면 로컬 캐시를 사용한다. 같은 요청을 동시에 보내도 외부 호출은 하나여야 한다. 원본 수집 시각이 새 시각으로 바뀌면 안 된다.
- 공급자 429·잘못된 JSON·차단/오류·1MiB 초과·타임아웃은 재시도 없이 실패해야 한다. API 키·원본 오류 본문을 응답에 노출하면 안 된다.
- 계정 전환·로그아웃·화면 이동 시 이전 응답을 지우거나 요청을 취소해야 한다. 모바일 표 스크롤과 다크 모드도 확인한다.

테스트 소스는 합성 응답과 모킹만 사용한다. 실제 Reef 호출은 하지 않도록 작성했다. 사용자가 직접 실행한다.

```powershell
cd D:\github\codyssey_final\backend\collector
npm test
```

```powershell
cd D:\github\codyssey_final\backend\api
mvn test
```

```powershell
cd D:\github\codyssey_final\frontend
npm run build
```

실행 결과는 아직 없다.

## 무신사 문서 확인 결과

현재 범위는 다나와다. 무신사 product는 data.product 아래 prices, options.variants, delivery 등을 제공한다. sale_price·coupon_price·final_price를 구분하고 variant_id·price_delta·active·in_stock을 함께 다뤄야 한다. delivery.return_shipping_fee는 반품 배송비이며 초기 배송비로 쓰면 안 된다. 현재 문서에는 초기 배송비의 명확한 수치 필드가 없어 무료로 가정하지 않는다. product_id와 다나와 pcode는 별도 출처 식별자로 관리해야 한다. 무신사 연동은 후속 작업이다.

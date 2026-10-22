> 실제 Firebase Cloud · Reef · Gemini 통합 연결은 [LIVE-CONNECTION.md](LIVE-CONNECTION.md)를 먼저 참고하세요. 이 문서의 에뮬레이터/더미 실행 절차는 이전 개발 모드 안내입니다.

# 실제 다나와 관측 저장·일별 표시가 이력

구현·테스트 소스·OpenAPI만 작성했다. 설치·실행·빌드·테스트·실제 Reef/Firebase 저장 호출은 수행하지 않았다. FirebaseAccess의 기존 로컬 demo- Emulator 제한을 유지한다. 실클라우드 저장은 허용하지 않는다.

## API와 화면

- POST /api/v1/me/danawa/observations: productId와 page(기본 1, 최대 20)만 받는다. 서버가 Node의 offers를 조회한 뒤 저장한다. 브라우저가 보낸 가격은 사용하지 않는다. 기존 Node 캐시를 사용할 수 있어 버튼을 눌러도 매번 새로운 원시 관측이 생기는 것은 아니다.
- GET /api/v1/me/danawa/history/{productId}: 저장소만 읽는다. Reef 호출 없이 최근 90개 관측 날짜를 조회한다. 미저장 상품은 exists=false, points=[]와 판단 보류를 반환한다.

두 API 모두 로그인과 OPERATIONS_UIDS 권한을 요구한다. 기존 조회 화면의 상품 선택 후 ‘현재 페이지 조회·저장’, ‘저장 이력 조회’ 버튼을 사용한다. 판매처 페이지를 자동 순회하거나 자동 저장하지 않는다. 저장 버튼은 현재 표시된 판매처 페이지(없으면 1페이지)를 다시 조회한다. 명시적 저장은 실제 Reef 크레딧을 소비할 수 있다.

저장 응답은 observationId·duplicate·productId·observedAt·day·validQuoteSamples·decisionEligible=false다. 저장 후 이력 조회 실패나 네트워크 중단이면 저장 자체는 커밋되었을 수 있다. 이력을 먼저 다시 확인한다. 같은 원본 관측을 재저장하면 중복으로 처리하지만 더 새로운 실제 수집 시각으로 조회된 응답은 새 관측이다. 별도 클라이언트 요청 키로 외부 조회까지 멱등 처리하는 기능은 아직 없다.

## 저장 구조와 식별자

| 경로 | 보관 내용 |
| --- | --- |
| liveDanawaProducts/{pcode} | source=reef-danawa, synthetic=false, providerProductId, optionKey=danawa:{pcode}, identityBasis, 검증 상태, 최신 관측 포인터 |
| 위 경로/danawaObservations/{observationId} | 불변 관측: 원본/수신 시각, 페이지·hasMore, 결제/배송 조건을 포함한 판매처 배열, 내용 해시, 최초 저장 시각 |
| 위 경로/danawaDailyQuotes/{한국 날짜} | 카드 표시가 표본 원장, 일 최저 표시가, 고유 표본 수·관측 수, 판단 가능 표본 수 0 |

더미 fixturePriceSnapshots/fixtureSnapshotBatches, users, 기존 가격 분석 캐시에는 쓰지 않는다. 실제 공용 가격 기록이므로 개인 계정 삭제 대상에 넣지 않는다. 더미 스냅샷 정리 API도 이 컬렉션을 삭제하지 않는다. 실제 가격의 자동 보존 기간·정리 정책은 후속 범위이며 에뮬레이터 내보내기/가져오기는 FIREBASE.md를 따른다. 운영 화면의 기존 저장 통계는 더미 컬렉션만 센다.

다나와 pcode를 상품/옵션 기준으로 사용하되 판매처 개별 상품이 정확히 같은 옵션이라는 검증은 하지 않는다. Node가 mall_code와 mall_product_id를 우선하여 offerKey를 만든다. 결제 유형·그룹도 구분하고 판매처 식별자가 없으면 판매처명·제목·URL 등의 대체 필드를 사용해 identityQuality=FALLBACK_FIELDS로 표시한다. 이름만 비슷한 상품을 합치거나 fixture 상품 ID에 자동 연결하지 않는다.

관측 ID는 상품 ID·페이지·정규화된 원본 수집 시각의 SHA-256이다. 원본 시각이 없으면 명시적으로 received: 수신 시각을 ID에 사용하지만 observedAt은 null로 유지한다. 같은 ID와 내용은 duplicate=true다. 같은 ID인데 가격·조건 등 내용이 다르면 409로 거부한다. 원본 시각이 있는 재전송의 수신 시각 변화는 내용 해시와 표본을 바꾸지 않는다. 판매처 행 순번은 내용 해시에서 제외한다.

## 집계와 판단 보류

원본 fetchedAt을 한국 날짜로 바꿔 일 집계를 만든다. fetchedAt=null이면 관측만 저장하고 날짜별 집계는 만들지 않는다. 서버 createdAt이나 수신 시각을 과거 관측으로 사용하지 않는다. 미래 원본 관측·출처 불일치·잘못된 금액/합계·충돌 표본은 저장 전에 거부한다.

하루 표본은 ‘원본 관측 시각 + offerKey’ 해시로 구분한다. 같은 시각의 같은 판매처가 여러 페이지에 겹치면 원장에 한 번만 넣는다. 같은 원본 관측 재전송은 관측 수·표본 수를 바꾸지 않는다. 다른 페이지 관측은 별도 관측으로 저장하되 겹친 표시가 표본은 중복 누적하지 않는다. 다른 시각의 동일 판매처 가격은 새 관측이다.

payment=card, 정수 KRW 상품가·확인된 배송비, 원본 합계와 불일치가 없는 quotedTotal만 일 표시가 집계에 넣는다. 현금 전용·결제 미상·배송비 null·합계 불일치는 원시 관측에 남기고 카드 표시가 집계에서는 제외한다. 쿠폰을 자동 차감하지 않는다. 그날 유효 표시가가 하나도 없으면 일 최소값은 null이며 0원으로 채우지 않는다. 새 날짜가 빠져 있어도 보간하지 않는다.

한 트랜잭션이 관측·일 원장·최신 상품 포인터를 함께 쓴다. 한 관측은 최대 1000개 판매처·내용 256KiB, 상품별 일 원장은 최대 고유 카드 표본 2000개·관측 10000개다. 초과 시 일부 저장 없이 409/502로 거부한다. offers와 samples의 인덱스를 제외하여 배열/원장 확장에 따른 불필요한 색인을 줄였다.

일 quotedCardMinimum은 ‘조회한 페이지들에서 확보한 카드 표시가’의 최소값이다. 모든 판매처의 확정 최저가가 아니며 페이지 수·재고·판매처 옵션 완전성을 보장하지 않는다. 표본 수는 가격이 유효한 출처 표본 수이지 구매 가능한 판매처 수가 아니다.

차트는 표시가가 있는 날짜를 실제 날짜 간격으로 배치하며 날짜별 표본 내역도 제공한다. 최근 30개 달력 날짜 범위의 일 표시가 평균과 관측 일수를 별도로 계산한다. 표본이 없으면 평균은 null이다. 기존 주별·월별 이력이나 더미 30일 이력을 섞지 않는다.

구매 판단은 현재 항상 INSUFFICIENT_DATA, reasonCode=UNVERIFIED_STOCK_OR_OPTION, eligibleObservedDays=0이다. 실제 API에 판매처 재고 확정 필드가 없고 옵션 일치도 확인되지 않았기 때문이다. 표시가가 여러 날 쌓였다는 이유만으로 BUY/WAIT를 반환하지 않는다. 이 화면의 보류 분석은 서버가 안내하며 Gemini·기존 더미 분석 API·목표가 알림은 호출하지 않는다. 실제 검증 근거를 확보한 뒤 판단·알림 정책을 확장해야 한다.

## 사용자 직접 실행·검증

1. DANAWA.md의 Reef 플래그·키·운영 UID, FIREBASE.md의 Emulator 환경을 유지한다. Node·API·프론트엔드를 모두 재시작한다. Node의 새 offerKey 필드가 필요하므로 API만 갱신하면 저장 검증이 실패할 수 있다. 별도 설치 의존성은 추가하지 않았다.
2. 다나와 검색 → 옵션 상품 상세 → 판매처 가격 조회 → ‘현재 페이지 조회·저장’을 실행한다. 저장 완료 메시지와 실제 일별 표시가 이력 또는 수집 시각 미제공 안내를 확인한다. 구매 판단은 보류여야 한다.
3. 같은 관측을 다시 저장하면 duplicate=true이며 일 표본/관측 수가 늘어나지 않아야 한다. 60초 캐시 이후 새 원본 수집 시각이면 새 관측이 정상이다. fetchedAt이 계속 같으면 재전송으로 처리한다.
4. Emulator UI에서 liveDanawaProducts만 변경되는지 확인한다. 날짜별 quotedCardMinimum·samples·validQuoteSamples를 확인하고 fixture/개인 컬렉션에 가격이 복제되지 않는지 확인한다.
5. 다른 판매처 페이지를 조회·저장한다. 같은 수집 시각의 동일 offerKey는 표본 수를 늘리지 않아야 한다. 원본 수집 시각이 다르면 별도 표본인 점에 유의한다. 날짜 구분·겹치는 페이지는 작성한 모킹 테스트와 별도 검증용 응답으로 확인한다.
6. 현금 전용·배송비 미확인·합계 불일치는 원시 관측에 남고 일 집계에서 제외되어야 한다. 유효 카드 표시가가 전혀 없으면 날짜 점은 null이고 차트에 0원 점이 생기면 안 된다. 원본 시각 미제공 관측은 수신 날짜로 집계하면 안 된다.
7. Firestore를 중단하면 저장 실패가 표시되어야 한다. 복구 후 저장 이력을 재조회한다. 조회 실패를 가격 0원·저장 0건으로 처리하면 안 된다. 저장 API의 프론트엔드 대기 상한은 22초이며 이미 커밋된 관측은 오류 후에도 존재할 수 있다.
8. 저장 이력 GET은 Node를 종료해도 Auth/Firestore가 살아 있으면 조회되어야 한다. API·에뮬레이터를 내보내기/가져오기 방식으로 재시작하면 기록이 유지되어야 한다. 미로그인·일반 계정은 계속 거부해야 한다.

테스트 소스는 한국 날짜·시각 누락·원본 재전송/페이지 겹침·금액 검증·단일 트랜잭션 읽기/쓰기 순서·더미 무접근·빈 이력·임의 브라우저 가격 거부를 다룬다. 실제 SDK 경합·보존·차트 연동은 위 수동 절차로 확인한다. 실행하지 않았다.

```powershell
cd D:\github\codyssey_final\backend\api
mvn test
```

```powershell
cd D:\github\codyssey_final\backend\collector
npm test
```

```powershell
cd D:\github\codyssey_final\frontend
npm run build
```

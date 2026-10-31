# 무신사 패션·스포츠 연동

## 사용 방법

1. `backend/collector/.env`에 아래 설정을 추가합니다. Reef 키는 다나와와 같은 `REEF_API_KEY`를 사용합니다. 기존 `REEF_DANAWA_ENABLED=true`도 유지합니다.

```dotenv
REEF_MUSINSA_ENABLED=true
```

2. 수집기와 Spring Boot를 재시작하고 프론트엔드 변경 사항을 반영합니다. `DATA_PROVIDER=reef-danawa`, `FIREBASE_MODE=cloud` 등 기존 설정을 유지합니다. `DATA_PROVIDER`를 `reef-musinsa`로 바꾸지 않습니다.
3. 홈페이지의 **패션·스포츠 상품**을 선택한 뒤 **무신사** 버튼을 누릅니다. 다른 대분류에서는 무신사 선택을 제공하지 않습니다.
4. 무신사에서는 기본 `상의`를 표시합니다. 상의·아우터·바지·원피스/스커트·신발·가방·스포츠 분류를 선택합니다. 상단 검색창은 선택한 무신사 분류 안에서 검색합니다. 예를 들어 러닝화 검색은 `신발` 또는 적절한 스포츠 분류에서 진행합니다.
5. 공급자를 전환하면 검색어와 상세 선택을 초기화하고 해당 공급자의 목록을 표시합니다. 다나와는 기존 대표 검색을, 무신사는 선택한 실제 분류 조회를 사용합니다.

의존성 추가 설치나 새 Firestore 복합 인덱스는 필요하지 않습니다. 최초 저장 시 컬렉션을 자동 생성합니다. 실제 API 호출·설치·빌드·테스트는 이번 작업에서 실행하지 않았습니다.

## 제공 범위

- 분류 내 키워드 검색, 성별·정렬, 12개 단위 서버 페이지 조회
- 선택한 카테고리 안의 상품 종류·세부 분류·브랜드 상세 검색 ([사용 및 검증 안내](PRODUCT-FILTERS.md))
- 정상가·판매가·쿠폰가·표시 최종가 구분, 상품 사진과 브랜드·평점·리뷰 수
- 색상·사이즈 옵션, 재고 여부, 옵션 금액 차이와 제공된 잔여 수량
- 실측 사이즈표, 상품 정보, 제공된 배송 일정·반품비
- 실제 리뷰 본문·사진·구매 옵션, 최신순 20개 단위 불러오기
- 로그인 회원의 무신사 찜, 통합 찜 목록의 공급자 표기, 공급자별 상세 복원
- 공급자를 구분한 최근 본 상품과 상세 URL

무신사에는 다나와의 판매처별 가격 비교·과거 가격 이력·AI 구매 상담을 연결하지 않았습니다. 회원/카드 할인·확정 배송비·옵션 최종 결제액을 추정하지 않습니다. 수량 미제공은 0개가 아닌 미확인으로 취급합니다. 옵션의 추가 금액은 그대로 표시하며 표시 최종가에 임의로 더해 결제액으로 보장하지 않습니다.

브랜드 전용 조회·랭킹 API·리뷰 AI 요약·가격 이력 직접 축적은 이번 범위에 포함하지 않았습니다. 원본 리뷰의 닉네임·키·몸무게 등 작성자 프로필을 저장/반환하지 않습니다.

## 구조와 저장

```text
React Musinsa.tsx
  → Spring /api/v1/musinsa/*
    → Node /internal/musinsa/* (내부 키)
      → Reef /musinsa/v1/search 또는 category, product, reviews
```

`search`에 검색어가 없으면 Reef `category`를 호출합니다. 사이트 대분류 `fashion`과 승인된 무신사 분류 코드만 허용합니다. 상품 상세의 `category_path`도 패션·스포츠 코드인지 검사합니다. 리뷰는 해당 경로로 검증·저장된 상품에 대해서만 요청합니다. 중고·뷰티·일반 디지털/생활 등은 추가하지 않습니다.

공유 캐시 종류는 `musinsa-search`, `musinsa-product`, `musinsa-reviews`이며 다나와 키와 분리합니다. 검색·상품 요약은 `liveMusinsaCatalog/{productId}`에 저장하고 찜에는 서버가 검증한 저장 자료만 사용합니다.

무신사 찜은 `users/{uid}/musinsaWishlist/{productId}`입니다. 기존 `danawaWishlist`와 다나와 데이터를 이전하거나 덮어쓰지 않습니다. 공급자별 최대 100개를 허용하고 통합 목록에서 출처를 구분합니다. 회원탈퇴 대상에 `musinsaWishlist`를 추가했습니다.

최근 본 상품은 기존 브라우저 저장 형식에 `provider`를 추가하며, 필드가 없는 기존 기록은 다나와로 취급합니다. 같은 번호도 `(provider, productId)`가 다르면 별개 상품입니다. 전체 최대 5개·30일 정책을 유지합니다. URL은 무신사일 때 `?provider=musinsa&category=fashion&product=상품번호`로 복원합니다.

캐시는 기존 수동 갱신 정책을 따릅니다. 기본 최신성 기준은 1일이며 오래된 자료도 자동 재수집하지 않습니다. 최초 요청과 상품/검색 갱신은 외부 호출을 발생시킬 수 있습니다. 최근 60초의 성공 저장과 동시 요청은 병합·재사용합니다. 수집기 제한은 무신사 기준 분당 20회·동시 4건이며 다나와 어댑터와 별개입니다. 실제 Reef 계정 한도와 크레딧은 공유되므로 두 소스의 합산 사용량을 확인해야 합니다.

## API

| 경로 | 역할 |
| --- | --- |
| `POST /api/v1/musinsa/search` | `{categoryId:"fashion",category:"001",query:"",gender:"all",sort:"popular",page:1,refresh:false}` |
| `POST /api/v1/musinsa/product` | `{productId:"상품번호",refresh:false}` |
| `POST /api/v1/musinsa/reviews` | `{productId:"상품번호",page:1,refresh:false}` |
| `GET /api/v1/musinsa/jobs/{id}` | 무신사 공개 수집 작업만 조회 |
| `GET /api/v1/me/musinsa/wishlist` | 본인 무신사 찜 목록 |
| `PUT /api/v1/me/musinsa/wishlist/{id}` | 저장된 상품 찜, 본문 없음 |
| `DELETE /api/v1/me/musinsa/wishlist/{id}` | 본인 찜 해제 |

원본: [Reef Markdown 명세](https://reefapi.com/docs/musinsa.md), [HTML 명세](https://reefapi.com/docs/musinsa).

## 직접 확인할 항목

1. 다른 대분류에서 무신사 버튼이 보이지 않는지, 패션에서만 전환되는지 확인합니다.
2. 무신사에서 분류 조회·분류 내 검색·성별·정렬·이전/다음 페이지를 확인합니다.
3. 상품 상세·사진·옵션 재고·실측표·가격 구분·리뷰를 확인합니다. 미제공과 품절을 구별합니다.
4. 동일 숫자 ID의 다나와/무신사 상품을 찜·최근 본 상품에 각각 넣고, 선택 시 올바른 출처로 이동하는지 확인합니다. 기존 다나와 기록도 확인합니다.
5. URL 직접 진입·새로고침·뒤로/앞으로 가기·공급자 전환·다른 카테고리 전환 시 이전 요청 결과가 섞이지 않는지 확인합니다.
6. 무신사 비활성화·빈 결과·잘못된 분류·비패션 상품·429/장애·Firestore 오류를 확인합니다. 공급자가 실패해도 다나와로 전환할 수 있어야 합니다.
7. 반복 조회에서 Reef 호출이 재사용되는지, 명시적 갱신 후 저장 시각이 바뀌는지, 회원탈퇴에서 두 공급자 찜이 모두 정리되는지 확인합니다.

작성한 테스트는 `backend/collector/test/musinsa.test.mjs`, `backend/api/src/test/java/com/prizm/MusinsaControllerTest.java`이며 기존 live 경로·탈퇴 테스트를 확장했습니다. 테스트는 실행하지 않았습니다.

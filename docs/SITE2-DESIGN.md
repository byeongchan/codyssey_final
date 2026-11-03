# 현재 화면 디자인과 참고 이력

2026-11-03 코드 기준. 사이트2·AI 비교 사이트·로그인 베이스 디자인을 단계적으로 참고한 이전 적용 내역은 [디자인 이력](archive/SITE2-DESIGN-HISTORY.md)에 보관합니다. 참고 폴더는 실행·배포 의존성이 아닙니다.

| 화면 | 실제 사용 파일 |
| --- | --- |
| 홈·추천 검색어·헤더 | LiveApp.tsx, RecommendedSearches.tsx, recommendedSearches.css, index.css |
| 좌측 카테고리 | CategoryNavigation.tsx, categoryNavigationData.ts, categoryNavigation.css |
| 중고 | UsedLowestPage.tsx, usedLowest.css |
| AI 비서 | PriceAgentChat.tsx, priceAgentChat.css, UsedAgentChat.tsx |
| 작은 화면 쇼핑 도구 | RecentProductsBar.tsx, floatingTools.ts |
| 계정 | Account.tsx, AccountDetails.tsx, AccountActivity.tsx, accountPage.css |

중고 카테고리는 준비 중이 아니라 통합 검색으로 연결합니다. 중복 상단 카테고리바는 표시하지 않습니다. 회원 이름·권한은 서버 프로필 정책을 사용합니다. 추천 검색어는 작은 화면에서 1·2·3 → 4·5 묶음으로 위로 전환하고 같은 줄을 유지합니다. 계정·캐러셀 상세는 [계정 디자인](ACCOUNT-DESIGN.md), [배너](CAROUSEL-IMAGES.md)를 확인합니다.

실행 화면은 docs/screenshots, 배포·실행 증거는 docs/evidence에 구분해 보관합니다. 일부 화면 캡처는 전체 사용자 시나리오 검증을 뜻하지 않습니다.

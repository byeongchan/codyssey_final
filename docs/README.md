# 문서 인덱스 및 최신화 점검

기준일: 2026-11-03. **Markdown 문서는 앱 런타임에서 읽는 설정이 아닙니다.** 아래 “현재 사용”은 현재 기능·운영을 설명하는 문서라는 뜻입니다. 코드·설정·프론트 진입점·LiveModeFilter·Git 기록·배포 이미지를 대조했으며, 이 점검에서 서비스/테스트/유료 API를 실행하지 않았습니다.

## 먼저 볼 문서

- [프로젝트 소개·배포 URL](../README.md)
- [현재 완료/미검증 작업](../TASKS.md)
- [배포·실행 증거](evidence/README.md)

## 현재 기능·운영 문서

| 문서 | 대조한 현재 코드/기준 |
| --- | --- |
| [ACCOUNT-AUTH.md](ACCOUNT-AUTH.md) | Account.tsx · firebase.ts · SessionVerifier |
| [ACCOUNT-DESIGN.md](ACCOUNT-DESIGN.md) | Account.tsx · AccountDetails.tsx · AccountActivity.tsx · accountPage.css |
| [CAROUSEL-IMAGES.md](CAROUSEL-IMAGES.md) | LiveApp.tsx · site2Design.ts · index.css |
| [COMMUNITY.md](COMMUNITY.md) | Community.tsx · CommunityController · CommunityBody |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Dockerfile · deploy/start.py · render.yaml · frontend/vercel.json |
| [DETAIL-INSIGHTS.md](DETAIL-INSIGHTS.md) | Danawa.tsx · PublicDanawaController · PeriodPriceHistory |
| [EXTERNAL-CALL-REDUCTION.md](EXTERNAL-CALL-REDUCTION.md) | LiveDataCache · MANUAL-PRICE-REFRESH.md |
| [LEGAL-DOCUMENTS.md](LEGAL-DOCUMENTS.md) | legalDocuments.ts · ServiceFooter.tsx |
| [LIVE-CONNECTION.md](LIVE-CONNECTION.md) | main.tsx · LiveModeFilter · FirebaseAccess · 공급자별 Client |
| [LIVE-WISHLIST.md](LIVE-WISHLIST.md) | LiveWishlist.tsx · Danawa/Musinsa/UsedWishlistController |
| [MANUAL-PRICE-REFRESH.md](MANUAL-PRICE-REFRESH.md) | LiveDataCache · DanawaController · application.properties |
| [MEMBER-NAMES-AND-ADMINS.md](MEMBER-NAMES-AND-ADMINS.md) | MemberProfileController · DisplayNamePolicy · AdminAccess |
| [MUSINSA.md](MUSINSA.md) | Musinsa.tsx · MusinsaClient · collector/src/musinsa.mjs |
| [NAVER-NEWS.md](NAVER-NEWS.md) | naver_news.py · rss_settings.py (인증 설정, 단일 검색 이력은 문서 내 명시) |
| [NEWS-PRICE-IMPACT.md](NEWS-PRICE-IMPACT.md) | news_impact.py · price_agent.py · PriceAgentController |
| [PERIOD-HISTORY.md](PERIOD-HISTORY.md) | PeriodPriceHistory · PriceAgentPolicy · Danawa.tsx |
| [PRICE-AGENT.md](PRICE-AGENT.md) | PriceAgentChat.tsx · PriceAgentController · price_agent.py |
| [PRODUCT-FILTERS.md](PRODUCT-FILTERS.md) | productFilters.ts · CategoryNavigation.tsx · Danawa.tsx · Musinsa.tsx |
| [PURCHASE-DECISION-V3.md](PURCHASE-DECISION-V3.md) | PriceAgentPolicy · 서버 정책 JSON (v4 보완 설명 포함) |
| [SITE2-DESIGN.md](SITE2-DESIGN.md) | LiveApp.tsx · CategoryNavigation.tsx · 계정/AI 실제 컴포넌트 |
| [USED-AGENT.md](USED-AGENT.md) | UsedAgentController · UsedProductGroups · UsedPurchaseJudgment · UsedAgentChat.tsx |
| [USED-MARKET.md](USED-MARKET.md) | UsedLowestPage.tsx · UsedController · used.mjs · daangnRegionDirectory.ts |

## 정리 결과

- 초기 fixture/Emulator API·알림·합성 리뷰·스냅샷·기존 채팅 등 운영 live 경로와 다른 문서 18개는 [archive](archive/README.md)로 이동했습니다. 관련 코드는 회귀·개발 모드에 남아 있으므로 “프로젝트 전체에서 쓸모없음”으로 삭제하지 않았습니다.
- 버전 변경이 누적돼 현재 설명과 충돌하던 실연동·다나와 상담·중고 상담·호출 최소화·디자인 문서 5개는 기존 원문을 보관하고 현재 설명으로 재작성했습니다. 기존 TASKS 전체도 보관했습니다.
- 상세 이력의 6시간/만료 자동 수집 설명을 현재 1일·저장 우선·명시적 갱신으로 수정했습니다. 배너 contain, 이전 Windows 경로, 다나와만 언급하던 통합 찜 설명, 배포 전 상태 문구를 보완했습니다.
- 현재 문서에 남은 초기 검증 메모는 TASKS의 최신 검증 상태를 우선하도록 표시했습니다. 단위 테스트 성공을 실제 플랫폼·계정·에뮬레이터 통합 검증으로 확대하지 않았습니다.
- 이동한 문서의 Markdown 상대 링크와 루트 문서 참조를 갱신했습니다. 보관 문서의 수치·명령은 당시 기록이며 최신 실행 안내로 사용하지 않습니다.

## 남은 확인

- 외부 사용자 피드백·Node/Python 테스트·Emulator 검증 결과 미확인
- 운영자/문의처 등 개인정보 문서 미정 항목 확인 필요
- 개별 API의 외부 공급자 계약·실제 응답은 본 문서 점검만으로 재검증하지 않음

# 배포 및 실행 증거

- 프론트: https://codyssey-final.vercel.app/
- 백엔드: https://joopjoop-backend-azvl.onrender.com
- 헬스체크: https://joopjoop-backend-azvl.onrender.com/api/v1/health

| 자료 | 확인 가능한 내용 | 증거 범위의 한계 |
| --- | --- | --- |
| [Vercel 배포 화면](vercel_배포화면.png) | Production Ready·도메인·main·d8ae379 | 캡처 당시 배포 상태이며 전체 기능 검증은 아님 |
| [Render API 응답](render_api_health응답확인.png) | UP·reef-danawa·synthetic false, API referenceTime 2026-11-03T13:10:51Z | 외부 플랫폼·Firestore·Gemini 전체의 성공 여부는 별도 검증 |
| [상품 상세 및 AI 근거 화면](상품검색및AI답변.png) | 상품 상세 가격·판매처 수·기간 표본과 구매 비서 근거 표시 | 주소창·질문/답변 전체·참가자 결과가 없어 전체 사용자 시나리오 성공으로 단정하지 않음 |

프론트 빌드 성공 로그는 대화에서 확인했고 Java 테스트 성공은 사용자 보고를 받았습니다. 성공 원본 로그 파일·Node/Python 테스트 결과는 아직 이 폴더에 없습니다. 이 화면만으로 외부 사용자 테스트를 수행했다고 판단하지 않습니다.

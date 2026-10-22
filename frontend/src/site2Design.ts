import type { IconName } from './Icons'
import { mockupImages } from './presentation'

// Visual reference: 사이트2/AI 기반 가격 비교 사이트 v3. Keep its category order and icons.
export const site2Categories: { id: string; name: string; short: string; icon: IconName; query: string; description: string; keywords: string[] }[] = [
  { id: '', name: '전체 카테고리', short: '전체 카테고리', icon: 'menu', query: '', description: '관심 있는 상품의 가격과 판매 조건을 한눈에 확인하세요.', keywords: ['에어팟 프로', '로봇청소기', '맥북 에어'] },
  { id: 'pc', name: 'PC 부품 · 컴퓨터 주변기기', short: 'PC · 주변기기', icon: 'laptop', query: 'PC 부품', description: 'PC 부품부터 모니터·키보드까지, 원하는 구성의 가격을 비교해 보세요.', keywords: ['그래픽카드', '게이밍 모니터', '기계식 키보드'] },
  { id: 'digital', name: '스마트폰 · 디지털 기기', short: '스마트폰 · 디지털', icon: 'headphones', query: '스마트폰', description: '스마트폰부터 태블릿·웨어러블·음향기기까지 한곳에서 찾아보세요.', keywords: ['아이폰', '갤럭시', '에어팟 프로'] },
  { id: 'appliances', name: '가전제품', short: '가전제품', icon: 'home', query: '가전제품', description: '대형 가전과 생활 가전의 가격, 판매처, 배송 조건을 비교하세요.', keywords: ['OLED TV', '로봇청소기', '무풍 에어컨'] },
  { id: 'fashion', name: '패션 · 스포츠 상품', short: '패션 · 스포츠 상품', icon: 'shirt', query: '러닝화', description: '운동과 일상에 필요한 상품을 검색하고 사이즈와 옵션을 확인하세요.', keywords: ['러닝화', '아웃도어 재킷', '데일리 백'] },
  { id: 'hobby', name: '취미 · 레저 상품', short: '취미 · 레저 상품', icon: 'camera', query: '캠핑용품', description: '카메라·게임·캠핑 등 취미 생활에 필요한 상품을 비교하세요.', keywords: ['미러리스 카메라', '닌텐도 스위치', '캠핑 의자'] },
  { id: 'used', name: '중고 최저가', short: '중고 최저가', icon: 'trend', query: '', description: '중고거래 플랫폼 연결을 준비하고 있습니다.', keywords: [] },
]

export const site2Suggestions = ['에어팟 프로 3', '로봇청소기', '맥북 에어 M3', '닌텐도 스위치 2', '제습기']
export const site2Slides = [
  { status: '다나와 상품 · 판매처 가격 비교', eyebrow: '검색은 짧게, 비교는 깊게', title: '비교해서 찾는 오늘의', accent: '합리적인 가격', description: '관심 있는 상품의 표시 가격부터 배송비와 옵션까지, 한곳에서 확인해 보세요.', image: mockupImages.lifestyle },
  { status: '직접 저장한 가격 관측 이력', eyebrow: '가격의 흐름을 한눈에', title: '데이터를 모아 찾는', accent: '구매의 타이밍', description: '저장한 실제 관측을 날짜별로 비교하고, 가격의 흐름을 차근차근 살펴보세요.', image: mockupImages.laptop },
  { status: '저장된 관측에 대한 Gemini 설명', eyebrow: '숫자 너머의 조건까지', title: '구매 전에 확인하는', accent: '실속 있는 선택', description: '표시가와 실제 결제 조건의 차이, 아직 확인되지 않은 정보를 함께 살펴보세요.', image: mockupImages.audio },
]

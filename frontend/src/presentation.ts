import type { IconName } from './Icons'

// 목업의 순서·라벨·SVG 아이콘을 그대로 사용한다. 빈 카테고리는 빈 결과를 표시한다.
export const mockupCategories: { id: string; name: string; icon: IconName }[] = [
  { id: '', name: '전체 카테고리', icon: 'menu' },
  { id: 'appliances', name: '가전 · TV', icon: 'home' },
  { id: 'laptop', name: '컴퓨터 · 노트북', icon: 'laptop' },
  { id: 'audio', name: '디지털 · 음향', icon: 'headphones' },
  { id: 'wearable', name: '웨어러블', icon: 'watch' },
  { id: 'camera', name: '카메라', icon: 'camera' },
  { id: 'kitchen', name: '주방 · 생활', icon: 'kitchen' },
  { id: 'fashion', name: '패션 · 뷰티', icon: 'shirt' },
  { id: 'baby', name: '유아 · 완구', icon: 'baby' },
  { id: 'car', name: '자동차 용품', icon: 'car' },
]

// 사이트/src/App.tsx의 데모 이미지. 실제 상품 사진이 아닌 목업용 사진이다.
export const mockupImages: Record<string, string> = {
  audio: 'https://images.unsplash.com/photo-1618366712010-f4ae9c647dcb?auto=format&fit=crop&w=900&q=85',
  laptop: 'https://images.unsplash.com/photo-1611186871348-b1ce696e52c9?auto=format&fit=crop&w=900&q=85',
  wearable: 'https://images.unsplash.com/photo-1648200076219-bc83229919ca?auto=format&fit=crop&w=900&q=85',
  lifestyle: 'https://images.unsplash.com/photo-1721720671231-d3107662343f?auto=format&fit=crop&w=1400&q=85',
}

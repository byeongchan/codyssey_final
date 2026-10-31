import { referenceCategories } from './categoryNavigationData'
import { site2Categories } from './site2Design'

// Curated search choices, not an exhaustive catalogue of available products.
export const classificationDetails: Record<string, string[]> = {
  '아이폰': ['아이폰 13', '아이폰 13 mini', '아이폰 13 Pro', '아이폰 13 Pro Max', '아이폰 14', '아이폰 14 Plus', '아이폰 14 Pro', '아이폰 14 Pro Max', '아이폰 15', '아이폰 15 Plus', '아이폰 15 Pro', '아이폰 15 Pro Max', '아이폰 16', '아이폰 16 Plus', '아이폰 16 Pro', '아이폰 16 Pro Max', '아이폰 16e'],
  '갤럭시': ['갤럭시 S', '갤럭시 A', '갤럭시 Z 폴드', '갤럭시 Z 플립'],
  '아이패드': ['아이패드 Air', '아이패드 Pro', '아이패드 mini'],
  '갤럭시탭': ['갤럭시탭 S', '갤럭시탭 A'],
  '맥북': ['맥북 에어', '맥북 프로'],
  '애플워치': ['애플워치 Series', '애플워치 SE', '애플워치 Ultra'],
  '티셔츠': ['맨투맨', '후드 티셔츠', '카라 티셔츠'],
  '니트': ['니트 스웨터', '카디건', '니트 조끼'],
  '재킷': ['블레이저', '데님 재킷', '가죽 재킷', '바람막이'],
  '바지': ['청바지', '슬랙스', '면바지', '조거 팬츠', '반바지'],
  '원피스': ['셔츠 원피스', '니트 원피스', '데님 원피스'],
  '스커트': ['플리츠 스커트', '데님 스커트', '니트 스커트'],
  '운동화': ['스니커즈', '러닝화', '농구화', '트레이닝화'],
  '구두': ['로퍼', '옥스퍼드', '펌프스'],
  '샌들': ['샌들', '슬리퍼', '쪼리'],
  'CPU': ['Intel Core', 'AMD Ryzen'],
  '그래픽카드': ['GeForce RTX', 'Radeon RX', 'Intel Arc'],
  'SSD': ['NVMe SSD', 'SATA SSD'],
  '텐트': ['돔 텐트', '터널 텐트', '리빙쉘 텐트', '원터치 텐트'],
  '골프클럽': ['드라이버', '우드', '유틸리티', '아이언', '웨지', '퍼터'],
  '낚싯대': ['민물 낚싯대', '바다 낚싯대', '루어 낚싯대'],
  '릴': ['스피닝 릴', '베이트 릴', '전동 릴'],
}
export function classificationGroups(categoryId: string) {
  const category = site2Categories.find(item => item.id === categoryId)
  return referenceCategories.find(item => item.label === category?.name)?.subcategories ?? []
}
export const classificationBrands: Record<string, string[]> = {
  '아이폰': ['APPLE'], '아이패드': ['APPLE'], '맥북': ['APPLE'], '애플워치': ['APPLE'],
  '갤럭시': ['삼성전자'], '갤럭시탭': ['삼성전자'], '갤럭시워치': ['삼성전자'],
  '노트북': ['삼성전자', 'APPLE', 'LG전자', 'ASUS', 'MSI', 'HP', 'DELL', '레노버'],
  'PC 주요부품': ['Intel', 'AMD', 'ASUS', 'MSI', 'GIGABYTE', 'CORSAIR'],
  '저장장치': ['삼성전자', 'Western Digital', 'Crucial', 'Seagate'],
  '모니터': ['삼성전자', 'LG전자', 'ASUS', 'MSI', 'DELL'],
  '키보드 · 마우스': ['로지텍', '레이저', 'CORSAIR'],
  '카메라': ['SONY', 'Canon', 'Nikon', 'Fujifilm', 'DJI'],
  '캠핑': ['콜맨', '스노우피크', '헬리녹스'],
  '자전거': ['트렉', '시마노'], '낚시': ['시마노'],
}
export function musinsaClassification(item: string, group: string, fallback: string) {
  if (['재킷', '아웃도어 재킷', '등산복', '남성 정장', '여성 정장'].includes(item)) return '002'
  if (item === '바지') return '003'
  if (['원피스', '스커트'].includes(item)) return '100'
  if (['운동화', '구두', '샌들', '등산화'].includes(item)) return '103'
  if (['백팩', '숄더백', '여행가방', '등산가방'].includes(item)) return '004'
  if (['티셔츠', '셔츠', '니트', '블라우스'].includes(item)) return '001'
  return group === '남성패션' || group === '여성패션' ? '001' : group === '신발 · 가방' ? '103' : group ? '017' : fallback
}

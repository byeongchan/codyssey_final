import type { ProductProvider } from './productSource'

export type ProductFilters = {
  classificationGroup: string
  classificationItem: string
  classification: string
  brand: string
  brandCode: string
  minPrice: string
  maxPrice: string
  sort: string
  productType: string
  gender: string
  color: string
  size: string
  minDiscount: string
  onSale: boolean
  coupon: boolean
  exclusive: boolean
  includeSoldOut: boolean
}
export type BrandChoice = { label: string; code?: string }
export const emptyProductFilters = (): ProductFilters => ({
  classificationGroup: '',
  classificationItem: '',
  classification: '',
  brand: '',
  brandCode: '',
  minPrice: '',
  maxPrice: '',
  sort: 'popular',
  productType: '',
  gender: 'all',
  color: '',
  size: '',
  minDiscount: '',
  onSale: false,
  coupon: false,
  exclusive: false,
  includeSoldOut: false,
})
export const fashionTypes = [
  { value: '001', label: '상의' },
  { value: '002', label: '아우터' },
  { value: '003', label: '바지' },
  { value: '100', label: '원피스·스커트' },
  { value: '103', label: '신발' },
  { value: '004', label: '가방' },
  { value: '017', label: '스포츠' },
]
export const fashionColors = [
  { value: 'BLACK', label: '블랙' },
  { value: 'WHITE', label: '화이트' },
  { value: 'GRAY', label: '그레이' },
  { value: 'NAVY', label: '네이비' },
  { value: 'BEIGE', label: '베이지' },
  { value: 'IVORY', label: '아이보리' },
  { value: 'KHAKI', label: '카키' },
  { value: 'SKYBLUE', label: '스카이블루' },
]
const knownDanawa: BrandChoice[] = [
  { label: '삼성전자', code: '702' },
  { label: 'APPLE', code: '1452' },
  { label: 'Western Digital', code: '4213' },
]
const names: Record<string, string[]> = {
  pc: [
    '삼성전자',
    'APPLE',
    'LG전자',
    '로지텍',
    '레이저',
    'ASUS',
    'MSI',
    'GIGABYTE',
    'CORSAIR',
    'HP',
    'DELL',
    '레노버',
    'Intel',
    'AMD',
    'Western Digital',
    'Crucial',
    'Seagate',
  ],
  digital: ['삼성전자', 'APPLE', 'LG전자', 'SONY', 'Bose', 'JBL', '샤오미', 'Anker', 'Garmin'],
  hobby: [
    'SONY',
    'Canon',
    'Nikon',
    'Fujifilm',
    'DJI',
    '닌텐도',
    '플레이스테이션',
    'Xbox',
    'LEGO',
    '콜맨',
    '스노우피크',
    '헬리녹스',
    '시마노',
    '트렉',
  ],
  fashion: [
    '나이키',
    '아디다스',
    '뉴발란스',
    '무신사 스탠다드',
    '커버낫',
    '노스페이스',
    '데상트',
    '언더아머',
    '아식스',
  ],
}
export const knownMusinsaBrands: BrandChoice[] = [
  { label: '무신사 스탠다드', code: 'musinsastandard' },
  { label: '나이키', code: 'nike' },
  { label: '커버낫', code: 'covernat' },
  { label: '뉴발란스', code: 'newbalance' },
]
export function brandChoices(
  category: string,
  provider: ProductProvider,
  discovered: BrandChoice[],
): BrandChoice[] {
  const defaults: BrandChoice[] =
    provider === 'musinsa'
      ? knownMusinsaBrands
      : (names[category] ?? []).map(
          (label) => knownDanawa.find((item) => item.label === label) ?? { label },
        )
  const combined = new Map<string, BrandChoice>()
  for (const item of [...defaults, ...discovered])
    if (item.label && (item.code || !combined.has(item.label))) combined.set(item.label, item)
  return [...combined.values()]
}
export function combineSearchTerms(...parts: (string | undefined)[]): string {
  const seen = new Set<string>()
  return parts
    .flatMap((part) => part?.trim().split(/\s+/) ?? [])
    .filter((word) => {
      const key = word.toLocaleLowerCase()
      if (!key || seen.has(key)) return false
      seen.add(key)
      return true
    })
    .join(' ')
}
export function classificationSearchQuery(
  query: string,
  group: string,
  item: string,
  detail: string,
) {
  const normalize = (value: string) => value.trim().replace(/\s+/g, ' ').toLocaleLowerCase()
  // A selected subtype replaces its parent search term. Do not require both
  // "바지" and "청바지", or both "티셔츠" and "맨투맨", to match a product.
  const parentQuery = normalize(query) === normalize(item) || normalize(query) === normalize(group)
  return combineSearchTerms(detail || item, parentQuery && (detail || item) ? '' : query)
}
export function danawaFilterRequest(
  query: string,
  categoryId: string,
  filters: ProductFilters,
  page = 1,
) {
  const words = [
    filters.classificationGroup === '남성패션'
      ? '남성'
      : filters.classificationGroup === '여성패션'
        ? '여성'
        : '',
    classificationSearchQuery(
      query,
      filters.classificationGroup,
      filters.classificationItem,
      filters.classification,
    ),
    filters.brandCode ? '' : filters.brand,
    categoryId === 'fashion'
      ? fashionTypes.find((item) => item.value === filters.productType)?.label
      : '',
    categoryId === 'fashion' && filters.gender !== 'all'
      ? filters.gender === 'men'
        ? '남성'
        : '여성'
      : '',
    categoryId === 'fashion'
      ? fashionColors.find((item) => item.value === filters.color)?.label
      : '',
    categoryId === 'fashion' ? filters.size : '',
  ]
  const combined = combineSearchTerms(...words)
  return {
    query: combined,
    categoryId,
    page,
    ...(filters.brandCode ? { maker: filters.brandCode } : {}),
    ...(filters.minPrice !== '' ? { minPrice: Number(filters.minPrice) } : {}),
    ...(filters.maxPrice !== '' ? { maxPrice: Number(filters.maxPrice) } : {}),
    ...(filters.sort !== 'popular' ? { sort: filters.sort } : {}),
  }
}
export function musinsaFilterRequest(filters: ProductFilters) {
  return {
    ...(filters.brandCode ? { brand: filters.brandCode } : {}),
    ...(filters.minPrice !== '' ? { minPrice: Number(filters.minPrice) } : {}),
    ...(filters.maxPrice !== '' ? { maxPrice: Number(filters.maxPrice) } : {}),
    ...(filters.color ? { color: filters.color } : {}),
    ...(filters.size ? { size: filters.size } : {}),
    ...(filters.minDiscount ? { minDiscount: Number(filters.minDiscount) } : {}),
    ...(filters.onSale ? { onSale: true } : {}),
    ...(filters.coupon ? { coupon: true } : {}),
    ...(filters.exclusive ? { exclusive: true } : {}),
    ...(filters.includeSoldOut ? { includeSoldOut: true } : {}),
  }
}

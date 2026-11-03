export type ProductProvider = 'danawa' | 'musinsa' | 'joongna' | 'karrot'
export const providerName = (provider: ProductProvider) =>
  ({ danawa: '다나와', musinsa: '무신사', joongna: '중고나라', karrot: '당근' })[provider]
export const productIdentity = (productId: string, provider: ProductProvider = 'danawa') =>
  `${provider}:${productId}`

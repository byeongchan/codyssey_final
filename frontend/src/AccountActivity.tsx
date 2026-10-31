import { useEffect, useState } from 'react'
import { Icon } from './Icons'
import { useLiveWishlist } from './LiveWishlist'
import { recentProducts, watchRecentProducts } from './recentProducts'
import { providerName, type ProductProvider } from './productSource'

export default function AccountActivity({ busy, onWishlist, onProduct }: {
  busy: boolean; onWishlist?: () => void; onProduct?: (id: string, provider?: ProductProvider) => void
}) {
  const saved = useLiveWishlist()
  const [recent, setRecent] = useState(recentProducts)
  useEffect(() => watchRecentProducts(() => setRecent(recentProducts())), [])
  return <div className="account-activity">
    <div className="account-stat-grid">
      {saved && <button type="button" className="account-stat" disabled={busy || !onWishlist} onClick={onWishlist}><span><Icon name="heart" size={18}/><Icon name="chevron" size={12}/></span><strong>{saved.ready ? saved.entries.length : '—'}<small>개</small></strong><span>{saved.loading ? '찜 목록 확인 중' : saved.error ? '찜 목록 확인 필요' : '찜한 상품'}</span></button>}
      <div className="account-stat"><span><Icon name="clock" size={18}/></span><strong>{recent.length}<small>개</small></strong><span>최근 본 상품 · 이 기기 기준</span></div>
    </div>
    {saved && <section className="account-data-card"><div className="account-card-heading"><h3><Icon name="heart" size={16}/>찜한 상품 미리보기</h3>{onWishlist && <button type="button" disabled={busy} onClick={onWishlist}>전체 보기<Icon name="chevron" size={12}/></button>}</div>
      {saved.error ? <p className="account-card-note" role="alert">{saved.error}<button type="button" disabled={busy || saved.loading || saved.busy} onClick={saved.reload}>다시 불러오기</button></p> : !saved.ready ? <p className="account-card-note" role="status">찜한 상품을 확인하고 있습니다.</p> : saved.entries.length ? <div className="account-product-list">{saved.entries.slice(0, 2).map(item => <button type="button" key={`${item.provider}:${item.productId}`} disabled={busy || !onProduct} onClick={() => onProduct?.(item.productId, item.provider)}><span className="account-product-icon"><Icon name="heart" size={18}/></span><span className="account-product-copy"><strong>{item.title}</strong><small>{providerName(item.provider)}</small></span><strong className="account-product-price">{typeof item.listedPrice === 'number' ? `${item.listedPrice.toLocaleString('ko-KR')}원` : '가격 미제공'}</strong><Icon name="chevron" size={12}/></button>)}</div> : <p className="account-card-note">찜한 상품이 없습니다. 관심 있는 상품을 저장해 보세요.</p>}
    </section>}
    {recent.length > 0 && onProduct && <details className="account-data-card account-recent"><summary><Icon name="clock" size={16}/>최근 본 상품<span>이 기기에 저장된 기록</span></summary><div className="account-product-list">{recent.slice(0, 3).map(item => <button type="button" key={`${item.provider}:${item.productId}`} disabled={busy} onClick={() => onProduct(item.productId, item.provider)}><span className="account-product-icon"><Icon name="clock" size={18}/></span><span className="account-product-copy"><strong>{item.title}</strong><small>{providerName(item.provider ?? 'danawa')}</small></span><Icon name="chevron" size={12}/></button>)}</div></details>}
  </div>
}

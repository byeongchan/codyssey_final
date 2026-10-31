import { useEffect, useMemo, useRef, useState } from 'react'
import { collectedRequest } from './collectedRequest'
import type { Meta, Single } from './api'
import type { DanawaSearch } from './Danawa'
import { Icon } from './Icons'
import { WishlistButton } from './LiveWishlist'
import { rememberRecentProduct } from './recentProducts'
import ProductSearchFilters from './ProductSearchFilters'
import { musinsaClassification } from './classificationFilters'
import { classificationSearchQuery, combineSearchTerms, emptyProductFilters, musinsaFilterRequest, type ProductFilters } from './productFilters'

type Item = { productId: string; title: string; image: string | null; brand: string | null; brandSlug?: string | null; url: string; normalPrice: number | null; salePrice: number | null; couponPrice: number | null; finalPrice: number | null; soldOut: boolean | null; discountRate: number | null; rating: number | null; reviewCount: number | null; optionPriceVaries: boolean | null }
type Variant = { variantId: string; label: string; inStock: boolean | null; active: boolean | null; remaining: number | null; priceDelta: number | null }
type Product = Item & { images: string[]; styleNo: string | null; season: string | null; variants: Variant[]; facts: { name: string | null; value: string | null }[]; sizeChart: { unit: string | null; note: string | null; sizes: { size: string; measurements: Record<string, string | null> }[] } | null; delivery: { expectedArrivalDate: string | null; expectedReleaseDate: string | null; courier: string | null; overseas: boolean | null; returnShippingFee: number | null } }
type Review = { reviewId: string; content: string; rating: number | null; createdAt: string | null; chosenOption: string; images: string[] }
type Result = { source: 'reef-musinsa'; synthetic: false; action: string; fetchedAt: string | null; receivedAt: string; page: number; hasMore: boolean; productId: string | null; items?: Item[]; product?: Product; reviews?: Review[]; total?: number | null }

const money = (value: number | null | undefined) => value == null ? '미확인' : `${value.toLocaleString('ko-KR')}원`
const panel = 'rounded-2xl border border-[var(--line)] bg-[var(--surface)] p-5 sm:p-6'
const button = 'rounded-xl border border-[var(--line)] px-4 py-2 text-xs font-bold disabled:opacity-40'
const selectClass = 'rounded-xl border border-[var(--line)] bg-[var(--surface)] px-3 py-2 text-xs'

function Photo({ src, title, large = false }: { src: string | null; title: string; large?: boolean }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [src])
  return <div className={`grid place-items-center overflow-hidden bg-[var(--surface-2)] ${large ? 'min-h-80 rounded-2xl' : 'aspect-[4/5]'}`}>{src && !failed ? <img src={src} alt={title} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} className={`h-full w-full object-contain ${large ? 'max-h-[550px]' : ''}`}/> : <div className="grid gap-3 p-8 text-center text-[var(--subtle)]"><Icon name="shirt" size={38}/><span className="text-xs">상품 이미지 미제공</span></div>}</div>
}

export default function MusinsaPage({ searchRequest, onBusyChange, onProductChange, onLogin }: {
  searchRequest: DanawaSearch | null; onBusyChange: (value: boolean) => void; onProductChange: (value: boolean) => void; onLogin: () => void
}) {
  const [category] = useState(searchRequest?.musinsaCategory ?? '001')
  const [gender] = useState<string>(searchRequest?.musinsaGender ?? 'all')
  const [sort] = useState('popular')
  const [filters, setFilters] = useState<ProductFilters>(emptyProductFilters)
  const appliedFilters = useMemo(() => ({ ...filters, productType: category, gender, sort }), [filters, category, gender, sort])
  const [listing, setListing] = useState<Result | null>(null)
  const [product, setProduct] = useState<Product | null>(null)
  const [meta, setMeta] = useState<Meta | null>(null)
  const [reviews, setReviews] = useState<Result | null>(null)
  const [variant, setVariant] = useState('')
  const [image, setImage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const active = useRef<AbortController | null>(null)
  const query = searchRequest?.browseCategory ? '' : searchRequest?.query ?? ''
  useEffect(() => { onBusyChange(busy) }, [busy, onBusyChange])
  useEffect(() => { onProductChange(Boolean(product)) }, [product, onProductChange])
  useEffect(() => () => active.current?.abort(), [])
  async function read(action: string, body: object, signal: AbortSignal): Promise<Single<Result>> {
    const response = await collectedRequest<Result>(`/musinsa/${action}`, signal, body, setNotice, '/musinsa/jobs/')
    if (response.data.source !== 'reef-musinsa' || response.data.synthetic !== false) throw new Error('무신사 응답의 출처를 확인하지 못했습니다.')
    return response
  }
  async function task(action: (signal: AbortSignal) => Promise<void>) {
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    setBusy(true); setError(''); setNotice('')
    try { await action(abort.signal) } catch (e) { if (!abort.signal.aborted) setError(e instanceof Error ? e.message : '무신사 정보를 불러오지 못했습니다.') }
    finally { if (!abort.signal.aborted && active.current === abort) { setBusy(false); setNotice('') } }
  }
  const selectedGroup = searchRequest?.navigationGroup || filters.classificationGroup
  const selectedItem = searchRequest?.navigationItem || filters.classificationItem
  const filterQuery = (next: ProductFilters) => combineSearchTerms(next.brand && !next.brandCode ? next.brand : '',
    classificationSearchQuery(query, searchRequest?.navigationGroup || next.classificationGroup,
      searchRequest?.navigationItem || next.classificationItem, next.classification))
  const filteredQuery = filterQuery(filters)
  const searchBody = (page = 1, refresh = false) => ({ categoryId: 'fashion', category: musinsaClassification(selectedItem, selectedGroup, category), query: filteredQuery, gender: selectedGroup === '남성패션' ? 'men' : selectedGroup === '여성패션' ? 'women' : gender, sort, page, refresh, ...musinsaFilterRequest(filters) })
  function applyFilters(next: ProductFilters) {
    if(filterQuery(next).length > 100) { setError('브랜드명과 검색어를 합쳐 100자 이내로 입력해 주세요.'); return }
    const url = new URL(location.href); url.searchParams.delete('product'); history.replaceState(null, '', url)
    setFilters(next)
  }
  async function loadList(signal: AbortSignal, page = 1, refresh = false) {
    const response = await read('search', searchBody(page, refresh), signal)
    if (!signal.aborted) { setListing(response.data); setMeta(response.meta) }
  }
  async function loadProduct(id: string, signal: AbortSignal, refresh = false) {
    if(!refresh) { setProduct(null); setReviews(null) }
    const response = await read('product', { productId: id, refresh }, signal)
    if (signal.aborted || !response.data.product) return
    const next = response.data.product
    setProduct(next); setMeta(response.meta); setImage(next.image ?? next.images[0] ?? null); setVariant(''); setReviews(null)
    rememberRecentProduct({ provider: 'musinsa', productId: next.productId, title: next.title, image: next.image, price: next.finalPrice })
  }
  function open(id: string) {
    const url = new URL(location.href); url.searchParams.set('provider', 'musinsa'); url.searchParams.set('category', 'fashion'); url.searchParams.set('product', id)
    history.pushState(null, '', url); void task(signal => loadProduct(id, signal)); window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  function back() {
    const url = new URL(location.href); url.searchParams.delete('product'); history.pushState(null, '', url)
    setProduct(null); setReviews(null); setError(''); void task(signal => loadList(signal))
  }
  useEffect(() => {
    const id = searchRequest === null ? new URLSearchParams(location.search).get('product') : null
    if (id && /^\d{1,30}$/.test(id)) void task(signal => loadProduct(id, signal))
    else {
      const url = new URL(location.href); url.searchParams.delete('product'); history.replaceState(null, '', url)
      setProduct(null); setReviews(null); setListing(null); setMeta(null); void task(signal => loadList(signal))
    }
    return () => active.current?.abort()
  }, [searchRequest?.revision, category, gender, sort, filters])
  useEffect(() => {
    const restore = () => {
      const params = new URLSearchParams(location.search)
      if (params.get('provider') !== 'musinsa') return
      const id = params.get('product')
      if (id && /^\d{1,30}$/.test(id)) void task(signal => loadProduct(id, signal))
      else { setProduct(null); setReviews(null); void task(signal => loadList(signal)) }
    }
    window.addEventListener('popstate', restore)
    return () => window.removeEventListener('popstate', restore)
  }, [category, query, gender, sort, filters])
  const currentVariant = product?.variants.find((_, index) => String(index) === variant)
  const measured = product?.sizeChart?.sizes ?? []
  const measurementNames = [...new Set(measured.flatMap(row => Object.keys(row.measurements)))]
  return <section className="space-y-6" aria-label="무신사 패션·스포츠 상품" aria-busy={busy}>
    {error && <div role="alert" className="rounded-xl border border-rose-400/30 bg-rose-400/10 p-4 text-sm text-rose-500">{error}<button disabled={busy} className="ml-3 underline" onClick={() => { const id = new URLSearchParams(location.search).get('product'); void task(signal => id ? loadProduct(id, signal) : loadList(signal)) }}>다시 시도</button></div>}
    {busy && <p role="status" className="text-sm text-[var(--muted)]">{notice || '무신사 상품을 불러오는 중입니다…'}</p>}
    {!product ? <>
      <ProductSearchFilters categoryId="fashion" navigationGroup={searchRequest?.navigationGroup} navigationItem={searchRequest?.navigationItem} provider="musinsa" applied={appliedFilters} discoveredBrands={(listing?.items ?? []).map(item => ({ label: item.brand ?? '', code: item.brandSlug ?? undefined }))} disabled={busy} onApply={applyFilters}/>
      <div className="flex justify-end"><button type="button" disabled={busy} aria-label="상품 갱신" title="상품 갱신" onClick={() => void task(signal => loadList(signal, listing?.page ?? 1, true))} className="grid h-9 w-9 place-items-center rounded-full text-[var(--primary)] transition hover:bg-[var(--surface-2)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--primary)] disabled:opacity-50"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 7v5h-5M4 17v-5h5"/><path d="M6.1 7a7 7 0 0 1 11.6-1L20 9M4 15l2.3 3A7 7 0 0 0 17.9 17"/></svg></button></div>
      {!busy && listing?.items?.length === 0 && <div className={`${panel} text-center text-sm text-[var(--muted)]`}>검색 결과가 없습니다. 다른 분류를 선택하거나 검색어를 바꿔보세요.</div>}
      <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">{listing?.items?.map(item => <li key={`musinsa:${item.productId}`} className="relative overflow-hidden rounded-2xl border border-[var(--line)] bg-[var(--surface)]"><div className="absolute right-3 top-3 z-10"><WishlistButton provider="musinsa" productId={item.productId} onLogin={onLogin} compact/></div><button disabled={busy} onClick={() => open(item.productId)} className="w-full text-left disabled:opacity-50"><Photo src={item.image} title={item.title}/><div className="p-4"><p className="text-[11px] font-bold text-[var(--muted)]">무신사 · {item.brand || '브랜드 미확인'}</p><h3 className="mt-2 line-clamp-2 min-h-10 text-sm font-bold">{item.title}</h3><div className="mt-3 flex gap-2 text-lg font-black">{item.discountRate !== null && <span className="text-rose-500">{item.discountRate}%</span>}<span>{money(item.finalPrice)}</span></div><p className="mt-1 text-[10px] text-[var(--subtle)]">표시 최종가{item.optionPriceVaries ? ' · 옵션별 가격 상이' : ''}</p><p className="mt-2 text-xs text-[var(--muted)]">{item.rating !== null ? `★ ${item.rating.toFixed(1)} · ` : ''}리뷰 {item.reviewCount?.toLocaleString() ?? '미확인'}{item.soldOut ? ' · 품절' : ''}</p></div></button></li>)}</ul>
      {listing && <nav aria-label="무신사 검색 결과 페이지" className="flex items-center justify-center gap-4"><button className={button} disabled={busy || listing.page <= 1} onClick={() => void task(signal => loadList(signal, listing.page - 1))}>이전</button><span className="text-xs">{listing.page}페이지</span><button className={button} disabled={busy || !listing.hasMore || listing.page >= 1000} onClick={() => void task(signal => loadList(signal, listing.page + 1))}>다음</button></nav>}
    </> : <>
      <button disabled={busy} className={button} onClick={back}>상품 목록으로</button>
      <div className="grid gap-6 lg:grid-cols-2"><div><Photo src={image} title={product.title} large/>{product.images.length > 1 && <div className="mt-3 flex gap-2 overflow-x-auto">{product.images.map((src, index) => <button key={`${src}:${index}`} aria-label={`${index + 1}번 상품 이미지`} aria-pressed={image === src} onClick={() => setImage(src)} className={`w-16 shrink-0 overflow-hidden rounded-lg border ${image === src ? 'border-[var(--primary)]' : 'border-[var(--line)]'}`}><Photo src={src} title=""/></button>)}</div>}</div><div className={panel}><p className="text-xs font-black text-[var(--mint)]">무신사 · {product.brand}</p><h1 className="mt-3 text-2xl font-black">{product.title}</h1><p className="mt-3 text-xs text-[var(--muted)]">{product.styleNo ? `스타일 번호 ${product.styleNo} · ` : ''}{product.rating !== null ? `★ ${product.rating.toFixed(1)} · ` : ''}리뷰 {product.reviewCount?.toLocaleString() ?? '미확인'}개</p><p className="mt-6 text-3xl font-black">{money(product.finalPrice)}</p><dl className="mt-5 space-y-2 text-sm">{[['정상가', product.normalPrice], ['판매가', product.salePrice], ['쿠폰 적용가', product.couponPrice]].map(([label, price]) => <div key={String(label)} className="flex justify-between gap-4"><dt className="text-[var(--muted)]">{label}</dt><dd>{money(price as number | null)}</dd></div>)}</dl><p className="mt-4 text-xs leading-6 text-[var(--muted)]">무신사에 표시된 가격입니다. 회원·카드 혜택, 옵션 추가 금액과 배송 조건에 따라 실제 결제 금액이 달라질 수 있습니다.</p>
        {product.variants.length > 0 && <label className="mt-5 block text-xs font-bold">색상·사이즈<select className={`${selectClass} mt-2 w-full`} value={variant} onChange={e => setVariant(e.target.value)}><option value="">옵션을 선택해 주세요</option>{product.variants.map((item, index) => <option key={`${item.variantId}:${index}`} value={String(index)}>{item.label || `옵션 ${index + 1}`}{item.active === false || item.inStock === false ? ' · 품절/판매 중지' : item.inStock === true ? ' · 재고 있음' : ' · 재고 미확인'}</option>)}</select></label>}
        {currentVariant && <p className="mt-3 text-xs text-[var(--muted)]">{currentVariant.priceDelta !== null ? `옵션 금액 차이 ${currentVariant.priceDelta > 0 ? '+' : ''}${money(currentVariant.priceDelta)}` : '옵션별 금액 차이 미확인'}{currentVariant.remaining !== null ? ` · 표시 잔여 수량 ${currentVariant.remaining}` : ''}</p>}
        {product.soldOut && <p className="mt-4 font-bold text-rose-500">현재 품절된 상품입니다.</p>}
        <div className="mt-6 space-y-3"><a href={product.url} target="_blank" rel="noopener noreferrer" className="flex items-center justify-center gap-2 rounded-xl bg-[var(--primary)] px-4 py-3 text-sm font-bold text-white">무신사에서 조건 확인<Icon name="external" size={15}/></a><WishlistButton provider="musinsa" productId={product.productId} onLogin={onLogin}/><button disabled={busy} className={`${button} w-full`} onClick={() => void task(signal => loadProduct(product.productId, signal, true))}>최신 상품 정보 갱신</button></div>
      </div></div>
      {measured.length > 0 && <section className={panel}><h2 className="mb-4 font-black">사이즈 실측 {product.sizeChart?.unit ? `(${product.sizeChart.unit})` : ''}</h2><div className="overflow-x-auto"><table className="w-full text-left text-xs"><thead><tr><th className="p-3">사이즈</th>{measurementNames.map(name => <th key={name} className="p-3">{name}</th>)}</tr></thead><tbody>{measured.map((row, index) => <tr key={index} className="border-t border-[var(--line)]"><th className="p-3">{row.size}</th>{measurementNames.map(name => <td key={name} className="p-3">{row.measurements[name] ?? '미측정'}</td>)}</tr>)}</tbody></table></div>{product.sizeChart?.note && <p className="mt-3 text-xs text-[var(--muted)]">{product.sizeChart.note}</p>}</section>}
      <section className={panel}><h2 className="mb-4 font-black">상품·배송 정보</h2><dl className="grid gap-3 text-xs sm:grid-cols-2">{product.facts.filter(row => row.name && row.value).map((row, index) => <div key={index}><dt className="text-[var(--muted)]">{row.name}</dt><dd className="mt-1 whitespace-pre-wrap break-words">{row.value}</dd></div>)}{product.delivery.expectedArrivalDate && <div><dt className="text-[var(--muted)]">도착 예정일</dt><dd>{product.delivery.expectedArrivalDate}</dd></div>}{product.delivery.courier && <div><dt className="text-[var(--muted)]">택배사</dt><dd>{product.delivery.courier}</dd></div>}{product.delivery.returnShippingFee !== null && <div><dt className="text-[var(--muted)]">반품 배송비</dt><dd>{money(product.delivery.returnShippingFee)}</dd></div>}</dl><p className="mt-5 text-xs text-[var(--subtle)]">주문 배송비와 최종 배송 일정은 판매 페이지에서 확인해 주세요.</p></section>
      <section className={panel}><div className="flex items-center justify-between gap-3"><h2 className="font-black">무신사 구매 후기</h2><button disabled={busy || product.reviewCount === 0 || Boolean(reviews && !reviews.hasMore)} className={button} onClick={() => void task(async signal => { const response = await read('reviews', { productId: product.productId, page: reviews ? reviews.page + 1 : 1 }, signal); if (!signal.aborted) setReviews(previous => ({ ...response.data, reviews: [...(previous?.reviews ?? []), ...(response.data.reviews ?? [])] })) })}>{reviews ? '후기 더 보기' : '후기 불러오기'}</button></div>{product.reviewCount === 0 && !reviews && <p className="mt-5 text-sm text-[var(--muted)]">등록된 후기가 없습니다.</p>}{reviews && !reviews.reviews?.length && <p className="mt-5 text-sm text-[var(--muted)]">표시할 후기가 없습니다.</p>}<div className="divide-y divide-[var(--line)]">{reviews?.reviews?.map((review, index) => <article key={`${review.reviewId}:${index}`} className="py-5"><p className="text-xs text-[var(--muted)]">{review.rating !== null ? `★ ${review.rating} · ` : ''}{review.createdAt} {review.chosenOption && `· ${review.chosenOption}`}</p><p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{review.content}</p>{review.images.length > 0 && <div className="mt-3 flex gap-2 overflow-x-auto">{review.images.map((src, n) => <a key={`${src}:${n}`} href={src} target="_blank" rel="noopener noreferrer" className="w-24 shrink-0"><Photo src={src} title="리뷰 첨부 이미지"/></a>)}</div>}</article>)}</div>{reviews && !reviews.hasMore && <p className="mt-3 text-xs text-[var(--subtle)]">마지막 후기입니다.</p>}</section>
    </>}
    {meta && <p className="text-[11px] leading-6 text-[var(--subtle)]">무신사 표시 정보 · 조회 시각 {meta.updatedAt ? new Date(meta.updatedAt).toLocaleString('ko-KR') : '원본 시각 미제공'}{meta.stale ? ' · 저장된 정보가 오래되었습니다. 갱신 후 확인해 주세요.' : ''}</p>}
  </section>
}

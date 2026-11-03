import { saveUsedAgentContext, restoreUsedAgentContext } from './usedAgentContext'
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { collectedRequest } from './collectedRequest'
import type { DanawaSearch } from './Danawa'
import { Icon } from './Icons'
import { WishlistButton } from './LiveWishlist'
import { combineSearchTerms } from './productFilters'
import { providerName } from './productSource'
import { rememberRecentProduct } from './recentProducts'
import './usedLowest.css'
import RegionSelectionModal from './RegionSelectionModal'
import UsedAgentChat, { type UsedAgentContext } from './UsedAgentChat'
import { resolveDirectoryRegion } from './daangnRegionDirectory'

type Platform = 'joongna' | 'karrot'
type Item = { productId: string; provider: Platform; title: string; url: string | null; image: string | null; price: number | null; status: 'on_sale' | 'reserved' | 'sold' | null; regionName: string | null; createdAt: string | null; bumpedAt: string | null }
type Product = Item & { description: string | null; images: string[]; sellerName: string | null; mannerScore: number | null; categoryName: string | null; deliveryOptions: string | null }
type Region = { id: string; name: string; province?: string; district?: string; neighborhood?: string; depth?: number }
type Result = { evidenceIds?: string[]; evidenceId?: string; source: string; provider: Platform; synthetic: false; items?: Item[]; product?: Product; hasMore: boolean; page?: number; receivedAt: string; fetchedAt: string | null; regionId?: string; regionName?: string | null; matchScope?: string | null; truncated?: boolean; regions?: Region[]; categories?: { id: number; name: string }[] }
type SourceState = { result?: Result; loading: boolean; error: string }
type Search = { query: string; platforms: Platform[]; regionId: string; regionName: string; onSaleOnly: boolean; categoryId: string }
const platforms: Platform[] = ['joongna', 'karrot']
const money = (value: number | null) => value === null ? '가격 미확인' : value === 0 ? '나눔' : `${value.toLocaleString('ko-KR')}원`
const identity = (item: Item) => `${item.provider}:${item.productId}`
const navigationQuery = (search: DanawaSearch | null) => search?.browseCategory ? '' : search?.navigationGroup
  ? (search.query ?? '').replace(/(^중고\s+|\s+중고$)/g, '') : search?.query ?? ''
const status = (item: Item) => item.status === 'on_sale' ? '판매 중' : item.status === 'reserved' ? '예약 중' : item.status === 'sold' ? '판매 완료' : '거래 상태 미확인'
function timestamp(item: Item) {
  for (const raw of [item.bumpedAt, item.createdAt]) { const value = Date.parse(raw ?? ''); if (Number.isFinite(value)) return value }
  return 0
}
function Photo({ item, large = false }: { item: { image: string | null; title: string }; large?: boolean }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [item.image])
  return <div className={large ? 'used-photo used-photo-large' : 'used-photo'}>{item.image && !failed ? <img src={item.image} alt={item.title} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)}/> : <Icon name="camera" size={32}/>}</div>
}

export default function UsedLowestPage({ searchRequest, onBusyChange, onProductChange, onLogin }: {
  searchRequest: DanawaSearch | null; onBusyChange: (busy: boolean) => void; onProductChange: (detail: boolean) => void; onLogin: () => void
}) {
  const [query, setQuery] = useState(navigationQuery(searchRequest))
  const [selected, setSelected] = useState<Platform[]>(platforms)
  const [regionId, setRegionId] = useState('')
  const [popularRegions, setPopularRegions] = useState<Region[]>([])
  const [nearbyRegions, setNearbyRegions] = useState<{ origin: string; items: Region[] }>({ origin: '', items: [] })
  const [popularLoaded, setPopularLoaded] = useState(false)
  const [regionModalOpen, setRegionModalOpen] = useState(false)
  const [selectedRegionName, setSelectedRegionName] = useState('')
  const [regionMode, setRegionMode] = useState<'popular' | 'nearby'>('popular')
  const [regionBusy, setRegionBusy] = useState(false)
  const [regionError, setRegionError] = useState('')
  const [categories, setCategories] = useState<{ id: number; name: string }[]>([])
  const [categoryId, setCategoryId] = useState('')
  const [onSaleOnly, setOnSaleOnly] = useState(true)
  const [sources, setSources] = useState<Record<Platform, SourceState>>({ joongna: { loading: false, error: '' }, karrot: { loading: false, error: '' } })
  const [view, setView] = useState<'all' | Platform>('all')
  const [sort, setSort] = useState('low')
  const [minPrice, setMinPrice] = useState('')
  const [maxPrice, setMaxPrice] = useState('')
  const [priceRange, setPriceRange] = useState<{ min: number | null; max: number | null }>({ min: null, max: null })
  const [priceError, setPriceError] = useState('')
  const [page, setPage] = useState(1)
  const [error, setError] = useState('')
  const [searched, setSearched] = useState('')
  const [product, setProduct] = useState<Product | null>(null)
  const [detailBusy, setDetailBusy] = useState(false)
  const [detailError, setDetailError] = useState('')
  const [inDetail, setInDetail] = useState(Boolean(new URLSearchParams(location.search).get('product')))
  const [detailEvidence, setDetailEvidence] = useState('')
  const [photo, setPhoto] = useState<string | null>(null)
  const active = useRef<AbortController | null>(null), regionActive = useRef<AbortController | null>(null), detailActive = useRef<AbortController | null>(null)
  const applied = useRef<Search | null>(null)
  const busy = sources.joongna.loading || sources.karrot.loading || regionBusy || detailBusy
  useEffect(() => { onBusyChange(busy) }, [busy, onBusyChange])
  useEffect(() => { onProductChange(inDetail) }, [inDetail, onProductChange])
  useEffect(() => () => { active.current?.abort(); regionActive.current?.abort(); detailActive.current?.abort() }, [])
  async function read(provider: Platform, action: string, body: object, signal: AbortSignal) {
    const response = await collectedRequest<Result>(`/used/${provider}/${action}`, signal, body, undefined, '/used/jobs/')
    if (response.data.source !== `reef-${provider}` || response.data.synthetic !== false) throw new Error('매물의 출처를 확인하지 못했습니다.')
    return { ...response.data, evidenceId: response.meta.collectionJobId }
  }
  async function findRegions(mode: 'popular' | 'nearby') {
    if (mode === 'nearby' && !regionId) { setRegionError('인접 동네를 찾으려면 먼저 기준 동네를 선택해 주세요.'); return }
    const abort = new AbortController(); regionActive.current?.abort(); regionActive.current = abort; setRegionBusy(true); setRegionError(''); setRegionMode(mode)
    try {
      const result = await read('karrot', 'regions', mode === 'nearby' ? { regionId } : {}, abort.signal)
      if (!abort.signal.aborted) {
        const next = result.regions ?? []
        if (mode === 'popular') { setPopularRegions(next); setPopularLoaded(true) }
        else setNearbyRegions({ origin: regionId, items: next })
      }
      if (!categories.length && !abort.signal.aborted) {
        try {
          const response = await read('karrot', 'categories', {}, abort.signal)
          if (!abort.signal.aborted) setCategories(response.categories ?? [])
        } catch { /* Optional product categories must not hide successfully resolved neighbourhoods. */ }
      }
    } catch (e) { if (!abort.signal.aborted) setRegionError(e instanceof Error ? e.message : '동네를 불러오지 못했습니다.') }
    finally { if (!abort.signal.aborted) setRegionBusy(false) }
  }
  function closeRegionModal() { regionActive.current?.abort(); setRegionBusy(false); setRegionModalOpen(false) }
  async function chooseRegion(candidate: { id: string; name: string }) {
    setRegionBusy(true); setRegionError(''); setError('')
    try {
      const region = await resolveDirectoryRegion(candidate)
      setRegionId(region.id); setSelectedRegionName(region.name); closeRegionModal()
      if (applied.current && selected.includes('karrot')) {
        // Apply the visible query and filters together with the explicitly selected region.
        search(false, region)
      }
    } catch (e) {
      const message = e instanceof Error ? e.message : '지역 목록을 확인하지 못했습니다.'
      setRegionError(message); setError(message)
    } finally { setRegionBusy(false) }
  }
  function openRegionModal() {
    setRegionModalOpen(true); setRegionError('')
    if (!regionBusy) {
      if (regionId && nearbyRegions.origin !== regionId) void findRegions('nearby')
      else if (!popularLoaded) void findRegions('popular')
    }
  }
  async function loadSource(provider: Platform, search: Search, signal: AbortSignal, refresh = false, nextPage = 1) {
    setSources(old => ({ ...old, [provider]: { ...old[provider], loading: true, error: '' } }))
    try {
      if (provider === 'karrot') {
        const region = await resolveDirectoryRegion({ id: search.regionId, name: search.regionName })
        if (signal.aborted) return
        if (region.id !== search.regionId) throw new Error('선택한 지역 ID가 지역 목록과 다릅니다. 동네를 다시 선택해 주세요.')
        search = { ...search, regionId: region.id, regionName: region.name }
      }
      const result = await read(provider, 'search', { query: search.query, refresh, ...(provider === 'joongna' ? { page: nextPage } : { regionId: search.regionId, onSaleOnly: search.onSaleOnly, ...(search.categoryId ? { categoryId: Number(search.categoryId) } : {}) }) }, signal)
      if (provider === 'karrot' && result.regionId !== search.regionId) throw new Error('당근 응답의 검색 기준 지역이 일치하지 않습니다. 수집기와 API를 재시작한 뒤 다시 검색해 주세요.')
      if (!signal.aborted) setSources(old => {
        const items = nextPage > 1 ? [...new Map([...(old[provider].result?.items ?? []), ...(result.items ?? [])].map(item => [identity(item), item])).values()] : result.items
        const evidenceIds = nextPage > 1 ? [...new Set([...(old[provider].result?.evidenceIds ?? []), ...(result.evidenceId ? [result.evidenceId] : [])])] : result.evidenceId ? [result.evidenceId] : []
        return { ...old, [provider]: { loading: false, error: '', result: { ...result, items, evidenceIds } } }
      })
    } catch (e) { if (!signal.aborted) setSources(old => ({ ...old, [provider]: { ...old[provider], loading: false, error: e instanceof Error ? e.message : '매물을 조회하지 못했습니다.' } })) }
  }
  function search(refresh = false, region?: { id: string; name: string }) {
    if (busy && !region) return
    const next: Search = { query: combineSearchTerms(query), platforms: [...selected], regionId: region?.id ?? regionId, regionName: region?.name ?? selectedRegionName, onSaleOnly, categoryId }
    if (!next.query || next.query.length > 100) { setError('상품명을 1~100자로 입력해 주세요.'); return }
    if (!next.platforms.length) { setError('검색할 플랫폼을 선택해 주세요.'); return }
    if (next.platforms.includes('karrot') && !next.regionId) { setError('당근을 함께 검색하려면 동네를 선택해 주세요. 중고나라만 선택하면 동네 없이 검색할 수 있습니다.'); return }
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    const sameConditions = JSON.stringify(applied.current) === JSON.stringify(next)
    applied.current = next; setError(''); setSearched(next.query); setPage(1); setView('all')
    if (!refresh || !sameConditions) setSources({ joongna: { loading: false, error: '' }, karrot: { loading: false, error: '' } })
    void Promise.allSettled(next.platforms.map(provider => loadSource(provider, next, abort.signal, refresh)))
  }
  function retry(provider: Platform, nextPage = 1) {
    if (!applied.current || busy) return
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    void loadSource(provider, applied.current, abort.signal, nextPage === 1, nextPage)
  }
  async function loadDetail(provider: Platform, id: string, refresh = false) {
    const abort = new AbortController(); detailActive.current?.abort(); detailActive.current = abort
    setInDetail(true); setDetailBusy(true); setDetailError(''); if (!refresh) setProduct(null)
    try {
      const result = await read(provider, 'detail', { productId: id, refresh }, abort.signal)
      if (!abort.signal.aborted && result.product) {
        setDetailEvidence(`${result.evidenceId}:${result.fetchedAt}:${result.receivedAt}`)
        setProduct(result.product); setPhoto(result.product.image ?? result.product.images[0] ?? null)
        rememberRecentProduct({ provider, productId: id, title: result.product.title, image: result.product.image, price: result.product.price })
      }
    } catch (e) { if (!abort.signal.aborted) setDetailError(e instanceof Error ? e.message : '매물 상세를 조회하지 못했습니다.') }
    finally { if (!abort.signal.aborted) setDetailBusy(false) }
  }
  function open(item: Item) {
    // Keep only server evidence references so a detail reload can retain the original search scope.
    saveUsedAgentContext(item.provider, item.productId, currentAgentContext)
    const url = new URL(location.href); url.searchParams.set('category', 'used'); url.searchParams.set('provider', item.provider); url.searchParams.set('product', item.productId); history.pushState(null, '', url)
    void loadDetail(item.provider, item.productId); window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  function back() {
    detailActive.current?.abort(); setDetailBusy(false); setInDetail(false); setProduct(null); setDetailError('')
    const url = new URL(location.href); url.searchParams.delete('product'); url.searchParams.delete('provider'); url.searchParams.set('category', 'used'); history.pushState(null, '', url)
  }
  useEffect(() => {
    const restore = () => {
      const params = new URLSearchParams(location.search), provider = params.get('provider'), id = params.get('product')
      if (id && (provider === 'joongna' || provider === 'karrot')) void loadDetail(provider, id)
      else { detailActive.current?.abort(); setDetailBusy(false); setInDetail(false); setProduct(null) }
    }
    restore(); window.addEventListener('popstate', restore)
    return () => window.removeEventListener('popstate', restore)
  }, [])
  useEffect(() => {
    setQuery(navigationQuery(searchRequest))
    if (!searchRequest) return
    active.current?.abort(); setSources({ joongna: { loading: false, error: '' }, karrot: { loading: false, error: '' } }); applied.current = null; setSearched(''); setError(''); setPage(1)
  }, [searchRequest?.revision])
  function applyPrice(event: FormEvent) {
    event.preventDefault()
    const values = [minPrice, maxPrice].map(value => value.replace(/,/g, '').trim())
    if (values.some(value => value !== '' && (!/^\d+$/.test(value) || Number(value) > 100_000_000))) { setPriceError('가격은 0~100,000,000원의 정수로 입력해 주세요.'); return }
    const min = values[0] === '' ? null : Number(values[0]), max = values[1] === '' ? null : Number(values[1])
    if (min !== null && max !== null && min > max) { setPriceError('최저가는 최고가보다 클 수 없습니다.'); return }
    setPriceError(''); setPriceRange({ min, max }); setPage(1)
  }
  function resetPrice() { setMinPrice(''); setMaxPrice(''); setPriceRange({ min: null, max: null }); setPriceError(''); setPage(1) }
  const priceFilteredRows = useMemo(() => {
    const matches = (item: Item) => priceRange.min === null && priceRange.max === null || item.price !== null
      && (priceRange.min === null || item.price >= priceRange.min) && (priceRange.max === null || item.price <= priceRange.max)
    return { joongna: (sources.joongna.result?.items ?? []).filter(matches), karrot: (sources.karrot.result?.items ?? []).filter(matches) }
  }, [sources, priceRange])
  const items = useMemo(() => {
    const rows = platforms.flatMap(provider => view === 'all' || view === provider ? priceFilteredRows[provider] : [])
    const unique = [...new Map(rows.map(item => [identity(item), item])).values()]
    return unique.sort((a, b) => {
      const aValid = a.price !== null && a.price > 0, bValid = b.price !== null && b.price > 0
      if (aValid !== bValid) return aValid ? -1 : 1
      if (sort === 'latest') return timestamp(b) - timestamp(a)
      return aValid && bValid ? (sort === 'low' ? a.price! - b.price! : b.price! - a.price!) : 0
    })
  }, [priceFilteredRows, view, sort])
  const currentAgentContext = {
    searches: platforms.flatMap(provider => (sources[provider].result?.evidenceIds ?? []).map(id => ({ provider, id }))).slice(0, 3),
    candidates: items.slice(0, 1200).map(item => ({ provider: item.provider, productId: item.productId })),
  }
  const agentContext: UsedAgentContext = inDetail && product
    ? restoreUsedAgentContext(product.provider, product.productId) ?? { searches: [], candidates: [] }
    : currentAgentContext
  const agentEvidenceKey = JSON.stringify([detailEvidence, agentContext, applied.current])
  const searchAgentContext = {
    searches: agentContext.searches,
    candidates: items.slice(0, 1200).map(item => ({ provider: item.provider, productId: item.productId })),
  }
  const searchAgentEvidenceKey = JSON.stringify([searchAgentContext, platforms.map(provider => [sources[provider].result?.fetchedAt, sources[provider].result?.receivedAt]), priceRange, view])
  const pages = Math.max(1, Math.ceil(items.length / 12)), currentPage = Math.min(page, pages)
  function submit(event: FormEvent) { event.preventDefault(); search() }
  return <section className="used-page" aria-label="중고 최저가" aria-busy={busy}>
    {inDetail ? <>
      <button type="button" className="used-secondary" onClick={back}>← 매물 목록으로</button>
      {detailBusy && <p role="status" className="used-notice">매물 정보를 불러오는 중입니다.</p>}
      {detailError && <div role="alert" className="used-error">{detailError}<button type="button" onClick={() => { const p = new URLSearchParams(location.search); const source = p.get('provider'); if (source === 'joongna' || source === 'karrot') void loadDetail(source, p.get('product') ?? '') }}>다시 시도</button></div>}
      {product && <div className="used-detail-layout grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_380px]"><div className="used-detail"><div><Photo item={{ image: photo, title: product.title }} large/>{product.images.length > 1 && <div className="used-thumbnails">{product.images.map((image, i) => <button key={image} type="button" aria-label={`${i + 1}번 사진`} aria-pressed={photo === image} onClick={() => setPhoto(image)}><img src={image} alt="" loading="lazy" referrerPolicy="no-referrer"/></button>)}</div>}</div><div className="used-panel used-detail-info"><span className={`used-platform used-platform-${product.provider}`}>{providerName(product.provider)}</span><h1>{product.title}</h1><p className="used-detail-price">{money(product.price)}</p><p className="used-muted">{status(product)}{product.regionName ? ` · ${product.regionName}` : ''}</p>{product.sellerName && <p className="used-muted">판매자 {product.sellerName}{product.mannerScore !== null ? ` · 매너온도 ${product.mannerScore}°C` : ''}</p>}<p className="used-description">{product.description || '상품 설명이 제공되지 않았습니다.'}</p>{product.deliveryOptions && <p className="used-muted">거래 방식: {product.deliveryOptions}</p>}<div className="used-detail-actions">{product.url && <a className="used-primary" href={product.url} target="_blank" rel="noopener noreferrer">{providerName(product.provider)}에서 매물 확인<Icon name="external" size={15}/></a>}<WishlistButton provider={product.provider} productId={product.productId} onLogin={onLogin}/><button type="button" disabled={detailBusy} className="used-secondary" onClick={() => void loadDetail(product.provider, product.productId, true)}>매물 정보 갱신</button></div><p className="used-muted">저장된 표시 정보입니다. 실제 가격·상태·거래 가능 여부는 원본에서 확인해 주세요.</p></div></div>
        <aside className="used-agent-detail-sidebar min-w-0 self-start" aria-label="중고 매물 구매 상담">{!detailBusy && <UsedAgentChat provider={product.provider} productId={product.productId} title={product.title} context={agentContext} evidenceKey={agentEvidenceKey} onLogin={onLogin}/>}</aside>
      </div>}
    </> : <>
      <header className="used-hero"><span className="used-eyebrow"><span className="live-dot"/>중고나라 · 당근 통합 검색</span><h1>흩어진 중고 매물,<br/><em>한곳에서 가격 비교</em></h1><p>찾는 상품을 검색하고, 플랫폼별 매물과 표시 가격을 함께 확인하세요.</p></header>
      <section className="used-panel used-controls" aria-label="중고 검색 조건"><div className="used-control-heading"><div><h2>검색 플랫폼</h2><p>검색·매물 갱신은 현재 입력한 검색어와 선택 조건으로 조회합니다.</p></div><label className="used-check"><input type="checkbox" disabled={busy} checked={selected.length === platforms.length} onChange={e => setSelected(e.target.checked ? [...platforms] : [])}/>전체 선택</label></div><div className="used-platform-options">{platforms.map(provider => <button key={provider} type="button" disabled={busy} aria-pressed={selected.includes(provider)} className={`used-platform-choice used-platform-${provider} ${selected.includes(provider) ? 'is-active' : ''}`} onClick={() => setSelected(old => old.includes(provider) ? old.filter(value => value !== provider) : [...old, provider])}><span/>{providerName(provider)}{selected.includes(provider) && <Icon name="check" size={13}/>}</button>)}</div>
        {selected.includes('karrot') && <div className="used-region">
          <div className="used-region-heading"><h3>당근 검색 지역</h3><button type="button" className="used-region-trigger" disabled={busy} onClick={openRegionModal} aria-haspopup="dialog"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/></svg><span>{selectedRegionName || '지역 선택'}</span><Icon name="chevron" size={14}/></button></div>
          <div className="used-region-selects">
            {categories.length > 0 && <label>당근 상품 분류<select value={categoryId} disabled={busy} onChange={e => setCategoryId(e.target.value)}><option value="">전체 분류</option>{categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>}
            <label className="used-check"><input type="checkbox" disabled={busy} checked={onSaleOnly} onChange={e => setOnSaleOnly(e.target.checked)}/>당근 판매 중 매물만</label>
          </div>
          <p className="used-help">선택한 동네를 기준으로 검색합니다. 당근 검색 특성상 다른 지역 매물도 포함될 수 있습니다.</p>
          {regionModalOpen && <RegionSelectionModal selected={regionId ? { id: regionId, name: selectedRegionName } : null} popular={popularRegions} nearby={nearbyRegions.origin === regionId ? nearbyRegions.items : []} loading={regionBusy ? regionMode : null} error={regionError} onLoad={mode => void findRegions(mode)} onSelect={chooseRegion} onClose={closeRegionModal}/>}
        </div>}
        <div className="used-controls-search"><form onSubmit={submit} className="used-search"><input aria-label="중고 상품 검색" maxLength={100} value={query} onChange={e => setQuery(e.target.value)} placeholder="아이폰, 맥북, 카메라 등 상품명 검색"/><button disabled={busy} type="submit">{sources.joongna.loading || sources.karrot.loading ? '검색 중' : '검색'}</button></form><div className="used-suggestions">{['아이폰 16 Pro', '맥북 에어 M3', '닌텐도 스위치', '소니 헤드폰'].map(word => <button type="button" key={word} disabled={busy} onClick={() => setQuery(word)}>{word}</button>)}</div></div>
      </section>
      <form className="used-panel used-price-filter" onSubmit={applyPrice} aria-label="매물 가격 범위 필터">
        <span className="used-price-label">가격 범위</span>
        <label><span className="sr-only">최저가</span><input inputMode="numeric" maxLength={12} placeholder="최저가" value={minPrice} onChange={event => setMinPrice(event.target.value)} aria-invalid={Boolean(priceError)}/><span>원</span></label>
        <span aria-hidden="true">~</span>
        <label><span className="sr-only">최고가</span><input inputMode="numeric" maxLength={12} placeholder="최고가" value={maxPrice} onChange={event => setMaxPrice(event.target.value)} aria-invalid={Boolean(priceError)}/><span>원</span></label>
        <button type="submit" className="used-primary">가격 적용</button><button type="button" className="used-secondary" onClick={resetPrice}>초기화</button>
        <p className="used-help">{priceRange.min !== null || priceRange.max !== null ? `적용 범위: ${priceRange.min === null ? '하한 없음' : `${priceRange.min.toLocaleString('ko-KR')}원`} ~ ${priceRange.max === null ? '상한 없음' : `${priceRange.max.toLocaleString('ko-KR')}원`}` : '조회된 매물에서 가격을 필터링합니다. 비워 둔 금액은 제한하지 않습니다.'}</p>
        {priceError && <p role="alert" className="used-price-error">{priceError}</p>}
      </form>
      {error && <p role="alert" className="used-error">{error}</p>}
      <div className="used-content"><div className="used-results"><div className="used-results-heading"><div><h2>{searched ? `‘${searched}’ 검색 결과` : '통합 검색 결과'}<span>{items.length}</span></h2><p>조회된 매물 안에서 비교합니다. 나눔·가격 미확인은 뒤에 표시됩니다.</p></div><div className="used-result-tools"><select aria-label="조회한 매물 정렬" value={sort} onChange={e => { setSort(e.target.value); setPage(1) }}><option value="low">가격 낮은순</option><option value="high">가격 높은순</option><option value="latest">등록·끌올순</option></select><button className="used-refresh" type="button" disabled={busy || !applied.current} title="매물 갱신" aria-label="매물 갱신" onClick={() => search(true)}><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1"/></svg></button></div></div>
        <div className="used-result-tabs" role="group" aria-label="표시할 플랫폼">{(['all', ...platforms] as const).map(provider => <button type="button" key={provider} aria-pressed={view === provider} onClick={() => { setView(provider); setPage(1) }}>{provider === 'all' ? '전체' : providerName(provider)}<span>{provider === 'all' ? platforms.reduce((sum, p) => sum + priceFilteredRows[p].length, 0) : priceFilteredRows[provider].length}</span></button>)}</div>
        <div className="used-source-status">{platforms.map(provider => { const source = sources[provider]; return source.loading || source.result || source.error ? <div key={provider}><strong>{providerName(provider)}</strong>{provider === 'karrot' && applied.current?.regionName && <span className="used-applied-region">검색 기준: {source.result?.regionName || applied.current.regionName}</span>}{source.loading ? <span role="status">매물을 조회하고 있습니다…</span> : source.error ? <><span className="used-source-error" role="alert">{source.error}</span><button disabled={busy} type="button" onClick={() => retry(provider)}>재시도</button></> : <span>{source.result?.items?.length ?? 0}개 · {new Date(source.result!.fetchedAt ?? source.result!.receivedAt).toLocaleString('ko-KR')} 조회</span>}{provider === 'karrot' && source.result && <p>선택한 지역을 기준으로 조회한 결과입니다. 매물에 표시된 지역은 판매자의 등록 지역으로, 검색 기준 지역과 다를 수 있습니다.</p>}{source.result?.matchScope === 'region_feed' && <p>당근 검색 인덱스 대신 동네 피드의 제목·본문에서 검색한 결과입니다.</p>}{source.result?.truncated && <p>응답 크기에 따라 최대 400개 매물만 표시합니다.</p>}</div> : null })}</div>
        {items.length ? <><ul className="used-listings">{items.slice((currentPage - 1) * 12, currentPage * 12).map(item => <li key={identity(item)} className="used-listing"><button type="button" className="used-listing-image" onClick={() => open(item)} aria-label={`${item.title} 상세 보기`}><Photo item={item}/><span className={`used-platform used-platform-${item.provider}`}>{providerName(item.provider)}</span></button><div className="used-listing-info"><div className="used-listing-top"><span className={`used-state ${item.status === 'sold' || item.status === 'reserved' ? 'is-unavailable' : ''}`}>{status(item)}</span><WishlistButton provider={item.provider} productId={item.productId} compact onLogin={onLogin}/></div><button type="button" className="used-listing-title" onClick={() => open(item)}>{item.title}</button><p className="used-listing-location">{item.regionName || '지역 정보 미제공'}{timestamp(item) ? ` · ${new Date(timestamp(item)).toLocaleDateString('ko-KR')}` : ''}</p><strong className="used-listing-price">{money(item.price)}</strong><button type="button" className="used-listing-link" onClick={() => open(item)}>매물 확인<Icon name="arrow" size={12}/></button></div></li>)}</ul>{pages > 1 && <nav className="used-pagination" aria-label="조회한 매물 페이지"><button type="button" disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)}>이전</button><span>{currentPage} / {pages}</span><button type="button" disabled={currentPage === pages} onClick={() => setPage(currentPage + 1)}>다음</button></nav>}</> : !sources.joongna.loading && !sources.karrot.loading && <div className="used-empty"><h3>{searched ? '검색 결과가 없습니다' : '찾는 중고 상품을 검색해 보세요'}</h3><p>{searched ? '검색어나 플랫폼·동네 조건을 바꿔 보세요.' : '플랫폼을 선택하고 검색을 눌러 주세요.'}</p></div>}
        {sources.joongna.result?.hasMore && <button type="button" className="used-load-more" disabled={busy} onClick={() => retry('joongna', 2)}>중고나라 추가 매물 불러오기</button>}
      </div><aside className="used-sidebar used-search-agent-sidebar" aria-label="검색 결과 AI 구매 상담"><UsedAgentChat listing title={searched ? `‘${searched}’ 검색 결과` : '중고 검색 결과'} context={searchAgentContext} evidenceKey={searchAgentEvidenceKey} onLogin={onLogin} disabled={sources.joongna.loading || sources.karrot.loading}/></aside></div>
    </>}
  </section>
}

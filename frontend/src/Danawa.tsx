import { useEffect, useRef, useState, type FormEvent } from 'react'
import { request, type Meta, type Single } from './api'
import { collectedRequest, CollectionPending } from './collectedRequest'
import { useSession } from './session'
import { Icon, type IconName } from './Icons'
import { cachedSearch, latestCachedSearch, saveSearch, rememberSearch } from './searchCache'
import { productImages, rememberProductImage, watchProductImages } from './productImageCache'
import DetailInsights from './DetailInsights'
import { WishlistButton } from './LiveWishlist'

export type DanawaSearch = { query: string; revision: number; categoryId?: string; browseCategory?: boolean }

type Variant = { productId: string; label: string; listedPrice: number | null }
type Item = { productId: string; title: string; image?: string | null; displayUpdatedAt?: string | null; displayReceivedAt?: string | null; listedPrice: number | null; maker: string | null; category: string | null; variants: Variant[] }
type Product = { productId: string; title: string; image?: string | null; images?: string[]; maker: string | null; brand: string | null; intro: string | null; status: string | null; url: string; lowestPrice: number | null; lowestPriceWithShipping: number | null; cashLowestPrice: number | null; priceMismatch: boolean; offerCount: number | null; rating: number | null; reviewCount: number | null; variantProductIds: string[] }
type Offer = { rowId: string; mall: string; title: string | null; group: string | null; payment: 'card' | 'cash' | 'UNKNOWN'; price: number | null; shippingCost: number | null; shippingText: string | null; quotedTotal: number | null; reportedTotal: number | null; totalMismatch: boolean; availability: 'UNKNOWN'; couponText: string | null; interestFreeText: string | null; url: string | null }
type Result = { source: 'reef-danawa'; synthetic: false; fetchedAt: string | null; receivedAt: string; localCacheHit: boolean; browserCacheSavedAt?: number; savedCatalogue?: boolean; displayMeta?: Meta; hasMore: boolean; page: number; items: Item[] | null; product: Product | null; offers: Offer[] | null }
function validSearch(value: unknown): value is Result {
  if (!value || typeof value !== 'object') return false
  const data = value as Result
  return data.source === 'reef-danawa' && data.synthetic === false && typeof data.receivedAt === 'string' && Number.isFinite(Date.parse(data.receivedAt))
    && (data.fetchedAt === null || (typeof data.fetchedAt === 'string' && Number.isFinite(Date.parse(data.fetchedAt))))
    && typeof data.hasMore === 'boolean' && Array.isArray(data.items) && data.items.length <= 100
    && data.items.every(item => item && typeof item.productId === 'string' && /^[0-9]{1,30}$/.test(item.productId) && typeof item.title === 'string'
      && (item.listedPrice === null || (Number.isSafeInteger(item.listedPrice) && item.listedPrice > 0)) && Array.isArray(item.variants)
      && item.variants.every(variant => variant && typeof variant.productId === 'string' && /^[0-9]{1,30}$/.test(variant.productId)
        && (variant.listedPrice === null || (Number.isSafeInteger(variant.listedPrice) && variant.listedPrice > 0))))
}
const card = 'rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]'
const money = (value: number | null) => value === null ? '미확인' : `${value.toLocaleString('ko-KR')}원`
function lowestListing(item: Item) {
  return [item, ...item.variants].reduce<{ productId: string; listedPrice: number | null }>((best, candidate) =>
    candidate.listedPrice !== null && (best.listedPrice === null || candidate.listedPrice < best.listedPrice) ? candidate : best,
    { productId: item.productId, listedPrice: null })
}
function ProductPhoto({ src, title, icon, large = false }: { src?: string | null; title: string; icon: IconName; large?: boolean }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [src])
  return <div className={`grid place-items-center overflow-hidden bg-[var(--surface-2)] text-[var(--primary)] ${large ? 'min-h-64 rounded-2xl' : 'h-44'}`}>
    {src && !failed ? <img src={src} alt={title} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} className={`w-full object-contain p-4 ${large ? 'h-72 sm:h-96' : 'h-44'}`}/> : <div className="grid justify-items-center gap-2 p-5"><Icon name={icon} size={large ? 72 : 54}/><span className="text-[11px] text-[var(--muted)]">{failed ? '이미지를 불러오지 못했습니다' : '상품 이미지 미제공'}</span></div>}
  </div>
}
type History = { exists: boolean; productId: string; points: { date: string; quotedCardMinimum: number | null; validQuoteSamples: number; observationCount: number }[]; analysis: { verdict: string; reasons: string[]; eligibleObservedDays: number; quotedObservedDays: number; averageQuotedPrice: number | null } }
type Receipt = { duplicate: boolean; observedAt: string | null; day: string | null; observationId: string; validQuoteSamples: number }
function HistoryChart({ history }: { history: History }) {
  const points = history.points.filter(point => point.quotedCardMinimum !== null)
  const values = points.map(point => point.quotedCardMinimum as number)
  const low = Math.min(...values), high = Math.max(...values)
  const start = points.length ? Date.parse(points[0].date) : 0
  const end = points.length ? Date.parse(points[points.length - 1].date) : 0
  const coords = points.map(point => `${20 + (Date.parse(point.date) - start) * 560 / Math.max(86400000, end - start)},${150 - ((point.quotedCardMinimum as number) - low) * 120 / Math.max(1, high - low)}`).join(' ')
  return <article className={card}><h2 className="font-black">저장된 일별 카드 표시가 이력</h2><p className="mt-2 text-xs leading-5 text-[var(--muted)]">한국 날짜 기준 · 조회한 페이지에서 배송비가 확인된 카드 표시가의 일 최저값입니다. 재고·판매처 옵션은 미확인입니다. 최근 90개 관측 날짜만 조회하며 결측 날짜는 채우지 않습니다.</p>{points.length ? <><svg viewBox="0 0 600 180" role="img" aria-label="저장된 다나와 일별 카드 표시가" className="mt-4 w-full"><polyline points={coords} fill="none" stroke="var(--mint)" strokeWidth="3"/>{points.length === 1 && <circle cx="20" cy="150" r="4" fill="var(--mint)"/>}</svg><p className="text-xs text-[var(--muted)]">{points[0].date} ~ {points[points.length - 1].date} · 표시가 {points.length}일</p></> : <p className="mt-4 text-sm">수집 시각과 카드·배송비 조건을 갖춘 저장 표본이 없습니다.</p>}<details className="mt-4"><summary className="cursor-pointer text-sm">날짜별 표본 확인</summary><ul className="mt-2 space-y-2 text-xs">{history.points.map(point => <li key={point.date}>{point.date} · {money(point.quotedCardMinimum)} · 유효 표시가 표본 {point.validQuoteSamples}개 · 저장 관측 {point.observationCount}개</li>)}</ul></details><div className="mt-5 rounded-2xl bg-[var(--surface-2)] p-4"><h3 className="font-bold">구매 판단 보류</h3>{history.analysis.reasons.map(reason => <p key={reason} className="mt-2 text-sm">{reason}</p>)}<p className="mt-2 text-xs text-[var(--muted)]">최근 30일 표시가 관측 {history.analysis.quotedObservedDays}일 · 판단에 사용할 검증 관측 {history.analysis.eligibleObservedDays}일 · 이력 조회는 Gemini를 호출하지 않습니다</p></div></article>
}
export default function DanawaPage({ onLogin, embedded = false, homePreview = false, searchRequest = null, categoryIcon = 'search', onBusyChange, onProductChange }: { onLogin: () => void; embedded?: boolean; homePreview?: boolean; searchRequest?: DanawaSearch | null; categoryIcon?: IconName; onBusyChange?: (busy: boolean) => void; onProductChange?: (selected: boolean) => void }) {
  const { user } = useSession()
  const [query, setQuery] = useState('')
  const [searched, setSearched] = useState('')
  const [result, setResult] = useState<Result | null>(null)
  const [listPage, setListPage] = useState(1)
  const listTop = useRef<HTMLElement | null>(null)
  useEffect(() => setListPage(1), [result])
  const [detail, setDetail] = useState<Result | null>(null)
  const [offers, setOffers] = useState<Result | null>(null)
  const [selected, setSelected] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState<History | null>(null)
  const [saveNotice, setSaveNotice] = useState('')
  const [explanation, setExplanation] = useState<{ reasons: string[]; risks: string[]; model: string } | null>(null)
  const [images, setImages] = useState(productImages)
  const [collectionNotice, setCollectionNotice] = useState('')
  const [collectionPending, setCollectionPending] = useState(false)
  const active = useRef<AbortController | null>(null)
  const privateRequest = useRef(false)
  const locked = useRef(false)
  const retryRequest = useRef<{ action: 'search' | 'product' | 'offers'; body: unknown } | null>(null)
  useEffect(() => () => active.current?.abort(), [])
  useEffect(() => watchProductImages(() => setImages(productImages())), [])
  useEffect(() => { onBusyChange?.(busy) }, [busy, onBusyChange])
  useEffect(() => { onProductChange?.(Boolean(selected)) }, [selected, onProductChange])
  useEffect(() => {
    if (privateRequest.current) { active.current?.abort(); locked.current = false; setBusy(false) }
    setSaved(null); setSaveNotice(''); setExplanation(null)
  }, [user?.uid])
  useEffect(() => {
    if (!searchRequest) return
    setQuery(searchRequest.query); setSearched(searchRequest.query)
    const timer = setTimeout(() => void load('search', { query: searchRequest.query, page: 1, categoryId: searchRequest.categoryId ?? '' }, false, searchRequest.browseCategory), 350)
    return () => clearTimeout(timer)
  }, [searchRequest])
  useEffect(() => {
    function restore() {
      active.current?.abort(); locked.current = false; setBusy(false); setError('')
      const productId = new URLSearchParams(location.search).get('product')
      if (productId && /^[0-9]{1,30}$/.test(productId)) select(productId, false)
      else returnToList(false)
    }
    if (!searchRequest) {
      restore()
      if (homePreview && !new URLSearchParams(location.search).has('product')) {
        const cached = latestCachedSearch(validSearch)
        if (cached) {
          setSearched(cached.query); setQuery(cached.query)
          setResult({ ...cached.value, browserCacheSavedAt: cached.savedAt })
        }
        const abort = new AbortController(); active.current?.abort(); active.current = abort
        void request<Single<Result>>('/danawa/catalog', abort.signal).then(response => {
          if (!abort.signal.aborted && response.data.items?.length) {
            setResult({ ...response.data, displayMeta: response.meta }); setSearched('저장된 상품')
          }
        }).catch(error => {
          if (!abort.signal.aborted && !cached) setCollectionNotice(error instanceof Error ? error.message : '저장된 상품을 읽지 못했습니다.')
        })
      }
    }
    window.addEventListener('popstate', restore)
    return () => window.removeEventListener('popstate', restore)
  }, [])
  async function load(action: 'search' | 'product' | 'offers', body: unknown, refresh = false, browseCategory = false) {
    if (locked.current) return
    retryRequest.current = { action, body }
    privateRequest.current = false
    locked.current = true
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    setBusy(true); setError(''); setExplanation(null); setImages(productImages()); setCollectionNotice(''); setCollectionPending(false)
    if (action === 'search') {
      const url = new URL(location.href); url.searchParams.delete('product'); history.replaceState(null, '', url)
      setResult(null); setDetail(null); setOffers(null); setSelected(''); setSaved(null); setSaveNotice('')
    }
    else if (action === 'product') { setDetail(null); setOffers(null); setSaved(null); setSaveNotice('') }
    else setOffers(null)
    try {
      if (action === 'search') {
        const input = body as { query: string; page: number; categoryId?: string }
        const cached = refresh ? null : cachedSearch(input.query, input.page, validSearch, input.categoryId)
        if (cached && cached.value.page === input.page) {
          setResult({ ...cached.value, browserCacheSavedAt: cached.savedAt }); rememberSearch(input.query); return
        }
        if (browseCategory && input.categoryId) {
          const stored = await request<Single<Result>>(`/danawa/catalog?categoryId=${encodeURIComponent(input.categoryId)}`, abort.signal)
          if (abort.signal.aborted) return
          if (stored.data.items?.length) { setResult({ ...stored.data, displayMeta: stored.meta }); return }
        }
      }
      const response = await collectedRequest<Result>(`/danawa/${action}`, abort.signal, body, message => { setCollectionNotice(message); setCollectionPending(true) })
      if (abort.signal.aborted) return
      setCollectionPending(false)
      setCollectionNotice(response.meta.collectionState === 'COLLECTING' ? '저장된 이전 결과를 표시하고 있습니다. 새 데이터를 수집 중입니다.' : '')
      const displayed = { ...response.data, displayMeta: response.meta }
      if (action === 'search') {
        setResult(displayed)
        const input = body as { query: string; page: number; categoryId?: string }
        if (validSearch(response.data) && response.meta.collectionState !== 'COLLECTING'
          && (!response.meta.cacheExpiresAt || Date.parse(response.meta.cacheExpiresAt) > Date.now())) saveSearch(input.query, input.page, displayed, input.categoryId)
        rememberSearch(input.query)
      }
      else if (action === 'product') {
        setDetail(displayed)
        if (response.data.product) rememberProductImage(response.data.product.productId, response.data.product.image ?? response.data.product.images?.[0])
      }
      else setOffers(displayed)
    } catch (error) {
      if (!abort.signal.aborted) {
        if (error instanceof CollectionPending) { setCollectionPending(true); setCollectionNotice(error.message) }
        else { setCollectionPending(false); setError(error instanceof Error ? error.message : '다나와 조회 실패') }
      }
    }
    finally { if (active.current === abort) { locked.current = false; if (!abort.signal.aborted) setBusy(false) } }
  }
  function select(productId: string, push = true) {
    if (locked.current) return
    if (push) { const url = new URL(location.href); url.searchParams.set('product', productId); history.pushState(null, '', url) }
    setSelected(productId); void load('product', { productId })
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  function returnToList(push = true) {
    if (push) { const url = new URL(location.href); url.searchParams.delete('product'); history.pushState(null, '', url) }
    setSelected(''); setDetail(null); setOffers(null); setSaved(null); setSaveNotice(''); setExplanation(null); setError(''); setImages(productImages())
  }
  function search(event: FormEvent) {
    event.preventDefault(); if (locked.current) return
    setSearched(query.trim()); void load('search', { query: query.trim(), page: 1 })
  }
  async function stored(capture: boolean) {
    if (!user) { onLogin(); return }
    if (locked.current || !selected) return
    privateRequest.current = true
    locked.current = true; setBusy(true); setError(''); setSaveNotice(''); setExplanation(null)
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    try {
      if (capture) {
        const prepared = await collectedRequest<Result>('/danawa/offers', abort.signal, { productId: selected, page: offers?.page ?? 1 })
        if (abort.signal.aborted) return
        if (prepared.meta.collectionState === 'COLLECTING') throw new Error('판매처 자료를 갱신 중입니다. 완료 후 다시 저장해 주세요.')
        const receipt = await request<Single<Receipt>>('/me/danawa/observations', abort.signal, 'POST', { productId: selected, page: offers?.page ?? 1 })
        if (abort.signal.aborted) return
        setSaveNotice(`${receipt.data.duplicate ? '이미 보관한 관측입니다.' : '관측을 저장했습니다.'} ${receipt.data.day ? `${receipt.data.day} 일 집계 반영` : '수집 시각 미제공으로 일 집계 제외'}`)
      }
      const history = await request<Single<History>>(`/me/danawa/history/${encodeURIComponent(selected)}`, abort.signal)
      if (!abort.signal.aborted) setSaved(history.data)
    } catch (error) { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : '관측 저장·이력 조회 실패') }
    finally { if (active.current === abort) { locked.current = false; if (!abort.signal.aborted) setBusy(false) } }
  }
  async function explain() {
    if (!user) { onLogin(); return }
    if (locked.current || !selected) return
    privateRequest.current = true
    locked.current = true; setBusy(true); setError(''); setExplanation(null)
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    try {
      const result = await collectedRequest<{ reasons: string[]; risks: string[]; model: string }>(`/me/danawa/explanation/${selected}`, abort.signal, undefined)
      if (!abort.signal.aborted) setExplanation(result.data)
    } catch (error) { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : 'Gemini 연결 실패') }
    finally { if (active.current === abort) { locked.current = false; if (!abort.signal.aborted) setBusy(false) } }
  }
  const pageCount = Math.max(1, Math.ceil((result?.items?.length ?? 0) / 12))
  const currentPage = Math.min(listPage, pageCount)
  const visibleItems = result?.items?.slice((currentPage - 1) * 12, currentPage * 12) ?? []
  function changeListPage(page: number) {
    setListPage(page)
    listTop.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
  const product = detail?.product
  return <section className="space-y-5">
    <div className={embedded ? 'mb-4' : card}>
      {selected ? <div><button disabled={busy} onClick={() => returnToList()} className="mb-4 flex items-center gap-2 text-xs font-bold text-[var(--muted)] disabled:opacity-50"><span className="rotate-180"><Icon name="arrow" size={14}/></span>상품 목록으로</button><p className="text-sm font-extrabold text-[var(--mint)]">AI PRICE REPORT</p><h1 className="mt-2 text-2xl font-black tracking-tight sm:text-3xl">선택한 상품, 더 깊게 비교했어요</h1><p className="mt-2 text-sm text-[var(--muted)]">가격과 판매 조건, 저장한 관측을 한 화면에서 확인하세요.</p>{error && <button disabled={busy} onClick={() => void load('product', { productId: selected })} className="mt-3 text-sm underline">상품 상세 다시 불러오기</button>}</div> : embedded ? <div className="flex flex-wrap items-end justify-between gap-3"><div><p className="mb-1 flex items-center gap-2 text-sm font-extrabold text-[var(--mint)]"><Icon name="sparkles" size={16}/>{homePreview ? 'PRICE PICK' : 'PRICE COMPARISON'}</p><h2 className="text-xl font-black tracking-tight sm:text-2xl">{homePreview ? '최근 비교한 상품' : searched ? searched + ' 검색 결과' : '관심 있는 상품을 찾아보세요'}</h2><p className="mt-2 text-xs leading-5 text-[var(--muted)]">{searched ? '판매처별 표시 가격과 옵션을 비교해 보세요.' : '상단 검색창이나 카테고리를 선택하면 실제 상품을 조회합니다.'}</p></div></div> : <>
      <h1 className="text-xl font-black">다나와 상품·판매처 조회</h1>
      <p className="mt-3 text-sm leading-6 text-[var(--muted)]">Reef를 통한 실제 조회입니다. 버튼을 누르면 크레딧이 사용될 수 있습니다. 실제 관측은 별도 저장할 수 있습니다. 더미 상품과 분리하며 재고·옵션 미확인 상태에서는 구매 판단과 목표가 알림을 보류합니다.</p>
      <form onSubmit={search} className="mt-4 flex flex-wrap gap-3"><input aria-label="다나와 상품 검색어" required maxLength={100} value={query} onChange={event => setQuery(event.target.value)} className="min-w-0 flex-1 rounded-xl border border-[var(--line)] bg-[var(--surface-2)] p-3" placeholder="예: SSD 2TB"/><button disabled={busy || !query.trim()} className="rounded-xl bg-[var(--primary)] px-5 py-3 font-bold text-white disabled:opacity-50">검색</button></form>
      </>}
      {busy && <p role="status" className="mt-3 text-sm">조회 중…</p>}{collectionNotice && <p role="status" className="mt-3 rounded-xl bg-[var(--mint-soft)] p-3 text-xs leading-6 text-[var(--mint)]">{collectionNotice}</p>}{collectionPending && !busy && <button onClick={() => { const previous = retryRequest.current; if (previous) void load(previous.action, previous.body) }} className="mt-3 text-xs font-bold text-[var(--primary)]">수집 상태 다시 확인</button>}{error && <p role="alert" className="mt-3 text-sm text-rose-500">{error}</p>}
    </div>
    <div className={selected ? 'grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px]' : ''}><div className="min-w-0 space-y-5">
    {embedded && !selected && !result && !busy && !error && !collectionPending && <div className={`${card} grid min-h-48 place-items-center text-center`}><div><span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-[var(--mint-soft)] text-[var(--mint)]"><Icon name="search" size={26}/></span><p className="mt-4 font-bold">어떤 상품을 비교해 볼까요?</p><p className="mt-2 text-sm text-[var(--muted)]">검색 전에는 상품과 가격을 임의로 표시하지 않습니다.</p></div></div>}
    {result && !selected && <article ref={listTop} className="min-w-0 scroll-mt-44">
      <div className="flex flex-wrap items-center justify-between gap-2"><p className="text-xs font-bold text-[var(--muted)]">{homePreview ? `${searched} · 최근 검색 결과` : '상품 비교 결과'}</p><span className="text-[11px] text-[var(--subtle)]">총 {result.items?.length ?? 0}개 · {currentPage} / {pageCount}페이지</span></div>
      <div className="mt-3 flex items-center justify-end"><button type="button" disabled={busy} aria-label="결과 새로고침" title="결과 새로고침" onClick={() => void load('search', { query: searched, page: result.page, categoryId: searchRequest?.categoryId ?? '' }, true)} className="grid h-9 w-9 place-items-center rounded-full text-[var(--primary)] transition hover:bg-[var(--surface-2)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--primary)] disabled:opacity-50"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M4 17v-5h5"/><path d="M6.1 7a7 7 0 0 1 11.6-1L20 9M4 15l2.3 3A7 7 0 0 0 17.9 17"/></svg></button></div>
      <p className="mt-2 text-xs text-[var(--muted)]">카드에는 검색 응답의 옵션을 포함한 최저 표시가 하나만 표시합니다. 배송비는 별도이며, 상세에서 해당 가격의 옵션과 결제 조건을 확인하세요.</p>
      {!result.items?.length && <p className="mt-4">카탈로그 상품이 없습니다.</p>}
      <ul className="mt-4 grid gap-4 sm:grid-cols-3">{visibleItems.map(item => {
        const lowest = lowestListing(item)
        const observed = item.displayUpdatedAt === undefined ? result.fetchedAt : item.displayUpdatedAt
        return <li key={item.productId} className="group relative overflow-hidden rounded-2xl border border-[var(--line)] bg-[var(--surface)] transition duration-300 hover:-translate-y-1 hover:border-[var(--primary)] hover:shadow-[var(--shadow-lg)]"><div className="absolute right-3 top-3 z-10"><WishlistButton productId={lowest.productId} familyId={item.productId} onLogin={onLogin} compact/></div><button disabled={busy} onClick={() => select(lowest.productId)} className="w-full text-left disabled:opacity-50"><ProductPhoto src={item.image ?? images[item.productId]?.image ?? images[lowest.productId]?.image} title={item.title} icon={categoryIcon}/><div className="p-4"><p className="mb-1 truncate text-[11px] font-semibold text-[var(--subtle)]">{[item.maker, item.category].filter(Boolean).join(' · ') || '다나와 상품'}</p><h3 className="line-clamp-2 min-h-10 text-sm font-bold">{item.title}</h3><p className="mt-3 text-lg font-black tracking-tight">{money(lowest.listedPrice)}</p><p className="mt-1 text-[11px] text-[var(--muted)]">최저 표시가 · 배송 조건 별도 확인</p><p className="mt-2 text-[10px] text-[var(--subtle)]">{observed ? `관측 ${new Date(observed).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}` : '원본 관측 시각 미확인'}</p><span className="mt-3 flex items-center gap-1 text-xs font-bold text-[var(--primary)]">상품 상세 보기 <Icon name="arrow" size={13}/></span></div></button></li>
      })}</ul>
      {pageCount > 1 && <nav aria-label="상품 목록 페이지" className="mt-6 flex flex-wrap items-center justify-center gap-2">
        <button type="button" disabled={busy || currentPage === 1} onClick={() => changeListPage(currentPage - 1)} className="rounded-xl border border-[var(--line)] px-3 py-2 text-sm disabled:opacity-40">이전</button>
        {Array.from({ length: pageCount }, (_, index) => index + 1).map(page => <button key={page} type="button" disabled={busy} aria-label={`${page}페이지`} aria-current={page === currentPage ? 'page' : undefined} onClick={() => changeListPage(page)} className={`min-w-10 rounded-xl border px-3 py-2 text-sm font-bold disabled:opacity-40 ${page === currentPage ? 'border-[var(--primary)] bg-[var(--primary)] text-white' : 'border-[var(--line)] text-[var(--muted)] hover:bg-[var(--surface-2)]'}`}>{page}</button>)}
        <button type="button" disabled={busy || currentPage === pageCount} onClick={() => changeListPage(currentPage + 1)} className="rounded-xl border border-[var(--line)] px-3 py-2 text-sm disabled:opacity-40">다음</button>
      </nav>}
      {currentPage === pageCount && <div className="mt-4 flex justify-center gap-4">
        {!result.savedCatalogue && result.page > 1 && <button type="button" disabled={busy} onClick={() => void load('search', { query: searched, page: result.page - 1, categoryId: searchRequest?.categoryId ?? '' })} className="text-sm text-[var(--primary)] underline disabled:opacity-50">이전 검색 결과 불러오기</button>}
        {result.hasMore && result.page < 50 && <button type="button" disabled={busy} onClick={() => void load('search', { query: searched, page: result.page + 1, categoryId: searchRequest?.categoryId ?? '' })} className="text-sm text-[var(--primary)] underline disabled:opacity-50">추가 검색 결과 불러오기</button>}
      </div>}
    </article>}
    {product && detail && <article className="overflow-hidden rounded-3xl border border-[var(--line)] bg-[var(--surface)] shadow-[var(--shadow)]">
      <div className="grid md:grid-cols-[minmax(220px,300px)_minmax(0,1fr)]">
        <div className="min-w-0 bg-[var(--surface-2)]"><ProductPhoto key={product.productId} src={product.image ?? product.images?.[0]} title={product.title} icon={categoryIcon} large/></div>
        <div className="min-w-0 p-6 sm:p-8">
          <div className="mb-3 flex flex-wrap items-center gap-2"><span className="rounded-full bg-[var(--mint-soft)] px-3 py-1 text-[11px] font-extrabold text-[var(--mint)]">다나와 상품</span><span className="text-xs text-[var(--subtle)]">{product.brand || product.maker || '브랜드 미확인'}</span></div>
          <h2 className="text-xl font-black tracking-tight sm:text-2xl">{product.title}</h2>
          <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-[var(--muted)]">{product.rating !== null && <span className="flex items-center gap-1 font-black text-amber-500"><Icon name="star" size={14}/>{product.rating} / 5</span>}<span>리뷰 {product.reviewCount?.toLocaleString('ko-KR') ?? '미확인'}개</span><span>·</span><span>판매처 {product.offerCount ?? '미확인'}개</span></div>
          <div className="my-6 rounded-2xl bg-[var(--surface-2)] p-5"><p className="text-xs font-bold text-[var(--muted)]">카드 결제 가능 표시 최저가</p><p className="mt-1 text-3xl font-black tracking-[-0.04em]">{money(product.lowestPrice)}</p><div className="mt-4 grid gap-3 border-t border-[var(--line)] pt-4 sm:grid-cols-2"><div><p className="text-[10px] text-[var(--subtle)]">배송비 포함 표시가</p><p className="mt-1 text-sm font-bold">{money(product.lowestPriceWithShipping)}</p></div><div><p className="text-[10px] text-[var(--subtle)]">현금 전용 표시가</p><p className="mt-1 text-sm font-bold">{money(product.cashLowestPrice)}</p></div></div></div>
          <a href={product.url} target="_blank" rel="noopener noreferrer" className="flex items-center justify-center gap-2 rounded-2xl bg-[var(--primary)] px-5 py-3.5 text-sm font-bold text-white transition hover:brightness-110">다나와 원본에서 확인<Icon name="external" size={15}/></a>
          <div className="mt-3"><WishlistButton productId={product.productId} onLogin={onLogin}/></div>
          <p className="mt-3 text-[11px] leading-5 text-[var(--muted)]">배송·결제 조건이 다른 표시 가격입니다. 구매 전 옵션과 재고를 확인하세요.</p>
        </div>
      </div>
      {(product.intro || product.priceMismatch) && <div className="border-t border-[var(--line)] p-6 sm:p-8">{product.priceMismatch && <p role="status" className="mb-3 text-sm text-amber-600">원본의 가격 표시가 서로 일치하지 않습니다. 판매 조건을 확인하세요.</p>}{product.intro && <p className="text-sm leading-6 text-[var(--muted)]">{product.intro}</p>}</div>}
    </article>}

    {product && <DetailInsights key={product.productId} productId={product.productId}/>}
    {saved && <HistoryChart history={saved}/>}
    {offers && <article className={card}>
      <h2 className="font-black">판매처 가격 · {offers.page}페이지</h2>
      <p className="mt-3 text-xs leading-5 text-[var(--muted)]">배송비 포함 표시가는 상품가와 확인된 배송비의 합입니다. 재고·판매처 옵션까지 검증한 확정 최저가가 아닙니다. 페이지를 자동으로 순회하지 않으며 다음 페이지는 현재 표를 대체합니다.</p>
      {!offers.offers?.length && <p className="mt-4">판매처 응답이 비어 있습니다. 품절 여부를 확정하지 않습니다.</p>}
      <div className="mt-4 overflow-x-auto"><table className="w-full min-w-[650px] text-left text-sm"><caption className="sr-only">선택한 다나와 상품의 판매처별 표시 가격</caption><thead><tr className="border-b border-[var(--line)] text-xs text-[var(--muted)]"><th className="p-3">판매처·상품</th><th className="p-3">결제</th><th className="p-3">상품가</th><th className="p-3">배송비</th><th className="p-3">배송 포함 표시가</th></tr></thead><tbody>{offers.offers?.map(row => <tr key={row.rowId} className="border-b border-[var(--line)] align-top"><td className="max-w-xs p-3"><p className="font-bold">{row.mall}</p><p className="mt-1 text-xs text-[var(--muted)]">{row.title} · {row.group} · 재고 미확인</p>{row.couponText && <p className="mt-1 text-xs">쿠폰 안내: {row.couponText}</p>}{row.interestFreeText && <p className="mt-1 text-xs">{row.interestFreeText}</p>}{row.url && <a href={row.url} target="_blank" rel="noopener noreferrer" className="mt-2 inline-block text-xs text-[var(--primary)]">판매처 원본 확인</a>}</td><td className="p-3">{row.payment === 'cash' ? '현금 전용' : row.payment === 'card' ? '카드 결제 가능' : '미확인'}</td><td className="whitespace-nowrap p-3">{money(row.price)}</td><td className="p-3"><p className="whitespace-nowrap">{money(row.shippingCost)}</p><p className="mt-1 text-xs text-[var(--muted)]">{row.shippingText}</p></td><td className="p-3"><p className="whitespace-nowrap font-bold">{money(row.quotedTotal)}</p>{row.totalMismatch && <p className="mt-1 text-xs text-amber-600">원본 합계와 불일치 · 계산 제외</p>}</td></tr>)}</tbody></table></div>
      {offers.hasMore && offers.page < 20 && <button disabled={busy} onClick={() => void load('offers', { productId: selected, page: offers.page + 1 })} className="mt-4 underline disabled:opacity-50">다음 판매처 페이지 조회</button>}
    </article>}
    </div>
    {selected && product && <aside className="min-w-0 space-y-5 self-start xl:sticky xl:top-44">
      <article className="overflow-hidden rounded-3xl border border-[var(--line)] bg-[var(--surface)] shadow-[var(--shadow)]">
        <div className="ai-header p-6 text-white"><p className="mb-3 flex items-center gap-2 text-xs font-black text-emerald-200"><Icon name="sparkles" size={16}/>PRIZM AI REPORT</p><h2 className="text-xl font-black">구매 판단 보류</h2><p className="mt-3 text-xs leading-6 text-white/70">판매처 재고와 옵션 일치가 확인되지 않아 지금은 구매 시점을 확정하지 않습니다.</p></div>
        <div className="space-y-4 p-6"><div className="flex items-start gap-3"><span className="mt-0.5 text-[var(--mint)]"><Icon name="check" size={17}/></span><div><p className="text-xs font-bold">가격과 판매 조건을 함께 확인</p><p className="mt-1 text-[11px] leading-5 text-[var(--muted)]">카드·현금 가격과 배송비를 구분해서 비교하세요.</p></div></div><div className="flex items-start gap-3"><span className="mt-0.5 text-[var(--primary)]"><Icon name="clock" size={17}/></span><div><p className="text-xs font-bold">직접 저장한 관측으로 이력 확인</p><p className="mt-1 text-[11px] leading-5 text-[var(--muted)]">관측을 저장하고 일별 표시가의 흐름을 살펴보세요.</p></div></div></div>
      </article>
    {selected && product && <div className={card}><h2 className="font-black">관측 저장 · 가격 이력</h2><p className="mt-2 text-sm leading-6 text-[var(--muted)]">판매처의 재고는 응답에서 확인되지 않아 미확인으로 표시합니다. 쿠폰은 자동 차감하지 않고 현금 전용 가격도 구분합니다.</p><button disabled={busy} onClick={() => void load('offers', { productId: selected, page: 1 })} className="mt-3 font-bold text-[var(--primary)] disabled:opacity-50">판매처 가격 조회</button><div className="mt-4 flex flex-wrap gap-4"><button disabled={busy} onClick={() => void stored(true)} className="text-sm font-bold text-[var(--primary)] disabled:opacity-50">현재 페이지 조회·저장</button><button disabled={busy} onClick={() => void stored(false)} className="text-sm underline disabled:opacity-50">저장 이력 조회</button></div>{saveNotice && <p role="status" className="mt-3 text-sm">{saveNotice}</p>}<p className="mt-2 text-xs text-[var(--muted)]">저장은 공유 판매처 자료를 먼저 확인하고 원본 관측 시각을 유지합니다. 저장 후 이력 조회가 실패해도 관측은 저장되었을 수 있습니다.</p></div>}
      {saved?.exists && <article className={card}><div className="mb-4 flex items-center gap-2 font-black"><Icon name="sparkles" size={18}/>{explanation?.model === 'server-rule-only' ? '서버 기본 안내' : 'Gemini 관측 설명'}</div><button disabled={busy} onClick={() => void explain()} className="w-full rounded-xl bg-[var(--primary)] px-4 py-3 text-sm font-bold text-white disabled:opacity-50">관측 한계 설명하기</button><p className="mt-3 text-[11px] leading-5 text-[var(--muted)]">저장된 관측 요약을 Gemini에 전송합니다. 요청 시 API 요금이 발생할 수 있으며 서버의 판단 보류는 유지됩니다.</p>{explanation && <div className="mt-4 space-y-3 border-t border-[var(--line)] pt-4 text-sm leading-6">{explanation.reasons.map((text, i) => <p key={`reason:${i}`}>{text}</p>)}{explanation.risks.map((text, i) => <p key={`risk:${i}`} className="text-xs text-[var(--muted)]">{text}</p>)}<p className="text-[10px] text-[var(--subtle)]">{explanation.model}</p></div>}</article>}
    </aside>}
    </div>
  </section>
}

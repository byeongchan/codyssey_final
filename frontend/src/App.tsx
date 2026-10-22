import { useEffect, useState, type FormEvent } from 'react'
import { request, type Analysis, type AnalysisJob, type Category, type Listing, type Offer, type Prices, type Product, type Single } from './api'
import { Icon, PrizmMark } from './Icons'
import { mockupCategories, mockupImages } from './presentation'
import { AccountPanel, WishlistButton, WishlistPage } from './Account'
import { useSession } from './session'
import { PriceAlertForm, NotificationsPage } from './Alerts'
import { HistoryPage, HistoryRecorder } from './History'
import Chat from './Chat'
import { Reviews, Deals } from './Discovery'
import Collection from './Collection'
import CatalogSource from './CatalogSource'
import OperationsPage from './Operations'
import DanawaPage from './Danawa'

const money = (value: number | null) => value === null ? '확정가 없음' : `${new Intl.NumberFormat('ko-KR').format(value)}원`
const verdicts = { BUY: '지금 구매를 고려해 보세요', WAIT: '가격 흐름을 더 지켜보세요', NEUTRAL: '평균 수준의 가격이에요', INSUFFICIENT_DATA: '판단할 데이터가 부족해요' }
const panel = 'rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]'
const dateTime = (value: string) => new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })

function RefreshNotice({ text }: { text: string }) {
  return <p role="status" className="mt-3 flex items-start gap-2 rounded-xl border border-amber-400/40 bg-amber-400/10 px-3 py-2 text-xs leading-5 text-[var(--text)]"><span className="mt-0.5 shrink-0 text-amber-600"><Icon name="clock" size={14}/></span>{text}</p>
}

function ProductImage({ categoryId, title }: { categoryId: string; title: string }) {
  const [failed, setFailed] = useState(false)
  const category = mockupCategories.find(item => item.id === categoryId)
  const image = mockupImages[categoryId]
  if (!image || failed) return <div className="grid h-full min-h-44 place-items-center text-[var(--primary)]" aria-label={`${title} 이미지 준비 중`}><Icon name={category?.icon ?? 'laptop'} size={72}/></div>
  return <img src={image} alt={`${title} 목업 참고 이미지`} loading="lazy" onError={() => setFailed(true)} className="h-full w-full object-cover transition duration-500 group-hover:scale-105"/>
}

function Chart({ prices }: { prices: Prices }) {
  if (!prices.points.length) return <p>아직 가격 이력이 없습니다.</p>
  const values = prices.points.map(point => point.comparablePrice)
  const low = Math.min(...values), high = Math.max(...values)
  const coordinates = prices.points.map((point, index) => `${20 + index * 560 / Math.max(1, values.length - 1)},${150 - (point.comparablePrice - low) * 120 / Math.max(1, high - low)}`).join(' ')
  return <>
    <p className="mt-2 text-sm text-[var(--muted)]">합성 이력 {prices.observedDays}일 · {prices.coverageStart} ~ {prices.coverageEnd}</p>
    {prices.stale && <RefreshNotice text="갱신 필요 · 최신 가격 이력이 없습니다. 이전 관측 구간의 차트입니다."/>}
    <svg viewBox="0 0 600 180" role="img" aria-label={`${prices.observedDays}일 더미 가격 추이`} className="price-chart mt-5 w-full">
      <line x1="20" x2="580" y1="160" y2="160" stroke="var(--line)"/>
      <polyline points={coordinates} fill="none" stroke="var(--mint)" strokeWidth="4"/>
      {values.length === 1 && <circle cx="20" cy="150" r="5" fill="var(--mint)"/>}
    </svg>
    <div className="flex justify-between text-xs text-[var(--muted)]"><span>최저 {money(low)}</span><span>평균 {money(Math.round(prices.averagePrice))}</span><span>최고 {money(high)}</span></div>
  </>
}

function Detail({ id, onLogin, onNotifications }: { id: string; onLogin: () => void; onNotifications: () => void }) {
  const { user } = useSession()
  const [detail, setDetail] = useState<Product | null>(null)
  const [offers, setOffers] = useState<Offer[]>([])
  const [prices, setPrices] = useState<Prices | null>(null)
  const [analysis, setAnalysis] = useState<Analysis | null>(null)
  const [error, setError] = useState('')
  const [aiError, setAiError] = useState('')
  const [notice, setNotice] = useState('')
  const [retry, setRetry] = useState(0)
  const [aiRetry, setAiRetry] = useState(0)
  const [aiStatus, setAiStatus] = useState<AnalysisJob['status'] | 'REQUESTING'>('REQUESTING')
  const [cacheHit, setCacheHit] = useState(false)
  const [evidenceVersion, setEvidenceVersion] = useState('')
  const [analysisEvidenceVersion, setAnalysisEvidenceVersion] = useState('')
  useEffect(() => {
    const abort = new AbortController()
    setDetail(null); setPrices(null); setError('')
    Promise.all([
      request<Single<Product>>(`/products/${encodeURIComponent(id)}`, abort.signal),
      request<Listing<Offer>>(`/products/${encodeURIComponent(id)}/offers`, abort.signal),
      request<Single<Prices>>(`/products/${encodeURIComponent(id)}/prices?days=90`, abort.signal),
    ]).then(([product, stores, history]) => {
      if (abort.signal.aborted) return
      const versions = [product.meta.catalogVersion, stores.meta.catalogVersion, history.meta.catalogVersion].filter(Boolean)
      if (new Set(versions).size > 1) throw new Error('조회 중 가격 근거가 바뀌었습니다. 다시 조회해 주세요.')
      setEvidenceVersion(product.meta.catalogVersion || '')
      setDetail(product.data); setOffers(stores.items); setPrices(history.data)
    }).catch(e => { if (!abort.signal.aborted) setError(e instanceof Error ? e.message : '조회 실패') })
    return () => abort.abort()
  }, [id, retry])
  useEffect(() => {
    const abort = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    const deadline = Date.now() + 60000
    setAnalysis(null); setAiError(''); setAiStatus('REQUESTING'); setCacheHit(false)
    if (!user) return () => abort.abort()
    async function analyze(jobId?: string) {
      try {
        const result = jobId
          ? await request<Single<AnalysisJob>>(`/analysis-jobs/${encodeURIComponent(jobId)}`, abort.signal)
          : await request<Single<AnalysisJob>>(`/products/${encodeURIComponent(id)}/analysis-jobs`, abort.signal, 'POST')
        if (abort.signal.aborted) return
        const job = result.data
        setAiStatus(job.status)
        if (!jobId) setCacheHit(job.cacheHit)
        if (job.status === 'SUCCEEDED') {
          if (!job.analysis) throw new Error('분석 결과가 비어 있습니다. 다시 시도해 주세요.')
          setAnalysis(job.analysis)
          setAnalysisEvidenceVersion(result.meta.catalogVersion || '')
        } else if (job.status === 'FAILED' || job.status === 'EXPIRED') {
          throw new Error(job.error?.message || '분석에 실패했습니다. 다시 시도해 주세요.')
        } else {
          if (Date.now() >= deadline) throw new Error('분석 대기 시간이 길어지고 있습니다. 잠시 후 다시 확인해 주세요.')
          timer = setTimeout(() => { void analyze(job.jobId) }, Math.min(5, Math.max(1, job.pollAfterSeconds)) * 1000)
        }
      } catch (e) {
        if (!abort.signal.aborted) setAiError(e instanceof Error ? e.message : '분석 실패')
      }
    }
    void analyze()
    return () => { abort.abort(); if (timer !== undefined) clearTimeout(timer) }
  }, [id, aiRetry, user?.uid])
  if (error) return <div role="alert" className={panel}>{error}<button className="ml-4 underline" onClick={() => setRetry(x => x + 1)}>다시 시도</button></div>
  if (!detail || !prices) return <p role="status" className={panel}>상품 데이터를 불러오고 있습니다…</p>
  return <>
    <section className="mb-6 overflow-hidden rounded-3xl border border-[var(--line)] bg-[var(--surface)] shadow-[var(--shadow)]">
      <div className="grid md:grid-cols-[300px_minmax(0,1fr)]">
      <div className="relative min-h-72 bg-[var(--surface-2)]"><div className="absolute inset-0"><ProductImage categoryId={detail.categoryId} title={detail.title}/></div></div>
      <div className="p-6 sm:p-8">
      <p className="text-xs font-bold text-[var(--mint)]">상품 상세 · DEMO</p>
      <h1 className="mt-3 text-2xl font-black sm:text-3xl">{detail.title}</h1>
      <p className="mt-2 text-sm text-[var(--muted)]">{detail.description}</p>
      <p className="mt-6 text-3xl font-black text-[var(--primary)]">{money(detail.currentPrice)}</p>
      {detail.catalogSource === 'snapshot' && <p className="mt-2 text-xs text-[var(--muted)]">{detail.snapshotState === 'MISSING' ? '이 옵션의 저장된 가격 근거가 없습니다. 검색 화면에서 스냅샷을 저장해 주세요.' : '이 옵션의 검증된 저장 스냅샷 기준입니다.'}</p>}
      <p className="mt-2 text-xs text-[var(--muted)]">배송비 확인된 판매처의 확정 비교가 기준</p>
      <HistoryRecorder productId={id}/><WishlistButton productId={id} onLogin={onLogin}/>
      {detail.updatedAt && <p className="mt-2 text-xs text-[var(--subtle)]">최종 관측 {dateTime(detail.updatedAt)}</p>}
      {detail.priceStale && <RefreshNotice text={`갱신 필요 · 모든 확정 비교가가 24시간을 초과했습니다. 이전 최저 관측가 ${money(detail.lastKnownPrice)}는 현재가 비교에서 제외합니다.`}/>}
      </div></div>
    </section>
    <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px]">
      <div className="space-y-6">
        <article className={panel}><h2 className="font-black">가격 흐름</h2><Chart prices={prices}/></article>
        <article className={panel}>
          <h2 className="font-black">쇼핑몰별 가격</h2>
          <p className="mt-2 text-xs text-[var(--muted)]">조건부 혜택은 확정 비교가에 포함하지 않습니다.</p>
          <div className="mt-5 divide-y divide-[var(--line)]">{offers.map(offer => <div key={offer.id} className="flex flex-wrap items-center justify-between gap-4 py-4">
            <div className="flex min-w-0 items-center gap-4"><div className={`grid h-11 w-11 shrink-0 place-items-center rounded-xl text-xs font-black text-white ${offer.store.startsWith('쿠팡') ? 'bg-rose-500' : offer.store.startsWith('11번가') ? 'bg-red-500' : 'bg-emerald-500'}`}>{offer.store.slice(0, 2)}</div><div><p className="text-sm font-black">{offer.store}</p><p className="mt-1 text-xs text-[var(--muted)]">상품가 {money(offer.productPrice)} · 배송비 {offer.shippingFee === null ? '미확인' : money(offer.shippingFee)}</p>
              <p className="mt-1 text-xs text-[var(--muted)]">{offer.availability === 'OUT_OF_STOCK' ? '품절' : '재고 있음 (더미)'}{offer.conditionalBenefits.map(benefit => ` · ${benefit.label} ${money(benefit.amount)}`).join('')}</p></div>
            </div><div className="flex items-center gap-4"><div className="text-right"><p className="text-sm font-black">{money(offer.comparablePrice)}</p><p className="mt-1 text-[10px] text-[var(--muted)]">{dateTime(offer.observedAt)}</p>{offer.stale && <p className="mt-1 text-xs font-bold text-amber-600">갱신 필요 · 이전 관측가</p>}</div><button aria-label={`${offer.store} 구매 안내`} disabled={offer.availability === 'OUT_OF_STOCK' || offer.stale} onClick={() => setNotice('데모에서는 구매 이동을 제공하지 않습니다.')} className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-[var(--primary)] text-white transition hover:brightness-110 disabled:opacity-40"><Icon name="external" size={15}/></button></div>
          </div>)}</div>
          <p role="status" className="mt-3 text-sm text-[var(--muted)]">{notice}</p>
        </article>
        <PriceAlertForm productId={id} currentPrice={detail.currentPrice} onLogin={onLogin} onNotifications={onNotifications}/>
        <Reviews productId={id}/>
      </div>
      <aside className="xl:sticky xl:top-28 xl:self-start">
        <article className="overflow-hidden rounded-3xl border border-[var(--line)] bg-[var(--surface)] shadow-[var(--shadow-lg)]">
          <div className="ai-header flex items-center gap-3 px-6 py-5 text-white"><div className="grid h-10 w-10 place-items-center rounded-2xl bg-white/15 backdrop-blur"><Icon name="sparkles" size={20}/></div><div><p className="text-sm font-black">PRIZM AI 구매 비서</p><p className="mt-1 text-[10px] text-white/60">서버 규칙 판단 · 설명 출처는 결과에 표시</p></div><span className="ml-auto rounded-full border border-white/15 px-2 py-1 text-[9px] font-bold">BETA</span></div>
          <div className="p-6">
          {!user ? <div className="mt-5 text-sm"><p>로그인하면 구매 타이밍 분석을 요청할 수 있습니다.</p><button onClick={onLogin} className="mt-3 font-bold text-[var(--primary)]">로그인하고 분석하기</button></div> : analysis && evidenceVersion && analysisEvidenceVersion && evidenceVersion !== analysisEvidenceVersion ? <div role="alert" className="mt-5 text-sm"><p>분석 중 가격 근거가 바뀌었습니다. 같은 근거로 상품과 분석을 다시 불러와 주세요.</p><button onClick={() => { setRetry(value => value + 1); setAiRetry(value => value + 1) }} className="mt-2 underline">상품·분석 다시 불러오기</button></div> : aiError ? <div role="alert" className="mt-5"><p>{aiError}</p><button onClick={() => setAiRetry(x => x + 1)} className="mt-2 text-sm underline">분석 다시 시도</button></div> : !analysis ? <p role="status" aria-live="polite" className="mt-5">{aiStatus === 'QUEUED' ? '분석 순서를 기다리고 있습니다…' : aiStatus === 'RUNNING' ? '구매 타이밍을 분석하고 있습니다…' : '분석을 요청하고 있습니다…'}</p> : <>
            <p role="status" className="mt-3 text-xs text-[var(--muted)]">{cacheHit ? '기존 분석 결과를 불러왔습니다.' : '분석이 완료되었습니다.'}</p>
            {analysis.stale && <RefreshNotice text="갱신 필요 · 가격 또는 분석의 유효 기간이 지났습니다. 구매 판단을 보류합니다."/>}
            <h2 className={`mt-6 text-xl font-black ${analysis.stale ? 'text-[var(--muted)]' : 'text-[var(--mint)]'}`}>{verdicts[analysis.verdict]}</h2>
            <p className="mt-2 text-xs text-[var(--muted)]">서버 판단 규칙 {analysis.ruleVersion} · {analysis.explanationSource === 'GEMINI_EXPLANATION' ? `Gemini 설명 · ${analysis.model} · 가격 근거는 더미입니다.` : analysis.explanationSource === 'FIXTURE_EXPLANATION' ? '더미 설명' : analysis.explanationSource === 'SERVER_FALLBACK' ? '설명 서비스 응답을 사용하지 못해 서버 기본 설명을 표시합니다.' : '판단 보류 사유를 서버에서 안내합니다.'}</p>
            {analysis.reasons.map(reason => <p key={reason} className="mt-4 text-sm leading-7">{reason}</p>)}
            {analysis.risks.map(risk => <p key={risk} className="mt-4 text-xs leading-6 text-[var(--muted)]">{risk}</p>)}
            <p className="mt-4 text-xs text-[var(--subtle)]">기준 {new Date(analysis.generatedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</p>
          </>}
          <Chat key={id} productId={id} onLogin={onLogin}/>
          </div>
        </article>
      </aside>
    </div>
  </>
}

export default function App() {
  const { user } = useSession()
  const [accountOpen, setAccountOpen] = useState(false)
  const [dark, setDark] = useState(false)
  const [params, setParams] = useState(() => new URLSearchParams(location.search))
  const [query, setQuery] = useState(params.get('q') || '')
  const [categories, setCategories] = useState<Category[]>([])
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  const [notice, setNotice] = useState('')
  const id = params.get('product')
  const wishlistView = params.get('view') === 'wishlist' && !id
  const notificationsView = params.get('view') === 'notifications' && !id
  const historyView = params.get('view') === 'history' && !id
  const dealsView = params.get('view') === 'deals' && !id
  const operationsView = params.get('view') === 'operations' && !id
  const danawaView = params.get('view') === 'danawa' && !id
  const category = params.get('categoryId') || ''
  const search = params.get('q') || ''
  const sort = params.get('sort') || 'title'
  const scenario = params.get('scenario') || 'normal'
  function navigate(next: URLSearchParams) {
    history.pushState(null, '', `${location.pathname}${next.size ? `?${next}` : ''}`)
    setParams(new URLSearchParams(next)); setQuery(next.get('q') || ''); setNotice('')
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  useEffect(() => {
    const pop = () => { const next = new URLSearchParams(location.search); setParams(next); setQuery(next.get('q') || '') }
    window.addEventListener('popstate', pop)
    return () => window.removeEventListener('popstate', pop)
  }, [])
  useEffect(() => {
    const abort = new AbortController()
    setError(''); setLoading(true)
    Promise.all([
      request<Listing<Category>>('/categories', abort.signal),
      request<Listing<Product>>(`/products?${new URLSearchParams({ q: search, categoryId: category, sort, scenario })}`, abort.signal),
    ]).then(([cats, items]) => { setCategories(cats.items); setProducts(items.items) })
      .catch(e => { if (!abort.signal.aborted) setError(e instanceof Error ? e.message : 'API 연결 실패') })
      .finally(() => { if (!abort.signal.aborted) setLoading(false) })
    return () => abort.abort()
  }, [search, category, sort, scenario, retry])
  function submit(event: FormEvent) { event.preventDefault(); const next = new URLSearchParams(params); next.delete('product'); next.delete('view'); next.set('q', query.trim()); navigate(next) }
  function filter(key: string, value: string) { const next = new URLSearchParams(params); next.delete('product'); next.delete('view'); value ? next.set(key, value) : next.delete(key); navigate(next) }
  return <div className={`app min-h-screen bg-[var(--bg)] text-[var(--text)] ${dark ? 'dark' : ''}`}>
    <header className="sticky top-0 z-50 border-b border-[var(--line)] bg-[color:var(--header)] backdrop-blur-xl">
      <div className="mx-auto flex max-w-[1440px] flex-wrap items-center gap-5 px-5 py-4 lg:px-8">
        <button onClick={() => navigate(new URLSearchParams())} aria-label="PRIZM 홈" className="flex shrink-0 items-center gap-2"><span className="logo-symbol"><PrizmMark size={31}/></span><span className="text-xl font-black tracking-[-0.06em] sm:text-2xl">PRIZM</span></button>
        <form onSubmit={submit} className="relative order-3 mx-auto w-full md:order-none md:flex-1 md:max-w-2xl">
          <Icon name="search" size={19}/>
          <input aria-label="상품 검색" placeholder="어떤 상품의 최저가를 찾고 계세요?" value={query} onChange={e => setQuery(e.target.value)} className="w-full rounded-2xl border border-[var(--line-strong)] bg-[var(--surface)] py-3.5 pl-12 pr-24 text-sm outline-none transition focus:border-[var(--mint)] focus:ring-4 focus:ring-[var(--mint-glow)]"/>
          <button className="absolute right-2 top-2 rounded-xl bg-[var(--primary)] px-4 py-2 text-xs font-bold text-white transition hover:brightness-110">검색</button>
        </form>
        <div className="ml-auto flex shrink-0 items-center gap-1">
          <button onClick={() => navigate(new URLSearchParams({ view: 'deals' }))} aria-label="평균 대비 특가 상품" className="icon-button"><Icon name="trend" size={19}/></button>
          <button onClick={() => setDark(!dark)} aria-label={dark ? '라이트 모드' : '다크 모드'} aria-pressed={dark} className="icon-button">{dark ? <Icon name="sun" size={19}/> : <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M21 15.2A9 9 0 0 1 8.8 3 9 9 0 1 0 21 15.2Z"/></svg>}</button>
          <button onClick={() => navigate(new URLSearchParams({ view: 'wishlist' }))} aria-label="찜한 상품" className="icon-button"><Icon name="heart" size={19}/></button>
          <button onClick={() => navigate(new URLSearchParams({ view: 'notifications' }))} aria-label="가격 알림함" className="icon-button"><Icon name="bell" size={19}/></button>
          <button onClick={() => navigate(new URLSearchParams({ view: 'history' }))} aria-label="최근 비교 기록" className="icon-button"><Icon name="clock" size={19}/></button>
          <button onClick={() => setAccountOpen(true)} aria-label={user ? '내 정보' : '로그인'} title={user?.email || '로그인'} className="icon-button"><Icon name="user" size={19}/>{user && <span className="sr-only">로그인됨</span>}</button>
        </div>
      </div>
      <div className="border-t border-[var(--line)]"><div className="mx-auto flex max-w-[1440px] items-center gap-4 overflow-x-auto px-5 py-2.5 lg:px-8">
        <span className="flex shrink-0 items-center gap-1.5 text-xs font-extrabold text-[var(--mint)]"><Icon name="sparkles" size={15}/>추천 검색어</span>
        <div className="flex items-center gap-6 whitespace-nowrap text-xs font-medium text-[var(--muted)]">{['소니', '맥북', '애플워치'].map((word, index) => <button key={word} onClick={() => navigate(new URLSearchParams({ q: word }))} className="transition hover:text-[var(--text)]"><span className="mr-1 font-bold">{index + 1}</span>{word}</button>)}</div>
        <span className="ml-auto hidden shrink-0 items-center gap-1 text-[11px] text-[var(--subtle)] sm:flex"><span className="live-dot"/>더미 데이터 미리보기</span>
      </div></div>
    </header>
    <div className="bg-[var(--mint-soft)] px-5 py-2 text-center text-xs font-bold text-[var(--mint)]">{danawaView ? '다나와 실조회 · 배송비·결제 조건·옵션을 확인하세요.' : '더미 가격 데이터 · 서버 규칙 기반 분석 · 실제 가격 및 구매 판단에 사용할 수 없습니다.'}</div>
    <div role="status" className="text-center text-sm">{notice}</div>
    {accountOpen && <AccountPanel onClose={() => setAccountOpen(false)}/>}
    <div className={`mx-auto grid max-w-[1440px] gap-5 px-5 py-6 lg:px-8 ${id || wishlistView || notificationsView || historyView || dealsView || operationsView || danawaView ? '' : 'lg:grid-cols-[220px_minmax(0,1fr)]'}`}>
      {!id && !wishlistView && !notificationsView && !historyView && !dealsView && !operationsView && !danawaView && <aside className="self-start rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-3 shadow-[var(--shadow)]">
        <div className="px-3 pb-3 pt-2 text-sm font-extrabold">카테고리</div>
        <nav aria-label="상품 카테고리" className="flex gap-1 overflow-x-auto lg:flex-col lg:space-y-1 lg:gap-0">
          {mockupCategories.map(cat => <button key={cat.id} onClick={() => filter('categoryId', cat.id)} aria-current={category === cat.id ? 'page' : undefined} className={`flex shrink-0 items-center gap-3 rounded-xl px-3 py-2.5 text-left text-[13px] font-semibold transition lg:w-full ${category === cat.id ? 'bg-[var(--primary)] text-white shadow-lg shadow-indigo-500/15' : 'text-[var(--muted)] hover:bg-[var(--surface-2)] hover:text-[var(--text)]'}`}><Icon name={cat.icon} size={18}/><span className="whitespace-nowrap">{categories.find(item => item.id === cat.id)?.name ?? cat.name}</span>{category === cat.id && <span className="ml-auto"><Icon name="chevron" size={14}/></span>}</button>)}
        </nav>
      </aside>}
      <main className="min-w-0">
        {!operationsView && !danawaView && <CatalogSource revision={retry} onRefresh={() => setRetry(value => value + 1)}/>}
        {danawaView ? <DanawaPage key={user?.uid || 'anonymous'} onLogin={() => setAccountOpen(true)}/> : operationsView ? <OperationsPage key={user?.uid || 'anonymous'} onLogin={() => setAccountOpen(true)}/> : dealsView ? <Deals key={retry} onProduct={id => navigate(new URLSearchParams({ view: 'deals', product: id }))}/> : historyView ? <HistoryPage key={`${user?.uid || 'anonymous'}:${retry}`} onLogin={() => setAccountOpen(true)} onProduct={id => navigate(new URLSearchParams({ view: 'history', product: id }))}/> : notificationsView ? <NotificationsPage key={`${user?.uid || 'anonymous'}:${retry}`} onLogin={() => setAccountOpen(true)} onProduct={id => navigate(new URLSearchParams({ view: 'notifications', product: id }))}/> : wishlistView ? <WishlistPage key={`${user?.uid || 'anonymous'}:${retry}`} onLogin={() => setAccountOpen(true)} onProduct={id => navigate(new URLSearchParams({ view: 'wishlist', product: id }))}/> : id ? <><button onClick={() => { const next = new URLSearchParams(params); next.delete('product'); navigate(next) }} className="mb-6 flex items-center gap-1 text-xs font-bold text-[var(--muted)] hover:text-[var(--text)]"><span className="rotate-180"><Icon name="arrow" size={14}/></span>상품 목록으로</button><div className="mb-6"><p className="mb-2 text-sm font-extrabold text-[var(--primary)]">AI PRICE REPORT</p><h2 className="text-2xl font-black tracking-tight sm:text-3xl">선택한 상품, 더 깊게 비교했어요</h2><p className="mt-2 text-sm text-[var(--muted)]">가격 흐름과 구매 시점을 한 화면에서 확인하세요.</p></div><Detail key={`${id}:${user?.uid || 'anonymous'}:${retry}`} id={id} onLogin={() => setAccountOpen(true)} onNotifications={() => navigate(new URLSearchParams({ view: 'notifications' }))}/></> : <>
          <section className="hero-grid relative mb-5 overflow-hidden rounded-3xl bg-[var(--hero)] px-6 py-8 sm:px-9 sm:py-10">
            <div className="relative z-10 max-w-xl"><div className="mb-4 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/10 px-3 py-1.5 text-xs font-bold text-emerald-200 backdrop-blur"><span className="live-dot"/>AI 구매 분석 미리보기</div><p className="mb-2 text-sm font-semibold text-white/60">검색은 짧게, 비교는 깊게</p><h1 className="text-3xl font-black leading-tight tracking-[-0.045em] text-white sm:text-5xl">가격의 흐름을 읽고,<br/><span className="text-emerald-300">구매의 타이밍을 찾다.</span></h1><p className="mt-5 max-w-md text-sm leading-6 text-white/65">가격 추이와 구매 분석을 더미 데이터로 먼저 만나보세요.</p></div>
            <img src={mockupImages.lifestyle} alt="노트북과 스마트 기기로 구성된 목업 작업 공간" className="absolute inset-y-0 right-0 h-full w-[48%] object-cover opacity-50 mix-blend-luminosity [mask-image:linear-gradient(to_right,transparent,black_35%)]"/>
            <div className="hero-orb"/>
          </section>
          <Collection key={`${search}:${category}`} q={search} categoryId={category} onComplete={() => setRetry(value => value + 1)}/>
          <div className="mb-4 flex flex-wrap items-end justify-between gap-3"><div><p className="mb-1 flex items-center gap-2 text-sm font-extrabold text-[var(--mint)]"><Icon name="sparkles" size={16}/>AI PICK · DEMO</p><h2 className="text-xl font-black tracking-tight sm:text-2xl">{search ? `‘${search}’ 검색 결과` : '지금 비교하는 상품'}</h2></div>
            <div className="flex gap-2 text-sm"><select aria-label="정렬" value={sort} onChange={e => filter('sort', e.target.value)} className="rounded-lg bg-[var(--surface)] p-2"><option value="title">상품명순</option><option value="priceAsc">낮은 확정가순</option></select><select aria-label="더미 시나리오" value={scenario} onChange={e => filter('scenario', e.target.value)} className="rounded-lg bg-[var(--surface)] p-2"><option value="normal">정상 시나리오</option><option value="empty">빈 결과</option><option value="error">공급자 장애</option></select></div>
          </div>
          {loading ? <p role="status" className={panel}>상품 불러오는 중…</p> : error ? <div role="alert" className={panel}><p>{error}</p><button onClick={() => setRetry(x => x+1)} className="mt-3 underline">다시 시도</button></div> : !products.length ? <p className={panel}>검색 결과가 없습니다. 다른 검색어나 카테고리를 선택해 주세요.</p> : <div className="grid gap-4 sm:grid-cols-3">{products.map(product => <button key={product.id} onClick={() => { const next = new URLSearchParams(params); next.set('product', product.id); navigate(next) }} className="group overflow-hidden rounded-2xl border border-[var(--line)] bg-[var(--surface)] text-left transition duration-300 hover:-translate-y-1 hover:border-[var(--primary)] hover:shadow-[var(--shadow-lg)]">
            <div className="relative h-44 overflow-hidden bg-[var(--surface-2)]"><ProductImage categoryId={product.categoryId} title={product.title}/><span className="absolute left-3 top-3 rounded-lg bg-rose-500 px-2 py-1 text-[10px] font-black text-white shadow-lg">DEMO</span><span className="absolute bottom-3 right-3 rounded-full bg-black/60 px-2.5 py-1 text-[10px] font-bold text-white backdrop-blur">목업 참고 이미지</span></div>
            <div className="p-4"><p className="mb-1 text-[11px] font-semibold text-[var(--subtle)]">{mockupCategories.find(item => item.id === product.categoryId)?.name ?? '상품'}</p><h3 className="truncate text-sm font-bold">{product.title}</h3><p className="mt-3 text-lg font-black tracking-tight">{product.priceStale ? '가격 갱신 필요' : money(product.currentPrice)}</p>{product.snapshotState === 'MISSING' && <p className="mt-1 text-xs text-[var(--muted)]">저장된 가격 근거 없음</p>}{product.priceStale && <p className="mt-1 text-xs text-[var(--muted)]">이전 관측가 {money(product.lastKnownPrice)}</p>}<p className="mt-2 flex items-center gap-1 text-[11px] font-bold text-[var(--muted)]">가격 분석 보기 <Icon name="arrow" size={13}/></p></div>
          </button>)}</div>}
        </>}
      </main>
    </div>
    <footer className="mt-20 border-t border-[var(--line)] bg-[var(--surface)]">
      <div className="mx-auto max-w-[1440px] px-5 py-10 lg:px-8">
        <div className="grid gap-8 md:grid-cols-[1fr_1.4fr]">
          <div><button onClick={() => navigate(new URLSearchParams())} aria-label="PRIZM 홈으로 이동" className="flex items-center gap-2"><span className="logo-symbol"><PrizmMark size={30}/></span><span className="text-lg font-black tracking-[-0.05em]">PRIZM</span></button><p className="mt-3 max-w-sm text-xs leading-5 text-[var(--muted)]">여러 쇼핑몰의 가격과 리뷰를 AI로 분석해 더 나은 구매 결정을 돕는 스마트 가격 비교 서비스입니다.</p><p className="mt-5 flex items-center gap-1.5 text-[10px] text-[var(--subtle)]"><Icon name="shield" size={13}/>현재는 더미 데이터 기반 개발용 데모입니다.</p></div>
          <div><button onClick={() => navigate(new URLSearchParams({ view: 'danawa' }))} className="mb-4 mr-4 text-xs font-bold text-[var(--primary)]">다나와 실조회</button><button onClick={() => navigate(new URLSearchParams({ view: 'operations' }))} className="mb-4 text-xs font-bold text-[var(--primary)]">로컬 운영 상태</button><p className="mb-4 text-xs font-black">데이터·분석 연동 예정</p><div className="flex flex-wrap gap-2">{['ReefAPI', 'Gemini'].map(provider => <span key={provider} className="rounded-xl border border-[var(--line)] bg-[var(--surface-2)] px-4 py-2.5 text-[10px] font-extrabold text-[var(--muted)]">{provider}</span>)}</div></div>
        </div>
        <div className="mt-9 border-t border-[var(--line)] pt-6 text-[10px] text-[var(--subtle)]">© 2026 PRIZM. All rights reserved.</div>
      </div>
    </footer>
  </div>
}








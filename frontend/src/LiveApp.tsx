import Community from './Community'
import ServiceFooter from './ServiceFooter'
import RecentProductsBar from './RecentProductsBar'
import { useEffect, useState, type CSSProperties, type FormEvent } from 'react'
import DanawaPage, { type DanawaSearch } from './Danawa'
import { AccountPanel } from './Account'
import { Icon, BrandMark } from './Icons'
import { useSession } from './session'
import { recentSearches, clearSearches, watchSearches } from './searchCache'
import { site2Categories, site2Slides, site2Suggestions } from './site2Design'
import { request, type Single } from './api'
import { LiveWishlistProvider, WishlistPage, useLiveWishlist } from './LiveWishlist'
import MusinsaPage from './Musinsa'
import UsedLowestPage from './UsedLowestPage'
import type { ProductProvider } from './productSource'
import CategoryNavigation from './CategoryNavigation.tsx'
import { combineSearchTerms } from './productFilters'

function HomeCarousel() {
  const intervalMs = 8000
  const [active, setActive] = useState(0)
  const [cycle, setCycle] = useState(0)
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  return <section aria-label="줍줍각 서비스 소개" aria-roledescription="캐러셀" className="hero-grid joop-carousel relative overflow-hidden rounded-3xl bg-[var(--hero)]" onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)} onFocus={() => setFocused(true)} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false) }}>
    <div className="prizm-hero-track flex" style={{ transform: `translateX(-${active * 100}%)` }}>{site2Slides.map((slide, index) => <article key={slide.accent} aria-hidden={index !== active} aria-label={`${index + 1} / ${site2Slides.length}`} aria-roledescription="슬라이드" className="relative min-h-[310px] min-w-full overflow-hidden px-6 pb-14 pt-8 sm:px-9 sm:pt-10">
      <div className="relative z-10 max-w-xl"><div className="mb-4 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/10 px-3 py-1.5 text-xs font-bold text-emerald-200 backdrop-blur"><span className="live-dot"/>{slide.status}</div><p className="mb-2 text-sm font-semibold text-white/60">{slide.eyebrow}</p>{index === 0 ? <h1 className="text-3xl font-black leading-tight tracking-[-0.045em] text-white sm:text-5xl">{slide.title}<br/><span className="text-emerald-300">{slide.accent}</span></h1> : <h2 className="text-3xl font-black leading-tight tracking-[-0.045em] text-white sm:text-5xl">{slide.title}<br/><span className="text-emerald-300">{slide.accent}</span></h2>}<p className="mt-5 max-w-md text-sm leading-6 text-white/65">{slide.description}</p></div>
      <img src={slide.image} alt="" aria-hidden="true" className="joop-carousel-image absolute inset-0 h-full w-full" loading={index === 0 ? 'eager' : 'lazy'} decoding="async"/>
    </article>)}</div><div role="group" aria-label="배너 선택" className="absolute bottom-2 left-1/2 z-20 flex -translate-x-1/2 gap-1.5">{site2Slides.map((slide, index) => <button key={slide.accent} type="button" aria-label={`${index + 1}번 배너: ${slide.title} ${slide.accent}`} aria-pressed={active === index} onClick={() => { setActive(index); setCycle(previous => previous + 1) }} className="grid h-8 min-w-8 place-items-center rounded-full px-1"><span aria-hidden="true" className={`block h-1.5 overflow-hidden rounded-full bg-white/30 ${active === index ? 'w-10' : 'w-6'}`}>{active === index && <span key={`${active}:${cycle}`} className="prizm-carousel-progress block h-full w-full origin-left rounded-full bg-white" style={{ '--carousel-duration': `${intervalMs}ms`, animationPlayState: hovered || focused ? 'paused' : 'running' } as CSSProperties} onAnimationEnd={event => { if (event.animationName === 'prizmCarouselFill') setActive(previous => (previous + 1) % site2Slides.length) }}/>}</span></button>)}</div>
  </section>
}

export default function LiveApp() { return <LiveWishlistProvider><LiveAppPage/></LiveWishlistProvider> }
function LiveAppPage() {
  const savedProducts = useLiveWishlist()
  const [community, setCommunity] = useState(false)
  const [wishlist, setWishlist] = useState(false)
  const [account, setAccount] = useState(false)
  const [accountBusy, setAccountBusy] = useState(false)
  const [dark, setDark] = useState(false)
  const [query, setQuery] = useState('')
  const [provider, setProvider] = useState<ProductProvider>(() => { const source = new URLSearchParams(location.search).get('provider'); return source === 'musinsa' || source === 'joongna' || source === 'karrot' ? source : 'danawa' })
  const [category, setCategory] = useState(() => {
    const params = new URLSearchParams(location.search)
    return params.get('category') === 'used' || ['joongna', 'karrot'].includes(params.get('provider') ?? '') ? 'used' : params.get('provider') === 'musinsa' || params.get('category') === 'fashion' ? 'fashion' : ''
  })
  const [searchRequest, setSearchRequest] = useState<DanawaSearch | null>(() => {
    const params = new URLSearchParams(location.search)
    return params.get('provider') !== 'musinsa' && params.get('category') === 'fashion' && !params.get('product')
      ? { query: '러닝화', categoryId: 'fashion', browseCategory: true, revision: 1 } : null
  })
  const [homeRevision, setHomeRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [detailView, setDetailView] = useState(false)
  const [recent, setRecent] = useState(recentSearches)
  const [serverCategories, setServerCategories] = useState<{ id: string; name: string; query: string }[] | null>(null)
  useEffect(() => watchSearches(() => setRecent(recentSearches())), [])
  useEffect(() => {
    const abort = new AbortController()
    void request<Single<{ id: string; name: string; query: string }[]>>('/danawa/categories', abort.signal)
      .then(response => { if (!abort.signal.aborted) setServerCategories(response.data) })
      .catch(() => { if (!abort.signal.aborted) setServerCategories([]) })
    return () => abort.abort()
  }, [])
  const { user, accountUser } = useSession()
  const activeCategory = site2Categories.find(item => item.id === category) ?? site2Categories[0]
  const isHome = provider === 'danawa' && !category && !searchRequest && !detailView && !wishlist && !community
  useEffect(() => {
    const restore = () => {
      const params = new URLSearchParams(location.search)
      const value = params.get('provider')
      const source: ProductProvider = value === 'musinsa' || value === 'joongna' || value === 'karrot' ? value : 'danawa'
      setProvider(source); setCategory(params.get('category') === 'used' || source === 'joongna' || source === 'karrot' ? 'used' : source === 'musinsa' || params.get('category') === 'fashion' ? 'fashion' : '')
      const fashion = source === 'musinsa' || params.get('category') === 'fashion'
      setSearchRequest(!params.get('product') && fashion ? { query: source === 'musinsa' ? '' : '러닝화', categoryId: 'fashion', browseCategory: true, revision: Date.now() } : null)
      setCommunity(false); setWishlist(false); setAccount(false); setDetailView(Boolean(params.get('product'))); setBusy(false)
    }
    window.addEventListener('popstate', restore)
    return () => window.removeEventListener('popstate', restore)
  }, [])
  function home() {
    if (busy || accountBusy) return
    returnHome()
  }
  function returnHome() {
    const url = new URL(location.href); url.searchParams.delete('product'); url.searchParams.delete('provider'); url.searchParams.delete('category'); history.pushState(null, '', url)
    setProvider('danawa')
    setCommunity(false); setWishlist(false); setAccount(false); setDetailView(false); setQuery(''); setCategory(''); setSearchRequest(null); setHomeRevision(value => value + 1)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  function search(value: string, categoryId = '', browseCategory = false) {
    if (busy || accountBusy || !value.trim()) return
    const source = categoryId === 'fashion' && provider === 'musinsa' ? 'musinsa' : 'danawa'
    setProvider(source)
    const url = new URL(location.href); url.searchParams.delete('product')
    if(categoryId === 'used') { url.searchParams.set('category', 'used'); url.searchParams.delete('provider') }
    else if(categoryId === 'fashion') { url.searchParams.set('category', 'fashion'); url.searchParams.set('provider', source) }
    else { url.searchParams.delete('category'); url.searchParams.delete('provider') }
    history.replaceState(null, '', url)
    setAccount(false)
    setCommunity(false); setWishlist(false); setQuery(browseCategory ? '' : value); setCategory(categoryId)
    setSearchRequest(previous => ({ ...(source === 'musinsa' && categoryId === 'fashion' ? previous : {}), query: combineSearchTerms(value), categoryId, browseCategory, revision: (previous?.revision ?? 0) + 1 }))
  }
  function chooseCategory(id: string) {
    if (!id) { home(); return }
    if (id === 'used') { search('중고', 'used', true); return }
    const item = serverCategories?.find(item => item.id === id)
    if (item?.query) search(item.query, item.id, true)
  }
  function chooseSubcategory(id: string, keyword: string, group: string) {
    if(busy || accountBusy) return
    search(keyword, id)
    setSearchRequest(previous => previous ? { ...previous, navigationGroup: group, navigationItem: keyword === group ? '' : keyword, navigationId: [id, group, keyword].join(':') } : previous)
    if(id !== 'fashion' || provider !== 'musinsa') return
    const codes: Record<string, string> = {
      '티셔츠': '001', '셔츠': '001', '니트': '001', '블라우스': '001',
      '재킷': '002', '아웃도어 재킷': '002', '등산복': '002', '남성 정장': '002', '여성 정장': '002',
      '바지': '003', '원피스': '100', '스커트': '100',
      '운동화': '103', '구두': '103', '샌들': '103', '등산화': '103',
      '백팩': '004', '숄더백': '004', '여행가방': '004', '등산가방': '004',
    }
    const code = codes[keyword] ?? (group === '신발 · 가방' ? '103' : group === '남성패션' || group === '여성패션' ? '001' : '017')
    setSearchRequest(previous => previous ? { ...previous, query: keyword === group ? '' : keyword, browseCategory: keyword === group,
      musinsaCategory: code, musinsaGender: group === '남성패션' ? 'men' : group === '여성패션' ? 'women' : 'all', navigationId: `${id}:${keyword}:${Date.now()}` } : previous)
    if(keyword === group) setQuery('')
  }
  function submit(event: FormEvent) { event.preventDefault(); search(query, category) }
  function changeProvider(source: ProductProvider) {
    if(accountBusy || source === provider) return
    const url = new URL(location.href); url.searchParams.delete('product'); url.searchParams.set('provider', source); url.searchParams.set('category', 'fashion')
    history.pushState(null, '', url)
    setProvider(source); setCategory('fashion'); setDetailView(false); setBusy(false); setQuery('')
    setSearchRequest(previous => ({ query: source === 'danawa' ? '러닝화' : '', categoryId: 'fashion', browseCategory: true, revision: (previous?.revision ?? 0) + 1 }))
  }
  function openAccount() {
    if (busy || accountBusy) return
    setAccount(true); window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  function closeAccount() { setAccount(false); window.scrollTo({ top: 0, behavior: 'smooth' }) }
  function openSavedProduct(productId: string, source: ProductProvider = 'danawa') {
    setCommunity(false); setWishlist(false)
    const url = new URL(location.href)
    url.searchParams.set('product', productId)
    url.searchParams.set('provider', source)
    if(source === 'joongna' || source === 'karrot') url.searchParams.set('category', 'used'); else if(source === 'musinsa') url.searchParams.set('category', 'fashion'); else url.searchParams.delete('category')
    setProvider(source); setCategory(source === 'joongna' || source === 'karrot' ? 'used' : source === 'musinsa' ? 'fashion' : ''); setSearchRequest(null); setDetailView(true); setBusy(false)
    history.pushState(null, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  return <div className={`app site2 min-h-screen bg-[var(--bg)] text-[var(--text)] ${dark ? 'dark' : ''}`}>
    <header className="sticky top-0 z-50 border-b border-[var(--line)] bg-[color:var(--header)] backdrop-blur-xl">
      <div className="mx-auto flex max-w-[1440px] flex-wrap items-center gap-5 px-5 py-4 md:grid md:grid-cols-[1fr_minmax(0,672px)_1fr] lg:px-8">
        <button onClick={home} disabled={busy || accountBusy} aria-label="줍줍각 홈" className="brand-home flex shrink-0 items-center gap-2 md:justify-self-start"><span className="logo-symbol"><BrandMark size={40}/></span><span className="brand-wordmark text-left"><span className="block text-xl font-black tracking-[-0.06em] sm:text-2xl">줍줍각</span><span className="mt-1.5 block text-[8px] font-extrabold tracking-[0.1em] text-[var(--muted)] sm:text-[9px]">AI SMART PRICE PICK</span></span></button>
        <form onSubmit={submit} className="relative order-3 w-full md:order-none md:justify-self-center"><Icon name="search" size={19}/><input aria-label="상품 검색" required maxLength={100} placeholder="어떤 상품의 최저가를 찾고 계세요?" value={query} onChange={event => setQuery(event.target.value)} className="w-full rounded-2xl border border-[var(--line-strong)] bg-[var(--surface)] py-3.5 pl-12 pr-24 text-sm outline-none transition focus:border-[var(--mint)] focus:ring-4 focus:ring-[var(--mint-glow)]"/><button disabled={busy || accountBusy || !query.trim()} className="absolute right-2 top-2 rounded-xl bg-[var(--primary)] px-4 py-2 text-xs font-bold text-white transition hover:brightness-110 disabled:opacity-50">검색</button></form>
        <div className="ml-auto flex shrink-0 items-center gap-1 md:ml-0 md:justify-self-end"><button onClick={() => setDark(!dark)} aria-label={dark ? '라이트 모드' : '다크 모드'} aria-pressed={dark} className="icon-button"><Icon name={dark ? 'sun' : 'moon'} size={19}/></button><button disabled={busy || accountBusy} onClick={() => { if (!user) { openAccount(); return }; setCommunity(false); setAccount(false); setWishlist(true); window.scrollTo({ top: 0, behavior: 'smooth' }) }} aria-label="찜한 상품" title="찜한 상품" aria-pressed={wishlist && !account} className={`icon-button relative ${wishlist && !account ? 'text-rose-500' : ''}`}><Icon name="heart" size={19}/>{Boolean(savedProducts?.entries.length) && <span className="absolute -right-1 -top-1 rounded-full bg-rose-500 px-1.5 text-[9px] font-bold leading-4 text-white">{savedProducts?.entries.length}</span>}</button><button disabled={busy || accountBusy} onClick={openAccount} aria-label={accountUser ? '내 정보' : '로그인'} title={accountUser?.email || '로그인'} className="icon-button"><Icon name="user" size={19}/></button></div>
      </div>
      <div className="border-t border-[var(--line)]"><div className="mx-auto flex max-w-[1440px] items-center gap-4 px-5 py-2.5 lg:px-8"><span className="flex shrink-0 items-center gap-1.5 text-xs font-extrabold text-[var(--mint)]"><Icon name="sparkles" size={15}/>추천 검색어</span><div className="flex min-w-0 flex-1 items-center justify-start gap-6 overflow-x-auto whitespace-nowrap text-xs font-medium text-[var(--muted)]">{site2Suggestions.map((word, index) => <button key={word} disabled={busy || accountBusy} onClick={() => search(word)} className="transition hover:text-[var(--text)] disabled:opacity-50">{index + 1} {word}</button>)}</div><span className="ml-auto hidden shrink-0 items-center gap-1 text-[11px] text-[var(--subtle)] lg:flex"><span className="live-dot"/>로그인 없이 가격 비교</span><button disabled={busy || accountBusy} onClick={() => { setCommunity(true); setAccount(false); setWishlist(false); window.scrollTo({ top: 0, behavior: 'smooth' }) }} aria-pressed={community} className="community-nav-button shrink-0 rounded-full bg-[var(--mint-soft)] px-3 py-1.5 text-xs font-black text-[var(--mint)]">커뮤니티</button></div></div>
      {recent.length > 0 && <div className="border-t border-[var(--line)]"><div className="mx-auto flex max-w-[1440px] items-center gap-2 overflow-x-auto px-5 py-1 text-xs font-medium lg:px-8"><span className="flex shrink-0 items-center gap-1 text-[10px] font-bold text-[var(--muted)]"><Icon name="clock" size={12}/>최근 검색</span>{recent.map(word => <button key={word} disabled={busy || accountBusy} onClick={() => search(word)} className="max-w-28 shrink-0 truncate rounded-md bg-[var(--surface-2)] px-2 py-1 text-xs font-medium text-[var(--muted)] disabled:opacity-50" title={word}>{word}</button>)}<button disabled={busy || accountBusy} onClick={clearSearches} className="ml-auto shrink-0 text-[10px] text-[var(--subtle)] disabled:opacity-50">기록 지우기</button></div></div>}
    </header>
    {savedProducts?.error && !account && <div role="alert" className="mx-auto mt-4 max-w-[1440px] px-5 text-sm text-rose-500">{savedProducts.error}<button disabled={savedProducts.loading || savedProducts.busy} onClick={savedProducts.reload} className="ml-3 underline disabled:opacity-50">찜 목록 다시 불러오기</button></div>}
    <main hidden={account || wishlist || community} className="mx-auto max-w-[1440px] px-5 py-6 lg:px-8">
      <div className={`grid gap-5 ${!detailView ? 'lg:grid-cols-[260px_minmax(0,1fr)]' : ''}`}>
        {!detailView && <CategoryNavigation selected={category} disabled={busy || accountBusy} available={serverCategories?.map(item => item.id) ?? null} onCategory={chooseCategory} onSearch={chooseSubcategory}/>}
        <div className="min-w-0">
          {isHome && <div className="mb-5"><HomeCarousel/></div>}
          {!detailView && !isHome && category !== 'used' && <section className="entry-hero relative mb-7 overflow-hidden rounded-3xl px-7 py-9 text-white sm:px-10 sm:py-11"><div className="relative z-10 max-w-2xl"><div className="mb-5 flex items-center gap-2 text-xs font-bold text-emerald-200"><span className="live-dot"/>{provider === 'musinsa' ? '무신사 패션 · 스포츠 상품' : '다나와 상품 · 가격 비교'}</div><div className="flex items-start gap-4">{category && <span className="hidden h-14 w-14 shrink-0 place-items-center rounded-2xl bg-white/10 sm:grid"><Icon name={activeCategory.icon} size={26}/></span>}<div><h1 className="text-3xl font-black tracking-[-0.045em] sm:text-4xl">{category ? activeCategory.name : `‘${searchRequest?.query}’ 검색 결과`}</h1><p className="mt-3 max-w-xl text-sm leading-6 text-white/65">{category ? activeCategory.description : '판매처별 표시 가격과 배송 조건, 옵션을 비교해 보세요.'}</p></div></div><div className="mt-7 flex flex-wrap gap-2">{(category ? activeCategory.keywords : ['판매처 가격 비교', '옵션별 조건 확인', '저장된 검색 재사용']).map(word => category ? <button key={word} disabled={busy || accountBusy} onClick={() => search(word, category)} className="rounded-full border border-white/10 bg-white/10 px-3 py-1.5 text-[11px] font-bold transition hover:bg-white/20 disabled:opacity-50">{word}</button> : <span key={word} className="rounded-full border border-white/10 bg-white/10 px-3 py-1.5 text-[11px] font-bold">{word}</span>)}</div></div></section>}
          {!detailView && category === 'fashion' && <div className="mb-6 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-[var(--line)] bg-[var(--surface)] p-3"><div className="flex gap-2" role="group" aria-label="패션·스포츠 상품 출처">{([{ id: 'danawa', name: '다나와' }, { id: 'musinsa', name: '무신사' }] as const).map(source => <button key={source.id} disabled={accountBusy} aria-pressed={provider === source.id} onClick={() => changeProvider(source.id)} className={`rounded-xl px-5 py-2.5 text-sm font-bold transition disabled:opacity-50 ${provider === source.id ? 'bg-[var(--primary)] text-white' : 'text-[var(--muted)] hover:bg-[var(--surface-2)]'}`}>{source.name}</button>)}</div><p className="px-2 text-[11px] text-[var(--subtle)]">{provider === 'musinsa' ? '무신사 표시 가격 · 사이즈·재고 확인' : '다나와 판매처별 표시 가격 비교'}</p></div>}
          {category === 'used' ? <UsedLowestPage key={homeRevision} searchRequest={searchRequest} onBusyChange={setBusy} onProductChange={setDetailView} onLogin={openAccount}/> : provider === 'musinsa' && category === 'fashion' ? <MusinsaPage key={searchRequest?.navigationId ?? 'musinsa'} searchRequest={searchRequest} onBusyChange={setBusy} onProductChange={setDetailView} onLogin={openAccount}/> : <DanawaPage key={homeRevision} embedded homePreview={isHome} searchRequest={searchRequest} categoryIcon={activeCategory.icon === 'menu' ? 'search' : activeCategory.icon} onBusyChange={setBusy} onProductChange={setDetailView} onLogin={openAccount}/>}
        </div>
      </div>
      {isHome && <>
        <section className="mt-16"><div className="mb-6"><p className="mb-2 flex items-center gap-2 text-sm font-black text-[var(--mint)]"><Icon name="sparkles" size={16}/>줍줍각 AI SIGNAL</p><h2 className="text-2xl font-black tracking-tight sm:text-3xl">가격 데이터로 살펴보는 구매 타이밍</h2><p className="mt-2 text-sm text-[var(--muted)]">가격의 흐름부터 결제 조건까지, 구매 전에 필요한 정보를 확인하세요.</p></div><div className="grid gap-4 lg:grid-cols-[1.15fr_.85fr]"><article className="ai-radar overflow-hidden rounded-3xl p-7 text-white sm:p-9"><div className="relative z-10"><div className="flex items-start justify-between gap-4"><div><p className="text-xs font-bold text-emerald-200">나의 가격 비교 리포트</p><h3 className="mt-2 text-2xl font-black tracking-tight sm:text-3xl">가격보다 먼저,<br/>조건까지 함께 비교하세요.</h3></div><span className="rounded-2xl bg-white/10 p-3"><Icon name="sparkles" size={25}/></span></div><div className="mt-8 grid grid-cols-3 gap-2">{['상품·옵션 확인', '배송비 확인', '관측 이력 비교'].map((label, index) => <div key={label} className="rounded-2xl border border-white/10 bg-white/5 p-3"><p className="text-[9px] text-white/55">STEP 0{index + 1}</p><p className="mt-1 text-xs font-black sm:text-sm">{label}</p></div>)}</div></div></article><div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-1">{[{ icon: 'trend' as const, label: 'PRICE HISTORY', title: '가격의 흐름을 한눈에', detail: '직접 저장한 관측의 일별 표시가를 비교합니다.', tone: 'text-violet-500 bg-violet-500/10' }, { icon: 'shield' as const, label: 'GEMINI EXPLANATION', title: '확인된 정보로 신중하게', detail: '재고·옵션이 확인되기 전에는 구매 판단을 보류합니다.', tone: 'text-[var(--mint)] bg-[var(--mint-soft)]' }].map(signal => <article key={signal.label} className="flex items-center gap-4 rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-5 shadow-[var(--shadow)]"><span className={`grid h-12 w-12 shrink-0 place-items-center rounded-2xl ${signal.tone}`}><Icon name={signal.icon} size={20}/></span><div className="min-w-0"><p className="text-[10px] font-bold text-[var(--subtle)]">{signal.label}</p><h3 className="mt-1 text-sm font-black">{signal.title}</h3><p className="mt-1 text-[11px] leading-5 text-[var(--muted)]">{signal.detail}</p></div></article>)}</div></div></section>
        <section className="mt-16"><div className="mb-7"><p className="mb-2 text-sm font-black text-[var(--primary)]">CATEGORY DISCOVERY</p><h2 className="text-2xl font-black tracking-tight sm:text-3xl">카테고리별 상품 탐색</h2><p className="mt-2 text-sm text-[var(--muted)]">관심 있는 분야의 검색어로 상품을 찾아보세요.</p></div><div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{site2Categories.filter(item => item.query).map(item => <article key={item.id} className="rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]"><div className="mb-5 flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-2xl bg-[var(--surface-2)] text-[var(--primary)]"><Icon name={item.icon} size={18}/></span><h3 className="text-sm font-black">{item.name}</h3></div><div className="space-y-2">{item.keywords.map(word => <button key={word} disabled={busy || accountBusy} onClick={() => search(word, item.id)} className="flex w-full items-center justify-between rounded-xl px-3 py-2.5 text-left text-xs text-[var(--muted)] transition hover:bg-[var(--surface-2)] hover:text-[var(--text)] disabled:opacity-50">{word}<Icon name="arrow" size={13}/></button>)}</div></article>)}</div></section>
      </>}
    </main>
    {community && !account && <Community key={user?.uid ?? 'guest'} onLogin={openAccount}/>}
    {wishlist && !account && <main className="mx-auto max-w-[1440px] px-5 py-8 lg:px-8"><WishlistPage onLogin={openAccount} onSelect={openSavedProduct}/></main>}
    {account && <AccountPanel presentation="page" onClose={closeAccount} onLogout={returnHome} onBusyChange={setAccountBusy} onWishlist={() => { setAccount(false); setCommunity(false); setWishlist(true) }} onProduct={(id, source) => { setAccount(false); openSavedProduct(id, source) }}/>}
    {!account && <RecentProductsBar disabled={busy || accountBusy} onSelect={openSavedProduct}/>}
    <ServiceFooter disabled={busy || accountBusy} signedIn={Boolean(accountUser)} onHome={home} onAccount={openAccount} onWishlist={() => {
      if (!user) { openAccount(); return }
      setCommunity(false); setAccount(false); setWishlist(true); window.scrollTo({ top: 0, behavior: 'smooth' })
    }}/>


  </div>
}

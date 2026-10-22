import { useEffect, useState, type FormEvent } from 'react'
import DanawaPage, { type DanawaSearch } from './Danawa'
import { AccountPanel } from './Account'
import { Icon, PrizmMark } from './Icons'
import { useSession } from './session'
import { recentSearches, clearSearches, watchSearches } from './searchCache'
import { site2Categories, site2Slides, site2Suggestions } from './site2Design'
import { request, type Single } from './api'
import { LiveWishlistProvider, WishlistPage, useLiveWishlist } from './LiveWishlist'

function HomeCarousel() {
  return <section aria-label="PRIZM 서비스 소개" aria-roledescription="슬라이드" className="hero-grid relative overflow-hidden rounded-3xl bg-[var(--hero)]" tabIndex={0}>
    <div className="prizm-hero-track flex">{[...site2Slides, site2Slides[0]].map((slide, index) => <article key={`${slide.accent}:${index}`} aria-hidden={index === site2Slides.length} className="relative min-h-[310px] min-w-full overflow-hidden px-6 py-8 sm:px-9 sm:py-10">
      <div className="relative z-10 max-w-xl"><div className="mb-4 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/10 px-3 py-1.5 text-xs font-bold text-emerald-200 backdrop-blur"><span className="live-dot"/>{slide.status}</div><p className="mb-2 text-sm font-semibold text-white/60">{slide.eyebrow}</p>{index === 0 ? <h1 className="text-3xl font-black leading-tight tracking-[-0.045em] text-white sm:text-5xl">{slide.title}<br/><span className="text-emerald-300">{slide.accent}</span></h1> : <h2 className="text-3xl font-black leading-tight tracking-[-0.045em] text-white sm:text-5xl">{slide.title}<br/><span className="text-emerald-300">{slide.accent}</span></h2>}<p className="mt-5 max-w-md text-sm leading-6 text-white/65">{slide.description}</p></div>
      <img src={slide.image} alt="" aria-hidden="true" className="absolute inset-y-0 right-0 h-full w-[48%] object-cover opacity-50 mix-blend-luminosity [mask-image:linear-gradient(to_right,transparent,black_35%)]"/><div className="hero-orb"/>
    </article>)}</div><div aria-hidden="true" className="pointer-events-none absolute bottom-4 left-1/2 z-20 flex -translate-x-1/2 gap-1.5">{site2Slides.map(slide => <span key={slide.accent} className="h-1.5 w-6 rounded-full bg-white/30"/>)}</div>
  </section>
}

export default function LiveApp() { return <LiveWishlistProvider><LiveAppPage/></LiveWishlistProvider> }
function LiveAppPage() {
  const savedProducts = useLiveWishlist()
  const [wishlist, setWishlist] = useState(false)
  const [account, setAccount] = useState(false)
  const [accountBusy, setAccountBusy] = useState(false)
  const [dark, setDark] = useState(false)
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState('')
  const [searchRequest, setSearchRequest] = useState<DanawaSearch | null>(null)
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
  const { user } = useSession()
  const activeCategory = site2Categories.find(item => item.id === category) ?? site2Categories[0]
  const isHome = !searchRequest && !detailView && !wishlist
  function home() {
    if (busy || accountBusy) return
    const url = new URL(location.href); url.searchParams.delete('product'); history.pushState(null, '', url)
    setWishlist(false); setAccount(false); setDetailView(false); setQuery(''); setCategory(''); setSearchRequest(null); setHomeRevision(value => value + 1)
  }
  function search(value: string, categoryId = '', browseCategory = false) {
    if (busy || accountBusy || !value.trim()) return
    setAccount(false)
    setWishlist(false); setQuery(value); setCategory(categoryId)
    setSearchRequest(previous => ({ query: value.trim().replace(/\s+/g, ' '), categoryId, browseCategory, revision: (previous?.revision ?? 0) + 1 }))
  }
  function chooseCategory(id: string) {
    if (!id) { home(); return }
    const item = serverCategories?.find(item => item.id === id)
    if (item?.query) search(item.query, item.id, true)
  }
  function submit(event: FormEvent) { event.preventDefault(); search(query) }
  function openAccount() {
    if (busy || accountBusy) return
    setAccount(true); window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  function closeAccount() { setAccount(false); window.scrollTo({ top: 0, behavior: 'smooth' }) }
  function openSavedProduct(productId: string) {
    setWishlist(false)
    const url = new URL(location.href)
    url.searchParams.set('product', productId)
    history.pushState(null, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  function categoryMenu(pills = false) {
    return <nav aria-label="상품 카테고리" className={pills ? 'flex gap-2 overflow-x-auto pb-2' : 'flex gap-1 overflow-x-auto lg:flex-col lg:space-y-1 lg:gap-0'}>{site2Categories.map(item => <button key={item.id} disabled={busy || accountBusy || item.id === 'used' || !serverCategories?.some(row => row.id === item.id)} title={item.id === 'used' ? '중고거래 플랫폼 연결 준비 중' : item.name} onClick={() => chooseCategory(item.id)} aria-current={category === item.id ? 'page' : undefined} className={`${pills ? 'flex shrink-0 items-center gap-2 rounded-full px-4 py-2.5 text-xs' : 'category-link flex shrink-0 items-center gap-3 rounded-xl px-3 py-2.5 text-left text-[13px] lg:w-full'} font-bold transition disabled:cursor-default disabled:opacity-45 ${category === item.id ? 'bg-[var(--primary)] text-white shadow-lg shadow-indigo-500/15' : `${pills ? 'border border-[var(--line)] bg-[var(--surface)]' : ''} text-[var(--muted)] hover:bg-[var(--surface-2)] hover:text-[var(--text)]`}`}><Icon name={item.icon} size={pills ? 15 : 18}/><span className="whitespace-nowrap">{pills ? item.name : item.short}</span>{!pills && category === item.id && <span className="ml-auto"><Icon name="chevron" size={14}/></span>}</button>)}</nav>
  }
  return <div className={`app site2 min-h-screen bg-[var(--bg)] text-[var(--text)] ${dark ? 'dark' : ''}`}>
    <header className="sticky top-0 z-50 border-b border-[var(--line)] bg-[color:var(--header)] backdrop-blur-xl">
      <div className="mx-auto flex max-w-[1440px] flex-wrap items-center gap-5 px-5 py-4 md:grid md:grid-cols-[1fr_minmax(0,672px)_1fr] lg:px-8">
        <button onClick={home} disabled={busy || accountBusy} aria-label="PRIZM 홈" className="flex shrink-0 items-center gap-2 md:justify-self-start"><span className="logo-symbol"><PrizmMark size={31}/></span><span className="text-xl font-black tracking-[-0.06em] sm:text-2xl">PRIZM</span></button>
        <form onSubmit={submit} className="relative order-3 w-full md:order-none md:justify-self-center"><Icon name="search" size={19}/><input aria-label="상품 검색" required maxLength={100} placeholder="어떤 상품의 최저가를 찾고 계세요?" value={query} onChange={event => setQuery(event.target.value)} className="w-full rounded-2xl border border-[var(--line-strong)] bg-[var(--surface)] py-3.5 pl-12 pr-24 text-sm outline-none transition focus:border-[var(--mint)] focus:ring-4 focus:ring-[var(--mint-glow)]"/><button disabled={busy || accountBusy || !query.trim()} className="absolute right-2 top-2 rounded-xl bg-[var(--primary)] px-4 py-2 text-xs font-bold text-white transition hover:brightness-110 disabled:opacity-50">검색</button></form>
        <div className="ml-auto flex shrink-0 items-center gap-1 md:ml-0 md:justify-self-end"><button onClick={() => setDark(!dark)} aria-label={dark ? '라이트 모드' : '다크 모드'} aria-pressed={dark} className="icon-button"><Icon name={dark ? 'sun' : 'moon'} size={19}/></button><button disabled={busy || accountBusy} onClick={() => { if (!user) { openAccount(); return }; setAccount(false); setWishlist(true); window.scrollTo({ top: 0, behavior: 'smooth' }) }} aria-label="찜한 상품" title="찜한 상품" aria-pressed={wishlist && !account} className={`icon-button relative ${wishlist && !account ? 'text-rose-500' : ''}`}><Icon name="heart" size={19}/>{Boolean(savedProducts?.entries.length) && <span className="absolute -right-1 -top-1 rounded-full bg-rose-500 px-1.5 text-[9px] font-bold leading-4 text-white">{savedProducts?.entries.length}</span>}</button><button disabled={busy || accountBusy} onClick={openAccount} aria-label={user ? '내 정보' : '로그인'} title={user?.email || '로그인'} className="icon-button"><Icon name="user" size={19}/></button></div>
      </div>
      <div className="border-t border-[var(--line)]"><div className="mx-auto flex max-w-[1440px] items-center gap-4 px-5 py-2.5 lg:px-8"><span className="flex shrink-0 items-center gap-1.5 text-xs font-extrabold text-[var(--mint)]"><Icon name="sparkles" size={15}/>추천 검색어</span><div className="flex min-w-0 flex-1 items-center justify-start gap-6 overflow-x-auto whitespace-nowrap text-xs font-medium text-[var(--muted)] md:justify-center">{site2Suggestions.map((word, index) => <button key={word} disabled={busy || accountBusy} onClick={() => search(word)} className="transition hover:text-[var(--text)] disabled:opacity-50">{index + 1} {word}</button>)}</div><span className="ml-auto hidden shrink-0 items-center gap-1 text-[11px] text-[var(--subtle)] lg:flex"><span className="live-dot"/>로그인 없이 가격 비교</span><button disabled title="커뮤니티 연결 준비 중" className="hidden shrink-0 rounded-full bg-[var(--mint-soft)] px-3 py-1.5 text-[10px] font-black text-[var(--mint)] opacity-60 sm:block">커뮤니티</button></div></div>
      {recent.length > 0 && <div className="border-t border-[var(--line)]"><div className="mx-auto flex max-w-[1440px] items-center gap-3 overflow-x-auto px-5 py-2 lg:px-8"><span className="flex shrink-0 items-center gap-1.5 text-xs font-bold text-[var(--muted)]"><Icon name="clock" size={14}/>최근 검색</span>{recent.map(word => <button key={word} disabled={busy || accountBusy} onClick={() => search(word)} className="max-w-40 shrink-0 truncate rounded-lg bg-[var(--surface-2)] px-3 py-1.5 text-xs text-[var(--muted)] disabled:opacity-50">{word}</button>)}<button disabled={busy || accountBusy} onClick={clearSearches} className="ml-auto shrink-0 text-[11px] text-[var(--subtle)] disabled:opacity-50">기록 지우기</button></div></div>}
    </header>
    {savedProducts?.error && !account && <div role="alert" className="mx-auto mt-4 max-w-[1440px] px-5 text-sm text-rose-500">{savedProducts.error}<button disabled={savedProducts.loading || savedProducts.busy} onClick={savedProducts.reload} className="ml-3 underline disabled:opacity-50">찜 목록 다시 불러오기</button></div>}
    <main hidden={account || wishlist} className="mx-auto max-w-[1440px] px-5 py-6 lg:px-8">
      {!detailView && !isHome && <div className="mb-7">{categoryMenu(true)}</div>}
      <div className={`grid gap-5 ${isHome ? 'lg:grid-cols-[220px_minmax(0,1fr)]' : ''}`}>
        {isHome && <aside className="self-start rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-3 shadow-[var(--shadow)]"><div className="px-3 pb-3 pt-2 text-sm font-extrabold">카테고리</div>{categoryMenu()}</aside>}
        <div className="min-w-0">
          {isHome && <div className="mb-5"><HomeCarousel/></div>}
          {!detailView && !isHome && <section className="entry-hero relative mb-7 overflow-hidden rounded-3xl px-7 py-9 text-white sm:px-10 sm:py-11"><div className="relative z-10 max-w-2xl"><div className="mb-5 flex items-center gap-2 text-xs font-bold text-emerald-200"><span className="live-dot"/>다나와 상품 · 가격 비교</div><div className="flex items-start gap-4">{category && <span className="hidden h-14 w-14 shrink-0 place-items-center rounded-2xl bg-white/10 sm:grid"><Icon name={activeCategory.icon} size={26}/></span>}<div><h1 className="text-3xl font-black tracking-[-0.045em] sm:text-4xl">{category ? activeCategory.name : `‘${searchRequest?.query}’ 검색 결과`}</h1><p className="mt-3 max-w-xl text-sm leading-6 text-white/65">{category ? activeCategory.description : '판매처별 표시 가격과 배송 조건, 옵션을 비교해 보세요.'}</p></div></div><div className="mt-7 flex flex-wrap gap-2">{(category ? activeCategory.keywords : ['판매처 가격 비교', '옵션별 조건 확인', '저장된 검색 재사용']).map(word => category ? <button key={word} disabled={busy || accountBusy} onClick={() => search(word, category)} className="rounded-full border border-white/10 bg-white/10 px-3 py-1.5 text-[11px] font-bold transition hover:bg-white/20 disabled:opacity-50">{word}</button> : <span key={word} className="rounded-full border border-white/10 bg-white/10 px-3 py-1.5 text-[11px] font-bold">{word}</span>)}</div></div></section>}
          <DanawaPage key={homeRevision} embedded homePreview={isHome} searchRequest={searchRequest} categoryIcon={activeCategory.icon === 'menu' ? 'search' : activeCategory.icon} onBusyChange={setBusy} onProductChange={setDetailView} onLogin={openAccount}/>
        </div>
      </div>
      {isHome && <>
        <section className="mt-16"><div className="mb-6"><p className="mb-2 flex items-center gap-2 text-sm font-black text-[var(--mint)]"><Icon name="sparkles" size={16}/>PRIZM AI SIGNAL</p><h2 className="text-2xl font-black tracking-tight sm:text-3xl">가격 데이터로 살펴보는 구매 타이밍</h2><p className="mt-2 text-sm text-[var(--muted)]">가격의 흐름부터 결제 조건까지, 구매 전에 필요한 정보를 확인하세요.</p></div><div className="grid gap-4 lg:grid-cols-[1.15fr_.85fr]"><article className="ai-radar overflow-hidden rounded-3xl p-7 text-white sm:p-9"><div className="relative z-10"><div className="flex items-start justify-between gap-4"><div><p className="text-xs font-bold text-emerald-200">나의 가격 비교 리포트</p><h3 className="mt-2 text-2xl font-black tracking-tight sm:text-3xl">가격보다 먼저,<br/>조건까지 함께 비교하세요.</h3></div><span className="rounded-2xl bg-white/10 p-3"><Icon name="sparkles" size={25}/></span></div><div className="mt-8 grid grid-cols-3 gap-2">{['상품·옵션 확인', '배송비 확인', '관측 이력 비교'].map((label, index) => <div key={label} className="rounded-2xl border border-white/10 bg-white/5 p-3"><p className="text-[9px] text-white/55">STEP 0{index + 1}</p><p className="mt-1 text-xs font-black sm:text-sm">{label}</p></div>)}</div></div></article><div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-1">{[{ icon: 'trend' as const, label: 'PRICE HISTORY', title: '가격의 흐름을 한눈에', detail: '직접 저장한 관측의 일별 표시가를 비교합니다.', tone: 'text-violet-500 bg-violet-500/10' }, { icon: 'shield' as const, label: 'GEMINI EXPLANATION', title: '확인된 정보로 신중하게', detail: '재고·옵션이 확인되기 전에는 구매 판단을 보류합니다.', tone: 'text-[var(--mint)] bg-[var(--mint-soft)]' }].map(signal => <article key={signal.label} className="flex items-center gap-4 rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-5 shadow-[var(--shadow)]"><span className={`grid h-12 w-12 shrink-0 place-items-center rounded-2xl ${signal.tone}`}><Icon name={signal.icon} size={20}/></span><div className="min-w-0"><p className="text-[10px] font-bold text-[var(--subtle)]">{signal.label}</p><h3 className="mt-1 text-sm font-black">{signal.title}</h3><p className="mt-1 text-[11px] leading-5 text-[var(--muted)]">{signal.detail}</p></div></article>)}</div></div></section>
        <section className="mt-16"><div className="mb-7"><p className="mb-2 text-sm font-black text-[var(--primary)]">CATEGORY DISCOVERY</p><h2 className="text-2xl font-black tracking-tight sm:text-3xl">카테고리별 상품 탐색</h2><p className="mt-2 text-sm text-[var(--muted)]">관심 있는 분야의 검색어로 상품을 찾아보세요.</p></div><div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{site2Categories.filter(item => item.query).map(item => <article key={item.id} className="rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]"><div className="mb-5 flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-2xl bg-[var(--surface-2)] text-[var(--primary)]"><Icon name={item.icon} size={18}/></span><h3 className="text-sm font-black">{item.name}</h3></div><div className="space-y-2">{item.keywords.map(word => <button key={word} disabled={busy || accountBusy} onClick={() => search(word, item.id)} className="flex w-full items-center justify-between rounded-xl px-3 py-2.5 text-left text-xs text-[var(--muted)] transition hover:bg-[var(--surface-2)] hover:text-[var(--text)] disabled:opacity-50">{word}<Icon name="arrow" size={13}/></button>)}</div></article>)}</div></section>
      </>}
    </main>
    {wishlist && !account && <main className="mx-auto max-w-[1440px] px-5 py-8 lg:px-8"><WishlistPage onLogin={openAccount} onSelect={openSavedProduct}/></main>}
    {account && <AccountPanel presentation="page" onClose={closeAccount} onBusyChange={setAccountBusy}/>}
    <footer className="mt-20 border-t border-[var(--line)] bg-[var(--surface)]"><div className="mx-auto max-w-[1440px] px-5 py-10 lg:px-8"><div className="grid gap-8 md:grid-cols-[1fr_1.4fr]"><div><button onClick={home} disabled={busy || accountBusy} aria-label="PRIZM 홈으로 이동" className="flex items-center gap-2"><span className="logo-symbol"><PrizmMark size={30}/></span><span className="text-lg font-black tracking-[-0.05em]">PRIZM</span></button><p className="mt-3 max-w-sm text-xs leading-5 text-[var(--muted)]">가격과 판매 조건을 비교해 더 나은 구매 결정을 돕는 스마트 가격 비교 서비스입니다.</p><p className="mt-5 flex items-start gap-1.5 text-[10px] leading-5 text-[var(--subtle)]"><span className="mt-1"><Icon name="shield" size={13}/></span>가격 정보는 원본 쇼핑몰의 제공 기준이며 실제 결제 조건과 다를 수 있습니다.</p></div><div><p className="mb-4 text-xs font-black">가격 데이터 · 분석 서비스</p><div className="flex flex-wrap gap-2">{['ReefAPI · 다나와', 'Gemini'].map(provider => <span key={provider} className="rounded-xl border border-[var(--line)] bg-[var(--surface-2)] px-4 py-2.5 text-[10px] font-extrabold text-[var(--muted)]">{provider}</span>)}</div></div></div><div className="mt-9 flex flex-col gap-3 border-t border-[var(--line)] pt-6 text-[10px] text-[var(--subtle)] sm:flex-row sm:items-center sm:justify-between"><p>© 2026 PRIZM. All rights reserved.</p><p>구매 전 판매처에서 옵션 · 배송비 · 재고를 확인하세요.</p></div></div></footer>

  </div>
}

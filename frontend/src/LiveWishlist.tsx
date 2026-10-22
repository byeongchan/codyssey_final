import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { request, type Single } from './api'
import { auth } from './firebase'
import { useSession } from './session'
import { Icon } from './Icons'

type Entry = { productId: string; title: string; image: string | null; listedPrice: number | null; savedAt: string; priceObservedAt: string | null }
type State = { entries: Entry[]; loading: boolean; busy: boolean; error: string; ready: boolean; reload: () => void; toggle: (productId: string, familyId?: string) => void }
const Context = createContext<State | null>(null)
export const useLiveWishlist = () => useContext(Context)

export function LiveWishlistProvider({ children }: { children: ReactNode }) {
  const { user, ready: sessionReady } = useSession()
  const [stored, setStored] = useState<{ owner: string; entries: Entry[] } | null>(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const generation = useRef(0)
  const active = useRef<AbortController | null>(null)
  const mutation = useRef(false)
  useEffect(() => () => { generation.current++; active.current?.abort() }, [])
  useEffect(() => {
    const turn = ++generation.current
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    mutation.current = false; setBusy(false); setError(''); setStored(null)
    if (!sessionReady || !user) { setLoading(false); return () => abort.abort() }
    const uid = user.uid
    setLoading(true)
    void request<Single<Entry[]>>('/me/danawa/wishlist', abort.signal).then(response => {
      if (!abort.signal.aborted && turn === generation.current && auth?.currentUser?.uid === uid) setStored({ owner: uid, entries: response.data })
    }).catch(error => {
      if (!abort.signal.aborted && turn === generation.current) setError(error instanceof Error ? error.message : '찜 목록을 불러오지 못했습니다.')
    }).finally(() => { if (!abort.signal.aborted && turn === generation.current) setLoading(false) })
    return () => abort.abort()
  }, [user?.uid, sessionReady, revision])
  const entries = stored?.owner === user?.uid && stored?.owner === auth?.currentUser?.uid ? stored?.entries ?? [] : []
  const ready = Boolean(user && stored?.owner === user.uid && user.uid === auth?.currentUser?.uid)
  async function toggle(productId: string, familyId?: string) {
    if (!user || !ready || mutation.current || loading) return
    const uid = user.uid, turn = generation.current
    const saved = entries.some(item => item.productId === productId)
    mutation.current = true; setBusy(true); setError('')
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    try {
      await request<Single<{ saved: boolean }>>(`/me/danawa/wishlist/${productId}`, abort.signal, saved ? 'DELETE' : 'PUT', saved ? undefined : { catalogProductId: familyId ?? productId })
      if (abort.signal.aborted || turn !== generation.current || auth?.currentUser?.uid !== uid) return
      const response = await request<Single<Entry[]>>('/me/danawa/wishlist', abort.signal)
      if (!abort.signal.aborted && turn === generation.current && auth?.currentUser?.uid === uid) setStored({ owner: uid, entries: response.data })
    } catch (error) {
      if (!abort.signal.aborted && turn === generation.current && auth?.currentUser?.uid === uid) {
        setStored(null) // A write may have succeeded before its response was lost; reload before another toggle.
        setError(error instanceof Error ? error.message : '찜을 변경하지 못했습니다. 목록을 다시 불러와 주세요.')
      }
    } finally { if (turn === generation.current && active.current === abort) { mutation.current = false; if (!abort.signal.aborted) setBusy(false) } }
  }
  return <Context.Provider value={{ entries, loading, busy, error, ready, reload: () => { if (!mutation.current) setRevision(value => value + 1) }, toggle: (id, family) => { void toggle(id, family) } }}>{children}</Context.Provider>
}

export function WishlistButton({ productId, familyId, onLogin, compact = false }: { productId: string; familyId?: string; onLogin: () => void; compact?: boolean }) {
  const state = useLiveWishlist()
  const { user, ready } = useSession()
  if (!state) return null
  const saved = state.entries.some(item => item.productId === productId)
  return <button type="button" disabled={!ready || state.loading || state.busy || Boolean(user && !state.ready)} onClick={() => user ? state.toggle(productId, familyId) : onLogin()} aria-label={saved ? '찜 해제' : '찜하기'} aria-pressed={saved} title={saved ? '찜 해제' : '찜하기'} className={`${compact ? 'grid h-9 w-9 place-items-center rounded-full border border-[var(--line)] bg-[var(--surface)] shadow-sm' : 'flex w-full items-center justify-center gap-2 rounded-2xl border border-[var(--line)] px-4 py-3 text-sm font-bold'} transition disabled:opacity-40 ${saved ? 'text-rose-500 [&_svg]:fill-current' : 'text-[var(--muted)] hover:text-rose-500'}`}><Icon name="heart" size={18}/>{!compact && (saved ? '찜한 상품' : '찜하기')}</button>
}

export function WishlistPage({ onSelect, onLogin }: { onSelect: (productId: string) => void; onLogin: () => void }) {
  const state = useLiveWishlist()
  const { user } = useSession()
  const [page, setPage] = useState(1)
  const [failedImages, setFailedImages] = useState<string[]>([])
  useEffect(() => { setPage(1); setFailedImages([]) }, [user?.uid])
  if (!state) return null
  const sorted = [...state.entries].sort((a, b) => b.savedAt.localeCompare(a.savedAt))
  const pages = Math.max(1, Math.ceil(sorted.length / 12)), current = Math.min(page, pages)
  return <section className="space-y-6">
    <div className="flex items-center justify-between gap-3"><div><p className="mb-2 text-xs font-black text-[var(--mint)]">MY WISHLIST</p><h1 className="flex items-center gap-2 text-2xl font-black"><span className="text-rose-500"><Icon name="heart" size={23}/></span>찜한 상품</h1><p className="mt-2 text-xs text-[var(--muted)]">{user ? `${sorted.length}개 저장 · 최대 100개` : '로그인하고 관심 있는 상품을 모아보세요.'}</p></div>{user && <button type="button" disabled={state.loading || state.busy} onClick={state.reload} className="rounded-xl border border-[var(--line)] px-3 py-2 text-xs font-bold disabled:opacity-40">목록 새로고침</button>}</div>
    {!user ? <button onClick={onLogin} className="rounded-xl bg-[var(--primary)] px-5 py-3 text-sm font-bold text-white">로그인하기</button> : <>
      {state.loading && <p role="status" className="text-sm text-[var(--muted)]">찜 목록을 불러오는 중…</p>}
      {!state.loading && !state.error && !sorted.length && <div className="rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-10 text-center"><p className="font-bold">아직 찜한 상품이 없습니다.</p><p className="mt-2 text-sm text-[var(--muted)]">상품 카드나 상세 화면의 하트를 눌러 저장해 보세요.</p></div>}
      <ul className="grid gap-4 sm:grid-cols-3">{sorted.slice((current - 1) * 12, current * 12).map(item => <li key={item.productId} className="relative overflow-hidden rounded-2xl border border-[var(--line)] bg-[var(--surface)]"><div className="absolute right-3 top-3 z-10"><WishlistButton productId={item.productId} onLogin={onLogin} compact/></div><button onClick={() => onSelect(item.productId)} className="w-full text-left"><div className="grid h-44 place-items-center bg-[var(--surface-2)]">{item.image && !failedImages.includes(item.productId) ? <img src={item.image} alt={item.title} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailedImages(previous => [...previous, item.productId])} className="h-44 w-full object-contain p-4"/> : <span className="text-[var(--subtle)]"><Icon name="heart" size={40}/></span>}</div><div className="p-4"><h2 className="line-clamp-2 min-h-10 text-sm font-bold">{item.title}</h2><p className="mt-3 text-lg font-black">{item.listedPrice === null ? '가격 미확인' : `${item.listedPrice.toLocaleString('ko-KR')}원`}</p><p className="mt-1 text-[10px] text-[var(--subtle)]">저장 당시 표시가 · 현재 가격은 상세에서 확인</p><span className="mt-3 inline-flex items-center gap-1 text-xs font-bold text-[var(--primary)]">상품 상세 보기<Icon name="arrow" size={13}/></span></div></button></li>)}</ul>
      {pages > 1 && <nav aria-label="찜 목록 페이지" className="flex flex-wrap justify-center gap-2">{Array.from({ length: pages }, (_, i) => i + 1).map(value => <button key={value} type="button" aria-current={value === current ? 'page' : undefined} onClick={() => setPage(value)} className={`rounded-xl border px-3 py-2 text-sm ${value === current ? 'border-[var(--primary)] bg-[var(--primary)] text-white' : 'border-[var(--line)]'}`}>{value}</button>)}</nav>}
    </>}
  </section>
}

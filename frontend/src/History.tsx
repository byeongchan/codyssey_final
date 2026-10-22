import { useEffect, useState } from 'react'
import { ApiRequestError, request, type Listing, type Product, type Single } from './api'
import { auth } from './firebase'
import { useSession } from './session'
import { Icon } from './Icons'

type Entry = { productId: string; viewedAt: string; product: Product | null }

export function HistoryRecorder({ productId }: { productId: string }) {
  const { user } = useSession()
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    setError('')
    if (user) void request(`/me/history/${encodeURIComponent(productId)}`, abort.signal, 'POST')
      .catch(error => { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : '기록 저장 실패') })
    return () => abort.abort()
  }, [user?.uid, productId, retry])
  return error ? <p role="alert" className="mt-3 text-xs text-rose-500">최근 기록을 저장하지 못했습니다. {error}<button onClick={() => setRetry(value => value + 1)} className="ml-2 underline">다시 시도</button></p> : null
}

export function HistoryPage({ onLogin, onProduct }: { onLogin: () => void; onProduct: (id: string) => void }) {
  const { user, ready } = useSession()
  const [items, setItems] = useState<Entry[]>([])
  const [cursor, setCursor] = useState('')
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [removing, setRemoving] = useState(false)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    if (!ready) return () => abort.abort()
    if (!user) { setLoading(false); return () => abort.abort() }
    setLoading(true); setError('')
    void request<Listing<Omit<Entry, 'product'>>>(`/me/history?${new URLSearchParams({ cursor, limit: '20' })}`, abort.signal)
      .then(async page => {
        const rows = await Promise.all(page.items.map(async entry => {
          try { return { ...entry, product: (await request<Single<Product>>(`/products/${encodeURIComponent(entry.productId)}`, abort.signal)).data } }
          catch (error) { if (error instanceof ApiRequestError && error.status === 404) return { ...entry, product: null }; throw error }
        }))
        if (!abort.signal.aborted) {
          setItems(previous => cursor ? [...previous, ...rows.filter(row => !previous.some(item => item.productId === row.productId))] : rows)
          setNextCursor(page.nextCursor)
        }
      }).catch(error => { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : '기록 조회 실패') })
      .finally(() => { if (!abort.signal.aborted) setLoading(false) })
    return () => abort.abort()
  }, [user?.uid, ready, cursor, retry])
  async function remove(id: string) {
    const uid = user?.uid
    setRemoving(true); setError('')
    try {
      await request(`/me/history/${encodeURIComponent(id)}`, undefined, 'DELETE')
      if (auth?.currentUser?.uid === uid) setItems(previous => previous.filter(item => item.productId !== id))
    } catch (error) { if (auth?.currentUser?.uid === uid) setError(error instanceof Error ? error.message : '기록 삭제 실패') }
    finally { setRemoving(false) }
  }
  return <section className="rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]">
    <p className="flex items-center gap-2 text-sm font-extrabold text-[var(--mint)]"><Icon name="clock" size={16}/>RECENT HISTORY</p>
    <h1 className="mt-2 text-2xl font-black">최근 비교 기록</h1>
    <p className="mt-2 text-xs text-[var(--muted)]">로그인한 상태에서 확인한 상품을 마지막 방문 순서로 보여드려요.</p>
    {!ready ? <p role="status" className="mt-5">로그인 확인 중…</p> : !user ? <button onClick={onLogin} className="mt-5 rounded-xl bg-[var(--primary)] px-5 py-3 text-sm font-bold text-white">로그인</button> : <>
      {items.map(item => <div key={item.productId} className="mt-4 flex items-center justify-between gap-3 rounded-2xl border border-[var(--line)] p-4">
        <button disabled={!item.product} onClick={() => onProduct(item.productId)} className="min-w-0 text-left"><p className="truncate text-sm font-bold">{item.product?.title ?? `판매 종료 상품 (${item.productId})`}</p><p className="mt-1 text-xs text-[var(--muted)]">{new Date(item.viewedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</p></button>
        <button disabled={removing} onClick={() => void remove(item.productId)} className="shrink-0 text-xs font-bold text-[var(--primary)]" aria-label={`${item.product?.title ?? item.productId} 기록 삭제`}>기록 삭제</button>
      </div>)}
      {loading ? <p role="status" className="mt-5">기록 불러오는 중…</p> : !error && !items.length ? <p className="mt-5 text-sm text-[var(--muted)]">최근 비교 기록이 없습니다.</p> : null}
      {error && <p role="alert" className="mt-5 text-sm text-rose-500">{error}<button onClick={() => setRetry(value => value + 1)} className="ml-3 underline">다시 시도</button></p>}
      {!loading && !error && nextCursor && <button onClick={() => setCursor(nextCursor)} className="mt-5 text-sm font-bold text-[var(--primary)]">더 보기</button>}
    </>}
  </section>
}

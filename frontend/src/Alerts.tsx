import { useEffect, useState, type FormEvent } from 'react'
import { request, ApiRequestError, type Listing, type Product, type Single } from './api'
import { auth } from './firebase'
import { useSession } from './session'
import { Icon } from './Icons'

export type PriceAlert = { id: string; productId: string; targetPrice: number; enabled: boolean; createdAt: string | null; updatedAt: string | null; lastConditionMet: boolean; lastNotifiedAt: string | null }
export type PriceNotification = { id: string; productId: string; alertId: string; targetPrice: number; observedPrice: number; observedAt: string; createdAt: string | null; readAt: string | null; synthetic: boolean }
type Evaluation = { checked: number; notified: number; nextCursor: string | null }
const money = (value: number) => `${value.toLocaleString('ko-KR')}원`
const panel = 'rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]'
const button = 'rounded-xl border border-[var(--line)] px-3 py-2 text-xs font-bold text-[var(--primary)] disabled:opacity-50'
const errorMessage = (error: unknown) => error instanceof ApiRequestError ? error.message : '로그인과 에뮬레이터 상태를 확인하고 다시 시도해 주세요.'

export function PriceAlertForm({ productId, currentPrice, onLogin, onNotifications }: { productId: string; currentPrice: number | null; onLogin: () => void; onNotifications: () => void }) {
  const { user, ready } = useSession()
  const [record, setRecord] = useState<PriceAlert | null>(null)
  const [target, setTarget] = useState(currentPrice?.toString() || '')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    setRecord(null); setError(''); setNotice('')
    if (!ready) return () => abort.abort()
    if (!user) { setLoading(false); return () => abort.abort() }
    setLoading(true)
    request<Single<PriceAlert>>(`/me/alerts/${encodeURIComponent(productId)}`, abort.signal)
      .then(result => { if (!abort.signal.aborted) { setRecord(result.data); setTarget(result.data.targetPrice.toString()) } })
      .catch(error => { if (!abort.signal.aborted && !(error instanceof ApiRequestError && error.status === 404)) setError(errorMessage(error)) })
      .finally(() => { if (!abort.signal.aborted) setLoading(false) })
    return () => abort.abort()
  }, [user?.uid, ready, productId, retry])
  async function change(kind: 'save' | 'toggle' | 'delete') {
    if (!user) { onLogin(); return }
    if (busy || loading) return
    const uid = user.uid
    const value = Number(target)
    if (kind === 'save' && (!/^\d+$/.test(target) || !Number.isSafeInteger(value) || value < 1 || value > 1000000000000)) { setError('목표가는 1원 이상 1조 원 이하의 정수로 입력해 주세요.'); return }
    setBusy(true); setError(''); setNotice('')
    try {
      if (kind === 'delete') {
        await request(`/me/alerts/${encodeURIComponent(productId)}`, undefined, 'DELETE')
        if (uid === auth?.currentUser?.uid) { setRecord(null); setNotice('목표 가격 알림을 삭제했습니다.') }
      } else {
        const result = kind === 'toggle'
          ? await request<Single<PriceAlert>>(`/me/alerts/${encodeURIComponent(productId)}`, undefined, 'PATCH', { enabled: !record?.enabled })
          : record
            ? await request<Single<PriceAlert>>(`/me/alerts/${encodeURIComponent(productId)}`, undefined, 'PATCH', { targetPrice: value })
            : await request<Single<PriceAlert>>('/me/alerts', undefined, 'POST', { productId, targetPrice: value })
        if (uid === auth?.currentUser?.uid) { setRecord(result.data); setTarget(result.data.targetPrice.toString()); setNotice('알림 설정을 저장했습니다. 알림함에서 결과를 확인하세요.') }
      }
    } catch (error) { if (uid === auth?.currentUser?.uid) setError(errorMessage(error)) } finally { setBusy(false) }
  }
  return <article className={panel}><h2 className="flex items-center gap-2 font-black"><Icon name="clock" size={18}/>목표 가격 알림</h2><p className="mt-2 text-xs leading-5 text-[var(--muted)]">배송비를 확인한 유효 가격이 목표가 이하일 때 인앱 알림을 남깁니다. 현재는 더미 가격 기준입니다.</p>
    {!ready || loading && user ? <p role="status" className="mt-4 text-sm">설정 확인 중…</p> : !user ? <button onClick={onLogin} className={`${button} mt-4`}>로그인하고 알림 설정</button> : <>
      <form onSubmit={(event: FormEvent) => { event.preventDefault(); void change('save') }} className="mt-4 flex flex-wrap items-end gap-2"><label className="min-w-0 flex-1 text-xs font-bold">목표 가격 (원)<input type="text" inputMode="numeric" pattern="[0-9]+" required value={target} onChange={event => setTarget(event.target.value)} disabled={busy} className="mt-2 w-full rounded-xl border border-[var(--line)] bg-[var(--surface-2)] p-3 text-sm outline-none focus:border-[var(--primary)]"/></label><button disabled={busy} className="rounded-xl bg-[var(--primary)] px-4 py-3 text-xs font-bold text-white disabled:opacity-50">{busy ? '처리 중…' : record ? '목표가 변경' : '알림 등록'}</button></form>
      {record && <div className="mt-3 flex flex-wrap items-center gap-2"><span className="mr-auto text-xs text-[var(--muted)]">{record.enabled ? '알림 켜짐' : '알림 꺼짐'} · {record.lastConditionMet ? '마지막 유효 판정: 충족' : '목표가 확인 대기'}</span><button disabled={busy} onClick={() => void change('toggle')} className={button}>{record.enabled ? '끄기' : '켜기'}</button><button disabled={busy} onClick={() => void change('delete')} className={button}>설정 삭제</button></div>}
      <button onClick={onNotifications} className="mt-4 flex items-center gap-1 text-xs font-bold text-[var(--primary)]">알림함 보기<Icon name="arrow" size={14}/></button>
    </>}
    {error && <p role="alert" className="mt-3 text-xs text-rose-500">{error}<button onClick={() => setRetry(value => value + 1)} className="ml-2 underline">설정 다시 확인</button></p>}
    {notice && <p role="status" className="mt-3 text-xs text-[var(--muted)]">{notice}</p>}
  </article>
}

export function NotificationsPage({ onLogin, onProduct }: { onLogin: () => void; onProduct: (id: string) => void }) {
  const { user, ready } = useSession()
  const [alerts, setAlerts] = useState<PriceAlert[]>([])
  const [notifications, setNotifications] = useState<PriceNotification[]>([])
  const [names, setNames] = useState<Record<string, string>>({})
  const [alertCursor, setAlertCursor] = useState<string | null>(null)
  const [notificationCursor, setNotificationCursor] = useState<string | null>(null)
  const [evaluationCursor, setEvaluationCursor] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    if (!ready) return () => abort.abort()
    if (!user) { setLoading(false); return () => abort.abort() }
    setLoading(true); setError('')
    Promise.all([
      request<Listing<PriceAlert>>('/me/alerts', abort.signal),
      request<Listing<PriceNotification>>('/me/notifications', abort.signal),
      request<Listing<Product>>('/products?limit=50', abort.signal),
    ]).then(([targets, messages, products]) => {
      if (abort.signal.aborted) return
      setAlerts(targets.items); setAlertCursor(targets.nextCursor); setNotifications(messages.items); setNotificationCursor(messages.nextCursor)
      setNames(Object.fromEntries(products.items.map(product => [product.id, product.title])))
    }).catch(error => { if (!abort.signal.aborted) setError(errorMessage(error)) }).finally(() => { if (!abort.signal.aborted) setLoading(false) })
    return () => abort.abort()
  }, [user?.uid, ready, retry])
  async function action(task: () => Promise<void>) {
    if (busy || !user) return
    const uid = user.uid
    setBusy(true); setError(''); setNotice('')
    try { await task() } catch (error) { if (uid === auth?.currentUser?.uid) setError(errorMessage(error)) } finally { setBusy(false) }
  }
  async function evaluate() {
    const uid = user?.uid
    let cursor = evaluationCursor, count = 0
    for (let page = 0; page < 10; page++) {
      const result = await request<Single<Evaluation>>(`/me/alerts/evaluate?${new URLSearchParams({ cursor, limit: '50' })}`, undefined, 'POST')
      if (uid !== auth?.currentUser?.uid) return
      count += result.data.notified
      if (!result.data.nextCursor) { setEvaluationCursor(''); setNotice(`가격 확인 완료 · 새 알림 ${count}개`); setRetry(value => value + 1); return }
      cursor = result.data.nextCursor
    }
    setEvaluationCursor(cursor); setNotice('일부 설정을 확인했습니다. 가격 다시 확인을 눌러 이어서 확인해 주세요.'); setRetry(value => value + 1)
  }
  async function more(kind: 'alerts' | 'notifications') {
    const uid = user?.uid, cursor = kind === 'alerts' ? alertCursor : notificationCursor
    if (!cursor) return
    if (kind === 'alerts') {
      const page = await request<Listing<PriceAlert>>(`/me/alerts?${new URLSearchParams({ cursor })}`)
      if (uid === auth?.currentUser?.uid) { setAlerts(previous => [...previous, ...page.items]); setAlertCursor(page.nextCursor) }
    } else {
      const page = await request<Listing<PriceNotification>>(`/me/notifications?${new URLSearchParams({ cursor })}`)
      if (uid === auth?.currentUser?.uid) { setNotifications(previous => [...previous, ...page.items]); setNotificationCursor(page.nextCursor) }
    }
  }
  return <section className="space-y-5"><div className={panel}><div className="flex flex-wrap items-center justify-between gap-3"><div><p className="text-sm font-extrabold text-[var(--mint)]">PRICE ALERT</p><h1 className="mt-2 text-2xl font-black">가격 알림함</h1></div>{user && <button disabled={busy || loading} onClick={() => void action(evaluate)} className={button}>{busy ? '처리 중…' : '가격 다시 확인'}</button>}</div><p className="mt-3 text-xs leading-5 text-[var(--muted)]">현재 더미 가격으로 생성한 인앱 알림입니다. 새 결과는 가격 다시 확인 또는 화면 새로고침으로 확인하세요.</p>
    {!ready ? <p role="status" className="mt-4">로그인 확인 중…</p> : !user ? <button onClick={onLogin} className={`${button} mt-4`}>로그인하고 알림 확인</button> : <>
      {loading && <p role="status" className="mt-4 text-sm">알림 불러오는 중…</p>}
      {!loading && !notifications.length && !error && <p className="mt-5 text-sm text-[var(--muted)]">아직 가격 알림이 없습니다.</p>}
      {notifications.map(item => <div key={item.id} className={`mt-4 rounded-2xl border p-4 ${item.readAt ? 'border-[var(--line)]' : 'border-[var(--primary)] bg-[var(--primary-glow)]'}`}><button onClick={() => onProduct(item.productId)} className="text-left text-sm font-bold">{names[item.productId] || item.productId}</button><p className="mt-2 text-sm">관측가 {money(item.observedPrice)} · 목표가 {money(item.targetPrice)}</p><p className="mt-2 text-[11px] text-[var(--muted)]">더미 관측 {new Date(item.observedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</p><div className="mt-3 flex items-center justify-between text-xs text-[var(--muted)]"><span>{item.readAt ? '읽음' : '새 알림'} · {item.createdAt ? new Date(item.createdAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : ''}</span>{!item.readAt && <button disabled={busy} className={button} onClick={() => void action(async () => { const uid = user.uid; await request(`/me/notifications/${encodeURIComponent(item.id)}`, undefined, 'PATCH', { read: true }); if (uid === auth?.currentUser?.uid) setNotifications(previous => previous.map(row => row.id === item.id ? { ...row, readAt: new Date().toISOString() } : row)) })}>읽음 처리</button>}</div></div>)}
      {notificationCursor && <button disabled={busy || loading} onClick={() => void action(() => more('notifications'))} className={`${button} mt-4`}>이전 알림 더 보기</button>}
    </>}
    {error && <p role="alert" className="mt-4 text-sm text-rose-500">{error}<button onClick={() => setRetry(value => value + 1)} className="ml-2 underline">다시 조회</button></p>}{notice && <p role="status" className="mt-4 text-sm text-[var(--muted)]">{notice}</p>}
    </div>{user && <div className={panel}><h2 className="font-black">목표가 설정</h2><p className="mt-2 text-xs text-[var(--muted)]">상품 상세에서 목표가를 등록·변경할 수 있습니다.</p>{!loading && !alerts.length && <p className="mt-4 text-sm text-[var(--muted)]">등록한 설정이 없습니다.</p>}{alerts.map(item => <div key={item.id} className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-[var(--line)] p-4"><button onClick={() => onProduct(item.productId)} className="text-left"><p className="text-sm font-bold">{names[item.productId] || item.productId}</p><p className="mt-1 text-xs text-[var(--muted)]">{money(item.targetPrice)} · {item.enabled ? '켜짐' : '꺼짐'}</p></button><div className="flex gap-2"><button disabled={busy || loading} className={button} onClick={() => void action(async () => { const uid = user.uid; await request(`/me/alerts/${encodeURIComponent(item.id)}`, undefined, 'PATCH', { enabled: !item.enabled }); if (uid === auth?.currentUser?.uid) setRetry(value => value + 1) })}>{item.enabled ? '끄기' : '켜기'}</button><button disabled={busy || loading} className={button} onClick={() => void action(async () => { const uid = user.uid; await request(`/me/alerts/${encodeURIComponent(item.id)}`, undefined, 'DELETE'); if (uid === auth?.currentUser?.uid) setRetry(value => value + 1) })}>삭제</button></div></div>)}{alertCursor && <button disabled={busy || loading} className={`${button} mt-4`} onClick={() => void action(() => more('alerts'))}>설정 더 보기</button>}</div>}</section>
}

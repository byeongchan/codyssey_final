import { useEffect, useRef, useState, type FormEvent } from 'react'
import { request, type Single } from './api'
import { auth } from './firebase'
import { useSession } from './session'
import { Icon } from './Icons'

type Turn = { requestId: string; question: string; answer: string; createdAt: string; referenceTime: string }
type Conversation = { productId: string; turns: Turn[] }

export default function Chat({ productId, onLogin }: { productId: string; onLogin: () => void }) {
  const { user, ready } = useSession()
  const [turns, setTurns] = useState<Turn[]>([])
  const [message, setMessage] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const pending = useRef<{ requestId: string; message: string } | null>(null)
  const log = useRef<HTMLDivElement>(null)
  const path = `/me/conversations/${encodeURIComponent(productId)}`
  useEffect(() => { if (log.current) log.current.scrollTop = log.current.scrollHeight }, [turns])
  useEffect(() => {
    const abort = new AbortController()
    setTurns([]); setLoaded(false); setError(''); pending.current = null
    if (!ready || !user) return () => abort.abort()
    void request<Single<Conversation>>(path, abort.signal)
      .then(result => { if (!abort.signal.aborted) { setTurns(result.data.turns); setLoaded(true) } })
      .catch(error => { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : '대화를 불러오지 못했습니다.') })
    return () => abort.abort()
  }, [path, user?.uid, ready, retry])
  async function send(event: FormEvent) {
    event.preventDefault()
    if (!user) { onLogin(); return }
    const text = message.trim(), uid = user.uid
    if (!text || busy || !loaded) return
    if (pending.current?.message !== text) pending.current = { requestId: crypto.randomUUID(), message: text }
    setBusy(true); setError('')
    try {
      const result = await request<Single<Conversation>>(`${path}/messages`, undefined, 'POST', pending.current)
      if (auth?.currentUser?.uid === uid) { setTurns(result.data.turns); setMessage(''); pending.current = null }
    } catch (error) { if (auth?.currentUser?.uid === uid) setError(error instanceof Error ? error.message : '전송 실패. 같은 질문으로 다시 전송해 주세요.') }
    finally { setBusy(false) }
  }
  async function remove() {
    if (busy) return
    const uid = user?.uid
    setBusy(true); setError('')
    try {
      await request(path, undefined, 'DELETE')
      if (auth?.currentUser?.uid === uid) { setTurns([]); pending.current = null; setConfirmDelete(false) }
    } catch (error) { if (auth?.currentUser?.uid === uid) setError(error instanceof Error ? error.message : '대화 삭제 실패') }
    finally { setBusy(false) }
  }
  return <section className="mt-6 border-t border-[var(--line)] pt-5" aria-label="상품 AI 구매 비서 대화">
    <h3 className="flex items-center gap-2 text-sm font-black"><Icon name="sparkles" size={16}/>상품에 대해 물어보세요</h3>
    <p className="mt-2 text-xs leading-5 text-[var(--muted)]">더미 가격 근거로 답하는 예시 채팅입니다. Gemini 호출 없음 · 로그인한 본인만 대화를 확인할 수 있어요.</p>
    {!ready ? <p role="status" className="mt-4 text-xs">로그인 확인 중…</p> : !user ? <button onClick={onLogin} className="mt-4 rounded-xl bg-[var(--primary)] px-4 py-2 text-xs font-bold text-white">로그인하고 질문하기</button> : <>
      {!loaded && !error && <p role="status" className="mt-4 text-xs">저장된 대화 불러오는 중…</p>}
      <div ref={log} role="log" aria-label="저장된 질문과 답변" className="mt-4 max-h-96 space-y-4 overflow-y-auto">
        {turns.map(turn => <div key={turn.requestId} className="space-y-2">
          <p className="ml-5 whitespace-pre-wrap break-words rounded-2xl bg-[var(--primary)] p-3 text-xs leading-6 text-white"><span className="font-bold">나: </span>{turn.question}</p>
          <div className="mr-3 rounded-2xl border border-[var(--line)] bg-[var(--surface-2)] p-3"><p className="whitespace-pre-wrap break-words text-xs leading-6">{turn.answer}</p><p className="mt-2 text-[10px] text-[var(--subtle)]">더미 데이터 기준 {new Date(turn.referenceTime).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</p></div>
        </div>)}
      </div>
      {loaded && !turns.length && <p className="mt-3 text-xs text-[var(--muted)]">“지금 구매해도 될까?” 또는 “쿠폰도 포함된 가격이야?”처럼 질문해 보세요.</p>}
      <form onSubmit={send} className="mt-4 space-y-2">
        <label className="sr-only" htmlFor={`chat-${productId}`}>상품 질문</label>
        <textarea id={`chat-${productId}`} value={message} onChange={event => setMessage(event.target.value)} maxLength={1000} rows={3} disabled={busy || !loaded || turns.length >= 20} placeholder="상품 가격과 구매 시점을 질문하세요" className="w-full resize-y rounded-xl border border-[var(--line)] bg-[var(--surface-2)] p-3 text-xs leading-6 outline-none focus:border-[var(--primary)] disabled:opacity-50"/>
        <div className="flex items-center justify-between gap-2"><span className="text-[10px] text-[var(--muted)]">{turns.length}/20회 · {message.length}/1000자</span><button disabled={busy || !loaded || !message.trim() || turns.length >= 20} className="flex items-center gap-1 rounded-xl bg-[var(--primary)] px-4 py-2 text-xs font-bold text-white disabled:opacity-50"><Icon name="send" size={13}/>{busy ? '처리 중…' : '전송'}</button></div>
      </form>
      {turns.length >= 20 && <p className="mt-3 text-xs text-[var(--muted)]">보관 한도에 도달했습니다. 대화를 삭제하면 다시 질문할 수 있어요.</p>}
      {loaded && <div className="mt-3 text-xs">{confirmDelete ? <><p className="mb-2 text-[var(--muted)]">이 상품의 저장된 대화를 모두 삭제할까요?</p><button disabled={busy} onClick={() => void remove()} className="mr-4 text-rose-500">삭제하기</button><button disabled={busy} onClick={() => setConfirmDelete(false)}>취소</button></> : <button disabled={busy} onClick={() => setConfirmDelete(true)} className="text-[var(--muted)] underline">저장된 대화 삭제</button>}</div>}
      {busy && <p role="status" className="mt-3 text-xs">요청을 처리하고 있습니다…</p>}
    </>}
    {error && <div role="alert" className="mt-3 text-xs leading-5 text-rose-500">{error}{!loaded ? <button onClick={() => setRetry(value => value + 1)} className="ml-2 underline">대화 다시 불러오기</button> : <p>전송 실패 시 같은 질문을 다시 전송하면 중복 저장을 방지합니다.</p>}</div>}
  </section>
}

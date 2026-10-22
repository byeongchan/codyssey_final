import { useEffect, useRef, useState } from 'react'
import { request, type Single } from './api'
import { Icon } from './Icons'

type Job = {
  jobId: string; status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED'; reused: boolean; pollAfterSeconds: number; mode: 'READ_ONLY' | 'STORE_SNAPSHOT';
  storage: { idempotencyKey: string; duplicate: boolean; snapshotCount: number; newSnapshots: number; offerCount: number; dailyPriceCount: number } | null;
  result: { productIds: string[]; dataVersion: string; referenceTime: string } | null;
  error: { code: string; message: string; retryable: boolean } | null;
}

export default function Collection({ q, categoryId, onComplete }: { q: string; categoryId: string; onComplete: () => void }) {
  const [job, setJob] = useState<Job | null>(null)
  const [scenario, setScenario] = useState('normal')
  const [mode, setMode] = useState('READ_ONLY')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const abort = useRef<AbortController | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const complete = useRef(onComplete)
  complete.current = onComplete
  useEffect(() => () => { abort.current?.abort(); if (timer.current) clearTimeout(timer.current) }, [])
  async function start() {
    abort.current?.abort(); if (timer.current) clearTimeout(timer.current)
    const controller = new AbortController(); abort.current = controller
    const deadline = Date.now() + 45000
    setBusy(true); setError(''); setJob(null)
    async function poll(id?: string) {
      try {
        const result = id
          ? await request<Single<Job>>(`/collection-jobs/${encodeURIComponent(id)}`, controller.signal)
          : await request<Single<Job>>('/collection-jobs', controller.signal, 'POST', { q, categoryId, scenario, mode })
        if (controller.signal.aborted) return
        const current = result.data; setJob(current)
        if (current.status === 'SUCCEEDED') {
          if (!current.result) throw new Error('더미 재확인 결과가 비어 있습니다.')
          setBusy(false); complete.current()
        } else if (current.status === 'FAILED' || current.status === 'EXPIRED') {
          setBusy(false); setError(current.error?.message || '재사용 기간이 지났습니다. 다시 요청해 주세요.')
        } else {
          if (Date.now() >= deadline) throw new Error('대기 시간이 길어지고 있습니다. 상태 조회를 중단했습니다. 다시 요청하면 진행 중 작업을 재사용합니다.')
          timer.current = setTimeout(() => { void poll(current.jobId) }, Math.max(1, Math.min(5, current.pollAfterSeconds)) * 1000)
        }
      } catch (error) { if (!controller.signal.aborted) { setBusy(false); setError(error instanceof Error ? error.message : '더미 재확인 요청 실패') } }
    }
    await poll()
  }
  return <section className="mb-5 rounded-2xl border border-[var(--line)] bg-[var(--surface)] p-4" aria-label="더미 공급자 재확인">
    <div className="flex flex-wrap items-center justify-between gap-3"><div><h3 className="flex items-center gap-2 text-sm font-bold"><Icon name="clock" size={15}/>더미 공급자 재확인·저장</h3><p className="mt-1 text-xs leading-5 text-[var(--muted)]">현재 검색 조건을 재확인하고, 선택하면 같은 가격 근거를 에뮬레이터에 보관합니다. 실제 수집·관측 시각 갱신은 수행하지 않습니다.</p></div>
      <div className="flex flex-wrap gap-2"><select aria-label="수집 결과 처리 모드" disabled={busy} value={mode} onChange={event => setMode(event.target.value)} className="rounded-xl border border-[var(--line)] bg-[var(--surface-2)] p-2 text-xs"><option value="READ_ONLY">재확인만</option><option value="STORE_SNAPSHOT">스냅샷 저장</option></select><select aria-label="수집기 더미 시나리오" disabled={busy} value={scenario} onChange={event => setScenario(event.target.value)} className="rounded-xl border border-[var(--line)] bg-[var(--surface-2)] p-2 text-xs"><option value="normal">정상 재확인</option><option value="empty">빈 결과 예시</option><option value="error">공급자 장애 예시</option><option value="rate-limit">요청 한도 예시</option><option value="unsupported">미지원 예시</option></select><button disabled={busy || q.length > 100} onClick={() => void start()} className="rounded-xl bg-[var(--primary)] px-4 py-2 text-xs font-bold text-white disabled:opacity-50">{busy ? '처리 중…' : mode === 'STORE_SNAPSHOT' ? '재확인 후 저장' : '재확인 요청'}</button></div>
    </div>
    {q.length > 100 && <p className="mt-2 text-xs text-[var(--muted)]">재확인은 100자 이하 검색어를 지원합니다.</p>}
    {job && <p role="status" className="mt-3 text-xs text-[var(--muted)]">{job.status === 'QUEUED' ? '작업 대기 중' : job.status === 'RUNNING' ? '더미 파일 확인·선택한 모드 처리 중' : job.status === 'SUCCEEDED' ? `더미 재확인 완료 · 해당 시나리오 ${job.result?.productIds.length ?? 0}개 상품` : job.status === 'EXPIRED' ? '작업 재사용 기간 만료' : '더미 재확인·저장 실패'}{job.reused && ' · 기존 작업 재사용'}</p>}
    {job?.storage && <div className="mt-2 text-xs leading-5 text-[var(--muted)]"><p>저장 영수증 · 상품 스냅샷 {job.storage.snapshotCount}개 · 이 작업의 신규 저장 {job.storage.newSnapshots}개 · 판매처 관측 {job.storage.offerCount}개 · 일 대표값 {job.storage.dailyPriceCount}개{job.storage.duplicate && ' · 중복 요청, 추가 저장 없음'}</p><p>더미 가격 근거를 보관했습니다. 저장 스냅샷 조회 모드에서는 선택된 가격으로 목표가 알림을 순차 평가합니다. 저장 완료 후 알림 평가까지 시간이 걸릴 수 있습니다.</p></div>}
    {job?.status === 'SUCCEEDED' && <p className="mt-2 text-xs text-[var(--muted)]">검색 목록을 다시 조회했습니다. 빈 결과 예시는 재확인 결과만 비우며 기존 목록을 변경하지 않습니다.</p>}
    {error && <p role="alert" className="mt-3 text-xs leading-5 text-rose-500">{error}{job?.error && ` (${job.error.code})`} 위 재확인 요청으로 다시 시도할 수 있습니다.</p>}
  </section>
}

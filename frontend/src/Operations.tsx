import { useEffect, useState } from 'react'
import { request, type Single } from './api'
import { useSession } from './session'

type Count = { sampled: number; truncated: boolean }
type Operations = {
  checkedAt: string
  collection: { retained: number; statuses: Record<string, number>; lastFinishedAt: string | null; lastFinishedErrorCode: string | null }
  catalog: { mode: string; selectedCount: number; missingCount: number; loadedAt: string; lastError: string | null }
  storage: { state: 'UNAVAILABLE'; message: string } | {
    state: 'AVAILABLE'; snapshots: Count; receipts: Count
    events: Count & { statuses: Record<string, number>; oldestPendingAt: string | null }
  }
  configuration: { snapshotAlertEventsEnabled: boolean; snapshotAlertEventsActive: boolean; periodicAlertsEnabled: boolean; automaticRetention: boolean }
}
const labels: Record<string, string> = { QUEUED: '대기', RUNNING: '진행 중', SUCCEEDED: '완료', FAILED: '실패', EXPIRED: '재사용 만료', PENDING: '대기', PROCESSING: '평가 중', RETRYING: '재시도 대기', DONE: '완료', UNKNOWN: '알 수 없음' }
const time = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '기록 없음'
const count = (value: Count) => `${value.sampled}${value.truncated ? '개 이상 · 일부 표본' : '개'}`
const panel = 'rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]'
function Statuses({ values }: { values: Record<string, number> }) {
  return <dl className="mt-4 flex flex-wrap gap-3">{Object.entries(values).map(([status, value]) => <div key={status} className="rounded-xl bg-[var(--surface-2)] px-4 py-3"><dt className="text-xs text-[var(--muted)]">{labels[status] || status}</dt><dd className="mt-1 text-xl font-black">{value}</dd></div>)}</dl>
}
export default function OperationsPage({ onLogin }: { onLogin: () => void }) {
  const { user } = useSession()
  const [data, setData] = useState<Operations | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    setData(null); setError('')
    if (!user) return
    const abort = new AbortController()
    setBusy(true)
    void request<Single<Operations>>('/me/operations', abort.signal)
      .then(result => { if (!abort.signal.aborted) setData(result.data) })
      .catch(error => { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : '운영 상태 조회 실패') })
      .finally(() => { if (!abort.signal.aborted) setBusy(false) })
    return () => abort.abort()
  }, [user?.uid, revision])
  if (!user) return <section className={panel}><h1 className="text-xl font-black">로컬 운영 상태</h1><p className="mt-3 text-sm">운영 조회 권한이 있는 계정으로 로그인해 주세요.</p><button onClick={onLogin} className="mt-4 font-bold text-[var(--primary)]">로그인</button></section>
  return <section className="space-y-5">
    <div className={panel}><div className="flex flex-wrap items-center justify-between gap-3"><h1 className="text-xl font-black">로컬 운영 상태</h1><button disabled={busy} onClick={() => setRevision(value => value + 1)} className="rounded-xl bg-[var(--primary)] px-4 py-2 text-sm font-bold text-white disabled:opacity-50">{busy ? '확인 중…' : '상태 다시 확인'}</button></div><p className="mt-3 text-xs leading-5 text-[var(--muted)]">더미 개발 환경 · 수동 조회 · 현재 API 프로세스와 에뮬레이터 기준입니다. 실제 Reef·Gemini 연결 상태는 측정하지 않습니다.</p>{data && <p className="mt-2 text-xs text-[var(--muted)]">조회 시각 {time(data.checkedAt)}</p>}{busy && <p role="status" className="mt-3 text-sm">운영 상태를 불러오고 있습니다…</p>}{error && <p role="alert" className="mt-3 text-sm text-rose-500">{error}</p>}</div>
    {data && <>
      <div className="grid gap-5 md:grid-cols-2">
        <article className={panel}><h2 className="font-black">수집 작업</h2><p className="mt-2 text-xs text-[var(--muted)]">현재 프로세스 보관 {data.collection.retained}개 · 완료/실패 후 1시간 보관 · 재시작 시 초기화</p><Statuses values={data.collection.statuses}/>{data.collection.retained === 0 && <p className="mt-4 text-sm">보관된 작업이 없습니다.</p>}<p className="mt-4 text-xs text-[var(--muted)]">마지막 종료 {time(data.collection.lastFinishedAt)}</p>{data.collection.lastFinishedErrorCode && <p className="mt-2 text-sm text-rose-500">마지막 종료 작업 오류: {data.collection.lastFinishedErrorCode}</p>}</article>
        <article className={panel}><h2 className="font-black">가격 조회 근거</h2><p className="mt-3 text-sm">{data.catalog.mode === 'snapshot' ? `저장 근거 ${data.catalog.selectedCount}개 상품 · 근거 없음 ${data.catalog.missingCount}개 상품` : 'fixture 파일 모드'}</p><p className="mt-2 text-xs text-[var(--muted)]">뷰 불러오기 {time(data.catalog.loadedAt)}</p>{data.catalog.lastError && <p role="alert" className="mt-3 text-sm text-rose-500">{data.catalog.lastError}</p>}</article>
      </div>
      <article className={panel}><h2 className="font-black">더미 스냅샷 저장·알림 이벤트</h2>{data.storage.state === 'UNAVAILABLE' ? <p role="alert" className="mt-3 text-sm text-rose-500">확인 불가 · {data.storage.message}</p> : <><p className="mt-3 text-sm">스냅샷 {count(data.storage.snapshots)} · 영수증 {count(data.storage.receipts)} · 이벤트 {count(data.storage.events)}</p><Statuses values={data.storage.events.statuses}/><p className="mt-4 text-xs leading-5 text-[var(--muted)]">{data.storage.events.truncated ? '이벤트 상태별 건수와 가장 오래된 대기 시각은 조회한 일부 표본 기준입니다.' : '이벤트 상태별 건수는 현재 저장된 기록 기준입니다.'} 완료 이벤트는 정리 후 감소할 수 있으며 누적 알림 발송 수가 아닙니다.</p><p className="mt-2 text-xs text-[var(--muted)]">표본 내 가장 오래된 미완료 이벤트 생성 {time(data.storage.events.oldestPendingAt)}</p></>}</article>
      <article className={panel}><h2 className="font-black">처리 설정</h2><ul className="mt-3 space-y-2 text-sm"><li>저장 완료 알림 평가: {data.configuration.snapshotAlertEventsActive ? '활성 설정' : data.configuration.snapshotAlertEventsEnabled ? '파일 조회 모드로 대기' : '비활성 설정'}</li><li>정기 목표가 점검: {data.configuration.periodicAlertsEnabled ? '활성 설정' : '비활성 설정'}</li><li>자동 정리: 사용하지 않음</li></ul><p className="mt-3 text-xs leading-5 text-[var(--muted)]">설정 표시는 워커의 정상 실행을 보장하지 않습니다. 이벤트 변화와 서버 로그를 함께 확인하세요. 데이터 정리는 별도 내부 API에서 계획을 확인한 후 수행합니다.</p></article>
    </>}
  </section>
}

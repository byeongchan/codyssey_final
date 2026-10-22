import { useEffect, useState } from 'react'
import { request, type Single } from './api'

type Source = { mode: 'fixture' | 'snapshot'; dataVersion: string; loadedAt: string; selectedCount: number; missingCount: number; lastError: string | null }

export default function CatalogSource({ revision, onRefresh }: { revision: number; onRefresh: () => void }) {
  const [source, setSource] = useState<Source | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    void request<Single<Source>>('/catalog-source', abort.signal)
      .then(result => { if (!abort.signal.aborted) { setSource(result.data); setError('') } })
      .catch(error => { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : '조회 근거 확인 실패') })
    return () => abort.abort()
  }, [revision, retry])
  async function refresh() {
    if (busy) return
    setBusy(true); setError('')
    try { const result = await request<Single<Source>>('/catalog-source/refresh', undefined, 'POST'); setSource(result.data); onRefresh() }
    catch (error) { setError(error instanceof Error ? error.message : '저장 근거 갱신 실패') }
    finally { setBusy(false) }
  }
  return <div className="mb-4 rounded-2xl border border-[var(--line)] bg-[var(--surface)] px-4 py-3 text-xs leading-5 text-[var(--muted)]">
    {source && <><div className="flex flex-wrap items-center justify-between gap-2"><p>더미 가격 근거 · {source.mode === 'snapshot' ? `저장 스냅샷 ${source.selectedCount}개 상품 · 근거 없는 상품 ${source.missingCount}개` : 'fixture 파일'}</p>{source.mode === 'snapshot' && <button disabled={busy} onClick={() => void refresh()} className="font-bold text-[var(--primary)] disabled:opacity-50">{busy ? '불러오는 중…' : '저장 근거 다시 불러오기'}</button>}</div>
      {source.mode === 'snapshot' && <p className="mt-1">마지막 불러오기 {new Date(source.loadedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} · 보관 시각 대신 관측 시각으로 최신 근거를 선택합니다.</p>}
      {source.lastError && <p role="alert" className="mt-2 text-rose-500">{source.lastError}</p>}
    </>}
    {error && <p role="alert" className="text-rose-500">{error}<button onClick={() => setRetry(value => value + 1)} className="ml-2 underline">근거 상태 다시 확인</button></p>}
  </div>
}

import { request, type Single } from './api'

export class CollectionPending extends Error {}
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new CollectionPending('수집 작업이 진행 중입니다. 잠시 후 다시 조회하세요.')); return }
    const abort = () => { clearTimeout(timer); reject(new CollectionPending('수집 작업이 진행 중입니다. 잠시 후 다시 조회하세요.')) }
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, ms)
    signal.addEventListener('abort', abort, { once: true })
  })
}
/** Polling only reads Firestore job state; it never creates another collection job. */
export async function collectedRequest<T>(path: string, signal: AbortSignal, body: unknown, progress?: (message: string) => void): Promise<Single<T>> {
  const bounded = AbortSignal.any([signal, AbortSignal.timeout(45000)])
  let response = await request<Single<T | null>>(path, bounded, 'POST', body)
  while (response.data === null) {
    const id = response.meta.collectionJobId
    if (!id) throw new Error('수집 작업 상태를 확인하지 못했습니다.')
    progress?.(response.meta.jobStatus === 'RETRY_WAIT' ? '요청 제한으로 수집 작업이 대기 중입니다.' : '저장 데이터가 없어 수집 중입니다. 같은 요청은 하나의 작업으로 처리합니다.')
    if (response.meta.jobStatus === 'RETRY_WAIT') throw new CollectionPending('수집 작업이 재시도를 기다리고 있습니다. 약 1분 후 다시 조회하세요.')
    await pause(Math.max(1, Math.min(5, response.meta.pollAfterSeconds ?? 2)) * 1000, bounded)
    const prefix = path.startsWith('/me/') ? '/me/danawa/jobs/' : '/danawa/jobs/'
    try { response = await request<Single<T | null>>(`${prefix}${encodeURIComponent(id)}`, bounded) }
    catch (error) {
      if (bounded.aborted && !signal.aborted) throw new CollectionPending('수집 작업이 진행 중입니다. 잠시 후 다시 조회하세요.')
      throw error
    }
  }
  return response as Single<T>
}

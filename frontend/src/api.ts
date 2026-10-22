import { accessToken, auth } from './firebase'

export type Meta = { source: string; synthetic: boolean; referenceTime: string; updatedAt: string | null; stale: boolean; requestId: string; catalogVersion?: string; collectionJobId?: string; jobStatus?: string; collectionState?: string; pollAfterSeconds?: number; cacheHit?: boolean; cacheExpiresAt?: string; errorCode?: string }
export type WishlistEntry = { productId: string; createdAt: string | null }
export type WishlistState = { productId: string; saved: boolean }
export type ApiErrorDetail = { code: string; message: string; requestId: string; retryable: boolean }
export class ApiRequestError extends Error {
  constructor(public status: number, public detail: ApiErrorDetail) {
    super(detail.message)
    this.name = 'ApiRequestError'
  }
}
export type Single<T> = { data: T; meta: Meta }
export type Listing<T> = { items: T[]; nextCursor: string | null; meta: Meta }
export type Category = { id: string; name: string }
export type Product = { id: string; title: string; description: string; categoryId: string; optionKey: string; currentPrice: number | null; lastKnownPrice: number | null; priceStale: boolean; updatedAt: string | null; catalogSource?: 'snapshot'; snapshotState?: 'SELECTED' | 'MISSING'; snapshotId?: string | null }
export type Offer = { id: string; store: string; productPrice: number; shippingFee: number | null; comparablePrice: number | null; availability: string; observedAt: string; stale: boolean; expiresAt: string; conditionalBenefits: {label: string; amount: number}[] }
export type Prices = { points: { date: string; comparablePrice: number }[]; observedDays: number; averagePrice: number; coverageStart: string | null; coverageEnd: string | null; stale: boolean }
export type Analysis = { verdict: 'BUY' | 'WAIT' | 'NEUTRAL' | 'INSUFFICIENT_DATA'; reasons: string[]; risks: string[]; generatedAt: string; expiresAt: string; stale: boolean; model: string; observedDays: number; ruleVersion: string; explanationSource: 'FIXTURE_EXPLANATION' | 'GEMINI_EXPLANATION' | 'SERVER_FALLBACK' | 'SERVER_RULE_ONLY' }
export type AnalysisJob = {
  jobId: string; productId: string; dataVersion: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';
  pollAfterSeconds: number; cacheHit: boolean; reused: boolean;
  analysis: Analysis | null; error: { code: string; message: string; retryable: boolean } | null;
}

export async function request<T>(path: string, signal?: AbortSignal, method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' = 'GET', body?: unknown): Promise<T> {
  const protectedPath = path === '/me' || path.startsWith('/me/')
    || /^\/products\/[^/]+\/analysis(?:-jobs)?$/.test(path) || /^\/analysis-jobs\/[^/]+$/.test(path)
  const initialOwner = auth?.currentUser?.uid
  if (protectedPath) await auth?.authStateReady()
  const owner = initialOwner ?? auth?.currentUser?.uid
  const url = `${import.meta.env.VITE_API_BASE_URL || '/api/v1'}${path}`
  const timeout = path.startsWith('/me/danawa/explanation/') ? 35000 : path === '/me/danawa/observations' ? 22000 : 12000
  if (protectedPath && new URL(url, location.origin).origin !== location.origin)
    throw new Error('로그인 요청은 같은 사이트의 API로만 전송할 수 있습니다.')
  const send = async (refresh: boolean) => {
    const headers: Record<string, string> = {}
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (protectedPath) {
      headers.Authorization = `Bearer ${await accessToken(refresh)}`
      if (owner !== auth?.currentUser?.uid) throw new Error('계정이 변경되었습니다. 다시 시도해 주세요.')
    }
    return fetch(url, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(timeout)]) : AbortSignal.timeout(timeout),
    })
  }
  let response = await send(false)
  if (protectedPath && response.status === 401 && !signal?.aborted) response = await send(true)
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiRequestError(response.status, {
      code: body?.error?.code || `HTTP_${response.status}`,
      message: body?.error?.message || `요청 실패 (${response.status})`,
      requestId: body?.error?.requestId || response.headers.get('X-Request-Id') || '',
      retryable: body?.error?.retryable ?? (response.status === 429 || response.status >= 500),
    })
  }
  return response.json()
}

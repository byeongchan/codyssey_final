import type { UsedAgentContext } from './UsedAgentChat'

type Provider = 'joongna' | 'karrot'
type Saved = { context: UsedAgentContext; at: number }
const key = `joopjoop:used-agent-context:v1:${import.meta.env.VITE_API_BASE_URL || '/api/v1'}`
const ttl = 86400000
let memory: Record<string, Saved> = {}
const providerValid = (value: unknown) => value === 'joongna' || value === 'karrot'
function valid(value: unknown): value is Saved {
  const saved = value as Saved | null
  return (
    !!saved &&
    Number.isFinite(saved.at) &&
    saved.at <= Date.now() &&
    Date.now() - saved.at < ttl &&
    Array.isArray(saved.context?.searches) &&
    saved.context.searches.length > 0 &&
    saved.context.searches.length <= 3 &&
    saved.context.searches.every(
      (row) =>
        row &&
        providerValid(row.provider) &&
        typeof row.id === 'string' &&
        /^[a-f0-9]{64}$/.test(row.id),
    ) &&
    Array.isArray(saved.context?.candidates) &&
    saved.context.candidates.length <= 1200 &&
    saved.context.candidates.every(
      (row) =>
        row &&
        providerValid(row.provider) &&
        typeof row.productId === 'string' &&
        /^[A-Za-z0-9_-]{1,100}$/.test(row.productId),
    )
  )
}
function read() {
  try {
    const raw = localStorage.getItem(key)
    if (raw && raw.length <= 5000000) {
      const parsed = JSON.parse(raw)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed))
        memory = { ...parsed, ...memory }
    }
  } catch {
    /* Retain the in-memory fallback if browser storage is unavailable. */
  }
  memory = Object.fromEntries(
    Object.entries(memory)
      .filter(([, saved]) => valid(saved))
      .sort((a, b) => b[1].at - a[1].at)
      .slice(0, 30),
  )
  return memory
}
export function saveUsedAgentContext(
  provider: Provider,
  productId: string,
  context: UsedAgentContext,
  at = Date.now(),
) {
  const saved = { context, at }
  if (!valid(saved)) return
  const entries = read()
  memory = { ...entries, [`${provider}:${productId}`]: saved }
  try {
    localStorage.setItem(key, JSON.stringify(read()))
  } catch {
    /* Keep the current-page fallback. */
  }
}
export function restoreUsedAgentContext(
  provider: Provider,
  productId: string,
): UsedAgentContext | null {
  const saved = read()[`${provider}:${productId}`]
  if (saved) return saved.context
  try {
    // Migrate the previous tab-only format without extending its validity.
    const legacy = JSON.parse(
      sessionStorage.getItem(`used-agent-context:${provider}:${productId}`) ?? 'null',
    )
    if (valid(legacy)) {
      saveUsedAgentContext(provider, productId, legacy.context, legacy.at)
      return legacy.context
    }
  } catch {
    /* No usable search references: detail evidence remains available. */
  }
  return null
}

/** Public search results only: no tokens, account data or AI explanations. */
import { clearProductImages } from './productImageCache'
const storageKey = `prizm:danawa-search:v1:${import.meta.env.VITE_API_BASE_URL || '/api/v1'}`
const ttl = 15 * 60 * 1000
const historyTtl = 30 * 24 * 60 * 60 * 1000
const changed = 'prizm:search-history-changed'
type Entry = { query: string; page: number; categoryId?: string; savedAt: number; value: unknown }
type Recent = { query: string; searchedAt: number }
type Saved = { entries: Entry[]; recent: Recent[] }
let memory: Saved = { entries: [], recent: [] }
let storageUnavailable = false

function validQuery(value: unknown): value is string { return typeof value === 'string' && value.trim().length > 0 && value.length <= 100 }
function read(): Saved {
  let saved: unknown = memory
  try {
    if (!storageUnavailable) {
      const raw = localStorage.getItem(storageKey)
      saved = raw && raw.length <= 2_000_000 ? JSON.parse(raw) : { entries: [], recent: [] }
    }
  } catch { storageUnavailable = true /* Private mode or unavailable storage: keep in memory. */ }
  const data = saved as Partial<Saved> | null, now = Date.now()
  return {
    entries: Array.isArray(data?.entries) ? data.entries.filter(item => item && validQuery(item.query) && Number.isInteger(item.page) && item.page >= 1 && item.page <= 50
      && Number.isFinite(item.savedAt) && item.savedAt <= now && now - item.savedAt < ttl).slice(0, 20) : [],
    recent: Array.isArray(data?.recent) ? data.recent.filter(item => item && validQuery(item.query) && Number.isFinite(item.searchedAt)
      && item.searchedAt <= now && now - item.searchedAt < historyTtl).slice(0, 10) : [],
  }
}
function write(data: Saved) {
  while (data.entries.length && JSON.stringify(data).length > 2_000_000) data.entries.pop()
  memory = data
  try { if (!storageUnavailable) localStorage.setItem(storageKey, JSON.stringify(data)) } catch { storageUnavailable = true }
  window.dispatchEvent(new Event(changed))
}
export function cachedSearch<T>(query: string, page: number, valid: (value: unknown) => value is T, categoryId = ''): { value: T; savedAt: number } | null {
  const entry = read().entries.find(item => item.query === query.trim().replace(/\s+/g, ' ') && item.page === page && (item.categoryId ?? '') === categoryId)
  return entry && valid(entry.value) ? { value: structuredClone(entry.value), savedAt: entry.savedAt } : null
}
export function latestCachedSearch<T>(valid: (value: unknown) => value is T): { query: string; value: T; savedAt: number } | null {
  const entry = read().entries.find(item => item.page === 1 && valid(item.value))
  return entry && valid(entry.value) ? { query: entry.query, value: structuredClone(entry.value), savedAt: entry.savedAt } : null
}
export function saveSearch(query: string, page: number, value: unknown, categoryId = '') {
  const normalized = query.trim().replace(/\s+/g, ' '), data = read()
  const encoded = JSON.stringify(value)
  if (!validQuery(normalized) || !encoded || encoded.length > 200_000) return
  data.entries = [{ query: normalized, page, categoryId, value: structuredClone(value), savedAt: Date.now() }, ...data.entries.filter(item => item.query !== normalized || item.page !== page || (item.categoryId ?? '') !== categoryId)].slice(0, 20)
  write(data)
}
export function rememberSearch(query: string) {
  const normalized = query.trim()
  if (!validQuery(normalized)) return
  const data = read()
  data.recent = [{ query: normalized, searchedAt: Date.now() }, ...data.recent.filter(item => item.query !== normalized)].slice(0, 10)
  write(data)
}
export function recentSearches(): string[] { return read().recent.map(item => item.query) }
export function clearSearches() {
  clearProductImages()
  memory = { entries: [], recent: [] }
  try { localStorage.removeItem(storageKey); storageUnavailable = false } catch { storageUnavailable = true }
  window.dispatchEvent(new Event(changed))
}
export function watchSearches(listener: () => void) {
  window.addEventListener(changed, listener); window.addEventListener('storage', listener)
  return () => { window.removeEventListener(changed, listener); window.removeEventListener('storage', listener) }
}

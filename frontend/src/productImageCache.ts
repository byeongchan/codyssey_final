/** Reuse public product image URLs, without caching detail prices or personal data. */
const key = `prizm:danawa-images:v1:${import.meta.env.VITE_API_BASE_URL || '/api/v1'}`
const changed = 'prizm:product-images-changed'
type Entry = { productId: string; image: string | null; savedAt: number }
export type ProductImages = Record<string, Entry>
let memory: Entry[] = []
let unavailable = false
function safeImage(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 4096) return false
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && !url.port } catch { return false }
}
export function productImages(): ProductImages {
  let saved: unknown = memory
  try { if (!unavailable) { const raw = localStorage.getItem(key); saved = raw && raw.length < 500_000 ? JSON.parse(raw) : [] } }
  catch { unavailable = true }
  const now = Date.now()
  const entries: Entry[] = Array.isArray(saved) ? saved.filter((entry): entry is Entry => entry && typeof entry.productId === 'string'
    && /^[0-9]{1,30}$/.test(entry.productId) && (entry.image === null || safeImage(entry.image)) && Number.isFinite(entry.savedAt)
    && entry.savedAt <= now && now - entry.savedAt < (entry.image === null ? 15 * 60_000 : 7 * 24 * 60 * 60_000)).slice(0, 100) : []
  return Object.fromEntries(entries.map(entry => [entry.productId, entry]))
}
export function rememberProductImage(productId: string, image: string | null | undefined) {
  if (!/^[0-9]{1,30}$/.test(productId) || (image != null && !safeImage(image))) return
  const old = Object.values(productImages()).filter(entry => entry.productId !== productId).sort((a, b) => b.savedAt - a.savedAt)
  memory = [{ productId, image: image ?? null, savedAt: Date.now() }, ...old].slice(0, 100)
  try { if (!unavailable) localStorage.setItem(key, JSON.stringify(memory)) } catch { unavailable = true }
  window.dispatchEvent(new Event(changed))
}
export function clearProductImages() {
  memory = []
  try { localStorage.removeItem(key); unavailable = false } catch { unavailable = true }
  window.dispatchEvent(new Event(changed))
}
export function watchProductImages(listener: () => void) {
  window.addEventListener(changed, listener); window.addEventListener('storage', listener)
  return () => { window.removeEventListener(changed, listener); window.removeEventListener('storage', listener) }
}

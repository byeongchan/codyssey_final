export type RecentProduct = { productId: string; title: string; image: string | null; price: number | null; viewedAt: number }

const key = `prizm:recent-products:v1:${import.meta.env.VITE_API_BASE_URL || '/api/v1'}`
const changed = 'prizm:recent-products-changed'
const retention = 30 * 24 * 60 * 60 * 1000
let memory: RecentProduct[] = []
let unavailable = false

function safeImage(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 4096) return false
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password } catch { return false }
}

export function recentProducts(): RecentProduct[] {
  let value: unknown = memory
  try {
    if (!unavailable) {
      const raw = localStorage.getItem(key)
      value = raw && raw.length <= 50000 ? JSON.parse(raw) : []
    }
  } catch { unavailable = true }
  const now = Date.now()
  const seen = new Set<string>()
  return (Array.isArray(value) ? value : []).filter((item): item is RecentProduct => {
    if (!item || typeof item.productId !== 'string' || !/^\d{1,30}$/.test(item.productId) || seen.has(item.productId)
      || typeof item.title !== 'string' || !item.title.trim() || item.title.length > 1000
      || !Number.isFinite(item.viewedAt) || item.viewedAt > now || now - item.viewedAt >= retention
      || !(item.image === null || safeImage(item.image))
      || !(item.price === null || Number.isSafeInteger(item.price) && item.price > 0)) return false
    seen.add(item.productId)
    return true
  }).sort((a, b) => b.viewedAt - a.viewedAt).slice(0, 5)
}

function save(items: RecentProduct[]) {
  memory = items
  try { if (!unavailable) localStorage.setItem(key, JSON.stringify(items)) } catch { unavailable = true }
  window.dispatchEvent(new Event(changed))
}

export function rememberRecentProduct(product: Omit<RecentProduct, 'viewedAt'>) {
  const item = { ...product, title: product.title.slice(0, 1000), image: safeImage(product.image) ? product.image : null, viewedAt: Date.now() }
  save([item, ...recentProducts().filter(previous => previous.productId !== product.productId)].slice(0, 5))
}

export function clearRecentProducts() { save([]) }

export function watchRecentProducts(listener: () => void) {
  const storage = (event: StorageEvent) => { if (event.key === key || event.key === null) listener() }
  window.addEventListener(changed, listener)
  window.addEventListener('storage', storage)
  return () => { window.removeEventListener(changed, listener); window.removeEventListener('storage', storage) }
}

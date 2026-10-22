import { mapSearch, mapProduct, mapOffers, mapPriceHistory } from './danawa-mapping.mjs';
/** Opt-in, read-only paid upstream. No fixture fallback or guessed shipping/stock. */
export class DanawaProvider {
  constructor({ key, fetcher = fetch, now = () => Date.now() }) {
    if (!key?.trim()) throw new Error('Set REEF_API_KEY');
    this.key = key; this.fetcher = fetcher; this.now = now;
    this.cache = new Map(); this.pending = new Map(); this.window = now(); this.used = 0;
    this.stats = { requests: 0, cacheHits: 0, merged: 0, upstreamRequests: 0, successes: 0, failures: 0, rateLimited: 0, byAction: { search: 0, product: 0, offers: 0, price_history: 0 } };
    this.durations = [];
  }
  async read(action, input) {
    this.stats.requests++;
    if (!['search', 'product', 'offers', 'price_history'].includes(action)) throw failure(400);
    let body;
    if (action === 'search') {
      if (typeof input.query !== 'string' || !input.query.trim() || input.query.trim().length > 100) throw failure(400);
      body = { query: input.query.trim(), scope: 'catalog', sort: 'popular', page: page(input.page, 50) };
    } else {
      if (typeof input.productId !== 'string' || !/^[0-9]{1,30}$/.test(input.productId)) throw failure(400);
      body = { product_id: input.productId };
      if (action === 'offers') body.page = page(input.page, 20);
    }
    const cacheKey = JSON.stringify([action, body]), saved = this.cache.get(cacheKey);
    if (saved && this.now() - saved.savedAt < 60000) {
      this.stats.cacheHits++;
      this.cache.delete(cacheKey); this.cache.set(cacheKey, saved);
      return structuredClone({ ...saved.value, localCacheHit: true });
    }
    if (saved) this.cache.delete(cacheKey);
    if (this.pending.has(cacheKey)) { this.stats.merged++; return structuredClone(await this.pending.get(cacheKey)); }
    if (this.pending.size >= 4) { this.stats.rateLimited++; throw failure(429); }
    if (this.now() - this.window >= 60000 || this.now() < this.window) { this.window = this.now(); this.used = 0; }
    if (this.used >= 20) { this.stats.rateLimited++; throw failure(429); }
    this.used++;
    const task = this.fetchResult(action, body);
    this.pending.set(cacheKey, task);
    try {
      const value = await task;
      this.cache.set(cacheKey, { savedAt: this.now(), value });
      while (this.cache.size > 64) this.cache.delete(this.cache.keys().next().value);
      return structuredClone(value);
    } finally { this.pending.delete(cacheKey); }
  }
  async fetchResult(action, body) {
    const started = this.now(); this.stats.upstreamRequests++; this.stats.byAction[action]++;
    const signal = AbortSignal.timeout(8000);
    try {
      const response = await this.fetcher(`https://api.reefapi.com/danawa/v1/${action}`, {
        method: 'POST', redirect: 'error', signal,
        headers: { 'x-api-key': this.key, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      if (!response.ok) throw failure(response.status === 429 ? 429 : 503);
      let size = 0; const chunks = [];
      if (!response.body) throw failure(502);
      const reader = response.body.getReader();
      try {
        for (;;) {
          const { done, value } = await reader.read(); if (done) break;
          size += value.length;
          if (size > 1024 * 1024) { await reader.cancel(); throw failure(502); }
          chunks.push(Buffer.from(value));
        }
      } finally { reader.releaseLock(); }
      const envelope = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      const meta = envelope.meta;
      if (envelope.ok !== true || envelope.error != null || !meta || typeof meta !== 'object' || Array.isArray(meta)
          || (meta.api != null && meta.api !== 'danawa') || (meta.endpoint != null && meta.endpoint !== action)
          || (meta.mode != null && meta.mode !== 'live') || !envelope.data || typeof envelope.data !== 'object' || Array.isArray(envelope.data)) throw failure(502);
      let fetchedAt = null;
      if (meta.fetched_at != null) {
        if (typeof meta.fetched_at !== 'string' || !/^\d{4}-\d\d-\d\dT.*(?:Z|[+-]\d\d:\d\d)$/.test(meta.fetched_at)
            || !Number.isFinite(Date.parse(meta.fetched_at)) || Date.parse(meta.fetched_at) > this.now() + 30000) throw failure(502);
        fetchedAt = meta.fetched_at;
      }
      const items = action === 'search' ? mapSearch(envelope.data) : null;
      const product = action === 'product' ? mapProduct(envelope.data, body.product_id) : null;
      const offers = action === 'offers' ? mapOffers(envelope.data, body.product_id) : null;
      const priceHistory = action === 'price_history' ? mapPriceHistory(envelope.data, body.product_id) : null;
      this.stats.successes++;
      return { source: 'reef-danawa', synthetic: false, action, fetchedAt,
        receivedAt: new Date(this.now()).toISOString(), localCacheHit: false, upstreamCacheHit: meta.cache_hit === true,
        page: body.page ?? 1, hasMore: envelope.data.has_more === true || meta.pagination?.has_more === true,
        productId: body.product_id ?? null, items, product, offers,
        ...(action === 'price_history' ? { priceHistory } : {}) };
    } catch (error) { this.stats.failures++; throw failure(error instanceof SyntaxError ? 502 : [429, 502, 503].includes(error.status) ? error.status : 503); }
    finally { this.durations.push(Math.max(0, this.now() - started)); if (this.durations.length > 200) this.durations.shift(); }
  }
  metrics() {
    const sorted = [...this.durations].sort((a, b) => a - b);
    return structuredClone({ ...this.stats, scope: 'current collector process', actualCreditUsage: null,
      upstreamP95Millis: sorted.length ? sorted[Math.ceil(sorted.length * .95) - 1] : null });
  }
}
function page(value = 1, max) { if (!Number.isInteger(value) || value < 1 || value > max) throw failure(400); return value; }
function failure(status) { return Object.assign(new Error('Danawa request failed'), { status }); }

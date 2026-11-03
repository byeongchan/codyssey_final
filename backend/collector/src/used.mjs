import { createHash } from 'node:crypto'
const fail = (status) => Object.assign(new Error('중고 매물 요청·응답을 확인하세요'), { status })
const text = (v, max = 500) => (typeof v === 'string' ? v.slice(0, max) : null)
const validId = (v) => typeof v === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(v)
function link(value, provider, image = false) {
  try {
    const url = new URL(value)
    if (
      url.protocol !== 'https:' ||
      url.username ||
      url.password ||
      url.port ||
      url.href.length > 4096
    )
      return null
    if (
      !image &&
      !(provider === 'karrot' ? /(^|\.)daangn\.com$/ : /(^|\.)joongna\.com$/).test(url.hostname)
    )
      return null
    return url.href
  } catch {
    return null
  }
}
function listing(row, provider, detail = false) {
  if (!row || typeof row !== 'object' || (row.currency != null && row.currency !== 'KRW'))
    throw fail(502)
  const id = String(row.listing_id ?? '')
  if (!validId(id) || !text(row.title)?.trim()) throw fail(502)
  const seller = row.seller && typeof row.seller === 'object' ? row.seller : {}
  return {
    productId: id,
    provider,
    title: text(row.title),
    url: link(row.url, provider),
    image: link(row.image ?? row.images?.[0], provider, true),
    price: Number.isSafeInteger(row.price) && row.price >= 0 ? row.price : null,
    status:
      provider === 'karrot' && ['on_sale', 'reserved', 'sold'].includes(row.status)
        ? row.status
        : null,
    regionName: text(row.region_name ?? row.region?.neighbourhood ?? row.region?.name, 200),
    createdAt: text(row.created_at, 100),
    bumpedAt: text(row.bumped_at, 100),
    ...(detail
      ? {
          description: text(row.description, 20000),
          descriptionTruncated:
            typeof row.description === 'string' && row.description.length > 20000,
          images: (Array.isArray(row.images) ? row.images : [])
            .slice(0, 30)
            .map((v) => link(v, provider, true))
            .filter(Boolean),
          sellerName: text(seller.nickname ?? seller.name, 100),
          mannerScore:
            typeof seller.manner_score === 'number' && Number.isFinite(seller.manner_score)
              ? seller.manner_score
              : null,
          sellerKey: /^[A-Za-z0-9_-]{1,100}$/.test(String(seller.id ?? ''))
            ? createHash('sha256').update(`${provider}:${seller.id}`).digest('hex')
            : null,
          categoryName: text(row.category?.name, 200),
          deliveryOptions:
            typeof row.delivery_options === 'string' ? text(row.delivery_options, 1000) : null,
        }
      : {}),
  }
}
export class UsedProvider {
  constructor({ key, fetcher = fetch, now = Date.now } = {}) {
    if (!key || key.length > 1024) throw new Error('REEF_API_KEY is required')
    this.key = key
    this.fetcher = fetcher
    this.now = now
    this.cache = new Map()
    this.pending = new Map()
    this.window = now()
    this.used = 0
  }
  async read(provider, action, input) {
    if (
      !['joongna', 'karrot'].includes(provider) ||
      !['search', 'detail', 'regions', 'categories'].includes(action) ||
      (provider === 'joongna' && !['search', 'detail'].includes(action))
    )
      throw fail(400)
    const allowed =
      action === 'search'
        ? provider === 'joongna'
          ? ['query', 'page']
          : ['query', 'regionId', 'categoryId', 'onSaleOnly']
        : action === 'detail'
          ? ['productId']
          : action === 'regions'
            ? ['regionId', 'query']
            : []
    if (
      !input ||
      typeof input !== 'object' ||
      Array.isArray(input) ||
      Object.keys(input).some((key) => !allowed.includes(key))
    )
      throw fail(400)
    let body = {},
      upstream = provider === 'joongna' && action === 'detail' ? 'listing_detail' : action
    if (action === 'search') {
      if (typeof input.query !== 'string' || !input.query.trim() || input.query.length > 100)
        throw fail(400)
      body.query = input.query.trim().replace(/\s+/g, ' ')
      if (provider === 'joongna') {
        if (!Number.isInteger(input.page ?? 1) || (input.page ?? 1) < 1 || (input.page ?? 1) > 2)
          throw fail(400)
        body.page = input.page ?? 1
        body.limit = 50
      } else {
        if (!/^\d{1,20}$/.test(input.regionId ?? '') || typeof input.regionId !== 'string')
          throw fail(400)
        body.region_id = input.regionId
        if (input.categoryId !== undefined) {
          if (
            !Number.isSafeInteger(input.categoryId) ||
            input.categoryId < 1 ||
            input.categoryId > 10000
          )
            throw fail(400)
          body.category_id = input.categoryId
        }
        if (input.onSaleOnly !== undefined && typeof input.onSaleOnly !== 'boolean') throw fail(400)
        body.on_sale_only = input.onSaleOnly === true
      }
    } else if (action === 'detail') {
      if (!validId(input.productId)) throw fail(400)
      body.listing_id = input.productId
    } else if (action === 'regions') {
      if (input.regionId !== undefined) {
        if (typeof input.regionId !== 'string' || !/^\d{1,20}$/.test(input.regionId))
          throw fail(400)
        body.region_id = input.regionId
      }
      if (input.query !== undefined) {
        if (typeof input.query !== 'string' || input.query.length > 60) throw fail(400)
        if (input.query.trim()) body.query = input.query.trim()
      }
    }
    const key = JSON.stringify([provider, upstream, body]),
      saved = this.cache.get(key)
    if (saved && this.now() - saved.at >= 0 && this.now() - saved.at < 60000)
      return structuredClone({ ...saved.value, localCacheHit: true })
    if (this.pending.has(key)) return structuredClone(await this.pending.get(key))
    if (this.now() - this.window >= 60000 || this.now() < this.window) {
      this.window = this.now()
      this.used = 0
    }
    if (this.used >= 20 || this.pending.size >= 4) throw fail(429)
    this.used++
    const task = this.fetchResult(provider, action, upstream, body)
    this.pending.set(key, task)
    try {
      const value = await task
      this.cache.set(key, { at: this.now(), value })
      while (this.cache.size > 128) this.cache.delete(this.cache.keys().next().value)
      return structuredClone(value)
    } finally {
      this.pending.delete(key)
    }
  }
  async fetchResult(provider, action, upstream, body) {
    try {
      const response = await this.fetcher(`https://api.reefapi.com/${provider}/v1/${upstream}`, {
        method: 'POST',
        redirect: 'error',
        headers: { 'x-api-key': this.key, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(12000),
      })
      if (!response.ok) throw fail([404, 429].includes(response.status) ? response.status : 503)
      if (!response.body) throw fail(502)
      const reader = response.body.getReader(),
        chunks = []
      let bytes = 0
      try {
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          bytes += value.length
          if (bytes > 4 * 1024 * 1024) {
            await reader.cancel()
            throw fail(502)
          }
          chunks.push(Buffer.from(value))
        }
      } finally {
        reader.releaseLock()
      }
      const envelope = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      if (
        envelope.ok !== true ||
        envelope.error != null ||
        !envelope.data ||
        typeof envelope.data !== 'object' ||
        Array.isArray(envelope.data)
      )
        throw fail(envelope.error?.code === 'NOT_FOUND' ? 404 : 502)
      const data = envelope.data,
        meta = envelope.meta ?? {},
        fetchedAt = meta.fetched_at ?? null
      if (
        (meta.api !== undefined && meta.api !== provider) ||
        (meta.mode !== undefined && meta.mode !== 'live') ||
        (meta.endpoint !== undefined && meta.endpoint !== upstream)
      )
        throw fail(502)
      if (
        fetchedAt !== null &&
        (typeof fetchedAt !== 'string' ||
          !/(Z|[+-]\d\d:\d\d)$/.test(fetchedAt) ||
          !Number.isFinite(Date.parse(fetchedAt)) ||
          Date.parse(fetchedAt) > this.now() + 30000)
      )
        throw fail(502)
      const result = {
        source: `reef-${provider}`,
        provider,
        synthetic: false,
        action,
        fetchedAt,
        receivedAt: new Date(this.now()).toISOString(),
        localCacheHit: false,
        hasMore: false,
      }
      if (action === 'search') {
        if (!Array.isArray(data.results)) throw fail(502)
        if (provider === 'karrot') {
          // The search origin must match; individual listing neighbourhoods may differ.
          const returnedRegion = String(data.region?.region_id ?? '')
          if (returnedRegion !== body.region_id) throw fail(502)
          result.regionId = returnedRegion
        }
        result.items = []
        let normalizedBytes = 0
        for (const row of data.results.slice(0, 400)) {
          const normalized = listing(row, provider),
            size = Buffer.byteLength(JSON.stringify(normalized), 'utf8')
          if (normalizedBytes + size > 600000) break
          normalizedBytes += size
          result.items.push(normalized)
        }
        result.truncated = data.results.length > result.items.length
        result.page = body.page ?? 1
        result.hasMore = provider === 'joongna' && body.page < 2 && data.has_more === true
        result.matchScope = text(data.match_scope, 40)
        result.regionName = text(data.region?.full_name ?? data.region?.name, 200)
      } else if (action === 'detail') {
        result.product = listing(provider === 'karrot' ? data.listing : data, provider, true)
        if (result.product.productId !== body.listing_id) throw fail(502)
      } else if (action === 'regions') {
        if (!Array.isArray(data.regions)) throw fail(502)
        result.regions = data.regions
          .slice(0, 500)
          .filter((row) => /^\d{1,20}$/.test(String(row.region_id)))
          .map((row) => ({
            id: String(row.region_id),
            name: [row.province, row.city, row.neighbourhood ?? row.name].filter(Boolean).join(' '),
          }))
      } else {
        if (!Array.isArray(data.categories)) throw fail(502)
        result.categories = data.categories
          .slice(0, 100)
          .filter((row) => Number.isSafeInteger(row.category_id) && row.category_id > 0)
          .map((row) => ({ id: row.category_id, name: text(row.name, 100) }))
      }
      return result
    } catch (error) {
      throw fail([400, 404, 429, 502, 503].includes(error.status) ? error.status : 503)
    }
  }
}

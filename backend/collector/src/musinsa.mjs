const categories = new Set(['001', '002', '003', '100', '103', '004', '017'])
const sorts = new Set(['popular', 'newest', 'discount', 'price_asc', 'price_desc', 'reviews'])
const fail = (status) => Object.assign(new Error('Musinsa request failed'), { status })
const str = (value, max = 1000) => (typeof value === 'string' ? value.slice(0, max).trim() : null)
const amount = (value) =>
  Number.isSafeInteger(value) && value >= 0 && value <= 100_000_000 ? value : null
const price = (value) => (amount(value) > 0 ? amount(value) : null)
const bool = (value) => (typeof value === 'boolean' ? value : null)
const id = (value) => (/^(?:[0-9]{1,30})$/.test(String(value ?? '')) ? String(value) : null)
function photo(value) {
  const source = typeof value === 'string' ? value : (value?.url ?? value?.src)
  try {
    const url = new URL(source)
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null
  } catch {
    return null
  }
}
function rating(value, max = 5) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= max
    ? (value * 5) / max
    : null
}
function item(row) {
  const productId = id(row.product_id),
    title = str(row.title)
  if (!productId || !title) throw fail(502)
  return {
    productId,
    title,
    image: photo(row.image),
    brand: str(row.brand_name) ?? str(row.brand),
    brandSlug: str(row.brand, 80),
    url: `https://www.musinsa.com/products/${productId}`,
    normalPrice: price(row.normal_price),
    salePrice: price(row.sale_price),
    couponPrice: price(row.coupon_price),
    finalPrice: price(row.final_price),
    soldOut: bool(row.is_sold_out),
    discountRate:
      typeof row.final_discount_rate === 'number' &&
      row.final_discount_rate >= 0 &&
      row.final_discount_rate <= 100
        ? row.final_discount_rate
        : null,
    rating: rating(row.review_score_pct, 100),
    reviewCount: amount(row.review_count),
    optionPriceVaries: bool(row.option_price_varies),
  }
}
function list(value, max) {
  if (!Array.isArray(value) || value.length > max) throw fail(502)
  return value
}
function label(value) {
  if (typeof value === 'string') return value.slice(0, 200)
  if (Array.isArray(value)) return value.map(label).filter(Boolean).join(' / ').slice(0, 500)
  if (value && typeof value === 'object')
    return str(value.value) ?? str(value.name) ?? str(value.label) ?? ''
  return ''
}
export function normalizeMusinsa(action, data, productId) {
  if (action === 'search') return { items: list(data.results, 100).map(item) }
  if (action === 'reviews') {
    const rows = list(data.reviews, 20)
    if (rows.some((row) => row.product_id != null && id(row.product_id) !== productId))
      throw fail(502)
    return {
      reviews: rows.map((row) => ({
        reviewId: str(String(row.review_id ?? ''), 100),
        content: str(row.content, 6000) ?? '',
        rating: rating(row.rating),
        createdAt: str(row.created_at, 100),
        chosenOption: label(row.chosen_option),
        images: Array.isArray(row.images) ? row.images.slice(0, 6).map(photo).filter(Boolean) : [],
      })),
      total: amount(data.total),
    }
  }
  const p = data.product
  if (!p || id(p.product_id) !== productId || !str(p.title)) throw fail(502)
  const path = Array.isArray(p.category_path) ? p.category_path : []
  if (!path.some((row) => categories.has(String(row.code).slice(0, 3)))) throw fail(422)
  const prices = p.prices ?? {},
    images = Array.isArray(p.images) ? p.images.slice(0, 20).map(photo).filter(Boolean) : []
  return {
    product: {
      ...item({
        ...p,
        ...prices,
        brand_name: p.brand?.name,
        brand: p.brand?.slug,
        image: images[0],
        review_score_pct: null,
      }),
      images,
      rating: rating(p.rating),
      styleNo: str(p.style_no),
      season: str(p.season),
      categoryPath: path
        .slice(0, 10)
        .map((row) => ({ code: str(String(row.code), 30), name: str(row.name, 100) })),
      variants: Array.isArray(p.options?.variants)
        ? p.options.variants.slice(0, 300).map((row) => ({
            variantId: str(String(row.variant_id ?? row.code ?? ''), 200),
            label: label(row.options),
            inStock: bool(row.in_stock),
            active: bool(row.active),
            remaining: amount(row.remain_quantity),
            priceDelta:
              Number.isSafeInteger(row.price_delta) && Math.abs(row.price_delta) <= 100_000_000
                ? row.price_delta
                : null,
          }))
        : [],
      facts: Array.isArray(p.product_info)
        ? p.product_info
            .slice(0, 60)
            .map((row) => ({ name: str(row.name, 200), value: str(row.value, 2000) }))
        : [],
      sizeChart:
        p.size_chart && typeof p.size_chart === 'object'
          ? {
              unit: str(p.size_chart.unit, 20),
              note: str(p.size_chart.note, 1000),
              sizes: Array.isArray(p.size_chart.sizes)
                ? p.size_chart.sizes.slice(0, 50).map((row) => ({
                    size: str(String(row.size ?? ''), 100),
                    measurements: Object.fromEntries(
                      Object.entries(row.measurements ?? {})
                        .slice(0, 25)
                        .map(([key, value]) => [
                          key.slice(0, 100),
                          value === null ? null : str(String(value), 100),
                        ]),
                    ),
                  }))
                : [],
            }
          : null,
      delivery: {
        expectedArrivalDate: str(p.delivery?.expected_arrival_date, 100),
        expectedReleaseDate: str(p.delivery?.expected_release_date, 100),
        courier: str(p.delivery?.courier, 100),
        overseas: bool(p.delivery?.overseas),
        returnShippingFee: amount(p.delivery?.return_shipping_fee),
      },
    },
  }
}

/** Explicit Musinsa reads only. Cache and limit are independent of the Danawa adapter. */
export class MusinsaProvider {
  constructor({ key, fetcher = fetch, now = () => Date.now() }) {
    if (!key?.trim()) throw new Error('Set REEF_API_KEY')
    this.key = key
    this.fetcher = fetcher
    this.now = now
    this.cache = new Map()
    this.pending = new Map()
    this.window = now()
    this.used = 0
  }
  async read(action, input) {
    if (!['search', 'product', 'reviews'].includes(action) || !input || typeof input !== 'object')
      throw fail(400)
    let upstream = action,
      body
    if (action === 'search') {
      if (
        !categories.has(input.category) ||
        !['all', 'men', 'women'].includes(input.gender ?? 'all') ||
        !sorts.has(input.sort ?? 'popular')
      )
        throw fail(400)
      const query = typeof input.query === 'string' ? input.query.normalize('NFKC').trim() : ''
      if (query.length > 100) throw fail(400)
      upstream = query ? 'search' : 'category'
      body = {
        ...(query ? { query } : {}),
        category: input.category,
        gender: input.gender ?? 'all',
        sort: input.sort ?? 'popular',
        page: input.page ?? 1,
        page_size: 12,
        used: false,
      }
      if (input.brand !== undefined) {
        if (typeof input.brand !== 'string' || !/^[A-Za-z0-9._-]{1,80}$/.test(input.brand))
          throw fail(400)
        body.brand = input.brand
      }
      for (const [key, field] of [
        ['minPrice', 'min_price'],
        ['maxPrice', 'max_price'],
      ])
        if (input[key] !== undefined) {
          if (!Number.isSafeInteger(input[key]) || input[key] < 0 || input[key] > 100_000_000)
            throw fail(400)
          body[field] = input[key]
        }
      if (
        input.minPrice !== undefined &&
        input.maxPrice !== undefined &&
        input.minPrice > input.maxPrice
      )
        throw fail(400)
      if (input.color !== undefined) {
        if (
          !['BLACK', 'WHITE', 'GRAY', 'NAVY', 'BEIGE', 'IVORY', 'KHAKI', 'SKYBLUE'].includes(
            input.color,
          )
        )
          throw fail(400)
        body.color = input.color
      }
      if (input.size !== undefined) {
        if (!['XS', 'S', 'M', 'L', 'XL', 'XXL'].includes(input.size)) throw fail(400)
        body.size = input.size
      }
      if (input.minDiscount !== undefined) {
        if (![10, 20, 30, 40, 50, 60, 70, 80].includes(input.minDiscount)) throw fail(400)
        body.min_discount = input.minDiscount
      }
      for (const [key, field] of [
        ['onSale', 'on_sale'],
        ['coupon', 'coupon'],
        ['exclusive', 'exclusive'],
        ['includeSoldOut', 'include_sold_out'],
      ])
        if (input[key] !== undefined) {
          if (typeof input[key] !== 'boolean') throw fail(400)
          if (input[key]) body[field] = true
        }
    } else {
      if (!id(input.productId)) throw fail(400)
      body = {
        product_id: input.productId,
        ...(action === 'reviews' ? { page: input.page ?? 1, page_size: 20, sort: 'newest' } : {}),
      }
    }
    if (
      body.page !== undefined &&
      (!Number.isInteger(body.page) ||
        body.page < 1 ||
        body.page > (action === 'reviews' ? 5000 : 1000))
    )
      throw fail(400)
    const key = JSON.stringify([upstream, body]),
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
    const task = this.fetchResult(action, upstream, body)
    this.pending.set(key, task)
    try {
      const value = await task
      this.cache.set(key, { at: this.now(), value })
      while (this.cache.size > 64) this.cache.delete(this.cache.keys().next().value)
      return structuredClone(value)
    } finally {
      this.pending.delete(key)
    }
  }
  async fetchResult(action, upstream, body) {
    try {
      const response = await this.fetcher(`https://api.reefapi.com/musinsa/v1/${upstream}`, {
        method: 'POST',
        redirect: 'error',
        headers: { 'x-api-key': this.key, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(12000),
      })
      if (!response.ok)
        throw fail(response.status === 429 ? 429 : response.status === 404 ? 404 : 503)
      if (!response.body) throw fail(502)
      const reader = response.body.getReader(),
        chunks = []
      let bytes = 0
      try {
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          bytes += value.length
          if (bytes > 2 * 1024 * 1024) {
            await reader.cancel()
            throw fail(502)
          }
          chunks.push(Buffer.from(value))
        }
      } finally {
        reader.releaseLock()
      }
      const envelope = JSON.parse(Buffer.concat(chunks).toString('utf8')),
        meta = envelope.meta
      if (envelope.ok !== true || envelope.error != null)
        throw fail(envelope.error?.code === 'NOT_FOUND' ? 404 : 502)
      if (
        !meta ||
        meta.api !== 'musinsa' ||
        meta.endpoint !== upstream ||
        meta.mode !== 'live' ||
        !envelope.data ||
        typeof envelope.data !== 'object' ||
        Array.isArray(envelope.data)
      )
        throw fail(502)
      const fetchedAt = meta.fetched_at ?? null
      if (
        fetchedAt !== null &&
        (typeof fetchedAt !== 'string' ||
          !/(?:Z|[+-]\d\d:\d\d)$/.test(fetchedAt) ||
          !Number.isFinite(Date.parse(fetchedAt)) ||
          Date.parse(fetchedAt) > this.now() + 30000)
      )
        throw fail(502)
      const normalized = normalizeMusinsa(action, envelope.data, String(body.product_id ?? ''))
      return {
        source: 'reef-musinsa',
        synthetic: false,
        action,
        fetchedAt,
        receivedAt: new Date(this.now()).toISOString(),
        localCacheHit: false,
        upstreamCacheHit: meta.cache_hit === true,
        page: body.page ?? 1,
        hasMore: envelope.data.has_more === true || meta.pagination?.has_more === true,
        productId: body.product_id ?? null,
        ...normalized,
      }
    } catch (error) {
      throw fail(
        error instanceof SyntaxError
          ? 502
          : [400, 404, 422, 429, 502, 503].includes(error.status)
            ? error.status
            : 503,
      )
    }
  }
}

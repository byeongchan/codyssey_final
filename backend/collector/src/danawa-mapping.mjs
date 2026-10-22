import { createHash } from 'node:crypto';
/** Field names follow https://reefapi.com/docs/danawa.md; prices are source quotes, not verified stock. */
const invalid = () => { throw Object.assign(new Error('Invalid Danawa schema'), { status: 502 }); };
const text = (value, max = 1000) => typeof value === 'string' && value.length <= max ? value : null;
const money = (value, zero = false, max = 1e12) => Number.isSafeInteger(value) && value >= (zero ? 0 : 1) && value <= max ? value : null;
const id = value => typeof value === 'string' && /^[0-9]{1,30}$/.test(value) ? value : invalid();
const title = value => text(value, 500)?.trim() || invalid();
const count = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
const canonicalUrl = productId => `https://prod.danawa.com/info/?pcode=${productId}`;
/** Historical period minima, kept separate from daily price observations and today's offer. */
export function mapPriceHistory(data, expected) {
  if (!data.product || id(data.product.product_id) !== expected || data.currency !== 'KRW') invalid();
  const date = (value, monthly) => {
    const pattern = monthly ? /^\d{4}-\d{2}$/ : /^\d{4}-\d{2}-\d{2}$/;
    if (typeof value !== 'string' || !pattern.test(value)) invalid();
    const full = monthly ? `${value}-01` : value;
    if (!Number.isFinite(Date.parse(full)) || new Date(full).toISOString().slice(0, 10) !== full) invalid();
    return value;
  };
  const series = (rows, monthly, max) => {
    if (!Array.isArray(rows) || rows.length > max) invalid();
    const points = rows.map(row => {
      if (!row || typeof row !== 'object') invalid();
      const price = row.lowest_price === null ? null : money(row.lowest_price);
      if (row.lowest_price !== null && price === null) invalid();
      return { date: date(row.date, monthly), lowestPrice: price };
    }).sort((a, b) => a.date.localeCompare(b.date));
    if (new Set(points.map(point => point.date)).size !== points.length) invalid();
    return points;
  };
  // Do not promote all_time_lowest to a verified lifetime minimum; the documented window is bounded.
  return { productId: expected, currency: 'KRW', weekly: series(data.weekly, false, 12), monthly: series(data.monthly, true, 24) };
}
function safeUrl(value) {
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && !url.port ? url.href : null; }
  catch { return null; }
}
function imageUrl(value) {
  if (typeof value !== 'string' || value.length > 4096) return null;
  const absolute = value.startsWith('//') ? `https:${value}` : value.replace(/^http:\/\//i, 'https://');
  return safeUrl(absolute);
}
export function mapSearch(data) {
  if (!Array.isArray(data.results) || data.results.length > 100 || typeof data.has_more !== 'boolean') invalid();
  const items = data.results.filter(row => row?.row_type === 'product').map(row => {
    const productId = id(row.product_id);
    if (row.variants != null && (!Array.isArray(row.variants) || row.variants.length > 100)) invalid();
    return { productId, title: title(row.title), maker: text(row.maker), category: text(row.category), image: imageUrl(row.image),
      listedPrice: row.currency === 'KRW' ? money(row.price) : null, url: canonicalUrl(productId),
      variants: (row.variants ?? []).map(variant => ({ productId: id(variant.product_id), label: text(variant.label, 500) ?? '옵션명 미확인',
        listedPrice: row.currency === 'KRW' ? money(variant.price) : null })) };
  });
  if (new Set(items.map(row => row.productId)).size !== items.length) invalid();
  return items;
}
export function mapProduct(data, expected) {
  const product = data.product;
  if (!product || id(product.product_id) !== expected) invalid();
  if (product.variant_product_ids != null && (!Array.isArray(product.variant_product_ids) || product.variant_product_ids.length > 100)) invalid();
  const images = [...new Set([product.image, ...(Array.isArray(product.images) ? product.images.slice(0, 30) : [])].map(imageUrl).filter(Boolean))];
  return { productId: expected, title: title(product.title), maker: text(product.maker), brand: text(product.brand), image: images[0] ?? null, images,
    intro: text(product.intro, 4000), status: text(product.status, 200), url: canonicalUrl(expected),
    lowestPrice: product.currency === 'KRW' ? money(product.lowest_price) : null,
    lowestPriceWithShipping: product.currency === 'KRW' ? money(product.lowest_price_with_shipping, false, 2e12) : null,
    cashLowestPrice: product.currency === 'KRW' ? money(product.cash_lowest_price) : null,
    priceMismatch: product.price_mismatch === true, offerCount: count(product.offer_count),
    rating: typeof product.rating === 'number' && product.rating >= 0 && product.rating <= 5 ? product.rating : null,
    reviewCount: count(product.review_count), variantProductIds: (product.variant_product_ids ?? []).map(id) };
}
export function mapOffers(data, expected) {
  if (!data.product || id(data.product.product_id) !== expected || !Array.isArray(data.offers) || data.offers.length > 1000
      || typeof data.has_more !== 'boolean') invalid();
  return data.offers.map((row, index) => {
    const price = row.currency === 'KRW' ? money(row.price) : null;
    const shipping = row.currency === 'KRW' ? money(row.shipping_cost, true) : null;
    const reported = row.currency === 'KRW' ? money(row.price_with_shipping, false, 2e12) : null;
    const sum = price !== null && shipping !== null ? price + shipping : null;
    const totalMismatch = sum !== null && reported !== null && sum !== reported;
    const mallCode = text(row.mall_code, 200), mallProductId = text(row.mall_product_id, 500);
    const identity = [expected, mallCode, mallProductId, row.payment, text(row.group, 500)];
    if (!mallCode || !mallProductId) identity.push(text(row.mall), text(row.title, 1000), safeUrl(row.url));
    const offerKey = createHash('sha256').update(JSON.stringify(identity)).digest('hex');
    return { rowId: String(index), offerKey, mallCode, mallProductId,
      identityQuality: mallCode && mallProductId ? 'MALL_PRODUCT_ID' : 'FALLBACK_FIELDS',
      mall: title(row.mall), title: text(row.title, 1000), group: text(row.group, 500),
      payment: ['card', 'cash'].includes(row.payment) ? row.payment : 'UNKNOWN',
      price, shippingCost: shipping, shippingText: text(row.shipping_text, 500), reportedTotal: reported,
      quotedTotal: totalMismatch ? null : sum, totalMismatch, availability: 'UNKNOWN',
      couponText: text(row.coupon, 1000), interestFreeText: text(row.interest_free_text, 500), url: safeUrl(row.url) };
  });
}

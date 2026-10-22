import test from 'node:test';
import assert from 'node:assert/strict';
import { DanawaProvider } from '../src/danawa.mjs';
import { mapOffers, mapProduct, mapSearch, mapPriceHistory } from '../src/danawa-mapping.mjs';

const pid = '17788451';
const searchData = () => ({ results: [{ row_type: 'product', product_id: pid, title: '합성 SSD', price: 10000, currency: 'KRW',
  variants: [{ product_id: '17788452', label: '합성 2TB', price: 12000 }] }], has_more: false });
const offer = (changes = {}) => ({ mall: '합성 판매처', title: '합성 SSD 2TB', price: 10000, currency: 'KRW', payment: 'card', shipping_cost: 3000,
  shipping_text: '3,000원', price_with_shipping: 13000, url: 'https://example.test/product', ...changes });
const offersData = rows => ({ product: { product_id: pid }, offers: rows, page: 1, has_more: false });
const envelope = (action, data, meta = {}) => ({ ok: true, data, error: null, meta: { api: 'danawa', endpoint: action, mode: 'live', ...meta } });

test('period histories preserve gaps, sort dates and reject identity/currency/schema errors', () => {
  const data = { product: { product_id: pid }, currency: 'KRW', weekly: [{ date: '2026-10-16', lowest_price: 12000 }, { date: '2026-10-09', lowest_price: null }], monthly: [{ date: '2026-09', lowest_price: 11000 }] };
  const history = mapPriceHistory(data, pid);
  assert.deepEqual(history.weekly, [{ date: '2026-10-09', lowestPrice: null }, { date: '2026-10-16', lowestPrice: 12000 }]);
  assert.deepEqual(history.monthly, [{ date: '2026-09', lowestPrice: 11000 }]);
  assert.throws(() => mapPriceHistory(data, '1'));
  assert.throws(() => mapPriceHistory({ ...data, currency: 'USD' }, pid));
  assert.throws(() => mapPriceHistory({ ...data, weekly: [{ date: '2026-02-30', lowest_price: 1 }] }, pid));
  assert.throws(() => mapPriceHistory({ ...data, monthly: [{ date: '2026-13', lowest_price: 1 }] }, pid));
  assert.throws(() => mapPriceHistory({ ...data, weekly: [{ date: '2026-10-16', lowest_price: '12000' }] }, pid));
  assert.throws(() => mapPriceHistory({ ...data, weekly: [data.weekly[0], data.weekly[0]] }, pid));
  assert.deepEqual(mapPriceHistory({ ...data, weekly: [], monthly: [] }, pid).weekly, []);
});

test('price_history uses the documented fixed endpoint and cached product identity', async () => {
  let calls = 0;
  const provider = new DanawaProvider({ key: 'test', fetcher: async (url, options) => {
    calls++; assert.equal(url, 'https://api.reefapi.com/danawa/v1/price_history');
    assert.deepEqual(JSON.parse(options.body), { product_id: pid });
    return new Response(JSON.stringify(envelope('price_history', { product: { product_id: pid }, currency: 'KRW', weekly: [], monthly: [] })));
  } });
  const first = await provider.read('price_history', { productId: pid });
  assert.equal(first.productId, pid); assert.equal(first.priceHistory.currency, 'KRW');
  assert.equal(first.fetchedAt, null); assert.equal(first.synthetic, false);
  assert.equal((await provider.read('price_history', { productId: pid })).localCacheHit, true);
  assert.equal(calls, 1);
});

test('documented search image and product image gallery are preserved as HTTPS URLs', () => {
  const data = searchData(); data.results[0].image = '//img.danawa.com/example.jpg';
  assert.equal(mapSearch(data)[0].image, 'https://img.danawa.com/example.jpg');
  const product = mapProduct({ product: { product_id: pid, title: '합성 상품', image: 'http://img.danawa.com/main.jpg',
    images: ['https://img.danawa.com/main.jpg', 'https://img.danawa.com/other.jpg', 'javascript:alert(1)', 'data:image/png;base64,invalid'] } }, pid);
  assert.equal(product.image, 'https://img.danawa.com/main.jpg');
  assert.deepEqual(product.images, ['https://img.danawa.com/main.jpg', 'https://img.danawa.com/other.jpg']);
  data.results[0].image = 'javascript:alert(1)'; assert.equal(mapSearch(data)[0].image, null);
  assert.equal(mapProduct({ product: { product_id: pid, title: '합성 상품' } }, pid).image, null);
});

test('Markdown product wrapper, variants and offers map without inventing stock or discounts', () => {
  assert.equal(mapSearch(searchData())[0].variants[0].productId, '17788452');
  assert.equal(mapProduct({ product: { product_id: pid, title: '합성 상품', currency: 'KRW', lowest_price: 10000, cash_lowest_price: 9000 } }, pid).cashLowestPrice, 9000);
  const rows = mapOffers(offersData([offer(), offer({ shipping_cost: null, shipping_text: '배송비 별도', price_with_shipping: 10000 }),
    offer({ payment: 'cash', price: 9000, price_with_shipping: 12000 }), offer({ price_with_shipping: 14000 }), offer({ url: 'javascript:alert(1)' })]), pid);
  assert.equal(rows[0].quotedTotal, 13000); assert.equal(rows[0].availability, 'UNKNOWN');
  assert.equal(rows[1].quotedTotal, null); assert.equal(rows[1].shippingCost, null);
  assert.equal(rows[2].payment, 'cash'); assert.equal(rows[2].quotedTotal, 12000);
  assert.equal(rows[3].totalMismatch, true); assert.equal(rows[3].quotedTotal, null); assert.equal(rows[4].url, null);
  assert.equal(mapOffers(offersData([offer()]), pid)[0].offerKey, rows[0].offerKey);
  assert.notEqual(mapOffers(offersData([offer({ payment: 'cash' })]), pid)[0].offerKey, rows[0].offerKey);
  assert.throws(() => mapProduct({ product: { product_id: '1', title: 'wrong' } }, pid));
  assert.throws(() => mapOffers(offersData([]), '1'));
});

test('source timestamps are optional, cache is bounded by time and requests merge without secrets in output', async () => {
  let now = 0, calls = 0, release;
  const ready = new Promise(resolve => { release = resolve; });
  const provider = new DanawaProvider({ key: 'private-key', now: () => now, fetcher: async (url, options) => {
    calls++; assert.equal(url, 'https://api.reefapi.com/danawa/v1/search');
    assert.equal(options.headers['x-api-key'], 'private-key'); assert.equal(options.redirect, 'error');
    assert.equal(JSON.parse(options.body).scope, 'catalog'); await ready;
    return new Response(JSON.stringify(envelope('search', searchData())));
  } });
  const first = provider.read('search', { query: 'SSD' }); const second = provider.read('search', { query: 'SSD' }); release();
  const [a, b] = await Promise.all([first, second]); assert.equal(calls, 1); assert.equal(a.fetchedAt, null); assert.equal(a.synthetic, false);
  a.items[0].title = 'changed'; assert.equal(b.items[0].title, '합성 SSD');
  const cached = await provider.read('search', { query: 'SSD' }); assert.equal(cached.localCacheHit, true);
  assert.equal(provider.metrics().upstreamRequests, 1); assert.equal(provider.metrics().cacheHits, 1);
  assert.equal(provider.metrics().merged, 1); assert.equal(provider.metrics().actualCreditUsage, null);
  assert.ok(!JSON.stringify(cached).includes('private-key'));
  now = 60000; await provider.read('search', { query: 'SSD' }); assert.equal(calls, 2);
});

test('bad input, request budget, upstream errors and body limits do not retry', async () => {
  let calls = 0;
  const provider = new DanawaProvider({ key: 'test', now: () => 0, fetcher: async () => { calls++; return new Response(JSON.stringify(envelope('search', searchData()))); } });
  await assert.rejects(provider.read('search', { query: '' }), error => error.status === 400); assert.equal(calls, 0);
  for (let i = 0; i < 20; i++) await provider.read('search', { query: `q${i}` });
  await assert.rejects(provider.read('search', { query: 'q21' }), error => error.status === 429); assert.equal(calls, 20);
  const limited = new DanawaProvider({ key: 'test', fetcher: async () => new Response('', { status: 429 }) });
  await assert.rejects(limited.read('product', { productId: pid }), error => error.status === 429);
  const large = new DanawaProvider({ key: 'test', fetcher: async () => new Response('x'.repeat(1024 * 1024 + 1)) });
  await assert.rejects(large.read('product', { productId: pid }), error => error.status === 502);
});

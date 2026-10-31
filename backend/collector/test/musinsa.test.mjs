import test from 'node:test';
import assert from 'node:assert/strict';
import { MusinsaProvider, normalizeMusinsa } from '../src/musinsa.mjs';

test('fashion browsing calls category, separates filter cache keys, and does not expose the key', async () => {
  const calls = [];
  const provider = new MusinsaProvider({ key: 'test-secret', now: () => Date.parse('2026-10-30T00:00:00Z'), fetcher: async (url, options) => {
    calls.push({ url, options });
    return new Response(JSON.stringify({ ok: true, error: null, meta: { api: 'musinsa', endpoint: url.endsWith('/category') ? 'category' : 'search', mode: 'live' }, data: { results: [], has_more: false } }));
  } });
  const input = { category: '001', query: '', gender: 'all', sort: 'popular', page: 1 };
  const first = await provider.read('search', input);
  assert.equal(calls[0].url, 'https://api.reefapi.com/musinsa/v1/category');
  assert.deepEqual(JSON.parse(calls[0].options.body), { category: '001', gender: 'all', sort: 'popular', page: 1, page_size: 12, used: false });
  assert.equal(first.source, 'reef-musinsa'); assert.ok(!JSON.stringify(first).includes('test-secret'));
  assert.equal((await provider.read('search', input)).localCacheHit, true); assert.equal(calls.length, 1);
  await provider.read('search', { ...input, gender: 'women' }); assert.equal(calls.length, 2);
  await provider.read('search', { ...input, query: '맨투맨' }); assert.ok(calls[2].url.endsWith('/search'));
  await assert.rejects(provider.read('search', { ...input, category: '104' }), error => error.status === 400);
  await assert.rejects(provider.read('product', { productId: '../escape' }), error => error.status === 400);
});

test('product normalization rejects non-fashion categories and preserves unknown price and stock', () => {
  const product = { product_id: 123, title: '상의', brand: { name: '브랜드' }, category_path: [{ code: '001005', name: '맨투맨' }],
    prices: { normal_price: 50000, final_price: 30000 }, images: ['javascript:alert(1)', 'https://image.msscdn.net/a.jpg'],
    options: { variants: [{ variant_id: 'm', options: [{ name: '사이즈', value: 'M' }], in_stock: false, remain_quantity: null }] } };
  const result = normalizeMusinsa('product', { product }, '123').product;
  assert.equal(result.finalPrice, 30000); assert.equal(result.couponPrice, null); assert.equal(result.soldOut, null);
  assert.equal(result.images.length, 1); assert.equal(result.variants[0].inStock, false); assert.equal(result.variants[0].remaining, null);
  assert.equal(result.variants[0].label, 'M');
  assert.throws(() => normalizeMusinsa('product', { product: { ...product, category_path: [{ code: '104' }] } }, '123'), error => error.status === 422);
});

test('review adapter retains review content without forwarding reviewer profile data', () => {
  const result = normalizeMusinsa('reviews', { reviews: [{ review_id: 1, content: '착용 후기', rating: 5, reviewer: { nickname: '개인정보', height_cm: 170 }, images: [] }] }, '123');
  assert.equal(result.reviews[0].content, '착용 후기'); assert.equal(result.reviews[0].rating, 5);
  assert.ok(!JSON.stringify(result).includes('개인정보')); assert.ok(!JSON.stringify(result).includes('height_cm'));
});

test('detailed filters reach the upstream and become part of cache identity', async () => {
  const calls = [];
  const provider = new MusinsaProvider({ key: 'test-secret', fetcher: async (url, options) => {
    calls.push(JSON.parse(options.body));
    return new Response(JSON.stringify({ ok: true, error: null, meta: { api: 'musinsa', endpoint: 'category', mode: 'live' }, data: { results: [], has_more: false } }));
  } });
  const input = { category: '001', brand: 'nike', minPrice: 0, maxPrice: 50000, color: 'BLACK', size: 'M', minDiscount: 20, onSale: true, coupon: true, exclusive: true, includeSoldOut: true };
  await provider.read('search', input);
  assert.deepEqual(calls[0], { category: '001', gender: 'all', sort: 'popular', page: 1, page_size: 12, used: false,
    brand: 'nike', min_price: 0, max_price: 50000, color: 'BLACK', size: 'M', min_discount: 20, on_sale: true, coupon: true, exclusive: true, include_sold_out: true });
  await provider.read('search', { ...input, size: 'L' }); assert.equal(calls.length, 2);
  await assert.rejects(provider.read('search', { ...input, minPrice: 60000 }), error => error.status === 400);
  await assert.rejects(provider.read('search', { ...input, size: '260' }), error => error.status === 400);
  await assert.rejects(provider.read('search', { ...input, coupon: 'true' }), error => error.status === 400);
  assert.equal(calls.length, 2);
});

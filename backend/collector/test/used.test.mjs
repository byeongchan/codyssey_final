import test from 'node:test'
import assert from 'node:assert/strict'
import { UsedProvider } from '../src/used.mjs'

test('public seller IDs become provider-scoped pseudonyms, never inferred from names', async () => {
  const provider = new UsedProvider({
    key: 'test-secret',
    fetcher: async (_url, options) => {
      const id = JSON.parse(options.body).listing_id
      return new Response(
        JSON.stringify({
          ok: true,
          data: {
            listing: {
              listing_id: id,
              title: '상품',
              seller:
                id === 'known'
                  ? { id: 'public-seller', nickname: '표시 이름' }
                  : { nickname: '표시 이름' },
            },
          },
        }),
      )
    },
  })
  const known = await provider.read('karrot', 'detail', { productId: 'known' })
  const unknown = await provider.read('karrot', 'detail', { productId: 'unknown' })
  assert.match(known.product.sellerKey, /^[a-f0-9]{64}$/)
  assert.ok(!JSON.stringify(known).includes('public-seller'))
  assert.equal(unknown.product.sellerKey, null)
})

test('same listing ids from different platforms are isolated and result caps never change Joongna stride', async () => {
  const calls = []
  const provider = new UsedProvider({
    key: 'test-secret',
    fetcher: async (url, options) => {
      calls.push({ url, body: JSON.parse(options.body) })
      return new Response(
        JSON.stringify({
          ok: true,
          error: null,
          data: {
            region: { region_id: JSON.parse(options.body).region_id, full_name: '검색 동네' },
            results: [
              {
                listing_id: '123',
                title: '아이폰',
                price: 1000,
                currency: 'KRW',
                url: url.includes('/karrot/')
                  ? 'https://www.daangn.com/kr/buy-sell/123/'
                  : 'https://web.joongna.com/product/123',
                status: 'on_sale',
              },
            ],
            has_more: true,
          },
        }),
      )
    },
  })
  const joongna = await provider.read('joongna', 'search', { query: '아이폰', page: 1 })
  const karrot = await provider.read('karrot', 'search', {
    query: '아이폰',
    regionId: '6035',
    onSaleOnly: true,
  })
  assert.deepEqual(calls[0].body, { query: '아이폰', page: 1, limit: 50 })
  assert.deepEqual(calls[1].body, { query: '아이폰', region_id: '6035', on_sale_only: true })
  assert.equal(joongna.provider, 'joongna')
  assert.equal(karrot.provider, 'karrot')
  assert.equal(joongna.items[0].status, null)
  assert.equal(karrot.items[0].status, 'on_sale')
  assert.equal(karrot.hasMore, false)
  assert.equal(joongna.hasMore, true)
  assert.ok(!JSON.stringify(karrot).includes('test-secret'))
  await provider.read('joongna', 'search', { query: '아이폰', page: 1 })
  assert.equal(calls.length, 2)
  await provider.read('karrot', 'search', { query: '아이폰', regionId: '6036', onSaleOnly: true })
  assert.equal(calls.length, 3)
})

test('invalid requests never call a paid endpoint', async () => {
  let calls = 0
  const provider = new UsedProvider({
    key: 'test-secret',
    fetcher: async () => {
      calls++
      throw new Error()
    },
  })
  for (const [platform, action, input] of [
    ['joongna', 'search', { query: '폰', page: 3 }],
    ['karrot', 'search', { query: '폰' }],
    ['karrot', 'search', { query: '폰', regionId: '6035', onSaleOnly: 'true' }],
    ['karrot', 'detail', { productId: '../escape' }],
    ['joongna', 'regions', {}],
  ])
    await assert.rejects(provider.read(platform, action, input), (error) => error.status === 400)
  assert.equal(calls, 0)
})

test('detail preserves free and unknown prices, strips unsafe URLs, and refuses mismatched ids', async () => {
  const provider = new UsedProvider({
    key: 'test-secret',
    fetcher: async (_url, options) =>
      new Response(
        JSON.stringify({
          ok: true,
          data: {
            listing: {
              listing_id: JSON.parse(options.body).listing_id === 'bad' ? 'other' : 'zero',
              title: '나눔',
              price: 0,
              currency: 'KRW',
              status: 'reserved',
              url: 'javascript:alert(1)',
              images: ['https://example.com/photo.jpg', 'http://example.com/photo.jpg'],
              description: '설명',
            },
          },
        }),
      ),
  })
  const data = await provider.read('karrot', 'detail', { productId: 'zero' })
  assert.equal(data.product.price, 0)
  assert.equal(data.product.url, null)
  assert.equal(data.product.images.length, 1)
  assert.equal(data.product.status, 'reserved')
  await assert.rejects(
    provider.read('karrot', 'detail', { productId: 'bad' }),
    (error) => error.status === 502,
  )
})

test('Karrot verifies the returned search origin without confusing listing neighbourhoods with it', async () => {
  const calls = []
  const provider = new UsedProvider({
    key: 'test-secret',
    fetcher: async (_url, options) => {
      const body = JSON.parse(options.body)
      calls.push(body)
      return new Response(
        JSON.stringify({
          ok: true,
          data: {
            region: {
              region_id: body.region_id === '5789' ? '5789' : '6035',
              full_name: '대전광역시 서구 관저동',
            },
            results: [
              { listing_id: 'outside', title: '아이폰', region_name: '유가읍', price: 1000 },
            ],
          },
        }),
      )
    },
  })
  const result = await provider.read('karrot', 'search', { query: '아이폰', regionId: '5789' })
  assert.equal(calls[0].region_id, '5789')
  assert.equal(result.regionId, '5789')
  assert.equal(result.items[0].regionName, '유가읍')
  await assert.rejects(
    provider.read('karrot', 'search', { query: '아이폰', regionId: '1147' }),
    (error) => error.status === 502,
  )
})

test('Karrot refuses a search response that cannot confirm its origin', async () => {
  const provider = new UsedProvider({
    key: 'test-secret',
    fetcher: async () => new Response(JSON.stringify({ ok: true, data: { results: [] } })),
  })
  await assert.rejects(
    provider.read('karrot', 'search', { query: '아이폰', regionId: '5789' }),
    (error) => error.status === 502,
  )
})

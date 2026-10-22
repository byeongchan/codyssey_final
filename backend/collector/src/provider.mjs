import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';

const file = name => process.env.FIXTURE_DIR ? resolve(process.env.FIXTURE_DIR, name) : new URL(`../../../fixtures/${name}`, import.meta.url);
const parse = bytes => JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));

/** Reef 실연동 시 collect() 계약을 구현하는 별도 공급자를 추가한다. */
export class FixtureProvider {
  async collect() {
    const catalog = parse(await readFile(file('catalog.json')));
    return {
      source: 'fixture',
      referenceTime: catalog.referenceTime,
      idempotencyKey: `fixture-v1:${catalog.referenceTime}`,
    };
  }
  async preview({ q, categoryId, scenario, referenceTime, dataVersion }) {
    const [catalogBytes, pricesBytes] = await Promise.all([readFile(file('catalog.json')), readFile(file('prices.json'))]);
    const catalog = parse(catalogBytes), prices = parse(pricesBytes);
    const version = createHash('sha256').update(catalogBytes).update(pricesBytes).digest('hex');
    if (version !== dataVersion || !Number.isFinite(Date.parse(referenceTime)) || Date.parse(referenceTime) < Date.parse(catalog.referenceTime))
      throw Object.assign(new Error('Fixture snapshot mismatch'), { status: 409 });
    if (!Array.isArray(catalog.products) || !Array.isArray(prices.offers) || !Array.isArray(prices.dailyPrices)) throw new Error('Invalid fixture');
    if (scenario === 'error') throw Object.assign(new Error('Fixture unavailable'), { status: 503 });
    if (scenario === 'rate-limit') throw Object.assign(new Error('Fixture rate limit'), { status: 429 });
    if (scenario === 'unsupported') throw Object.assign(new Error('Unsupported fixture query'), { status: 422 });
    const productIds = scenario === 'empty' ? [] : catalog.products
      .filter(p => p.title.toLowerCase().includes(q.toLowerCase()) && (!categoryId || p.categoryId === categoryId))
      .map(p => p.id).sort();
    return { source: 'fixture', synthetic: true, mode: 'READ_ONLY', referenceTime, dataVersion: version, productIds };
  }
}

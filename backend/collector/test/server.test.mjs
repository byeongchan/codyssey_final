import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createCollectorServer } from '../src/server.mjs';
import { FixtureProvider } from '../src/provider.mjs';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

test('internal HTTP contract rejects unauthenticated/invalid requests and sanitizes failures', async t => {
  const server = createCollectorServer({ key: 'local-test-key', provider: { preview: async body => {
    if (body.scenario === 'error') throw new Error('private secret path');
    return { source: 'fixture', productIds: [] };
  } } });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const url = `http://127.0.0.1:${server.address().port}/internal/collect`;
  const send = (body, key = 'local-test-key') => fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Internal-Key': key }, body: JSON.stringify(body) });
  assert.equal((await send({}, 'wrong')).status, 403);
  assert.equal((await send({ q: 3 })).status, 400);
  const body = { q: '', categoryId: '', scenario: 'normal', dataVersion: 'a'.repeat(64), referenceTime: '2026-10-22T03:00:00Z' };
  assert.equal((await send(body)).status, 200);
  const failed = await send({ ...body, scenario: 'error' }); assert.equal(failed.status, 500);
  assert.ok(!(await failed.text()).includes('private secret path'));
});

test('fixture preview fingerprints both files and distinguishes empty/error/unsupported/rate limit', async () => {
  const [catalog, prices] = await Promise.all(['catalog', 'prices'].map(name => readFile(new URL(`../../../fixtures/${name}.json`, import.meta.url))));
  const dataVersion = createHash('sha256').update(catalog).update(prices).digest('hex');
  const referenceTime = JSON.parse(catalog.toString('utf8').replace(/^\uFEFF/, '')).referenceTime;
  const request = { q: '소니', categoryId: '', scenario: 'normal', dataVersion, referenceTime };
  const provider = new FixtureProvider();
  assert.deepEqual((await provider.preview(request)).productIds, ['sony-xm5-black']);
  assert.deepEqual((await provider.preview({ ...request, scenario: 'empty' })).productIds, []);
  for (const [scenario, status] of [['error', 503], ['unsupported', 422], ['rate-limit', 429]])
    await assert.rejects(provider.preview({ ...request, scenario }), error => error.status === status);
  await assert.rejects(provider.preview({ ...request, dataVersion: 'b'.repeat(64) }), error => error.status === 409);
});

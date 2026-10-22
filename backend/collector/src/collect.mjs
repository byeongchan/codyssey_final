import { FixtureProvider } from './provider.mjs';

if ((process.env.APP_ENV ?? 'development') !== 'development' || (process.env.DATA_PROVIDER ?? 'fixture') !== 'fixture') {
  throw new Error('Only development fixture mode is implemented');
}
const key = process.env.INTERNAL_API_KEY;
if (!key) throw new Error('Set INTERNAL_API_KEY in .env');
const base = new URL(process.env.API_URL ?? 'http://127.0.0.1:8080');
if (!['127.0.0.1', 'localhost'].includes(base.hostname) || base.protocol !== 'http:') {
  throw new Error('Fixture collector only supports a local HTTP API');
}
try {
  const event = await new FixtureProvider().collect();
  const response = await fetch(new URL('/api/v1/internal/fixture-batches', base), {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Internal-Key': key },
    body: JSON.stringify(event), signal: AbortSignal.timeout(5000),
  });
  if (!response.ok) throw new Error(`Fixture event failed: HTTP ${response.status}`);
  console.log(JSON.stringify(await response.json(), null, 2));
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}

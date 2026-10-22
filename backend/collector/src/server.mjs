import { createServer } from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { FixtureProvider } from './provider.mjs';

export function createCollectorServer({ key, provider = new FixtureProvider(), danawa = null, fixtureEnabled = true }) {
  if (!key || key.length > 1024) throw new Error('Set a local INTERNAL_API_KEY (1–1024 characters)');
  const expected = Buffer.from(key);
  const server = createServer(async (req, res) => {
    const requestId = randomUUID();
    const send = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Request-Id': requestId });
      res.end(JSON.stringify(body));
    };
    const fail = (status, message) => send(status, { error: { code: `HTTP_${status}`, message, requestId, retryable: status === 429 || status >= 500 } });
    if (req.method === 'GET' && req.url === '/health') { req.resume(); send(200, { status: 'UP', mode: fixtureEnabled ? 'fixture' : 'reef-danawa' }); return; }
    if (req.method === 'GET' && req.url === '/internal/danawa/metrics') {
      req.resume(); const received = Buffer.from(req.headers['x-internal-key'] ?? '');
      if (received.length !== expected.length || !timingSafeEqual(received, expected)) { fail(403, '내부 인증 실패'); return; }
      send(200, danawa ? danawa.metrics() : { enabled: false }); return;
    }
    if (!fixtureEnabled && req.url === '/internal/collect') { req.resume(); fail(404, '더미 수집은 비활성화되었습니다'); return; }
    const danawaAction = /^\/internal\/danawa\/(search|product|offers|price_history)$/.exec(req.url ?? '')?.[1];
    if ((req.url !== '/internal/collect' && !danawaAction) || req.method !== 'POST') { req.resume(); fail(404, '지원하지 않는 수집 경로입니다'); return; }
    const received = Buffer.from(req.headers['x-internal-key'] ?? '');
    if (received.length !== expected.length || !timingSafeEqual(received, expected)) { req.resume(); fail(403, '내부 인증 실패'); return; }
    if ((req.headers['content-type'] ?? '').split(';')[0].trim().toLowerCase() !== 'application/json') { req.resume(); fail(415, 'JSON 요청이 필요합니다'); return; }
    try {
      const body = await new Promise((accept, reject) => {
        const chunks = []; let size = 0, overflow = false;
        req.on('data', chunk => {
          size += chunk.length;
          if (size > 4096) { overflow = true; reject(Object.assign(new Error(), { status: 413 })); return; }
          if (!overflow) chunks.push(chunk);
        });
        req.on('end', () => { if (!overflow) { try { accept(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { reject(Object.assign(new Error(), { status: 400 })); } } });
        req.on('error', () => reject(Object.assign(new Error(), { status: 400 })));
      });
      if (danawaAction) {
        if (!body || Array.isArray(body) || typeof body !== 'object') { fail(400, '다나와 요청 형식을 확인하세요'); return; }
        if (!danawa) { fail(503, '다나와 실조회가 활성화되지 않았습니다'); return; }
        send(200, await danawa.read(danawaAction, body)); return;
      }
      if (!body || typeof body !== 'object' || typeof body.q !== 'string' || body.q.length > 100 || typeof body.categoryId !== 'string'
          || !/^[A-Za-z0-9_-]{0,128}$/.test(body.categoryId) || !['normal', 'empty', 'error', 'rate-limit', 'unsupported'].includes(body.scenario)
          || typeof body.referenceTime !== 'string' || body.referenceTime.length > 40 || typeof body.dataVersion !== 'string' || !/^[a-f0-9]{64}$/.test(body.dataVersion))
        { fail(400, '수집 요청 형식을 확인하세요'); return; }
      send(200, await provider.preview(body));
    } catch (error) {
      const status = [400, 409, 413, 422, 429, 502, 503].includes(error.status) ? error.status : 500;
      if (danawaAction) { fail(status, '다나와 요청·응답 또는 수집기 설정을 확인하세요'); return; }
      fail(status, ({ 400: '잘못된 JSON 요청', 409: 'API와 수집기의 더미 파일 버전이 다릅니다', 413: '수집 요청 크기 초과', 422: '더미 공급자 미지원', 429: '더미 공급자 요청 한도', 503: '더미 공급자 장애' })[status] ?? '수집기 처리에 실패했습니다');
    }
  });
  server.requestTimeout = 5000;
  server.headersTimeout = 5000;
  return server;
}

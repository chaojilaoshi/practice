// Tests for the resilience features: circuit breaker, provider failover, and
// the Anthropic thinking rectifier. Mix of pure unit tests and end-to-end tests
// against programmable mock upstreams. No API key needed.

import http from 'node:http';
import assert from 'node:assert';
import { fileURLToPath } from 'node:url';
import { loadConfig } from '../src/config.js';
import { Recorder } from '../src/recorder.js';
import { startProxy } from '../src/proxy.js';
import { BreakerRegistry, STATES } from '../src/breaker.js';
import { parseProviders, resolveCandidates, wireToGroup } from '../src/providers.js';
import { detectRectification, rectifySignature, rectifyBudget } from '../src/rectifier.js';

let pass = 0;
const check = (name, cond) => {
  assert.ok(cond, name);
  console.log('  ok -', name);
  pass++;
};

const ANTHROPIC_JSON = JSON.stringify({
  id: 'msg_1',
  type: 'message',
  role: 'assistant',
  content: [{ type: 'text', text: 'ok' }],
  stop_reason: 'end_turn',
  usage: { input_tokens: 1, output_tokens: 1 },
});

function makeServer(handler) {
  return new Promise((resolve) => {
    const requests = [];
    const srv = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        let obj = {};
        try {
          obj = body ? JSON.parse(body) : {};
        } catch {
          obj = {};
        }
        requests.push({ url: req.url, headers: req.headers, body: obj });
        handler(req, obj, res, requests);
      });
    });
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port, url: `http://127.0.0.1:${srv.address().port}`, requests }));
  });
}

function jsonRes(res, status, obj) {
  const buf = Buffer.from(typeof obj === 'string' ? obj : JSON.stringify(obj));
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': String(buf.length) });
  res.end(buf);
}

function post(port, path, bodyObj, headers = {}) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(bodyObj);
    const req = http.request(
      { host: '127.0.0.1', port, path, method: 'POST', headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), ...headers } },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => resolve({ status: res.statusCode, body: data }));
      },
    );
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const logDir = fileURLToPath(new URL('./.tmp-logs', import.meta.url));

function startProxyWith(overrides) {
  const config = loadConfig({ proxyPort: 0, uiPort: 0, logDir, ...overrides });
  const recorder = new Recorder(config);
  const proxy = startProxy(config, recorder);
  return new Promise((r) => proxy.on('listening', () => r({ proxy, recorder, port: proxy.address().port })));
}

async function unitTests() {
  console.log('# breaker unit');
  let clock = 1000;
  const reg = new BreakerRegistry({ failureThreshold: 2, cooldownMs: 100, halfOpenMax: 1, now: () => clock });
  check('breaker: starts allowed/closed', reg.canRequest('a').allowed === true);
  reg.recordFailure('a', false);
  check('breaker: one failure still allowed', reg.canRequest('a').allowed === true);
  reg.recordFailure('a', false);
  check('breaker: opens at threshold (denied)', reg.canRequest('a').allowed === false);
  check('breaker: snapshot shows open', reg.snapshot().a.state === STATES.OPEN);
  clock += 200; // past cooldown
  const probe = reg.canRequest('a');
  check('breaker: half-open grants one probe', probe.allowed === true && probe.halfOpen === true);
  check('breaker: half-open caps concurrent probes', reg.canRequest('a').allowed === false);
  reg.recordSuccess('a', true); // probe ok -> closed + permit released
  check('breaker: success closes', reg.snapshot().a.state === STATES.CLOSED && reg.canRequest('a').allowed === true);
  // neutral must release a half-open permit without changing health.
  reg.recordFailure('a', false);
  reg.recordFailure('a', false); // open
  clock += 200;
  const p2 = reg.canRequest('a');
  reg.recordNeutral('a', p2.halfOpen); // release permit, no health change
  check('breaker: neutral releases probe permit', reg.canRequest('a').allowed === true);

  console.log('# providers unit');
  const pools = parseProviders('[{"id":"x","group":"openai","baseUrl":"http://a/"},{"wire":"messages","baseUrl":"http://b"}]');
  check('providers: openai pool parsed + trailing slash trimmed', pools.openai.length === 1 && pools.openai[0].baseUrl === 'http://a');
  check('providers: wire alias maps to anthropic group', pools.anthropic.length === 1 && pools.anthropic[0].id === 'anthropic-0');
  check('providers: wireToGroup maps responses->openai', wireToGroup('responses') === 'openai' && wireToGroup('anthropic') === 'anthropic');
  const cands = resolveCandidates({ providers: { pools }, upstream: { openai: 'http://u' } }, 'chat');
  check('providers: resolveCandidates uses openai pool for chat wire', cands.length === 1 && cands[0].id === 'x');
  const fallback = resolveCandidates({ providers: { pools: { anthropic: [], openai: [] } }, upstream: { anthropic: 'http://legacy' } }, 'anthropic');
  check('providers: empty pool falls back to legacy upstream', fallback.length === 1 && fallback[0].baseUrl === 'http://legacy');

  console.log('# rectifier unit');
  check('rectifier: detects signature error', detectRectification(400, { error: { message: 'thinking signature is invalid' } }) === 'signature');
  check('rectifier: detects budget error', detectRectification(400, { error: { message: 'thinking.budget_tokens must be >= 1024' } }) === 'budget');
  check('rectifier: ignores 5xx (failover instead)', detectRectification(503, { error: { message: 'signature' } }) === null);
  check('rectifier: respects disabled signature rule', detectRectification(400, { error: { message: 'bad signature' } }, { signature: false, budget: true }) === null);
  const sigBody = { messages: [{ role: 'assistant', content: [{ type: 'thinking', thinking: 'x', signature: 'sig' }, { type: 'text', text: 'hi', signature: 'sig2' }] }] };
  const sigOut = rectifySignature(sigBody);
  check('rectifier: signature strips thinking blocks', sigOut.messages[0].content.length === 1 && sigOut.messages[0].content[0].type === 'text');
  check('rectifier: signature strips signature fields', sigOut.messages[0].content[0].signature === undefined);
  const budOut = rectifyBudget({ max_tokens: 100 });
  check('rectifier: budget enables thinking with valid budget', budOut.thinking.type === 'enabled' && budOut.thinking.budget_tokens === 32000);
  check('rectifier: budget raises max_tokens above budget', budOut.max_tokens === 64000);
}

async function integrationTests() {
  console.log('# failover e2e');
  const p1 = await makeServer((req, body, res) => jsonRes(res, 503, { error: { message: 'overloaded' } }));
  const p2 = await makeServer((req, body, res) => jsonRes(res, 200, ANTHROPIC_JSON));
  const { proxy, port } = await startProxyWith({
    providers: { pools: parseProviders([{ id: 'p1', group: 'anthropic', baseUrl: p1.url }, { id: 'p2', group: 'anthropic', baseUrl: p2.url }]) },
    breaker: { enabled: true, failureThreshold: 5, cooldownMs: 30000, halfOpenMax: 1, failoverStatuses: new Set([429, 500, 502, 503, 504]) },
  });
  const r = await post(port, '/v1/messages', { model: 'claude-x', messages: [{ role: 'user', content: 'hi' }] }, { 'x-api-key': 'k' });
  await wait(40);
  check('failover: client gets the 2nd provider 200', r.status === 200 && r.body === ANTHROPIC_JSON);
  check('failover: provider1 was tried once', p1.requests.length === 1);
  check('failover: provider2 served the request', p2.requests.length === 1);
  check('failover: breaker recorded p1 failure', proxy.breakers.snapshot().p1.failures >= 1);
  proxy.close(); p1.srv.close(); p2.srv.close();

  console.log('# breaker opens + skips e2e');
  const b1 = await makeServer((req, body, res) => jsonRes(res, 500, { error: { message: 'boom' } }));
  const b2 = await makeServer((req, body, res) => jsonRes(res, 200, ANTHROPIC_JSON));
  const r2 = await startProxyWith({
    providers: { pools: parseProviders([{ id: 'b1', group: 'anthropic', baseUrl: b1.url }, { id: 'b2', group: 'anthropic', baseUrl: b2.url }]) },
    breaker: { enabled: true, failureThreshold: 2, cooldownMs: 30000, halfOpenMax: 1, failoverStatuses: new Set([429, 500, 502, 503, 504]) },
  });
  for (let i = 0; i < 3; i++) {
    const rr = await post(r2.port, '/v1/messages', { model: 'm', messages: [{ role: 'user', content: 'hi' }] }, { 'x-api-key': 'k' });
    check(`breaker e2e: request ${i + 1} still succeeds via b2`, rr.status === 200);
    await wait(20);
  }
  check('breaker e2e: b1 stopped being tried after opening (<=2 hits)', b1.requests.length === 2);
  check('breaker e2e: b1 breaker is OPEN', r2.proxy.breakers.snapshot().b1.state === STATES.OPEN);
  r2.proxy.close(); b1.srv.close(); b2.srv.close();

  console.log('# rectifier signature e2e');
  const sig = await makeServer((req, body, res) => {
    const hasThinking = Array.isArray(body.messages) && body.messages.some((m) => Array.isArray(m.content) && m.content.some((b) => b.type === 'thinking' || b.type === 'redacted_thinking'));
    if (hasThinking) jsonRes(res, 400, { error: { message: 'messages.0: thinking blocks have an invalid signature' } });
    else jsonRes(res, 200, ANTHROPIC_JSON);
  });
  const r3 = await startProxyWith({ upstream: { anthropic: sig.url, openai: sig.url }, rectifier: { enabled: true, signature: true, budget: true } });
  const rr3 = await post(r3.port, '/v1/messages', {
    model: 'claude-x',
    messages: [{ role: 'assistant', content: [{ type: 'thinking', thinking: 't', signature: 'abc' }, { type: 'text', text: 'hi' }] }],
  }, { 'x-api-key': 'k' });
  await wait(40);
  check('rectify signature: client ends up with 200', rr3.status === 200 && rr3.body === ANTHROPIC_JSON);
  check('rectify signature: upstream hit twice (orig + rectified)', sig.requests.length === 2);
  check('rectify signature: first attempt had thinking block', sig.requests[0].body.messages[0].content.some((b) => b.type === 'thinking'));
  check('rectify signature: retried body had thinking stripped', !sig.requests[1].body.messages[0].content.some((b) => b.type === 'thinking'));
  r3.proxy.close(); sig.srv.close();

  console.log('# rectifier budget e2e');
  let budgetCalls = 0;
  const bud = await makeServer((req, body, res) => {
    budgetCalls++;
    if (budgetCalls === 1) jsonRes(res, 400, { error: { message: 'thinking.budget_tokens: must be greater than or equal to 1024' } });
    else jsonRes(res, 200, ANTHROPIC_JSON);
  });
  const r4 = await startProxyWith({ upstream: { anthropic: bud.url, openai: bud.url }, rectifier: { enabled: true, signature: true, budget: true } });
  const rr4 = await post(r4.port, '/v1/messages', { model: 'claude-x', max_tokens: 100, messages: [{ role: 'user', content: 'hi' }] }, { 'x-api-key': 'k' });
  await wait(40);
  check('rectify budget: client ends up with 200', rr4.status === 200);
  check('rectify budget: retried body has valid budget', bud.requests[1].body.thinking && bud.requests[1].body.thinking.budget_tokens === 32000);
  check('rectify budget: retried body raised max_tokens', bud.requests[1].body.max_tokens === 64000);
  r4.proxy.close(); bud.srv.close();

  console.log('# non-failover terminal error e2e');
  const t1 = await makeServer((req, body, res) => jsonRes(res, 401, { error: { message: 'unauthorized' } }));
  const t2 = await makeServer((req, body, res) => jsonRes(res, 200, ANTHROPIC_JSON));
  const r5 = await startProxyWith({
    providers: { pools: parseProviders([{ id: 't1', group: 'anthropic', baseUrl: t1.url }, { id: 't2', group: 'anthropic', baseUrl: t2.url }]) },
    breaker: { enabled: true, failureThreshold: 5, cooldownMs: 30000, halfOpenMax: 1, failoverStatuses: new Set([429, 500, 502, 503, 504]) },
  });
  const rr5 = await post(r5.port, '/v1/messages', { model: 'm', messages: [{ role: 'user', content: 'hi' }] }, { 'x-api-key': 'k' });
  await wait(40);
  check('terminal 401: returned to client as-is (no failover)', rr5.status === 401);
  check('terminal 401: second provider NOT tried', t2.requests.length === 0);
  check('terminal 401: 401 not counted as breaker failure (neutral)', (r5.proxy.breakers.snapshot().t1.failures || 0) === 0);
  r5.proxy.close(); t1.srv.close(); t2.srv.close();
}

async function main() {
  await unitTests();
  await integrationTests();
  console.log(`\nAll ${pass} resilience checks passed.`);
}

main().catch((err) => {
  console.error('TEST FAILED:', err);
  process.exit(1);
});

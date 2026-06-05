// Tests for the opt-in extensibility features:
//   1. tool-name normalization (request/response/SSE + input repair)
//   2. request filters/rules engine (set/delete header, JSON-path replace)
//   3. outbound proxy (HTTP CONNECT + SOCKS5), zero-dependency
// Mix of pure unit tests and end-to-end tests against local mock servers.
// No API key needed.

import http from 'node:http';
import net from 'node:net';
import assert from 'node:assert';
import { fileURLToPath } from 'node:url';
import { loadConfig } from '../src/config.js';
import { Recorder } from '../src/recorder.js';
import { startProxy } from '../src/proxy.js';
import {
  mapToolName,
  normalizeRequestToolNames,
  rewriteResponseToolNames,
  repairToolUseInput,
  rewriteStreamEventToolName,
  DEFAULT_TOOL_NAME_MAP,
} from '../src/transform.js';
import { parseFilters, applyFilters, summarizeFilters } from '../src/filters.js';
import { parseProxyUrl, createOutbound } from '../src/outbound.js';

let pass = 0;
const check = (name, cond) => {
  assert.ok(cond, name);
  console.log('  ok -', name);
  pass++;
};
const eq = (name, a, b) => check(`${name} (got ${JSON.stringify(a)})`, JSON.stringify(a) === JSON.stringify(b));

// ---------------------------------------------------------------------------
// Local mock helpers
// ---------------------------------------------------------------------------
function makeServer(handler) {
  return new Promise((resolve) => {
    const requests = [];
    const srv = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        let obj = {};
        try { obj = body ? JSON.parse(body) : {}; } catch { obj = {}; }
        requests.push({ url: req.url, headers: req.headers, body: obj, rawBody: body });
        handler(req, obj, res, requests);
      });
    });
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port, url: `http://127.0.0.1:${srv.address().port}`, requests }));
  });
}

function post(port, path, bodyObj, headers = {}) {
  return new Promise((resolve, reject) => {
    const body = typeof bodyObj === 'string' ? bodyObj : JSON.stringify(bodyObj);
    const req = http.request(
      { host: '127.0.0.1', port, path, method: 'POST', headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), ...headers } },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
      },
    );
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

// Minimal HTTP CONNECT forward proxy. Records the CONNECT targets it tunnels.
function makeConnectProxy() {
  return new Promise((resolve) => {
    const seen = [];
    const srv = http.createServer((req, res) => { res.writeHead(405); res.end(); });
    srv.on('connect', (req, clientSocket, head) => {
      seen.push(req.url);
      const [host, port] = req.url.split(':');
      const upstream = net.connect(Number(port), host, () => {
        clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
        if (head && head.length) upstream.write(head);
        upstream.pipe(clientSocket);
        clientSocket.pipe(upstream);
      });
      upstream.on('error', () => clientSocket.destroy());
      clientSocket.on('error', () => upstream.destroy());
    });
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port, seen }));
  });
}

// Minimal SOCKS5 server (no-auth, CONNECT). Records target host:port.
function makeSocks5() {
  return new Promise((resolve) => {
    const seen = [];
    const srv = net.createServer((sock) => {
      let stage = 0;
      let buf = Buffer.alloc(0);
      sock.on('error', () => {});
      sock.on('data', (d) => {
        buf = Buffer.concat([buf, d]);
        if (stage === 0) {
          if (buf.length < 2) return;
          const n = buf[1];
          if (buf.length < 2 + n) return;
          buf = buf.subarray(2 + n);
          sock.write(Buffer.from([0x05, 0x00]));
          stage = 1;
        }
        if (stage === 1) {
          if (buf.length < 4) return;
          const atyp = buf[3];
          let host; let offset;
          if (atyp === 0x01) { if (buf.length < 10) return; host = `${buf[4]}.${buf[5]}.${buf[6]}.${buf[7]}`; offset = 8; }
          else if (atyp === 0x03) { const len = buf[4]; if (buf.length < 5 + len + 2) return; host = buf.subarray(5, 5 + len).toString(); offset = 5 + len; }
          else { sock.end(); return; }
          const port = buf.readUInt16BE(offset);
          seen.push(`${host}:${port}`);
          buf = buf.subarray(offset + 2);
          stage = 2;
          const upstream = net.connect(port, host, () => {
            sock.write(Buffer.from([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
            upstream.pipe(sock);
            sock.pipe(upstream);
          });
          upstream.on('error', () => sock.destroy());
        }
      });
    });
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port, seen }));
  });
}

const logDir = fileURLToPath(new URL('./.tmp-logs', import.meta.url));

function startTestProxy(overrides) {
  const config = loadConfig({ proxyPort: 0, uiPort: 0, logDir, ...overrides });
  const recorder = new Recorder(config);
  const server = startProxy(config, recorder);
  return new Promise((resolve) => {
    const t = setInterval(() => {
      if (server.address()) { clearInterval(t); resolve({ server, port: server.address().port, recorder }); }
    }, 5);
  });
}

// ===========================================================================
async function run() {
  console.log('transform: tool-name normalization (unit)');
  {
    eq('mapToolName uses built-in map', mapToolName('todowrite', DEFAULT_TOOL_NAME_MAP), 'TodoWrite');
    eq('mapToolName capitalizes unknown', mapToolName('read', {}), 'Read');
    eq('mapToolName case-insensitive map hit', mapToolName('WebFetch', DEFAULT_TOOL_NAME_MAP), 'WebFetch');
    eq('mapToolName leaves non-strings', mapToolName(null, {}), null);
    eq('explicit map overrides', mapToolName('foo', { foo: 'BarBaz' }), 'BarBaz');

    const reqBody = {
      tools: [{ name: 'read' }, { name: 'todowrite' }, { name: 'AlreadyPascal' }],
      messages: [
        { role: 'assistant', content: [{ type: 'tool_use', name: 'write', id: 't1', input: {} }] },
        { role: 'user', content: [{ type: 'text', text: 'hi' }] },
      ],
    };
    const changed = normalizeRequestToolNames(reqBody, DEFAULT_TOOL_NAME_MAP);
    eq('request tools renamed', reqBody.tools.map((t) => t.name), ['Read', 'TodoWrite', 'AlreadyPascal']);
    eq('history tool_use renamed', reqBody.messages[0].content[0].name, 'Write');
    check('normalizeRequest reports changed count', changed === 3);

    const resBody = { content: [{ type: 'tool_use', name: 'read', input: { paths: '["a","b"]', note: 'plain', obj: '{"k":1}' } }] };
    rewriteResponseToolNames(resBody, DEFAULT_TOOL_NAME_MAP);
    eq('response tool_use renamed', resBody.content[0].name, 'Read');
    const repaired = repairToolUseInput(resBody);
    eq('input array string repaired', resBody.content[0].input.paths, ['a', 'b']);
    eq('input object string repaired', resBody.content[0].input.obj, { k: 1 });
    eq('plain string left alone', resBody.content[0].input.note, 'plain');
    check('repair reports count', repaired === 2);

    const evt = { type: 'content_block_start', content_block: { type: 'tool_use', name: 'webfetch' } };
    const did = rewriteStreamEventToolName(evt, DEFAULT_TOOL_NAME_MAP);
    eq('SSE content_block_start renamed', evt.content_block.name, 'WebFetch');
    check('SSE rewrite returns true on change', did === true);
    const evt2 = { type: 'content_block_delta', delta: {} };
    check('SSE non-start untouched', rewriteStreamEventToolName(evt2, {}) === false);
  }

  console.log('filters: rules engine (unit)');
  {
    const filters = parseFilters(JSON.stringify([
      { name: 'beta', action: 'set_header', target: 'anthropic-beta', value: 'ctx-1m', priority: 10 },
      { name: 'think-type', action: 'json_set', target: 'thinking.type', value: 'adaptive', priority: 1 },
      { name: 'budget', action: 'json_set', target: 'thinking.budget_tokens', value: '1024', priority: 2 },
      { name: 'disabled', action: 'set_header', target: 'x-no', value: '1', enabled: false, priority: 20 },
      { name: 'scoped', action: 'set_header', target: 'x-scoped', value: 'yes', scope: 'provider:vendorA', priority: 30 },
      { name: 'bad', action: 'nonsense', target: 'x' },
    ]));
    eq('parseFilters drops invalid action', filters.find((f) => f.name === 'bad'), undefined);
    eq('parseFilters sorts by priority', filters.map((f) => f.name), ['think-type', 'budget', 'beta', 'disabled', 'scoped']);

    const headers = {};
    const body = {};
    const res = applyFilters(filters, { providerId: null, headers, body });
    eq('set_header applied (lowercased)', headers['anthropic-beta'], 'ctx-1m');
    eq('json_set string value', body.thinking.type, 'adaptive');
    eq('json_set numeric coercion', body.thinking.budget_tokens, 1024);
    check('disabled rule skipped', headers['x-no'] === undefined);
    check('out-of-scope provider rule skipped', headers['x-scoped'] === undefined);
    check('applied list excludes disabled/scoped', !res.applied.includes('disabled') && !res.applied.includes('scoped'));

    const headers2 = {};
    const body2 = {};
    applyFilters(filters, { providerId: 'vendorA', headers: headers2, body: body2 });
    eq('in-scope provider rule applied', headers2['x-scoped'], 'yes');

    // delete_header + json_delete
    const fdel = parseFilters([
      { name: 'del-h', action: 'delete_header', target: 'X-Remove' },
      { name: 'del-b', action: 'json_delete', target: 'metadata.user_id' },
    ]);
    const h3 = { 'x-remove': 'gone', keep: 'yes' };
    const b3 = { metadata: { user_id: 'u1', session: 's1' } };
    applyFilters(fdel, { providerId: null, headers: h3, body: b3 });
    check('delete_header removes case-insensitively', h3['x-remove'] === undefined && h3.keep === 'yes');
    check('json_delete removes nested key', b3.metadata.user_id === undefined && b3.metadata.session === 's1');

    eq('summarizeFilters shape', summarizeFilters(fdel)[0], { name: 'del-h', enabled: true, priority: 0, scope: 'all', action: 'delete_header', target: 'X-Remove' });
    eq('parseFilters bad JSON -> []', parseFilters('{not json'), []);
  }

  console.log('outbound: proxy URL parsing (unit)');
  {
    eq('http proxy', parseProxyUrl('http://h:8080'), { kind: 'http', hostname: 'h', port: 8080, username: '', password: '' });
    eq('https proxy default port', parseProxyUrl('https://h'), { kind: 'https', hostname: 'h', port: 443, username: '', password: '' });
    eq('socks5 default port', parseProxyUrl('socks5://h'), { kind: 'socks5', hostname: 'h', port: 1080, username: '', password: '' });
    eq('socks alias', parseProxyUrl('socks://h:1081').kind, 'socks5');
    eq('socks5h alias', parseProxyUrl('socks5h://h').kind, 'socks5');
    eq('auth parsed', parseProxyUrl('http://u:p@h:3128'), { kind: 'http', hostname: 'h', port: 3128, username: 'u', password: 'p' });
    check('empty -> null', parseProxyUrl('') === null);
    check('unknown scheme -> null', parseProxyUrl('ftp://h') === null);
    check('createOutbound null when unset', createOutbound('') === null);
  }

  // =========================================================================
  console.log('integration: tool-name request normalization (e2e)');
  {
    const up = await makeServer((req, obj, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ id: 'm', type: 'message', role: 'assistant', content: [{ type: 'text', text: 'ok' }], stop_reason: 'end_turn', usage: {} }));
    });
    const { server, port } = await startTestProxy({
      upstream: { anthropic: up.url, openai: up.url },
      transform: { toolName: { enabled: true, request: true, response: false, repairInput: false, map: DEFAULT_TOOL_NAME_MAP } },
    });
    await post(port, '/v1/messages', {
      model: 'claude', stream: false,
      tools: [{ name: 'read' }, { name: 'todowrite' }],
      messages: [{ role: 'assistant', content: [{ type: 'tool_use', name: 'write', id: 't', input: {} }] }],
    });
    const got = up.requests[up.requests.length - 1].body;
    eq('upstream got PascalCase tool names', got.tools.map((t) => t.name), ['Read', 'TodoWrite']);
    eq('upstream got PascalCase history tool_use', got.messages[0].content[0].name, 'Write');
    server.close(); up.srv.close();
  }

  console.log('integration: tool-name response rewrite + input repair (e2e, JSON)');
  {
    const up = await makeServer((req, obj, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({
        id: 'm', type: 'message', role: 'assistant',
        content: [{ type: 'tool_use', name: 'read', id: 'tu', input: { paths: '["x.txt","y.txt"]' } }],
        stop_reason: 'tool_use', usage: {},
      }));
    });
    const { server, port } = await startTestProxy({
      upstream: { anthropic: up.url, openai: up.url },
      transform: { toolName: { enabled: true, request: true, response: true, repairInput: true, map: DEFAULT_TOOL_NAME_MAP } },
    });
    const r = await post(port, '/v1/messages', { model: 'claude', stream: false, messages: [] });
    const body = JSON.parse(r.body);
    eq('client got PascalCase response name', body.content[0].name, 'Read');
    eq('client got repaired array input', body.content[0].input.paths, ['x.txt', 'y.txt']);
    server.close(); up.srv.close();
  }

  console.log('integration: tool-name SSE response rewrite (e2e)');
  {
    const sse = [
      'event: message_start\ndata: {"type":"message_start","message":{"id":"m"}}\n\n',
      'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu","name":"webfetch","input":{}}}\n\n',
      'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n',
      'event: message_stop\ndata: {"type":"message_stop"}\n\n',
    ].join('');
    const up = await makeServer((req, obj, res) => {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.end(sse);
    });
    const { server, port } = await startTestProxy({
      upstream: { anthropic: up.url, openai: up.url },
      transform: { toolName: { enabled: true, request: true, response: true, repairInput: true, map: DEFAULT_TOOL_NAME_MAP } },
    });
    const r = await post(port, '/v1/messages', { model: 'claude', stream: true, messages: [] });
    check('SSE name rewritten to WebFetch', r.body.includes('"name":"WebFetch"'));
    check('SSE no longer carries lowercase webfetch name', !r.body.includes('"name":"webfetch"'));
    check('SSE preserves other events', r.body.includes('message_start') && r.body.includes('message_stop'));
    server.close(); up.srv.close();
  }

  console.log('integration: filters mutate outbound request (e2e)');
  {
    const up = await makeServer((req, obj, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ id: 'm', type: 'message', role: 'assistant', content: [], stop_reason: 'end_turn', usage: {} }));
    });
    const { server, port } = await startTestProxy({
      upstream: { anthropic: up.url, openai: up.url },
      filters: parseFilters([
        { name: 'beta', action: 'set_header', target: 'anthropic-beta', value: 'context-1m-2025' },
        { name: 'think', action: 'json_set', target: 'thinking.type', value: 'adaptive' },
        { name: 'budget', action: 'json_set', target: 'thinking.budget_tokens', value: '1024' },
        { name: 'drop', action: 'delete_header', target: 'x-drop-me' },
      ]),
    });
    await post(port, '/v1/messages', { model: 'claude', stream: false, messages: [] }, { 'x-drop-me': 'should-be-gone' });
    const last = up.requests[up.requests.length - 1];
    eq('upstream received injected header', last.headers['anthropic-beta'], 'context-1m-2025');
    eq('upstream body thinking.type set', last.body.thinking.type, 'adaptive');
    eq('upstream body budget numeric', last.body.thinking.budget_tokens, 1024);
    check('upstream did NOT receive dropped header', last.headers['x-drop-me'] === undefined);
    server.close(); up.srv.close();
  }

  console.log('integration: outbound proxy HTTP CONNECT (e2e)');
  {
    const up = await makeServer((req, obj, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
    });
    const proxy = await makeConnectProxy();
    const { server, port } = await startTestProxy({
      upstream: { anthropic: up.url, openai: up.url },
      outbound: createOutbound(`http://127.0.0.1:${proxy.port}`),
    });
    const r = await post(port, '/v1/messages', { model: 'claude', messages: [] });
    check('request succeeded through http proxy', r.status === 200);
    check('http proxy tunneled to upstream', proxy.seen.some((t) => t.endsWith(`:${up.port}`)));
    check('upstream actually received request', up.requests.length === 1);
    server.close(); up.srv.close(); proxy.srv.close();
  }

  console.log('integration: outbound proxy SOCKS5 (e2e)');
  {
    const up = await makeServer((req, obj, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
    });
    const socks = await makeSocks5();
    const { server, port } = await startTestProxy({
      upstream: { anthropic: up.url, openai: up.url },
      outbound: createOutbound(`socks5://127.0.0.1:${socks.port}`),
    });
    const r = await post(port, '/v1/messages', { model: 'claude', messages: [] });
    check('request succeeded through socks5 proxy', r.status === 200);
    check('socks5 connected to upstream', socks.seen.some((t) => t.endsWith(`:${up.port}`)));
    check('upstream received request via socks5', up.requests.length === 1);
    server.close(); up.srv.close(); socks.srv.close();
  }

  console.log('integration: default path unchanged when nothing enabled (e2e)');
  {
    const up = await makeServer((req, obj, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ id: 'm', type: 'message', content: [{ type: 'tool_use', name: 'read', input: { paths: '["a"]' } }], stop_reason: 'tool_use', usage: {} }));
    });
    const { server, port } = await startTestProxy({ upstream: { anthropic: up.url, openai: up.url } });
    await post(port, '/v1/messages', { model: 'claude', tools: [{ name: 'read' }], messages: [] });
    const r = await post(port, '/v1/messages', { model: 'claude', messages: [] });
    eq('default forwards lowercase tool name untouched', up.requests[0].body.tools[0].name, 'read');
    check('default leaves response bytes verbatim (string input kept)', JSON.parse(r.body).content[0].input.paths === '["a"]');
    server.close(); up.srv.close();
  }

  console.log(`\nextensions: ${pass} checks passed`);
}

run().catch((err) => { console.error(err); process.exit(1); });

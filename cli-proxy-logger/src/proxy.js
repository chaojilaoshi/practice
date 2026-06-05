// Core reverse proxy.
//
// Flow:
//   1. Buffer the incoming request body (CLI request bodies are complete JSON).
//   2. resolveUpstream() picks the real upstream + wire format from the path.
//   3. Forward method/path/headers/body to the upstream over http(s).
//   4. Stream the upstream response back to the client byte-for-byte (fidelity
//      first — the CLI must be unaffected), while teeing a decoded COPY into the
//      matching parser to reconstruct text + tool calls.
//   5. Record the normalized Exchange.

import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';
import { randomUUID } from 'node:crypto';
import { resolveUpstream } from './upstream.js';
import { SSEParser } from './sse.js';
import * as anthropic from './parsers/anthropic.js';
import * as openaiResponses from './parsers/openaiResponses.js';
import * as openaiChat from './parsers/openaiChat.js';

const PARSERS = { anthropic, responses: openaiResponses, chat: openaiChat };

// "Hop-by-hop" headers are meaningful only for a single transport connection
// (per RFC 7230 6.1) and must NOT be blindly relayed by a proxy. We also drop
// host/content-length here because we recompute them for the new connection,
// and transfer-encoding because Node re-frames the body for us.
const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'host',
  'content-length',
]);

// Mask credentials before they are written to disk. The *real* key is still
// forwarded to the upstream untouched — only the logged copy is redacted, so
// your JSONL files never contain a usable API key.
function redactHeaders(headers, redact) {
  const out = {};
  for (const [k, v] of Object.entries(headers)) {
    const lk = k.toLowerCase();
    if (redact && (lk === 'authorization' || lk === 'x-api-key' || lk === 'api-key')) {
      const s = Array.isArray(v) ? v.join(',') : String(v);
      // Keep a short prefix/suffix so two keys are distinguishable in logs
      // without exposing the secret (e.g. "Bearer...7912").
      out[k] = s.length <= 12 ? '***' : `${s.slice(0, 6)}...${s.slice(-4)}`;
    } else {
      out[k] = v;
    }
  }
  return out;
}

// Build a streaming decompressor for the COPY we parse. Node ships gzip,
// deflate AND brotli natively (unlike Python's stdlib), so we can decode
// whatever the upstream picked. Returns null for identity/unknown encodings —
// then we just parse the bytes as-is.
function makeDecoder(contentEncoding) {
  switch ((contentEncoding || '').toLowerCase()) {
    case 'gzip':
      return zlib.createGunzip();
    case 'deflate':
      return zlib.createInflate();
    case 'br':
      return zlib.createBrotliDecompress();
    default:
      return null;
  }
}

function truncate(buf, max) {
  if (buf.length <= max) return { text: buf.toString('utf8'), truncated: false };
  return { text: buf.slice(0, max).toString('utf8') + `\n...[truncated ${buf.length - max} bytes]`, truncated: true };
}

export function startProxy(config, recorder) {
  const server = http.createServer((clientReq, clientRes) => {
    const chunks = [];
    clientReq.on('data', (c) => chunks.push(c));
    clientReq.on('end', () => {
      const reqBodyBuf = Buffer.concat(chunks);
      handleRequest(config, recorder, clientReq, clientRes, reqBodyBuf);
    });
    clientReq.on('error', () => clientRes.destroy());
  });

  server.listen(config.proxyPort, '127.0.0.1', () => {
    console.log(`[proxy] listening on http://127.0.0.1:${server.address().port}`);
  });
  return server;
}

function handleRequest(config, recorder, clientReq, clientRes, reqBodyBuf) {
  const started = Date.now();

  // Step 2: pick the real upstream + wire format. The path alone tells us who
  // the client is: /v1/messages == Claude Code (Anthropic), /v1/responses or
  // /v1/chat/completions == Codex. (Step 1 — buffering the body — happened in
  // startProxy before calling us.)
  const { baseUrl, wire } = resolveUpstream(config, clientReq.url, clientReq.headers);
  const parser = PARSERS[wire];
  const upstreamUrl = new URL(clientReq.url, baseUrl);
  const isHttps = upstreamUrl.protocol === 'https:';
  const mod = isHttps ? https : http;

  // Step 3: copy the client's headers through verbatim (including the real
  // Authorization / x-api-key) so the upstream sees an identical request —
  // this is why CLI-specific gating (e.g. cc.freemodel.dev only answering real
  // Claude Code headers) still works through us. Only hop-by-hop headers are
  // stripped. (We keep Node's native Accept-Encoding, since Node can decode
  // brotli — the Python port has to normalize it to gzip/deflate instead.)
  const outHeaders = {};
  for (const [k, v] of Object.entries(clientReq.headers)) {
    if (!HOP_BY_HOP.has(k.toLowerCase())) outHeaders[k] = v;
  }
  outHeaders['host'] = upstreamUrl.host;
  if (reqBodyBuf.length > 0) outHeaders['content-length'] = String(reqBodyBuf.length);

  // Parse the request body now (it is plain JSON) into the normalized shape.
  // Wrapped in try/catch: a parse bug must never stop us from forwarding.
  let parsedRequest;
  try {
    parsedRequest = parser.parseRequest(reqBodyBuf.toString('utf8'));
  } catch {
    parsedRequest = { wire, model: null, messages: [], tools: [], stream: false, raw: null };
  }

  const exchange = {
    id: randomUUID(),
    ts: new Date(started).toISOString(),
    durationMs: 0,
    wire,
    method: clientReq.method,
    url: upstreamUrl.toString(),
    reqHeaders: redactHeaders(clientReq.headers, config.redactAuth),
    requestBodyRaw: truncate(reqBodyBuf, config.maxBodyBytes).text,
    request: parsedRequest,
    resStatus: 0,
    resHeaders: {},
    response: null,
    error: null,
  };

  // Step 4: open the upstream connection and send the request.
  const upstreamReq = mod.request(
    upstreamUrl,
    { method: clientReq.method, headers: outHeaders },
    (upstreamRes) => {
      // Step 5: relay the upstream status + headers straight back to the
      // client. Node forwards them as-is (it manages framing for us).
      clientRes.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
      exchange.resStatus = upstreamRes.statusCode || 0;
      exchange.resHeaders = upstreamRes.headers;

      // Step 6 setup: decide how to parse. For SSE we run an incremental SSE
      // parser whose events feed a per-wire aggregator that rebuilds text +
      // tool calls; for plain JSON we buffer and parse once at the end.
      const contentType = String(upstreamRes.headers['content-type'] || '');
      const isSSE = contentType.includes('text/event-stream');
      const decoder = makeDecoder(upstreamRes.headers['content-encoding']);

      const sse = isSSE ? new SSEParser() : null;
      const agg = isSSE ? parser.createStreamAggregator() : null;
      if (sse) sse.on('event', (e) => agg.feed(e));

      const rawCopy = [];
      let rawCopyLen = 0;

      const onDecoded = (buf) => {
        if (sse) {
          sse.push(buf.toString('utf8'));
        } else if (rawCopyLen < config.maxBodyBytes) {
          rawCopy.push(buf);
          rawCopyLen += buf.length;
        }
      };

      if (decoder) {
        decoder.on('data', onDecoded);
        decoder.on('error', () => {}); // never break forwarding on decode error
      }

      // Step 6: the dual-path tee. For every chunk from the upstream we write
      // the ORIGINAL bytes to the client FIRST (fidelity: the CLI must be
      // unaffected even if our parser later throws), then feed a decoded copy
      // into the SSE parser / raw buffer.
      upstreamRes.on('data', (chunk) => {
        clientRes.write(chunk); // forward raw bytes first
        if (decoder) decoder.write(chunk);
        else onDecoded(chunk);
      });

      upstreamRes.on('end', () => {
        clientRes.end();
        // Step 7: finalize the normalized response and record the exchange.
        const finalize = () => {
          try {
            if (sse) {
              sse.flush();
              exchange.response = agg.result();
            } else {
              const body = Buffer.concat(rawCopy);
              exchange.response = parser.parseResponse(body.toString('utf8'));
            }
          } catch (err) {
            exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
          }
          exchange.durationMs = Date.now() - started;
          recorder.record(exchange);
        };
        if (decoder) decoder.end(() => finalize());
        else finalize();
      });

      upstreamRes.on('error', (err) => {
        exchange.error = `upstream stream error: ${err.message}`;
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        clientRes.destroy();
      });
    },
  );

  upstreamReq.on('error', (err) => {
    exchange.error = `upstream request error: ${err.message}`;
    exchange.durationMs = Date.now() - started;
    recorder.record(exchange);
    if (!clientRes.headersSent) clientRes.writeHead(502, { 'content-type': 'application/json' });
    clientRes.end(JSON.stringify({ error: { type: 'proxy_error', message: err.message } }));
  });

  if (reqBodyBuf.length > 0) upstreamReq.write(reqBodyBuf);
  upstreamReq.end();
}

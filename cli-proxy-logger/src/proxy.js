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
import { safeJsonParse } from './model.js';
import {
  anthropicRequestToChat,
  chatResponseToAnthropic,
  chatErrorToAnthropic,
  anthropicMessageToSSE,
  anthropicErrorSSE,
  mapModel,
  ChatToAnthropicStream,
} from './translate.js';

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

  // Compat mode: when ANTHROPIC_COMPAT=chat and the client is speaking Anthropic
  // Messages, hand off to the TRANSLATING path (Anthropic -> OpenAI Chat) instead
  // of the transparent tee. This is the only situation where we rewrite both the
  // request and the response rather than forwarding bytes verbatim.
  if (config.compat?.anthropicTo === 'chat' && wire === 'anthropic'
      && (clientReq.url || '').split('?')[0].startsWith('/v1/messages')) {
    handleAnthropicToChat(config, recorder, clientReq, clientRes, reqBodyBuf, started);
    return;
  }
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

// =============================================================================
// Compat path: Anthropic /v1/messages  ->  OpenAI /v1/chat/completions
//
// Unlike handleRequest (transparent tee), this path REWRITES both directions:
//   request  : translate.anthropicRequestToChat() -> POST /v1/chat/completions
//   response : OpenAI Chat (SSE or JSON) -> Anthropic events sent to the client
// The client (Claude Code) never knows the vendor speaks a different protocol.
// =============================================================================
function handleAnthropicToChat(config, recorder, clientReq, clientRes, reqBodyBuf, started) {
  const anthBody = safeJsonParse(reqBodyBuf.toString('utf8')) || {};
  const wantStream = !!anthBody.stream;
  const originalModel = anthBody.model ?? null;

  // 1) Translate the request body and pick the mapped model.
  const chatBody = anthropicRequestToChat(anthBody, config.compat.modelMap);
  const mappedModel = chatBody.model;
  const chatBodyBuf = Buffer.from(JSON.stringify(chatBody));

  // 2) Build the upstream request to the OpenAI-style vendor.
  const baseUrl = config.upstream.openai;
  const upstreamUrl = new URL('/v1/chat/completions', baseUrl);
  const isHttps = upstreamUrl.protocol === 'https:';
  const mod = isHttps ? https : http;

  // Copy client headers minus hop-by-hop, then fix up auth + content for an
  // OpenAI vendor: Claude Code authenticates with `x-api-key`, but OpenAI-style
  // vendors expect `Authorization: Bearer`. We translate that, and drop
  // Anthropic-only headers the vendor would not understand.
  const outHeaders = {};
  for (const [k, v] of Object.entries(clientReq.headers)) {
    const lk = k.toLowerCase();
    if (HOP_BY_HOP.has(lk)) continue;
    if (lk === 'x-api-key' || lk === 'anthropic-version' || lk === 'anthropic-beta'
        || lk === 'anthropic-dangerous-direct-browser-access' || lk === 'content-type') {
      continue;
    }
    outHeaders[k] = v;
  }
  const auth = clientReq.headers['authorization'];
  const apiKey = clientReq.headers['x-api-key'];
  if (auth) outHeaders['authorization'] = auth;
  else if (apiKey) outHeaders['authorization'] = `Bearer ${apiKey}`;
  outHeaders['host'] = upstreamUrl.host;
  outHeaders['content-type'] = 'application/json';
  outHeaders['content-length'] = String(chatBodyBuf.length);

  // 3) Record the exchange as an Anthropic request (what the client sent) with a
  // translation marker; the response is normalized from the chat side below.
  let parsedRequest;
  try {
    parsedRequest = anthropic.parseRequest(reqBodyBuf.toString('utf8'));
  } catch {
    parsedRequest = { wire: 'anthropic', model: originalModel, messages: [], tools: [], stream: wantStream, raw: null };
  }
  const exchange = {
    id: randomUUID(),
    ts: new Date(started).toISOString(),
    durationMs: 0,
    wire: 'anthropic',
    method: clientReq.method,
    url: upstreamUrl.toString(),
    reqHeaders: redactHeaders(clientReq.headers, config.redactAuth),
    requestBodyRaw: truncate(reqBodyBuf, config.maxBodyBytes).text,
    request: parsedRequest,
    translation: { from: 'anthropic', to: 'chat', model: originalModel, upstreamModel: mappedModel },
    resStatus: 0,
    resHeaders: {},
    response: null,
    error: null,
  };

  const upstreamReq = mod.request(upstreamUrl, { method: 'POST', headers: outHeaders }, (upstreamRes) => {
    const status = upstreamRes.statusCode || 502;
    exchange.resStatus = status;
    exchange.resHeaders = upstreamRes.headers;
    const contentType = String(upstreamRes.headers['content-type'] || '');
    const isSSE = contentType.includes('text/event-stream');
    const decoder = makeDecoder(upstreamRes.headers['content-encoding']);

    // We always build a normalized response for logging via the chat parser:
    //  - streaming  -> feed the chat SSE aggregator
    //  - non-stream -> parse the buffered JSON once at the end
    const chatAgg = isSSE ? openaiChat.createStreamAggregator() : null;

    if (isSSE) {
      // ---- streaming translation ----
      clientRes.writeHead(status, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache',
        connection: 'close',
      });
      const translator = new ChatToAnthropicStream(originalModel || mappedModel);
      const sseParser = new SSEParser();
      sseParser.on('event', (e) => {
        const raw = (e.data || '').trim();
        if (raw === '' || raw === '[DONE]') return;
        const data = safeJsonParse(raw);
        if (!data) return;
        chatAgg.feed(e); // for the logged normalized response
        for (const frame of translator.feed(data)) clientRes.write(frame);
      });

      const onDecoded = (buf) => sseParser.push(buf.toString('utf8'));
      if (decoder) {
        decoder.on('data', onDecoded);
        decoder.on('error', () => {});
      }
      upstreamRes.on('data', (chunk) => {
        if (decoder) decoder.write(chunk);
        else onDecoded(chunk);
      });
      upstreamRes.on('end', () => {
        const finish = () => {
          sseParser.flush();
          for (const frame of translator.end()) clientRes.write(frame);
          clientRes.end();
          try {
            exchange.response = chatAgg.result();
          } catch (err) {
            exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
          }
          exchange.durationMs = Date.now() - started;
          recorder.record(exchange);
        };
        if (decoder) decoder.end(() => finish());
        else finish();
      });
      upstreamRes.on('error', (err) => {
        exchange.error = `upstream stream error: ${err.message}`;
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        clientRes.destroy();
      });
      return;
    }

    // ---- buffered (non-SSE) translation ----
    // Covers both a plain JSON chat completion and an error body. We may still
    // owe the client an SSE stream (if it asked for one), in which case we
    // synthesize the Anthropic event sequence from the full message.
    const rawCopy = [];
    const onDecoded = (buf) => rawCopy.push(buf);
    if (decoder) {
      decoder.on('data', onDecoded);
      decoder.on('error', () => {});
    }
    upstreamRes.on('data', (chunk) => {
      if (decoder) decoder.write(chunk);
      else onDecoded(chunk);
    });
    upstreamRes.on('end', () => {
      const finish = () => {
        const bodyText = Buffer.concat(rawCopy).toString('utf8');
        const obj = safeJsonParse(bodyText);
        const isError = status >= 400 || (obj && typeof obj === 'object' && obj.error);

        if (isError) {
          const anthErr = chatErrorToAnthropic(obj || bodyText);
          if (wantStream) {
            clientRes.writeHead(status, { 'content-type': 'text/event-stream; charset=utf-8', connection: 'close' });
            clientRes.end(anthropicErrorSSE(obj || bodyText));
          } else {
            const buf = Buffer.from(JSON.stringify(anthErr));
            clientRes.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': String(buf.length) });
            clientRes.end(buf);
          }
          exchange.error = anthErr.error?.message || 'upstream error';
        } else {
          const anthObj = chatResponseToAnthropic(obj || {}, originalModel || mappedModel);
          if (wantStream) {
            clientRes.writeHead(status, { 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-cache', connection: 'close' });
            for (const frame of anthropicMessageToSSE(anthObj)) clientRes.write(frame);
            clientRes.end();
          } else {
            const buf = Buffer.from(JSON.stringify(anthObj));
            clientRes.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': String(buf.length) });
            clientRes.end(buf);
          }
        }
        try {
          exchange.response = openaiChat.parseResponse(bodyText);
        } catch (err) {
          exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
        }
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
      };
      if (decoder) decoder.end(() => finish());
      else finish();
    });
    upstreamRes.on('error', (err) => {
      exchange.error = `upstream stream error: ${err.message}`;
      exchange.durationMs = Date.now() - started;
      recorder.record(exchange);
      clientRes.destroy();
    });
  });

  upstreamReq.on('error', (err) => {
    exchange.error = `upstream request error: ${err.message}`;
    exchange.durationMs = Date.now() - started;
    recorder.record(exchange);
    if (!clientRes.headersSent) clientRes.writeHead(502, { 'content-type': 'application/json' });
    clientRes.end(JSON.stringify(chatErrorToAnthropic({ error: { type: 'proxy_error', message: err.message } })));
  });

  upstreamReq.write(chatBodyBuf);
  upstreamReq.end();
}

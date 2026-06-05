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

function redactHeaders(headers, redact) {
  const out = {};
  for (const [k, v] of Object.entries(headers)) {
    const lk = k.toLowerCase();
    if (redact && (lk === 'authorization' || lk === 'x-api-key' || lk === 'api-key')) {
      const s = Array.isArray(v) ? v.join(',') : String(v);
      out[k] = s.length <= 12 ? '***' : `${s.slice(0, 6)}...${s.slice(-4)}`;
    } else {
      out[k] = v;
    }
  }
  return out;
}

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
  const { baseUrl, wire } = resolveUpstream(config, clientReq.url, clientReq.headers);
  const parser = PARSERS[wire];
  const upstreamUrl = new URL(clientReq.url, baseUrl);
  const isHttps = upstreamUrl.protocol === 'https:';
  const mod = isHttps ? https : http;

  const outHeaders = {};
  for (const [k, v] of Object.entries(clientReq.headers)) {
    if (!HOP_BY_HOP.has(k.toLowerCase())) outHeaders[k] = v;
  }
  outHeaders['host'] = upstreamUrl.host;
  if (reqBodyBuf.length > 0) outHeaders['content-length'] = String(reqBodyBuf.length);

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

  const upstreamReq = mod.request(
    upstreamUrl,
    { method: clientReq.method, headers: outHeaders },
    (upstreamRes) => {
      clientRes.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
      exchange.resStatus = upstreamRes.statusCode || 0;
      exchange.resHeaders = upstreamRes.headers;

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

      upstreamRes.on('data', (chunk) => {
        clientRes.write(chunk); // fidelity: forward raw bytes first
        if (decoder) decoder.write(chunk);
        else onDecoded(chunk);
      });

      upstreamRes.on('end', () => {
        clientRes.end();
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

// OpenAI Chat Completions parser (Codex chat mode / OpenAI-compatible APIs).
//
// Request body (POST /v1/chat/completions):
//   { model, messages:[{role, content, tool_calls, tool_call_id}], tools:[...], stream }
//
// Streaming response (SSE, "data: {json}\n\n", ends with "data: [DONE]"):
//   choices[].delta.content                 -> assistant text
//   choices[].delta.tool_calls[]            -> { index, id, function:{ name, arguments } }
//        accumulate by index: name once, arguments concatenated
//   choices[].finish_reason                 -> stop reason
//   usage (final chunk when stream_options.include_usage)

import { parseToolArgs, emptyResponse, safeJsonParse } from '../model.js';

export function parseRequest(body) {
  const obj = typeof body === 'string' ? safeJsonParse(body) : body;
  if (!obj || typeof obj !== 'object') {
    return { wire: 'chat', model: null, system: null, messages: [], tools: [], stream: false, raw: obj ?? body };
  }
  let system = null;
  const messages = [];
  for (const m of Array.isArray(obj.messages) ? obj.messages : []) {
    if (m.role === 'system' || m.role === 'developer') {
      system = (system ? system + '\n' : '') + (typeof m.content === 'string' ? m.content : '');
      continue;
    }
    const toolUses = Array.isArray(m.tool_calls)
      ? m.tool_calls.map((tc) => ({ id: tc.id, name: tc.function?.name, args: parseToolArgs(tc.function?.arguments) }))
      : [];
    const toolResults = m.role === 'tool' ? [{ toolUseId: m.tool_call_id, content: m.content, isError: false }] : [];
    messages.push({ role: m.role, text: typeof m.content === 'string' ? m.content : '', toolUses, toolResults });
  }
  const tools = Array.isArray(obj.tools)
    ? obj.tools.map((t) => ({ name: t.function?.name ?? t.name, description: t.function?.description ?? t.description }))
    : [];
  return { wire: 'chat', model: obj.model ?? null, system, messages, tools, stream: !!obj.stream, raw: obj };
}

export function parseResponse(body) {
  const obj = typeof body === 'string' ? safeJsonParse(body) : body;
  const res = emptyResponse();
  if (!obj || typeof obj !== 'object') {
    res.raw = obj ?? body;
    return res;
  }
  res.raw = obj;
  res.usage = obj.usage ?? null;
  const choice = Array.isArray(obj.choices) ? obj.choices[0] : null;
  if (choice) {
    res.stopReason = choice.finish_reason ?? null;
    const msg = choice.message || {};
    if (typeof msg.content === 'string') res.text += msg.content;
    for (const tc of Array.isArray(msg.tool_calls) ? msg.tool_calls : []) {
      res.toolCalls.push({ id: tc.id, name: tc.function?.name, args: parseToolArgs(tc.function?.arguments) });
    }
  }
  return res;
}

export function createStreamAggregator() {
  const res = emptyResponse();
  const calls = new Map(); // index -> { id, name, argText }

  function feed(evt) {
    const raw = (evt.data || '').trim();
    if (raw === '' || raw === '[DONE]') return;
    const data = safeJsonParse(raw);
    if (!data) return;
    if (data.usage) res.usage = data.usage;
    const choice = Array.isArray(data.choices) ? data.choices[0] : null;
    if (!choice) return;
    if (choice.finish_reason) res.stopReason = choice.finish_reason;
    const delta = choice.delta || {};
    if (typeof delta.content === 'string') res.text += delta.content;
    for (const tc of Array.isArray(delta.tool_calls) ? delta.tool_calls : []) {
      const idx = tc.index ?? 0;
      let c = calls.get(idx);
      if (!c) {
        c = { id: tc.id, name: tc.function?.name, argText: '' };
        calls.set(idx, c);
      }
      if (tc.id) c.id = tc.id;
      if (tc.function?.name) c.name = tc.function.name;
      if (tc.function?.arguments) c.argText += tc.function.arguments;
    }
  }

  function result() {
    if (res.toolCalls.length === 0 && calls.size > 0) {
      for (const [, c] of [...calls.entries()].sort((a, b) => a[0] - b[0])) {
        res.toolCalls.push({ id: c.id, name: c.name, args: parseToolArgs(c.argText) });
      }
    }
    return res;
  }

  return { feed, result };
}

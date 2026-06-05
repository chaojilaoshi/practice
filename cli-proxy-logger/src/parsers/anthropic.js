// Anthropic Messages API parser (Claude Code).
//
// Request body (POST /v1/messages):
//   { model, system, messages:[{role, content:[blocks]}], tools:[...], stream }
//   - content blocks of type "tool_use" are the model's tool calls (in prior turns)
//   - content blocks of type "tool_result" are the tool outputs fed back in
//
// Streaming response (SSE):
//   message_start -> { message: { usage, ... } }
//   content_block_start -> { index, content_block: { type, ... } }
//        type "text"     -> accumulate text_delta
//        type "tool_use" -> { id, name }, then input_json_delta partial_json
//   content_block_delta -> { index, delta: { type, text|partial_json } }
//   content_block_stop  -> { index }
//   message_delta       -> { delta: { stop_reason }, usage }
//   message_stop

import { parseToolArgs, emptyResponse, safeJsonParse } from '../model.js';

function blockText(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('');
}

export function parseRequest(body) {
  const obj = typeof body === 'string' ? safeJsonParse(body) : body;
  if (!obj || typeof obj !== 'object') {
    return { wire: 'anthropic', model: null, system: null, messages: [], tools: [], stream: false, raw: obj ?? body };
  }
  const messages = Array.isArray(obj.messages)
    ? obj.messages.map((m) => {
        const content = m.content;
        const toolUses = Array.isArray(content)
          ? content.filter((b) => b && b.type === 'tool_use').map((b) => ({ id: b.id, name: b.name, args: b.input }))
          : [];
        const toolResults = Array.isArray(content)
          ? content
              .filter((b) => b && b.type === 'tool_result')
              .map((b) => ({ toolUseId: b.tool_use_id, content: b.content, isError: !!b.is_error }))
          : [];
        return { role: m.role, text: blockText(content), toolUses, toolResults };
      })
    : [];
  const system = typeof obj.system === 'string' ? obj.system : blockText(obj.system);
  const tools = Array.isArray(obj.tools)
    ? obj.tools.map((t) => ({ name: t.name, description: t.description }))
    : [];
  return { wire: 'anthropic', model: obj.model ?? null, system: system || null, messages, tools, stream: !!obj.stream, raw: obj };
}

// Parse a complete (non-streaming) response body.
export function parseResponse(body) {
  const obj = typeof body === 'string' ? safeJsonParse(body) : body;
  const res = emptyResponse();
  if (!obj || typeof obj !== 'object') {
    res.raw = obj ?? body;
    return res;
  }
  res.raw = obj;
  res.stopReason = obj.stop_reason ?? null;
  res.usage = obj.usage ?? null;
  if (Array.isArray(obj.content)) {
    for (const b of obj.content) {
      if (b.type === 'text') res.text += b.text ?? '';
      else if (b.type === 'tool_use') res.toolCalls.push({ id: b.id, name: b.name, args: b.input ?? {} });
    }
  }
  return res;
}

// Stateful aggregator for streamed events.
export function createStreamAggregator() {
  const res = emptyResponse();
  const blocks = new Map(); // index -> { type, name, id, argText }

  function feed(evt) {
    const type = evt.event;
    const data = safeJsonParse(evt.data);
    if (!data) return;
    switch (type) {
      case 'message_start':
        if (data.message?.usage) res.usage = data.message.usage;
        break;
      case 'content_block_start': {
        const cb = data.content_block || {};
        blocks.set(data.index, { type: cb.type, name: cb.name, id: cb.id, argText: '' });
        break;
      }
      case 'content_block_delta': {
        const b = blocks.get(data.index);
        const d = data.delta || {};
        if (d.type === 'text_delta') res.text += d.text ?? '';
        else if (d.type === 'input_json_delta' && b) b.argText += d.partial_json ?? '';
        break;
      }
      case 'content_block_stop': {
        const b = blocks.get(data.index);
        if (b && b.type === 'tool_use') {
          res.toolCalls.push({ id: b.id, name: b.name, args: parseToolArgs(b.argText) });
        }
        break;
      }
      case 'message_delta':
        if (data.delta?.stop_reason) res.stopReason = data.delta.stop_reason;
        if (data.usage) res.usage = { ...(res.usage || {}), ...data.usage };
        break;
      default:
        break;
    }
  }

  return { feed, result: () => res };
}

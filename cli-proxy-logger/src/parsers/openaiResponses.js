// OpenAI Responses API parser (Codex default, wire_api = "responses").
//
// Request body (POST /v1/responses):
//   { model, instructions, input:[items], tools:[...], stream }
//   - input items can be messages or prior function_call / function_call_output
//
// Streaming response (SSE, semantic events):
//   response.created / response.in_progress
//   response.output_item.added -> { item: { type, id, name, call_id } }
//        type "function_call" -> tool call begins
//   response.function_call_arguments.delta -> { item_id, delta }  (accumulate args)
//   response.function_call_arguments.done  -> { item_id, arguments }
//   response.output_text.delta -> { delta }   (assistant text)
//   response.output_item.done / response.completed -> { response: { usage,... } }

import { parseToolArgs, emptyResponse, safeJsonParse } from '../model.js';

function inputText(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((c) => c && (c.type === 'input_text' || c.type === 'output_text' || c.type === 'text'))
    .map((c) => c.text ?? '')
    .join('');
}

export function parseRequest(body) {
  const obj = typeof body === 'string' ? safeJsonParse(body) : body;
  if (!obj || typeof obj !== 'object') {
    return { wire: 'responses', model: null, system: null, messages: [], tools: [], stream: false, raw: obj ?? body };
  }
  const items = Array.isArray(obj.input) ? obj.input : [];
  const messages = [];
  for (const it of items) {
    if (it.type === 'function_call' || (it.name && it.call_id && it.arguments !== undefined)) {
      messages.push({ role: 'assistant', text: '', toolUses: [{ id: it.call_id, name: it.name, args: parseToolArgs(it.arguments) }], toolResults: [] });
    } else if (it.type === 'function_call_output') {
      messages.push({ role: 'tool', text: '', toolUses: [], toolResults: [{ toolUseId: it.call_id, content: it.output, isError: false }] });
    } else {
      messages.push({ role: it.role || 'user', text: inputText(it.content), toolUses: [], toolResults: [] });
    }
  }
  const tools = Array.isArray(obj.tools)
    ? obj.tools.map((t) => ({ name: t.name ?? t.function?.name, description: t.description ?? t.function?.description }))
    : [];
  return { wire: 'responses', model: obj.model ?? null, system: obj.instructions ?? null, messages, tools, stream: !!obj.stream, raw: obj };
}

export function parseResponse(body) {
  const obj = typeof body === 'string' ? safeJsonParse(body) : body;
  const res = emptyResponse();
  if (!obj || typeof obj !== 'object') {
    res.raw = obj ?? body;
    return res;
  }
  res.raw = obj;
  res.stopReason = obj.status ?? null;
  res.usage = obj.usage ?? null;
  const output = Array.isArray(obj.output) ? obj.output : [];
  for (const item of output) {
    if (item.type === 'function_call') {
      res.toolCalls.push({ id: item.call_id ?? item.id, name: item.name, args: parseToolArgs(item.arguments) });
    } else if (item.type === 'message' && Array.isArray(item.content)) {
      res.text += inputText(item.content);
    }
  }
  return res;
}

// The Responses API streams "semantic" events that already name what is
// happening (response.output_item.added, ...function_call_arguments.delta, ...).
// Each output item has a stable item_id, so we accumulate tool-call arguments
// per item_id and emit the ToolCall when the item is done.
export function createStreamAggregator() {
  const res = emptyResponse();
  const calls = new Map(); // item_id -> { id, name, call_id, argText }

  function feed(evt) {
    // Responses events carry their type in the data payload's "type" too, but
    // the SSE "event:" line is authoritative for Codex's stream.
    const data = safeJsonParse(evt.data);
    if (!data) return;
    const type = evt.event || data.type;
    switch (type) {
      case 'response.output_item.added': {
        // A new output item appears; remember it if it's a function call.
        const item = data.item || {};
        if (item.type === 'function_call') {
          calls.set(item.id ?? data.item_id, { id: item.call_id ?? item.id, name: item.name, argText: '' });
        }
        break;
      }
      case 'response.function_call_arguments.delta': {
        // Argument JSON streams in as fragments -> concatenate by item_id.
        const c = calls.get(data.item_id);
        if (c) c.argText += data.delta ?? '';
        break;
      }
      case 'response.function_call_arguments.done': {
        // Some servers send the full arguments here instead of deltas; use it
        // only as a fallback when we never received any delta fragments.
        const c = calls.get(data.item_id);
        if (c && data.arguments !== undefined && c.argText === '') c.argText = data.arguments;
        break;
      }
      case 'response.output_item.done': {
        // Item finished -> finalize the tool call (parse accumulated args).
        const item = data.item || {};
        if (item.type === 'function_call') {
          const c = calls.get(item.id ?? data.item_id) || { id: item.call_id ?? item.id, name: item.name, argText: item.arguments ?? '' };
          res.toolCalls.push({ id: c.id, name: c.name, args: parseToolArgs(c.argText) });
          calls.delete(item.id ?? data.item_id);
        }
        break;
      }
      case 'response.output_text.delta':
        res.text += data.delta ?? '';
        break;
      case 'response.completed':
      case 'response.incomplete':
      case 'response.failed':
        if (data.response?.usage) res.usage = data.response.usage;
        if (data.response?.status) res.stopReason = data.response.status;
        // Flush any function calls that never got an explicit done event.
        for (const [, c] of calls) res.toolCalls.push({ id: c.id, name: c.name, args: parseToolArgs(c.argText) });
        calls.clear();
        break;
      default:
        break;
    }
  }

  return { feed, result: () => res };
}

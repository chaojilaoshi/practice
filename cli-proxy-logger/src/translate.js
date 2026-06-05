// Protocol translation: Anthropic Messages  <->  OpenAI Chat Completions.
//
// WHY this module exists
// ----------------------
// The proxy is normally a *transparent* pass-through: it forwards the client's
// bytes to the upstream untouched and streams the answer back byte-for-byte.
// That works when the CLI and the upstream speak the SAME wire format.
//
// But some third-party vendors only expose `/v1/chat/completions` (OpenAI Chat
// format). Claude Code (and opencode's anthropic provider) only speak Anthropic
// `/v1/messages`. To bridge them we must TRANSLATE in BOTH directions:
//
//   client (Anthropic /v1/messages request)
//        -> [anthropicRequestToChat]  -> vendor (OpenAI /v1/chat/completions)
//   vendor (OpenAI Chat response, JSON or SSE)
//        -> [chatResponseToAnthropic / ChatToAnthropicStream] -> client (Anthropic)
//
// This is opt-in (ANTHROPIC_COMPAT=chat). When off, the transparent path is used
// and this module is never touched.
//
// The hardest part is the STREAMING response translation: OpenAI streams
// `choices[].delta` chunks (text in `delta.content`, tool calls split across
// `delta.tool_calls[].index`), while Anthropic streams a sequence of *indexed
// content blocks* (message_start -> content_block_start/delta/stop ... ->
// message_delta -> message_stop). We rebuild the Anthropic event sequence on the
// fly. See ChatToAnthropicStream below.

import { randomUUID } from 'node:crypto';
import { safeJsonParse } from './model.js';

// ----- model mapping -------------------------------------------------------
// The user configures a map from the model name the CLI sends (e.g.
// "claude-sonnet-4-6") to the model the vendor actually serves (e.g. "gpt-4o").
// If a model is not in the map we pass it through unchanged.
export function mapModel(model, modelMap) {
  if (!model) return model;
  if (modelMap && Object.prototype.hasOwnProperty.call(modelMap, model)) {
    return modelMap[model];
  }
  return model;
}

// ----- helpers -------------------------------------------------------------

// Anthropic `system` may be a plain string OR an array of text blocks. OpenAI
// wants a single system message string, so flatten to text.
function systemToText(system) {
  if (typeof system === 'string') return system;
  if (Array.isArray(system)) {
    return system
      .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
      .map((b) => b.text)
      .join('');
  }
  return '';
}

// Anthropic tool_result content can be a string OR an array of blocks (usually
// text, sometimes images). OpenAI tool messages take a plain string, so flatten.
function toolResultToText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((b) => {
        if (typeof b === 'string') return b;
        if (b && b.type === 'text') return b.text ?? '';
        // Non-text tool output (e.g. image) — keep a JSON marker so nothing is lost.
        return typeof b === 'object' ? JSON.stringify(b) : String(b);
      })
      .join('');
  }
  if (content == null) return '';
  return typeof content === 'object' ? JSON.stringify(content) : String(content);
}

// Convert an Anthropic content-block array (a `user` message) into OpenAI
// "content parts" (text + image_url). If it is only text we return a string,
// which is the common/cheap case.
function userContentToChat(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  const parts = [];
  for (const b of content) {
    if (!b || typeof b !== 'object') continue;
    if (b.type === 'text') {
      parts.push({ type: 'text', text: b.text ?? '' });
    } else if (b.type === 'image' && b.source) {
      // Anthropic base64 image -> OpenAI data: URL.
      if (b.source.type === 'base64') {
        parts.push({
          type: 'image_url',
          image_url: { url: `data:${b.source.media_type};base64,${b.source.data}` },
        });
      } else if (b.source.type === 'url') {
        parts.push({ type: 'image_url', image_url: { url: b.source.url } });
      }
    }
  }
  // Collapse to a single string when there is exactly one text part and nothing
  // else — keeps the upstream request simple and matches what most vendors expect.
  if (parts.length === 1 && parts[0].type === 'text') return parts[0].text;
  if (parts.length === 0) return '';
  return parts;
}

// ----- request: Anthropic /v1/messages  ->  OpenAI /v1/chat/completions ----
//
// Anthropic request shape:
//   { model, system, max_tokens, temperature, top_p, stop_sequences, stream,
//     tools:[{name,description,input_schema}], tool_choice,
//     messages:[{role:'user'|'assistant', content: string | block[]}] }
//   block types: text | image | tool_use (assistant) | tool_result (user)
//
// OpenAI Chat request shape:
//   { model, messages:[{role, content, tool_calls, tool_call_id}],
//     tools:[{type:'function',function:{name,description,parameters}}],
//     tool_choice, max_tokens, temperature, top_p, stop, stream, stream_options }
export function anthropicRequestToChat(anthBody, modelMap) {
  const messages = [];

  // 1) system prompt -> a leading system message.
  const sysText = systemToText(anthBody.system);
  if (sysText) messages.push({ role: 'system', content: sysText });

  // 2) walk the conversation turns.
  for (const m of Array.isArray(anthBody.messages) ? anthBody.messages : []) {
    const content = m.content;

    if (m.role === 'assistant') {
      // Assistant turn: text becomes `content`, each tool_use becomes an entry
      // in `tool_calls` (OpenAI keeps tool calls OUT of content).
      let text = '';
      const toolCalls = [];
      if (typeof content === 'string') {
        text = content;
      } else if (Array.isArray(content)) {
        for (const b of content) {
          if (!b || typeof b !== 'object') continue;
          if (b.type === 'text') text += b.text ?? '';
          else if (b.type === 'tool_use') {
            toolCalls.push({
              id: b.id,
              type: 'function',
              function: { name: b.name, arguments: JSON.stringify(b.input ?? {}) },
            });
          }
        }
      }
      const msg = { role: 'assistant', content: text || null };
      if (toolCalls.length > 0) msg.tool_calls = toolCalls;
      messages.push(msg);
      continue;
    }

    // user turn: tool_result blocks must each become a separate `tool` message
    // (OpenAI links them back to the assistant call via tool_call_id). The
    // remaining text/image blocks become one `user` message.
    if (Array.isArray(content)) {
      const toolResults = content.filter((b) => b && b.type === 'tool_result');
      const rest = content.filter((b) => !b || b.type !== 'tool_result');
      for (const tr of toolResults) {
        messages.push({
          role: 'tool',
          tool_call_id: tr.tool_use_id,
          content: toolResultToText(tr.content),
        });
      }
      const userContent = userContentToChat(rest);
      // Skip an empty user message that held only tool_results.
      if (!(Array.isArray(userContent) ? userContent.length === 0 : userContent === '')) {
        messages.push({ role: 'user', content: userContent });
      }
    } else {
      messages.push({ role: 'user', content: userContentToChat(content) });
    }
  }

  const out = {
    model: mapModel(anthBody.model, modelMap),
    messages,
    stream: !!anthBody.stream,
  };

  // 3) translate sampling / limit params (only when present).
  if (anthBody.max_tokens != null) out.max_tokens = anthBody.max_tokens;
  if (anthBody.temperature != null) out.temperature = anthBody.temperature;
  if (anthBody.top_p != null) out.top_p = anthBody.top_p;
  if (Array.isArray(anthBody.stop_sequences) && anthBody.stop_sequences.length > 0) {
    out.stop = anthBody.stop_sequences;
  }

  // 4) tool definitions: Anthropic input_schema -> OpenAI function.parameters.
  if (Array.isArray(anthBody.tools) && anthBody.tools.length > 0) {
    out.tools = anthBody.tools.map((t) => ({
      type: 'function',
      function: {
        name: t.name,
        description: t.description ?? '',
        parameters: t.input_schema ?? { type: 'object', properties: {} },
      },
    }));
  }

  // 5) tool_choice: {type:'auto'|'any'|'tool', name?} -> 'auto'|'required'|{...}.
  if (anthBody.tool_choice && typeof anthBody.tool_choice === 'object') {
    const tc = anthBody.tool_choice;
    if (tc.type === 'auto') out.tool_choice = 'auto';
    else if (tc.type === 'any') out.tool_choice = 'required';
    else if (tc.type === 'tool' && tc.name) {
      out.tool_choice = { type: 'function', function: { name: tc.name } };
    }
  }

  // 6) ask the vendor to include token usage in the streamed final chunk, so we
  // can forward real usage numbers back in the Anthropic message_delta event.
  if (out.stream) out.stream_options = { include_usage: true };

  return out;
}

// ----- finish_reason / usage mapping --------------------------------------

// OpenAI finish_reason -> Anthropic stop_reason. If the model emitted tool
// calls, Anthropic's correct stop_reason is "tool_use" regardless.
function mapStopReason(finishReason, hadToolCalls) {
  if (hadToolCalls) return 'tool_use';
  switch (finishReason) {
    case 'stop':
      return 'end_turn';
    case 'length':
      return 'max_tokens';
    case 'tool_calls':
    case 'function_call':
      return 'tool_use';
    case 'content_filter':
      return 'end_turn';
    default:
      return finishReason ? 'end_turn' : null;
  }
}

// OpenAI usage {prompt_tokens, completion_tokens} -> Anthropic {input_tokens,
// output_tokens}. Returns null when usage is absent.
function mapUsage(usage) {
  if (!usage || typeof usage !== 'object') return null;
  return {
    input_tokens: usage.prompt_tokens ?? 0,
    output_tokens: usage.completion_tokens ?? 0,
  };
}

function newMessageId() {
  return 'msg_' + randomUUID().replace(/-/g, '');
}

function newToolUseId() {
  return 'toolu_' + randomUUID().replace(/-/g, '');
}

// ----- response (non-streaming): OpenAI Chat JSON -> Anthropic message ------
export function chatResponseToAnthropic(chatObj, displayModel) {
  const choice = Array.isArray(chatObj?.choices) ? chatObj.choices[0] : null;
  const msg = choice?.message || {};
  const content = [];

  if (typeof msg.content === 'string' && msg.content.length > 0) {
    content.push({ type: 'text', text: msg.content });
  }
  const toolCalls = Array.isArray(msg.tool_calls) ? msg.tool_calls : [];
  for (const tc of toolCalls) {
    const args = safeJsonParse(tc.function?.arguments);
    content.push({
      type: 'tool_use',
      id: tc.id || newToolUseId(),
      name: tc.function?.name || '',
      input: args !== undefined ? args : {},
    });
  }
  // Anthropic always returns at least one content block.
  if (content.length === 0) content.push({ type: 'text', text: '' });

  return {
    id: chatObj?.id || newMessageId(),
    type: 'message',
    role: 'assistant',
    model: displayModel || chatObj?.model || '',
    content,
    stop_reason: mapStopReason(choice?.finish_reason, toolCalls.length > 0),
    stop_sequence: null,
    usage: mapUsage(chatObj?.usage) || { input_tokens: 0, output_tokens: 0 },
  };
}

// Convert an OpenAI error body to an Anthropic-style error envelope, so Claude
// Code sees an error shape it understands.
export function chatErrorToAnthropic(obj) {
  const e = obj && typeof obj === 'object' ? obj.error || obj : {};
  return {
    type: 'error',
    error: {
      type: e.type || 'api_error',
      message: e.message || (typeof obj === 'string' ? obj : 'upstream error'),
    },
  };
}

// ----- SSE serialization ---------------------------------------------------
function sse(event, data) {
  // Anthropic SSE frames name the event type on an `event:` line AND repeat the
  // type inside the JSON `data:` payload — Claude Code reads both.
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

// ----- response (streaming): OpenAI Chat SSE -> Anthropic SSE ---------------
//
// We translate a stream of OpenAI chunks into the Anthropic event sequence.
//
// OpenAI per-chunk:  choices[0].delta = { content?, tool_calls?[{index,id,function:{name,arguments}}] }
// Anthropic blocks:  block 0 is usually the assistant text; each tool call is
//                    its own block. A block must be opened (content_block_start),
//                    streamed (content_block_delta), and closed (content_block_stop).
//
// Mapping rules implemented below:
//   - first chunk            -> emit message_start
//   - first text delta       -> open a text block, then stream text_delta's
//   - first chunk of a tool  -> close the open block, open a tool_use block
//                               (carrying id+name), then stream input_json_delta's
//   - stream end             -> close the open block, emit message_delta (with
//                               stop_reason + usage) then message_stop
//
// `feed(chunk)` and `end()` each RETURN an array of ready-to-write SSE strings.
export class ChatToAnthropicStream {
  constructor(displayModel) {
    this.displayModel = displayModel || '';
    this.started = false;
    this.nextIndex = 0; // next Anthropic block index to hand out
    this.openIndex = null; // Anthropic index of the block currently open (or null)
    this.textIndex = null; // Anthropic index of the (single) text block, if opened
    this.tools = new Map(); // OpenAI tool index -> { anthropicIndex }
    this.finishReason = null;
    this.usage = null;
    this.out = [];
  }

  _emit(event, data) {
    this.out.push(sse(event, data));
  }

  _flush() {
    const o = this.out;
    this.out = [];
    return o;
  }

  _ensureStarted() {
    if (this.started) return;
    this.started = true;
    this._emit('message_start', {
      type: 'message_start',
      message: {
        id: newMessageId(),
        type: 'message',
        role: 'assistant',
        model: this.displayModel,
        content: [],
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: 0, output_tokens: 0 },
      },
    });
  }

  _closeOpen() {
    if (this.openIndex !== null) {
      this._emit('content_block_stop', { type: 'content_block_stop', index: this.openIndex });
      if (this.textIndex === this.openIndex) this.textIndex = null;
      this.openIndex = null;
    }
  }

  feed(chunk) {
    this._ensureStarted();
    if (chunk.usage) this.usage = chunk.usage;
    const choice = Array.isArray(chunk.choices) ? chunk.choices[0] : null;
    if (!choice) return this._flush();
    if (choice.finish_reason) this.finishReason = choice.finish_reason;
    const delta = choice.delta || {};

    // --- text ---
    if (typeof delta.content === 'string' && delta.content.length > 0) {
      // Open a fresh text block if none is currently open (a tool call may have
      // closed a previous one). Reuse the open text block otherwise.
      if (this.openIndex === null || this.openIndex !== this.textIndex) {
        this._closeOpen();
        this.textIndex = this.nextIndex++;
        this.openIndex = this.textIndex;
        this._emit('content_block_start', {
          type: 'content_block_start',
          index: this.textIndex,
          content_block: { type: 'text', text: '' },
        });
      }
      this._emit('content_block_delta', {
        type: 'content_block_delta',
        index: this.textIndex,
        delta: { type: 'text_delta', text: delta.content },
      });
    }

    // --- tool calls ---
    for (const tc of Array.isArray(delta.tool_calls) ? delta.tool_calls : []) {
      const oidx = tc.index ?? 0;
      let slot = this.tools.get(oidx);
      if (!slot) {
        // First fragment of a new tool call: close whatever block is open and
        // open a tool_use block carrying id + name (args stream in next).
        this._closeOpen();
        const aidx = this.nextIndex++;
        slot = { anthropicIndex: aidx };
        this.tools.set(oidx, slot);
        this.openIndex = aidx;
        this._emit('content_block_start', {
          type: 'content_block_start',
          index: aidx,
          content_block: {
            type: 'tool_use',
            id: tc.id || newToolUseId(),
            name: tc.function?.name || '',
            input: {},
          },
        });
      }
      const argFrag = tc.function?.arguments;
      if (argFrag) {
        this._emit('content_block_delta', {
          type: 'content_block_delta',
          index: slot.anthropicIndex,
          delta: { type: 'input_json_delta', partial_json: argFrag },
        });
      }
    }

    return this._flush();
  }

  end() {
    this._ensureStarted(); // handle an empty stream gracefully
    this._closeOpen();
    this._emit('message_delta', {
      type: 'message_delta',
      delta: {
        stop_reason: mapStopReason(this.finishReason, this.tools.size > 0),
        stop_sequence: null,
      },
      usage: mapUsage(this.usage) || { output_tokens: 0 },
    });
    this._emit('message_stop', { type: 'message_stop' });
    return this._flush();
  }
}

// Build the full Anthropic SSE byte sequence from a COMPLETE (non-streamed)
// chat completion. Used when the client asked for a stream but the vendor
// answered with a single JSON body — we still owe the client an event stream.
export function anthropicMessageToSSE(anthObj) {
  const frames = [];
  frames.push(
    sse('message_start', {
      type: 'message_start',
      message: {
        id: anthObj.id,
        type: 'message',
        role: 'assistant',
        model: anthObj.model,
        content: [],
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: anthObj.usage?.input_tokens ?? 0, output_tokens: 0 },
      },
    }),
  );
  anthObj.content.forEach((block, index) => {
    if (block.type === 'text') {
      frames.push(
        sse('content_block_start', {
          type: 'content_block_start',
          index,
          content_block: { type: 'text', text: '' },
        }),
      );
      frames.push(
        sse('content_block_delta', {
          type: 'content_block_delta',
          index,
          delta: { type: 'text_delta', text: block.text ?? '' },
        }),
      );
    } else if (block.type === 'tool_use') {
      frames.push(
        sse('content_block_start', {
          type: 'content_block_start',
          index,
          content_block: { type: 'tool_use', id: block.id, name: block.name, input: {} },
        }),
      );
      frames.push(
        sse('content_block_delta', {
          type: 'content_block_delta',
          index,
          delta: { type: 'input_json_delta', partial_json: JSON.stringify(block.input ?? {}) },
        }),
      );
    }
    frames.push(sse('content_block_stop', { type: 'content_block_stop', index }));
  });
  frames.push(
    sse('message_delta', {
      type: 'message_delta',
      delta: { stop_reason: anthObj.stop_reason, stop_sequence: anthObj.stop_sequence ?? null },
      usage: { output_tokens: anthObj.usage?.output_tokens ?? 0 },
    }),
  );
  frames.push(sse('message_stop', { type: 'message_stop' }));
  return frames;
}

// An Anthropic streaming `error` event (used when the vendor returns an error
// but the client is in streaming mode).
export function anthropicErrorSSE(obj) {
  return sse('error', chatErrorToAnthropic(obj));
}

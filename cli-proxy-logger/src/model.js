// Unified data model. All three wire formats (Anthropic Messages, OpenAI
// Responses, OpenAI Chat Completions) are normalized into these shapes so the
// recorder and UI can treat them uniformly.
//
// NormalizedRequest {
//   wire: 'anthropic'|'responses'|'chat',
//   model: string|null,
//   system: string|null,
//   messages: Array<{ role, text, toolUses, toolResults }>,
//   tools: Array<{ name, description }>,   // tool *definitions* offered to the model
//   stream: boolean,
//   raw: any
// }
//
// ToolCall { id, name, args }   // args = parsed object (or { _raw } if unparseable)
//
// NormalizedResponse {
//   text: string,
//   toolCalls: ToolCall[],
//   stopReason: string|null,
//   usage: object|null,
//   raw: any|null
// }

export function safeJsonParse(str) {
  if (typeof str !== 'string' || str.length === 0) return undefined;
  try {
    return JSON.parse(str);
  } catch {
    return undefined;
  }
}

// Parse accumulated tool-argument JSON string into an object, preserving the
// raw text when it is not valid JSON (e.g. truncated stream).
export function parseToolArgs(rawString) {
  const parsed = safeJsonParse(rawString);
  if (parsed !== undefined) return parsed;
  return rawString === '' || rawString === undefined ? {} : { _raw: rawString };
}

export function emptyResponse() {
  return { text: '', toolCalls: [], stopReason: null, usage: null, raw: null };
}

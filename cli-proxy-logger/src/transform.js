// Tool-name normalization (工具名规范化) — opt-in.
//
// Why: some Anthropic-compatible upstreams (e.g. anyrouter) VALIDATE tool names
// and reject lowercase names that clients such as opencode emit (`read`, `write`,
// `edit`...). Renaming them to PascalCase (`Read`, `Write`, `Edit`) makes the
// upstream accept the request. This module rewrites tool names on the request
// (the critical fix) and, optionally, on the response so the round-trip stays
// consistent; it also repairs tool-call inputs whose array/object values were
// serialized to a JSON *string* by the upstream.
//
// All of this is OFF by default. When `TOOL_NAME_CASE` is enabled the proxy is
// no longer a byte-for-byte transparent relay for Anthropic traffic — that is
// the whole point of the feature, and it only affects opted-in requests.

// Built-in special-cases where simple capitalization is not the desired name.
// Extend/override via TOOL_NAME_MAP / TOOL_NAME_MAP_FILE.
export const DEFAULT_TOOL_NAME_MAP = {
  todowrite: 'TodoWrite',
  todoread: 'TodoRead',
  webfetch: 'WebFetch',
  websearch: 'WebSearch',
  google_search: 'Google_Search',
  multiedit: 'MultiEdit',
  notebookedit: 'NotebookEdit',
  notebookread: 'NotebookRead',
};

// Map one tool name. Exact match in the map wins; otherwise capitalize the
// first character (matching the user's reference reverse-proxy behavior).
export function mapToolName(name, map) {
  if (!name || typeof name !== 'string') return name;
  const m = map || {};
  if (Object.prototype.hasOwnProperty.call(m, name)) return m[name];
  // Case-insensitive lookup against the (lowercased) built-in keys.
  const lower = name.toLowerCase();
  if (Object.prototype.hasOwnProperty.call(m, lower)) return m[lower];
  return name.charAt(0).toUpperCase() + name.slice(1);
}

// Rewrite tool names on an Anthropic /v1/messages REQUEST body object, in place.
// Touches `tools[].name` and any historical `tool_use` blocks in messages.
// Returns the number of names changed.
export function normalizeRequestToolNames(body, map) {
  if (!body || typeof body !== 'object') return 0;
  let changed = 0;
  const rename = (obj) => {
    const next = mapToolName(obj.name, map);
    if (next !== obj.name) {
      obj.name = next;
      changed += 1;
    }
  };
  if (Array.isArray(body.tools)) {
    for (const tool of body.tools) {
      if (tool && typeof tool === 'object' && typeof tool.name === 'string') rename(tool);
    }
  }
  if (Array.isArray(body.messages)) {
    for (const msg of body.messages) {
      if (!msg || !Array.isArray(msg.content)) continue;
      for (const block of msg.content) {
        if (block && block.type === 'tool_use' && typeof block.name === 'string') rename(block);
      }
    }
  }
  return changed;
}

// Rewrite tool_use names on an Anthropic RESPONSE body object, in place.
export function rewriteResponseToolNames(body, map) {
  if (!body || !Array.isArray(body.content)) return 0;
  let changed = 0;
  for (const block of body.content) {
    if (block && block.type === 'tool_use' && typeof block.name === 'string') {
      const next = mapToolName(block.name, map);
      if (next !== block.name) {
        block.name = next;
        changed += 1;
      }
    }
  }
  return changed;
}

// Repair tool_use inputs where the upstream serialized an array/object value as
// a JSON *string* (a known quirk of some vendors). Parses those strings back
// into real arrays/objects, in place. Returns the number of values repaired.
export function repairToolUseInput(body) {
  if (!body || !Array.isArray(body.content)) return 0;
  let repaired = 0;
  for (const block of body.content) {
    if (!block || block.type !== 'tool_use' || !block.input || typeof block.input !== 'object') continue;
    for (const key of Object.keys(block.input)) {
      const val = block.input[key];
      if (typeof val !== 'string') continue;
      const t = val.trim();
      if (!(t.startsWith('[') || t.startsWith('{'))) continue;
      try {
        block.input[key] = JSON.parse(t);
        repaired += 1;
      } catch {
        /* leave non-JSON strings untouched */
      }
    }
  }
  return repaired;
}

// Rewrite the tool name carried by a parsed SSE `content_block_start` event
// object, in place. Returns true when the name changed.
export function rewriteStreamEventToolName(data, map) {
  if (
    data &&
    data.type === 'content_block_start' &&
    data.content_block &&
    data.content_block.type === 'tool_use' &&
    typeof data.content_block.name === 'string'
  ) {
    const next = mapToolName(data.content_block.name, map);
    if (next !== data.content_block.name) {
      data.content_block.name = next;
      return true;
    }
  }
  return false;
}

// Decide which upstream a request should be forwarded to and which wire format
// it uses, based on the request path. This is what lets a single proxy port
// serve both Claude Code (Anthropic Messages) and Codex (OpenAI Responses/Chat).
//
//   /v1/messages            -> anthropic  (Claude Code)
//   /v1/responses           -> openai responses (Codex default, wire_api="responses")
//   /v1/chat/completions     -> openai chat (Codex chat mode / OpenAI-compatible)
//   anything else            -> guessed from headers, defaults to anthropic

export function resolveUpstream(config, reqPath, headers) {
  const p = (reqPath || '').split('?')[0];

  if (p.startsWith('/v1/messages')) {
    return { baseUrl: config.upstream.anthropic, wire: 'anthropic' };
  }
  if (p.startsWith('/v1/responses')) {
    return { baseUrl: config.upstream.openai, wire: 'responses' };
  }
  if (p.startsWith('/v1/chat/completions')) {
    return { baseUrl: config.upstream.openai, wire: 'chat' };
  }
  if (p.startsWith('/v1/models') || p.startsWith('/v1/complete')) {
    // Model discovery / legacy completion: route by auth header style.
    if (headers['x-api-key'] || headers['anthropic-version']) {
      return { baseUrl: config.upstream.anthropic, wire: 'anthropic' };
    }
    return { baseUrl: config.upstream.openai, wire: 'chat' };
  }

  // Fallback: Anthropic uses x-api-key + anthropic-version, OpenAI uses Bearer.
  if (headers['x-api-key'] || headers['anthropic-version']) {
    return { baseUrl: config.upstream.anthropic, wire: 'anthropic' };
  }
  return { baseUrl: config.upstream.openai, wire: 'chat' };
}

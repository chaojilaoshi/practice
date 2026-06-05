// Provider pool (供应商池) — the ordered list of upstreams we may fail over
// between, grouped by "upstream group".
//
// There are two upstream GROUPS, matching the two protocol families the proxy
// already speaks:
//   - 'anthropic' : serves the Anthropic wire  (/v1/messages)
//   - 'openai'    : serves the OpenAI wires     (/v1/responses, /v1/chat/completions)
//
// A provider entry looks like:
//   { "id": "vendorA", "group": "openai", "baseUrl": "https://a.example.com",
//     "apiKey": "sk-..." }
// `apiKey` is optional: when present we REPLACE the client's auth header with it
// (different vendors need different keys); when absent we forward the client's
// own credential unchanged (the transparent default).
//
// Config sources (first non-empty wins): PROVIDERS_FILE (path to JSON) then
// PROVIDERS (inline JSON). When NO pool is configured for a group we fall back
// to the single legacy upstream (config.upstream.*) — so default behavior is
// completely unchanged unless you opt in by configuring a pool.

// Normalize a free-form "group"/"wire" hint onto one of our two groups.
function normGroup(g) {
  const s = String(g || '').toLowerCase();
  if (s === 'anthropic' || s === 'messages') return 'anthropic';
  // responses / chat / openai / completions all map to the openai group.
  return 'openai';
}

// Map a resolved request wire ('anthropic' | 'responses' | 'chat') to its group.
export function wireToGroup(wire) {
  return wire === 'anthropic' ? 'anthropic' : 'openai';
}

// Parse a providers array (already-parsed JS array OR a JSON string) into
// { anthropic: [...], openai: [...] }. Invalid entries are skipped.
export function parseProviders(raw) {
  let arr = raw;
  if (typeof raw === 'string') {
    const t = raw.trim();
    if (t === '') return { anthropic: [], openai: [] };
    try {
      arr = JSON.parse(t);
    } catch {
      return { anthropic: [], openai: [] };
    }
  }
  const pools = { anthropic: [], openai: [] };
  if (!Array.isArray(arr)) return pools;
  let auto = 0;
  for (const p of arr) {
    if (!p || typeof p !== 'object' || !p.baseUrl) continue;
    const group = normGroup(p.group ?? p.wire ?? p.upstream);
    pools[group].push({
      id: p.id || `${group}-${auto++}`,
      group,
      baseUrl: String(p.baseUrl).replace(/\/$/, ''),
      apiKey: p.apiKey || null,
    });
  }
  return pools;
}

// Ordered candidate list for a request wire. Falls back to the legacy single
// upstream when the matching pool is empty. Each candidate is
// { id, baseUrl, apiKey } where apiKey may be null (forward client's own).
export function resolveCandidates(config, wire) {
  const group = wireToGroup(wire);
  const pool = config.providers?.pools?.[group];
  if (Array.isArray(pool) && pool.length > 0) return pool;
  return [{ id: `default-${group}`, baseUrl: config.upstream[group], apiKey: null }];
}

// Request filters / rules engine (过滤器/规则引擎) — opt-in.
//
// Inspired by claude-code-hub's "编辑过滤器": an ordered list of rules that
// mutate the OUTBOUND request (headers and/or JSON body) just before it is sent
// to the upstream. Typical uses (from the screenshots):
//   - add an `anthropic-beta` header to every request (enable 1M context, etc.)
//   - force `thinking.type = "adaptive"` / `thinking.budget_tokens = 1024` in the
//     body so an upstream's thinking-budget validation passes.
//
// A rule is:
//   {
//     name:      "补充 think",            // human label (for logs/UI)
//     enabled:   true,                     // skip when false
//     priority:  0,                        // lower runs first
//     scope:     "all" | "provider:<id>",  // limit to one provider (failover pool)
//     stage:     "pre",                    // only stage today: before sending upstream
//     domain:    "header" | "body",
//     action:    "set_header" | "delete_header" | "json_set" | "json_delete",
//     target:    "anthropic-beta" | "thinking.type",  // header name or dot-path
//     value:     "adaptive" | 1024 | "a,b,c"           // header/json value
//   }
//
// `value` for body actions is interpreted as JSON when it parses (so "1024" -> 1024,
// "true" -> true, "[\"x\"]" -> array); otherwise it is used as a raw string
// (so "adaptive" stays the string "adaptive"). Header values are always strings.
//
// Everything is OFF unless `FILTERS` / `FILTERS_FILE` is configured.

// Parse the filters config from an already-parsed array OR a JSON string.
// Invalid entries are skipped; the result is sorted by ascending priority
// (stable for equal priorities) so application order is deterministic.
export function parseFilters(raw) {
  let arr = raw;
  if (typeof raw === 'string') {
    const t = raw.trim();
    if (t === '') return [];
    try {
      arr = JSON.parse(t);
    } catch {
      return [];
    }
  }
  if (!Array.isArray(arr)) return [];

  const out = [];
  let auto = 0;
  for (const r of arr) {
    if (!r || typeof r !== 'object') continue;
    const action = String(r.action || '').toLowerCase();
    if (!ACTIONS.has(action)) continue;
    const target = r.target != null ? String(r.target) : '';
    if (!target) continue;
    const domain = action.startsWith('json') ? 'body' : 'header';
    out.push({
      name: r.name ? String(r.name) : `filter-${auto}`,
      enabled: r.enabled !== false, // default enabled
      priority: Number.isFinite(r.priority) ? r.priority : 0,
      scope: normScope(r.scope),
      stage: 'pre',
      domain,
      action,
      target,
      value: r.value,
      _seq: auto++,
    });
  }
  out.sort((a, b) => (a.priority - b.priority) || (a._seq - b._seq));
  return out;
}

const ACTIONS = new Set(['set_header', 'delete_header', 'json_set', 'json_delete']);

// Normalize scope into { type:'all' } or { type:'provider', id }.
function normScope(scope) {
  if (!scope || scope === 'all') return { type: 'all' };
  const s = String(scope);
  if (s.startsWith('provider:')) {
    const id = s.slice('provider:'.length).trim();
    if (id) return { type: 'provider', id };
  }
  if (typeof scope === 'object' && scope.type === 'provider' && scope.id) {
    return { type: 'provider', id: String(scope.id) };
  }
  return { type: 'all' };
}

function inScope(rule, providerId) {
  if (rule.scope.type === 'all') return true;
  return providerId != null && rule.scope.id === providerId;
}

// Coerce a configured body value: JSON when parseable, else the raw string.
function coerceValue(value) {
  if (typeof value !== 'string') return value; // numbers/bools/objects pass through
  const t = value.trim();
  try {
    return JSON.parse(t);
  } catch {
    return value;
  }
}

// Set a dot-path on an object, creating intermediate objects as needed.
function setPath(obj, path, value) {
  const parts = path.split('.').filter(Boolean);
  if (parts.length === 0) return;
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const k = parts[i];
    if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {};
    cur = cur[k];
  }
  cur[parts[parts.length - 1]] = value;
}

function deletePath(obj, path) {
  const parts = path.split('.').filter(Boolean);
  if (parts.length === 0) return;
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const k = parts[i];
    if (cur[k] == null || typeof cur[k] !== 'object') return;
    cur = cur[k];
  }
  delete cur[parts[parts.length - 1]];
}

// Apply the matching, enabled filters to an outbound request.
//
// ctx = { providerId, headers, body }
//   - headers: a plain mutable object of lowercase-keyed request headers
//   - body:    the parsed request body object (or null when not JSON)
// Mutates headers/body in place and returns { applied: [names], bodyChanged }.
export function applyFilters(filters, ctx) {
  const applied = [];
  let bodyChanged = false;
  if (!Array.isArray(filters) || filters.length === 0) return { applied, bodyChanged };

  for (const rule of filters) {
    if (!rule.enabled || !inScope(rule, ctx.providerId)) continue;

    if (rule.domain === 'header') {
      const key = rule.target.toLowerCase();
      if (rule.action === 'set_header') {
        // Remove any existing case-variant first so we don't end up with dupes.
        for (const k of Object.keys(ctx.headers)) {
          if (k.toLowerCase() === key) delete ctx.headers[k];
        }
        ctx.headers[key] = rule.value == null ? '' : String(rule.value);
        applied.push(rule.name);
      } else if (rule.action === 'delete_header') {
        let hit = false;
        for (const k of Object.keys(ctx.headers)) {
          if (k.toLowerCase() === key) {
            delete ctx.headers[k];
            hit = true;
          }
        }
        if (hit) applied.push(rule.name);
      }
    } else if (rule.domain === 'body' && ctx.body && typeof ctx.body === 'object') {
      if (rule.action === 'json_set') {
        setPath(ctx.body, rule.target, coerceValue(rule.value));
        bodyChanged = true;
        applied.push(rule.name);
      } else if (rule.action === 'json_delete') {
        deletePath(ctx.body, rule.target);
        bodyChanged = true;
        applied.push(rule.name);
      }
    }
  }
  return { applied, bodyChanged };
}

// Compact summary for the UI / introspection (no secrets — values are config,
// not credentials, but we still keep it short).
export function summarizeFilters(filters) {
  return (filters || []).map((f) => ({
    name: f.name,
    enabled: f.enabled,
    priority: f.priority,
    scope: f.scope.type === 'all' ? 'all' : `provider:${f.scope.id}`,
    action: f.action,
    target: f.target,
  }));
}

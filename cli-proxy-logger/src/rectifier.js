// Request rectifier (请求整流器) — opt-in auto-repair for Anthropic "thinking"
// requests that some providers reject.
//
// This only applies to the Anthropic wire (/v1/messages), because "thinking"
// (extended/inline reasoning) and its cryptographic `signature` fields are
// Anthropic concepts. The proxy detects a specific upstream error, applies a
// minimal, targeted rewrite of the REQUEST body, and retries ONCE against the
// same provider. If the retry still fails we hand control back to the normal
// flow (which may then fail over to another provider).
//
// Two rectifications (each independently toggleable):
//
//   1) signature  — When switching between Claude providers, the assistant's
//      historical `thinking` / `redacted_thinking` blocks carry a provider-
//      specific `signature`. A different provider can't validate it and returns
//      a signature error. Fix: strip those blocks and any `signature` fields,
//      then retry. The conversation text is preserved; only the unverifiable
//      reasoning artifacts are dropped.
//
//   2) budget     — Some providers require `thinking.budget_tokens` to satisfy a
//      minimum (commonly >= 1024) and to be smaller than `max_tokens`. Fix:
//      enable thinking with a safe budget (32000) and raise `max_tokens` to at
//      least 64000 when needed, then retry.
//
// detectRectification() inspects the upstream error to choose which fix (if any)
// applies; the rectify* functions are pure (return a NEW body, never mutate).

// --- detection -------------------------------------------------------------

function errorText(errObj) {
  if (errObj == null) return '';
  if (typeof errObj === 'string') return errObj;
  if (typeof errObj === 'object') {
    // Anthropic shape: { error: { message } }. Be liberal about nesting.
    const msg = errObj.error?.message ?? errObj.message ?? '';
    return typeof msg === 'string' && msg ? msg : JSON.stringify(errObj);
  }
  return String(errObj);
}

// Returns 'signature' | 'budget' | null. `status` is the HTTP status; `errObj`
// is the parsed JSON error body (or the raw text if it wasn't JSON).
export function detectRectification(status, errObj, cfg = { signature: true, budget: true }) {
  // Only client-side request errors are rectifiable (4xx). 5xx is a provider
  // outage and should fail over, not be rewritten.
  if (typeof status === 'number' && status >= 500) return null;
  const text = errorText(errObj).toLowerCase();
  if (!text) return null;

  if (cfg.budget !== false) {
    // e.g. "thinking.budget_tokens: must be greater than or equal to 1024"
    if (text.includes('budget_tokens') || (text.includes('budget') && text.includes('thinking'))) {
      return 'budget';
    }
  }
  if (cfg.signature !== false) {
    // e.g. "signature ... invalid", "signature field ... required"
    if (text.includes('signature')) return 'signature';
  }
  return null;
}

// --- rectifications (pure) -------------------------------------------------

function stripSignatureFromBlock(block) {
  if (!block || typeof block !== 'object') return block;
  const { signature, ...rest } = block;
  return rest;
}

// Remove thinking/redacted_thinking blocks and any leftover `signature` fields
// from every message's content. Returns a new body object.
export function rectifySignature(body) {
  const clone = JSON.parse(JSON.stringify(body || {}));
  if (Array.isArray(clone.messages)) {
    for (const m of clone.messages) {
      if (!m || !Array.isArray(m.content)) continue;
      m.content = m.content
        .filter((b) => !(b && typeof b === 'object' && (b.type === 'thinking' || b.type === 'redacted_thinking')))
        .map(stripSignatureFromBlock);
    }
  }
  return clone;
}

// Enable thinking with a valid budget and ensure max_tokens leaves room for it.
export function rectifyBudget(body) {
  const clone = JSON.parse(JSON.stringify(body || {}));
  clone.thinking = { type: 'enabled', budget_tokens: 32000 };
  if (typeof clone.max_tokens !== 'number' || clone.max_tokens < 64000) {
    clone.max_tokens = 64000;
  }
  return clone;
}

// Apply the chosen rectification by name. Returns a new body (or the original
// when `kind` is unknown).
export function applyRectification(kind, body) {
  if (kind === 'signature') return rectifySignature(body);
  if (kind === 'budget') return rectifyBudget(body);
  return body;
}

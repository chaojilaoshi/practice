// Circuit breaker (熔断器) — one independent breaker per upstream provider.
//
// WHY: when failing over across multiple providers we must NOT keep hammering a
// provider that is already down. A breaker remembers recent failures and, once
// they cross a threshold, "opens" — short-circuiting that provider for a cooldown
// so requests immediately skip to the next one. After the cooldown it lets a
// LIMITED number of "probe" requests through (half-open) to see if the provider
// recovered.
//
// State machine (classic three-state breaker):
//
//   CLOSED  --failures >= threshold-->  OPEN
//   OPEN    --cooldown elapsed------->  HALF_OPEN   (allow a few probes)
//   HALF_OPEN --probe succeeds------->  CLOSED      (reset)
//   HALF_OPEN --probe fails---------->  OPEN        (restart cooldown)
//
// Two bugs that real implementations (e.g. cc-switch PR #595/#1005) hit and that
// we deliberately avoid:
//   1. A half-open PROBE PERMIT must ALWAYS be released when the request ends,
//      no matter the outcome — otherwise the breaker leaks permits and the
//      provider can get stuck "never probeable again" (looks like a deadlock).
//   2. A "client incompatibility" error (e.g. a malformed request body the user
//      sent) is NOT the provider's fault, so it must NOT count toward the
//      failure tally. We expose recordNeutral() for that — it only releases the
//      permit and touches no health counters.

const CLOSED = 'closed';
const OPEN = 'open';
const HALF_OPEN = 'half_open';

class Breaker {
  constructor(opts) {
    this.opts = opts;
    this.state = CLOSED;
    this.failures = 0;
    this.openedAt = 0;
    this.halfOpenInFlight = 0; // probe permits currently held
  }

  // Decide whether a request may be sent to this provider right now. When the
  // breaker is OPEN but the cooldown has elapsed we transition to HALF_OPEN and
  // hand out up to `halfOpenMax` probe permits. The caller MUST later call
  // exactly one of recordSuccess / recordFailure / recordNeutral so the permit
  // is released (see bug #1 above).
  canRequest(now) {
    if (this.state === CLOSED) return { allowed: true, halfOpen: false };
    if (this.state === OPEN) {
      if (now - this.openedAt >= this.opts.cooldownMs) {
        this.state = HALF_OPEN;
        this.halfOpenInFlight = 0;
      } else {
        return { allowed: false, halfOpen: false };
      }
    }
    // HALF_OPEN: only let a capped number of probes through at once.
    if (this.halfOpenInFlight < this.opts.halfOpenMax) {
      this.halfOpenInFlight += 1;
      return { allowed: true, halfOpen: true };
    }
    return { allowed: false, halfOpen: false };
  }

  _releaseProbe(halfOpen) {
    if (halfOpen && this.halfOpenInFlight > 0) this.halfOpenInFlight -= 1;
  }

  recordSuccess(halfOpen) {
    this._releaseProbe(halfOpen);
    // Any success fully resets the breaker to healthy.
    this.state = CLOSED;
    this.failures = 0;
    this.openedAt = 0;
  }

  recordFailure(halfOpen, now) {
    this._releaseProbe(halfOpen);
    if (this.state === HALF_OPEN) {
      // A probe failed: go straight back to OPEN and restart the cooldown.
      this.state = OPEN;
      this.openedAt = now;
      return;
    }
    this.failures += 1;
    if (this.failures >= this.opts.failureThreshold) {
      this.state = OPEN;
      this.openedAt = now;
    }
  }

  // Client-side incompatibility (not the provider's fault): release the probe
  // permit but do NOT change health. This keeps breaker stats clean (bug #2).
  recordNeutral(halfOpen) {
    this._releaseProbe(halfOpen);
  }
}

export class BreakerRegistry {
  constructor(opts = {}) {
    this.opts = {
      failureThreshold: opts.failureThreshold ?? 5,
      cooldownMs: opts.cooldownMs ?? 30000,
      halfOpenMax: opts.halfOpenMax ?? 1,
    };
    this.breakers = new Map();
    this.now = opts.now || (() => Date.now()); // injectable clock for tests
  }

  _get(id) {
    let b = this.breakers.get(id);
    if (!b) {
      b = new Breaker(this.opts);
      this.breakers.set(id, b);
    }
    return b;
  }

  canRequest(id) {
    return this._get(id).canRequest(this.now());
  }

  recordSuccess(id, halfOpen) {
    this._get(id).recordSuccess(halfOpen);
  }

  recordFailure(id, halfOpen) {
    this._get(id).recordFailure(halfOpen, this.now());
  }

  recordNeutral(id, halfOpen) {
    this._get(id).recordNeutral(halfOpen);
  }

  // For debugging / the UI: { id: {state, failures} }.
  snapshot() {
    const out = {};
    for (const [id, b] of this.breakers) out[id] = { state: b.state, failures: b.failures };
    return out;
  }
}

export const STATES = { CLOSED, OPEN, HALF_OPEN };

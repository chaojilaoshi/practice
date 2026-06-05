"""Circuit breaker (熔断器) -- one independent breaker per upstream provider.

Mirror of the Node ``src/breaker.js``. When failing over across providers we
must not keep hammering a provider that is already down. A breaker remembers
recent failures and, once they cross a threshold, "opens" -- short-circuiting
that provider for a cooldown so requests immediately skip to the next one. After
the cooldown it lets a LIMITED number of half-open "probe" requests through.

State machine::

    CLOSED   --failures >= threshold-->  OPEN
    OPEN     --cooldown elapsed-------->  HALF_OPEN  (allow a few probes)
    HALF_OPEN --probe succeeds-------->  CLOSED     (reset)
    HALF_OPEN --probe fails----------->  OPEN       (restart cooldown)

Two bugs we deliberately avoid (seen in similar implementations):
  1. A half-open PROBE PERMIT must ALWAYS be released when a request ends, no
     matter the outcome -- otherwise the breaker leaks permits and a provider
     gets stuck never being probeable again.
  2. A "client incompatibility" error is NOT the provider's fault, so it must
     not count toward the failure tally. ``record_neutral`` handles that: it
     releases the permit and touches no health counters.
"""

import time

CLOSED = "closed"
OPEN = "open"
HALF_OPEN = "half_open"


class _Breaker:
    def __init__(self, opts):
        self.opts = opts
        self.state = CLOSED
        self.failures = 0
        self.opened_at = 0.0
        self.half_open_in_flight = 0

    def can_request(self, now):
        if self.state == CLOSED:
            return {"allowed": True, "halfOpen": False}
        if self.state == OPEN:
            if now - self.opened_at >= self.opts["cooldownMs"] / 1000.0:
                self.state = HALF_OPEN
                self.half_open_in_flight = 0
            else:
                return {"allowed": False, "halfOpen": False}
        # HALF_OPEN: only let a capped number of probes through at once.
        if self.half_open_in_flight < self.opts["halfOpenMax"]:
            self.half_open_in_flight += 1
            return {"allowed": True, "halfOpen": True}
        return {"allowed": False, "halfOpen": False}

    def _release(self, half_open):
        if half_open and self.half_open_in_flight > 0:
            self.half_open_in_flight -= 1

    def record_success(self, half_open):
        self._release(half_open)
        self.state = CLOSED
        self.failures = 0
        self.opened_at = 0.0

    def record_failure(self, half_open, now):
        self._release(half_open)
        if self.state == HALF_OPEN:
            self.state = OPEN
            self.opened_at = now
            return
        self.failures += 1
        if self.failures >= self.opts["failureThreshold"]:
            self.state = OPEN
            self.opened_at = now

    def record_neutral(self, half_open):
        self._release(half_open)


class BreakerRegistry:
    def __init__(self, opts=None, now=None):
        opts = opts or {}
        self.opts = {
            "failureThreshold": opts.get("failureThreshold", 5),
            "cooldownMs": opts.get("cooldownMs", 30000),
            "halfOpenMax": opts.get("halfOpenMax", 1),
        }
        self._now = now or time.monotonic
        self._breakers = {}
        self._lock_free = True  # ThreadingHTTPServer: acceptable racey stats

    def _get(self, id):
        b = self._breakers.get(id)
        if b is None:
            b = _Breaker(self.opts)
            self._breakers[id] = b
        return b

    def can_request(self, id):
        return self._get(id).can_request(self._now())

    def record_success(self, id, half_open):
        self._get(id).record_success(half_open)

    def record_failure(self, id, half_open):
        self._get(id).record_failure(half_open, self._now())

    def record_neutral(self, id, half_open):
        self._get(id).record_neutral(half_open)

    def snapshot(self):
        return {i: {"state": b.state, "failures": b.failures} for i, b in self._breakers.items()}


STATES = {"CLOSED": CLOSED, "OPEN": OPEN, "HALF_OPEN": HALF_OPEN}

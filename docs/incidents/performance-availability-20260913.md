# Performance targets are observation-only (2026-09-13)

User instruction: 不要直接拒绝请求，可以记录下来作为后续优化点，优先保证可用性。

## Evidence

OAuth callbacks accepted state but failed after token acquisition due to the
performance-added 2.5s budget; provider failures were mislabeled as expired login.
See `oauth-callback-expiry-20260913.md`. The same deadline enforcement pattern was
found in common HTTP, LDAP/Redis/ES/Rabbit, AI/SMS and the frontend 5s JSON cap.
The global filter was called shadow-only, but adopted dependency guards could still
reject work. A latency target is not a provider SLA or permission to cancel work.

## Required behavior

- Late successful work remains successful. Do not shorten transport waits, refuse
  new dependency calls, or abort streams because the performance target elapsed.
- Do not forward an executable performance deadline header. Request/trace IDs stay.
- Keep elapsed-time and expired-target observation: `HttpRequestLogInterceptor`,
  `RequestDeadlineFilter`, `ApiPerformanceMetricsConfig` and frontend RUM.
- Keep explicit transport configuration, actual dependency failures, caller/identity
  cancellation, authorization, transaction/idempotency and lock ownership. No retry
  or fabricated success for a write with unknown outcome or an unacquired lock.
- No arbitrary larger performance thresholds; optimize measured slow paths later.

## Release scope

Common helpers keep compatibility method names but preserve the caller's transport
policy. Adapter-specific local timers/default overrides must be checked separately;
fixing only the shadow filter is insufficient. Existing selected tests cover late
continuation, preserved real errors/cancellation, state/ACL checks and observation.
Only the exact Flow run/artifact/online checks prove release, not static inspection.

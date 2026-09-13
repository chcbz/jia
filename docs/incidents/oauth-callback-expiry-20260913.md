# OAuth callback failure mislabeled as expiration (2026-09-13)

## Observed failure

Production application log (Asia/Shanghai):
- 10:10:55/10:10:59, 10:52:16/10:52:19 and 12:54:22/12:54:26:
  wxmp accepted state, fetched token, then failed at user stage with
  `OauthExternalBudgetExhaustedException`.
- 12:56:56/12:56:59 and 12:59:39/12:59:40: GitHub provider stage failed
  with `ResourceAccessException`; the old log lacks the underlying cause,
  so DNS, TLS and socket timeout cannot yet be distinguished.
- All of these became `thirdPartyLoginError=1`, rendered as "请求已过期".

## Minimal correction

External authorization callbacks are explicitly separate from ordinary synchronous
API SLOs (`specs/api-performance-3s-slo/spec.md` in the coordinating repository).
Delete the additional OAuth 500ms/1750ms/2500ms/100ms phase/total/margin policy:
no measured provider evidence supports forcing a multi-call browser authorization
through that ordinary-API budget. Reuse the configured transport factory and its
finite socket timeouts; do not change global transport settings or invent a new
callback numeric threshold. The existing transport values are not presented as
newly measured latency targets. No deadline header is sent to external providers.

Retain single-shot requests, no provider calls in DB transactions, status/body
validation, one-use state/provider binding and current account checks. Classify
provider, invalid/expired state and generic authentication failures separately.
Failure rendering wins over a saved automatic provider selection, preventing loops.
Log only failure/cause class names, not URLs, credentials, codes or provider bodies.

## Verification

Existing Flow selectors cover:
- `OauthExternalHttpTimeoutsTest`: token latency exceeding the removed 2.5s budget
  must still permit profile fetch; transport timeout/no retry; no internal deadline
  leakage; transaction rejection and safe non-2xx failure.
- `OauthControllerAccountSecurityTest`: provider/state error separation, replay,
  account state and recovered login session.
- `AuthFormTransportTest`: login error mapping/auto-loop suppression plus existing
  password login and public PKCE transport test.

Local static checks are not release evidence. Real provider login still needs an
interactive authenticated browser; anonymous health cannot prove completion.

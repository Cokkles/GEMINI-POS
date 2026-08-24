# GPOS Helper Security Model

## Enforced invariants

- Default listener is `127.0.0.1`; no LAN/Internet exposure by default.
- Browser origins are an explicit allowlist. Unknown origins receive no CORS grant; wildcard plus credentials is never configured.
- Production authentication is Google Authorization Code + PKCE with ID-token audience/email verification and a fail-closed local email allowlist.
- Development authentication is selected only by explicit `DevelopmentMode=true`.
- Local sessions are 256-bit random values, held only in memory, indexed by SHA-256, expire finitely, and can be revoked by logout.
- Google token material is protected with current-user Windows DPAPI. The helper session token is never forwarded to Apps Script.
- AUTH-1 remains authoritative upstream. The helper sends the Google ID token only inside the existing authenticated Apps Script POST envelope.
- Network calls have timeout, cancellation, bounded retry count, terminal error categories, backoff, and jitter. Mutation-capable calls are not retried.
- Logs omit bodies, query values and credentials; explicit redaction covers bearer, JSON token/secret, and query-secret patterns.
- Diagnostics disclose configuration state, not credential values or private domain data.

## Threat boundaries

Loopback reduces remote exposure but does not make all local processes trusted. Session cookies are `HttpOnly`, `SameSite=Strict`, scoped to `/api/v1`, and `Secure` outside development. A client may alternatively use `X-GPOS-Session`; it must protect that value. The helper must not be rebound beyond loopback without a new transport/security review.

The helper does not authorize or reinterpret subsystem facts. Calendar query retains the Apps Script preview/confirmation contract; no helper Calendar confirmation/write route exists in Phase 0.

## Secrets

Never commit populated `config/appsettings.json`, OAuth values, tokens, API keys, journal data, or financial detail. A POC token package is stored under the current Windows user's local application data as opaque DPAPI ciphertext. Logout revokes local sessions; durable credential revocation and refresh-token lifecycle are Phase-1 work.

## Known security limitations

- Real Google and Apps Script integration has not been executed without deployment credentials.
- Refresh-token rotation/revocation is not complete.
- Sessions are single-node/in-memory and disappear on restart.
- Plain HTTP is acceptable only for the loopback POC. Non-loopback hosting requires TLS and a revised cookie/CSRF design.
- The container secret-store implementation is intentionally absent, so the Dockerfile is not production-ready.


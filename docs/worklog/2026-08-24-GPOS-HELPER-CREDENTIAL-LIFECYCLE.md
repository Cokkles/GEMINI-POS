# GPOS Helper Credential Lifecycle

Date: 2026-08-24
Branch: `agent/gpos-helper-poc`
Owner: `integrations/gpos-helper`

## Change

Phase-1 development added a durable Google credential lifecycle behind `IGoogleCredentialProvider`.

- OAuth exchange results are stored as one versionable token package with an explicit expiry.
- A token with more than two minutes remaining is reused.
- An expiring token is refreshed through Google's token endpoint under a process-wide single-flight lock.
- The prior refresh token is preserved when Google does not rotate it.
- Missing credentials, malformed storage, timeouts, unavailable transport, rejected refreshes and refresh responses without an ID token all fail closed.
- Apps Script receives only the current Google ID token; the local helper session is never forwarded upstream.
- Logout clears both the local session and the DPAPI-protected Google token package.
- `/api/v1/auth/status` and the helper dashboard expose only a non-secret readiness category.

## Validation

The offline harness passes 25/25 checks, including fresh-token reuse, expired-token refresh, refresh-token preservation and failed-refresh closure. Real Google OAuth and production Apps Script validation remain pending runtime credentials.

## Remaining work

- Validate issuance and refresh with the registered Desktop OAuth client.
- Validate AUTH-1 allowlist behavior through the production Apps Script deployment.
- Decide whether logout should optionally call Google's remote revocation endpoint.


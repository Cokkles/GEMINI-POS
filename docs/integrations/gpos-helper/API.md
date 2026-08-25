# GPOS Helper API v1

Base URL: `http://127.0.0.1:47831/api/v1`

The local PWA-aligned helper control surface is served from `http://127.0.0.1:47831/` by the same process.

Responses are JSON. Protected routes accept the `HttpOnly` `gpos_session` cookie or `X-GPOS-Session`. Error responses use a finite category such as `auth_required`, `upstream_auth_required`, `timeout`, `malformed_response`, `upstream_unavailable`, or `retry_budget_exhausted`.

Authentication initiation, callback and one-time-code exchange routes share a bounded per-process fixed-window limiter. Excess requests return HTTP 429 without reaching the authentication provider.

| Method | Route | Auth | Purpose |
|---|---|---:|---|
| GET | `/health` | No | Availability, version, process instance/start time, uptime and safe upstream configuration state. |
| GET | `/live` | No | Lightweight process liveness probe for service managers and containers. |
| GET | `/ready` | No | Deployment readiness probe; development is ready while a production instance returns 503 until all safe setup checks pass. |
| GET | `/capabilities` | No | Machine-readable implemented capability IDs. |
| GET | `/diagnostics` | No | Safe runtime/build/listener/worker diagnostics; no secrets. |
| GET | `/activity?limit=20&include_routine=false` | No | Recent bounded in-memory request metadata; routine probes/polling are hidden by default and bodies, query strings, cookies and identities are always excluded. |
| GET | `/setup/status` | No | Safe production-readiness checks without returning configured values or identities. |
| GET | `/auth/status` | Optional | Local session state, safe identity summary and non-secret credential readiness (`ABSENT`, `VALID`, `REFRESHABLE`, `EXPIRED`, or `DEVELOPMENT_MOCK`). |
| POST | `/auth/login` | No | Starts login and returns `authorization_url`; production may open the system browser. |
| POST | `/auth/client/start` | No | Starts a PKCE-bound login for an allowlisted HTTPS PWA return URL. |
| POST | `/auth/client/exchange` | No | Exchanges a one-minute, single-use client code plus PKCE verifier for a helper-only session token. |
| GET | `/auth/callback?code=...&state=...` | No | OAuth loopback callback; sets the helper session cookie on success. |
| POST | `/auth/logout` | Optional | Revokes the local session, attempts bounded Google token revocation, clears its cookie and always deletes locally persisted credentials. |
| GET | `/aegis/dashboard` | Yes | Typed AUTH-1 request with upstream action `get_dashboard`. |
| GET | `/aegis/health` | Yes | Typed AUTH-1 request with upstream action `get_health`. |
| POST | `/aegis/calendar/query` | Yes | Sends `{question, history}` as `calendar_ai`; input is bounded and the operation is never retried. |

Compatibility redirects are provided from `/health`, `/live`, `/ready` and `/capabilities` to the versioned routes.

Every response includes `X-GPOS-Instance`, a random non-secret identifier that changes on process restart. The same value is returned as `instance_id` by health and diagnostics so clients can discard stale in-memory sessions cleanly.

Example Calendar query:

```json
{
  "question": "What is on my calendar tomorrow?",
  "history": []
}
```

The route does not implement `calendar_confirm`. Any upstream change proposal remains a preview; Calendar write is therefore not advertised.

## PWA session bridge

The GitHub Pages client must not depend on the helper cookie being available cross-site. It generates a PKCE verifier and S256 challenge, calls `/auth/client/start`, follows the returned authorization URL, and receives `gpos_code` in its allowlisted return URL fragment. The fragment is not sent to the hosting server. The client exchanges the code and verifier once at `/auth/client/exchange`, keeps the returned helper session token only in memory or session-scoped storage, and sends it as `X-GPOS-Session`. The value is a local helper session-not a Google credential. Codes expire after one minute and are consumed even when verification fails.


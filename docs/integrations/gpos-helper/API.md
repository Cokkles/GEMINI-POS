# GPOS Helper API v1

Base URL: `http://127.0.0.1:47831/api/v1`

The local PWA-aligned helper control surface is served from `http://127.0.0.1:47831/` by the same process.

Responses are JSON. Protected routes accept the `HttpOnly` `gpos_session` cookie or `X-GPOS-Session`. Error responses use a finite category such as `auth_required`, `upstream_auth_required`, `timeout`, `malformed_response`, `upstream_unavailable`, or `retry_budget_exhausted`.

| Method | Route | Auth | Purpose |
|---|---|---:|---|
| GET | `/health` | No | Availability, version, uptime and safe upstream configuration state. |
| GET | `/capabilities` | No | Machine-readable implemented capability IDs. |
| GET | `/diagnostics` | No | Safe runtime/build/listener/worker diagnostics; no secrets. |
| GET | `/auth/status` | Optional | Local session state, safe identity summary and non-secret credential readiness (`ABSENT`, `VALID`, `REFRESHABLE`, `EXPIRED`, or `DEVELOPMENT_MOCK`). |
| POST | `/auth/login` | No | Starts login and returns `authorization_url`; production may open the system browser. |
| GET | `/auth/callback?code=...&state=...` | No | OAuth loopback callback; sets the helper session cookie on success. |
| POST | `/auth/logout` | Optional | Revokes the presented local session, clears its cookie and deletes locally persisted Google credentials. |
| GET | `/aegis/dashboard` | Yes | Typed AUTH-1 request with upstream action `get_dashboard`. |
| GET | `/aegis/health` | Yes | Typed AUTH-1 request with upstream action `get_health`. |
| POST | `/aegis/calendar/query` | Yes | Sends `{question, history}` as `calendar_ai`; input is bounded and the operation is never retried. |

Compatibility redirects are provided from `/health` and `/capabilities` to the versioned routes.

Example Calendar query:

```json
{
  "question": "What is on my calendar tomorrow?",
  "history": []
}
```

The route does not implement `calendar_confirm`. Any upstream change proposal remains a preview; Calendar write is therefore not advertised.


# GPOS Helper Architecture

Status: Phase-0 POC implemented on `agent/gpos-helper-poc`.

## Boundary

`gpos-helper` is a single .NET 8 ASP.NET Core process. Windows is the first host, not an architectural dependency except for the selected POC secret-store implementation. The helper does not reproduce or reinterpret HORIZON, KINETIC, SPARK, SENTINEL-FIN, or PRISM logic. Apps Script AUTH-1 remains the upstream authorization authority.

```text
AEGIS / local client
  -> loopback helper API (/api/v1)
     -> local session state machine
     -> typed AEGIS gateway
        -> authenticated Apps Script POST envelope
           -> existing GEMINI-POS / Google contracts
```

## Components

- Minimal API host: loopback HTTP, CORS allowlist, diagnostics, request telemetry, graceful shutdown.
- `IAuthProvider`: development-only provider or Google Authorization Code + PKCE provider.
- `HelperSessionStore`: random opaque local sessions, stored only in memory, hashed at rest in the process, with finite expiry and logout revocation.
- `ISecretStore`: Windows DPAPI implementation. The interface is the container migration seam.
- `IAppsScriptGateway`: typed, authenticated POST envelopes compatible with AUTH-1; timeout, cancellation, retry budget, exponential backoff and jitter.
- `HeartbeatWorker`: one cancellation-aware `PeriodicTimer`; no busy loop or second process.

## Initialization and terminal states

There is one host startup and one auth provider. Login returns either `LOGIN_PENDING`, an authenticated finite session, or a terminal error. Every upstream call has a timeout and at most the configured retry count. HTTP 429/503 and transport failures are retryable only for operations marked idempotent. Calendar query is treated as a mutation-capable operation and is never retried.

## Configuration precedence

1. `GPOS_`-prefixed environment variables (for example `GPOS_Helper__Port`).
2. `config/appsettings.json` (local and ignored by policy; copy from the example).
3. safe defaults in `HelperOptions`.

Default endpoint: `http://127.0.0.1:47831`. Unknown browser origins are denied. No wildcard origin is combined with credentials.

## Capability policy

Capabilities are advertised individually and only when the route is implemented. Phase 0 advertises `helper.health`, `helper.auth`, `helper.background_jobs`, `aegis.proxy`, and `calendar.read`. It does not advertise notification delivery, task access, AI query, HORIZON generation, or Calendar write.


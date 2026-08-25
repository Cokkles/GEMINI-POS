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
- `IGoogleCredentialProvider`: reads the DPAPI-protected token package, reuses a sufficiently fresh ID token, and performs a single-flight refresh when it is near expiry. Missing, malformed, timed-out, or rejected refreshes fail closed.
- `HelperSessionStore`: random opaque local sessions, stored only in memory, hashed at rest in the process, with finite expiry, logout revocation, expired-entry pruning and a hard capacity.
- `ISecretStore`: Windows DPAPI implementation. The interface is the container migration seam.
- `IAppsScriptGateway`: typed, authenticated POST envelopes compatible with AUTH-1; timeout, cancellation, retry budget, exponential backoff and jitter.
- `HeartbeatWorker`: one cancellation-aware `PeriodicTimer`; no busy loop or second process.
- Local control surface: static assets served by the same process. It follows the AEGIS PWA navigation, status strip, panel, typography, and responsive-layout language while displaying only helper-owned state.

## UI relationship to AEGIS

The AEGIS PWA remains the primary user interface and the visual authority. The helper control surface is deliberately a companion view, not a fork: it owns local health, authentication, capabilities, diagnostics and connection state only. New helper screens should reuse AEGIS layout primitives and vocabulary wherever practical, but must not duplicate canonical subsystem data or create a second HORIZON, Calendar, Tasks, finance, or intelligence experience.

## Initialization and terminal states

There is one host startup and one auth provider. Login returns either `LOGIN_PENDING`, an authenticated finite session, or a terminal error. Every upstream call has a timeout and at most the configured retry count. HTTP 429/503 and transport failures are retryable only for operations marked idempotent. Calendar query is treated as a mutation-capable operation and is never retried.

Google credentials have a separate lifecycle from helper sessions. The local session authorizes the client to call the helper; it is never sent upstream. The credential provider supplies the current Google ID token to AUTH-1, refreshing under a process-wide lock when fewer than two minutes remain. Logout revokes the local session and removes the locally persisted Google token package.

## Configuration precedence

1. `GPOS_`-prefixed environment variables (for example `GPOS_Helper__Port`).
2. `config/appsettings.json` (local and ignored by policy; copy from the example).
3. safe defaults in `HelperOptions`.

Default endpoint: `http://127.0.0.1:47831`. Unknown browser origins are denied. No wildcard origin is combined with credentials.

## Capability policy

Capabilities are advertised individually and only when the route is implemented. The current helper advertises `helper.health`, `helper.auth`, `helper.background_jobs`, `helper.setup`, `aegis.proxy`, and `calendar.read`. It does not advertise notification delivery, task access, AI query, HORIZON generation, or Calendar write.


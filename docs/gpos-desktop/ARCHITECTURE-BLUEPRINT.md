# GPOS Desktop — Architecture Blueprint

Status: Initial implementation blueprint
Branch: `agent/gpos-desktop-v0-shell`
Foundation: existing `integrations/gpos-helper/` runtime

## Decision summary

GPOS Desktop is **one installed product with one primary resident process for v0.0.x**. The existing .NET 8 GPOS Helper becomes the resident product host and retains the hardened loopback API, OAuth/PKCE, DPAPI credential protection, health endpoints, background worker, tray lifecycle, and backend gateway. The foreground dashboard is a lifecycle-managed window owned/launched by that product. Closing the dashboard does not terminate the resident runtime; explicit **Exit GPOS Desktop** does.

Do not introduce a second always-running Windows service in v0.0.x. A later split into a UI process plus resident agent is permitted only if WebView/UI isolation, crash containment, updater constraints, or resource measurements justify it. The product boundary remains stable either way.

## 1. Current Helper architecture

Repository inspection confirms the current Helper is a .NET 8 ASP.NET Core application under `integrations/gpos-helper/src/Gpos.Helper/`. It binds a loopback-only Kestrel listener, exposes `/api/v1` health/readiness/auth/diagnostic and bounded AEGIS gateway endpoints, uses a background heartbeat worker, supports a notification-area controller, launches browser/UI surfaces, and uses platform secret stores. Windows selects `WindowsDpapiSecretStore`; production authentication uses Google OAuth. Existing scripts already publish a self-contained Windows executable, install it under the current user, create shortcuts, launch it windowlessly, smoke-test packages, and verify packaged output.

## 2. Reuse unchanged

Preserve unless a measured defect requires change:

- Loopback listener and local API boundary.
- Desktop OAuth + PKCE flow and identity allowlist enforcement.
- Windows DPAPI-protected auth material.
- Existing Apps Script gateway and backend authority.
- Health, liveness, readiness, diagnostics, support-bundle and activity surfaces.
- Existing tray controller and canonical AEGIS icon assets.
- Existing self-contained Windows publish/package verification pipeline.
- Existing windowless launch behavior.

## 3. Product architecture

```text
GPOS Desktop (installed product)
|
+-- Resident Host (.NET 8, existing Helper evolved in place)
|   +-- Tray lifecycle
|   +-- OAuth/session + DPAPI
|   +-- Loopback API / IPC
|   +-- Backend gateway
|   +-- Lightweight sync scheduler
|   +-- Notification broker
|   +-- Local last-known-good cache
|   +-- Health/diagnostics
|   +-- Dashboard lifecycle controller
|
+-- Foreground Dashboard
|   +-- Home
|   +-- Briefing
|   +-- Calendar
|   +-- Tasks
|   +-- Follow-ups
|   +-- Finances
|   +-- News & Insights
|   +-- Ask AEGIS
|   +-- System
|
+-- Canonical GPOS Backend
    +-- HORIZON / PRISM / SENTINEL-FIN / KINETIC / SPARK
    +-- Google Workspace integrations
    +-- Gemini reasoning
    +-- durable canonical state + business/authorization rules
```

The client caches and renders canonical state; it does not become canonical authority.

## 4. UI/background process model

### v0.0.x recommendation
Use the existing resident Helper as the primary process. Add a dashboard lifecycle abstraction so the product can open/reopen a foreground window without changing authentication or backend code. Keep local API boundaries explicit even when UI and runtime are hosted by the same installed product.

### Future split trigger
Move the dashboard to a separate child UI process only if at least one is demonstrated:

1. UI framework requires process isolation.
2. UI crashes threaten resident notification/sync reliability.
3. updater/installer requires independent replacement.
4. resource measurements show meaningful benefit.

A split must not create a second independent backend implementation.

## 5. Tray lifecycle

- Product launch starts resident host and opens dashboard.
- Closing dashboard hides/disposes/suspends foreground UI but leaves resident host alive.
- **Open Dashboard** activates an existing window or creates a new one.
- **Exit GPOS Desktop** explicitly shuts down UI, background jobs and host.
- Future startup-on-login starts resident host; opening the dashboard on login is separately configurable.

## 6. Window lifecycle

Maintain one logical dashboard instance. Repeated launch/open requests focus the existing instance. Window close is not product exit. UI state that is safe to persist (selected section, window bounds, non-sensitive preferences) may be local; canonical domain state remains backend-owned.

## 7. Authentication lifecycle

Preserve current Desktop OAuth + PKCE and DPAPI behavior. Authentication belongs to the resident host. UI obtains bounded local session capability through loopback IPC. Never pass OAuth refresh/access/ID tokens into browser-visible state or logs. Logout revokes local session and invokes the existing credential revocation path.

## 8. Local API / IPC

Retain `http://127.0.0.1:47831/` as the compatibility boundary unless a later security/architecture review justifies migration. Continue loopback-host validation, origin restrictions, no-store API responses, rate limits and request IDs. New desktop UI APIs should be versioned under `/api/v1` until semantics require a deliberate `/api/v2`.

## 9. Backend communication

Resident host is the single Windows gateway to canonical GPOS services. UI calls local APIs; the host calls the existing backend contracts. Client identity metadata should be added non-secretly when the shared backend accepts it:

- `client_type=GPOS_DESKTOP`
- `client_version=<product version>`
- `supported_contract_versions=<set>`

This metadata is diagnostic/compatibility information, never authentication material.

## 10. Cache/state model

Use a bounded per-domain last-known-good cache with metadata:

- contract version
- fetched timestamp
- source/backend status
- stale-after policy
- payload hash/etag when available

Cache may support offline/read-degraded UI. It must be visibly marked stale when appropriate. Local cache never becomes canonical truth and must not manufacture successful mutations while offline.

## 11. Notification architecture

```text
Canonical backend event/state
  -> bounded Helper poll/read
  -> local notification policy/dedup
  -> Windows toast
  -> activation/deep link
  -> dashboard section/object
```

Initial sources: PRISM watchdog/liveness, HORIZON completion, Calendar reminders, Follow-ups, backend degradation and sync failures. Recovery invokes canonical backend/orchestrator behavior; the Windows client does not reimplement PRISM/HORIZON.

## 12. Background sync policy

Automatic reads only for lightweight state: Calendar, Tasks, Follow-ups, latest HORIZON availability, SENTINEL summary, notification state, health/liveness. Use bounded intervals, jitter/backoff, network awareness and pause/suspend behavior.

Ordinary background sync must not trigger Gemini generation, HORIZON regeneration, PRISM ingestion or expensive RSS regeneration. **Read automatically; mutate explicitly.**

## 13. Startup-on-login

Not required for the first shell, but architecture reserves it as an installer/user preference. Startup launches the resident host minimized/tray-first. It must be removable through application settings and standard Windows startup management.

## 14. Installer/update strategy

Installable-first is a Phase-0 product requirement. Preserve the current self-contained publish pipeline and evolve packaging toward a versioned per-user installer. v0.0.x must support:

- install without Visual Studio/.NET SDK
- upgrade over prior build
- preservation of appropriate DPAPI-protected auth/config and user preferences
- clean uninstall
- explicit choice/documentation for whether uninstall removes retained user state

Preferred near-term path: keep the existing verified self-contained package while adding a conventional installer wrapper only after the shell lifecycle is stable. Avoid changing packaging and runtime architecture simultaneously.

## 15. Versioning

- 0.0.x — product shell, installer, lifecycle
- 0.1.x — auth + backend connectivity hardening
- 0.2.x — dashboard core
- 0.3.x — Calendar / Tasks / Follow-ups
- 0.4.x — notifications + background sync
- 0.5.x — richer modules / hardening

Display product version in System and diagnostics. Contract versions are separate from product version.

## 16. Logs/diagnostics

Keep structured logs, request IDs, redaction and bounded recent activity. Add product version, client type, UI lifecycle state, cache freshness and notification/sync health. Never log OAuth secrets/tokens, credential JSON, API keys, or sensitive backend payloads by default. Support bundles must remain sanitized.

## 17. Resource expectations

Tray-only steady state target: no continuous rendering, no busy loops, bounded polling, no routine model calls, bounded cache, and stable memory over all-day operation. Measure CPU wakeups, working set, handle/thread count and network calls during v0.0.x soak testing.

## 18. Failure/recovery

- Dashboard crash/close: resident runtime survives where architecture permits and dashboard can reopen.
- Backend unavailable: show degraded/stale state; retry reads with backoff.
- Auth expired/revoked: surface sign-in required; do not loop login.
- Cache corrupt: quarantine/delete cache and refetch; never overwrite canonical backend.
- Notification failure: record diagnostic state without crashing host.
- Update failure: retain prior install where installer technology permits rollback.

## 19. Android/shared-backend compatibility

Android is an independent direct client of the same GPOS backend and must never require the Windows Helper. Windows-specific local IPC, DPAPI, tray and notification semantics remain client-local. Shared backend proposals must describe Android impact and preserve/version contracts.

## 20. PWA compatibility

The PWA remains an optional backup/reference client. Do not modify it as part of Windows work without explicit authorization. Shared backend changes must account for PWA compatibility or provide a versioned migration path.

## 21. Backend improvements worth proposing

Non-breaking/versionable proposals for cross-client review:

1. Standard client identity/version metadata.
2. Explicit contract-version negotiation/capability endpoint.
3. A canonical lightweight notification feed with event IDs, type, timestamp, severity and deep-link target.
4. Read-oriented sync endpoints with etag/version metadata to reduce polling payloads.
5. A consistent health/compatibility envelope shared by Desktop, Android and PWA.

These are proposals only; no shared backend semantics are changed by this milestone.

## 22. Backend changes prohibited without cross-client review

Do not unilaterally change:

- OAuth trusted-audience interpretation or AUTH-1 semantics.
- endpoint names/required parameters used by other clients.
- response field meaning/removal/type.
- finance/HORIZON/PRISM canonical behavior.
- mutation confirmation semantics.
- PWA-facing compatibility behavior.
- any contract that would make Android depend on Windows.

## 23. Phase plan after v0.0 shell

### Phase D0 — Installable product shell (0.0.x)
Unify product identity, preserve Helper runtime/auth, establish dashboard lifecycle, tray open/exit, visible version, System status, installer/upgrade/uninstall test path, package CI.

### Phase D1 — Connectivity and auth (0.1.x)
Production auth UX, backend compatibility/client metadata (only after shared review where needed), connectivity state, cache foundation, diagnostics.

### Phase D2 — Dashboard core (0.2.x)
Home, Briefing, Ask AEGIS, navigation shell, per-section sync and Sync All. Ordinary sync never regenerates HORIZON.

### Phase D3 — Productivity modules (0.3.x)
Calendar CRUD with confirmation, Tasks/local drafts/promotion, Follow-up lifecycle and promotion/calendar actions.

### Phase D4 — Resident intelligence (0.4.x)
Windows notifications, bounded background reads, deep links, startup-on-login, resource soak testing.

### Phase D5 — Rich modules/hardening (0.5.x)
Finances from SENTINEL-FIN canonical output, News & Insights cached contracts/diagnostics, richer System diagnostics, updater/rollback hardening and accessibility/performance work.

## v0.0.x acceptance gate

The first testable shell passes when a user can install it without development tooling, launch one GPOS Desktop product, see the dashboard, close it while the tray runtime remains active, reopen from tray, explicitly exit from tray, see product/runtime/auth/backend status, upgrade over a previous test build without losing appropriate protected state, and uninstall cleanly. Existing Helper authentication/runtime must remain functional.
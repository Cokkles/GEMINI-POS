# GPOS Android A0 — Architecture & Application Foundation

Status: Architecture assessment / foundation workstream
Branch: `agent/gpos-android-foundation`

## Decision summary

GPOS Android is a first-class independent client of the canonical GPOS backend. It must not depend on GPOS Desktop, the Windows Helper, localhost Windows APIs, DPAPI, tray lifecycle, or a Windows PC being online.

The Android client should be implemented as a modern native Kotlin application using Jetpack Compose, Android Architecture Components, ViewModel, Navigation Compose, Kotlin Coroutines/Flow, Retrofit + OkHttp for HTTP contracts, Room for bounded last-known-good persistence, WorkManager for constrained background reads, Android Keystore-backed credential protection, and Android notification/deep-link facilities.

The client is a consumer and action surface. Canonical authority remains in the existing GPOS domains and Google Workspace integrations.

## 1. Repository-grounded architecture

The repository establishes the core pattern:

`raw evidence -> domain-owned canonical state -> bounded consumer contract -> orchestration/presentation`

Android therefore consumes bounded contracts and must not reconstruct HORIZON, SENTINEL-FIN, KINETIC, SPARK, PRISM or other canonical state from raw records.

The concurrent Desktop work confirms that Windows-specific concerns belong to Desktop/Helper only. Android communicates directly with the shared backend.

## 2. Recommended Android stack

- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Architecture: layered MVVM with unidirectional UI state
- Lifecycle/state: ViewModel + StateFlow/Flow
- Navigation: Navigation Compose
- Networking: Retrofit + OkHttp; Kotlin serialization or Moshi selected consistently at implementation bootstrap
- Persistence: Room
- Background work: WorkManager
- Credentials: Android Keystore with AndroidX Security where appropriate
- Notifications: NotificationManager/NotificationCompat, notification channels, deep links
- DI: Hilt
- Testing: JUnit, coroutine test utilities, repository/view-model tests, Compose UI tests
- Build: Gradle Kotlin DSL, version catalog
- Minimum SDK: choose during bootstrap from current library/backend/device requirements; do not lower security/lifecycle quality merely for obsolete devices

## 3. Proposed application architecture

```text
app/
  ui/
    home/
    briefing/
    calendar/
    tasks/
    followups/
    finances/
    aegis/
    system/
    navigation/
  domain/
    model/
    repository/
    usecase/
  data/
    remote/
    local/
    repository/
    auth/
    sync/
  platform/
    notifications/
    connectivity/
    security/
    diagnostics/
```

Dependency direction is UI -> domain abstractions -> data/platform implementations. Backend DTOs do not become UI state directly.

## 4. Authentication

Reuse the existing GPOS authentication architecture semantically rather than inventing a parallel identity system. Android needs a mobile-appropriate OAuth/OIDC flow using system browser/custom tabs and PKCE where supported by the existing backend/auth provider.

Tokens must never be placed in Compose state, ordinary logs, analytics, intents, deep-link parameters or plaintext persistence. Persist only what is required, protected with Keystore-backed storage. Session expiration must transition UI to an explicit re-authentication state.

Any change to shared authentication parameters, scopes, identity allowlists, token semantics or backend authorization is a cross-client review item.

## 5. Backend communication

Android talks directly to versioned shared GPOS endpoints. Use a single API boundary with:

- explicit DTO/contract version mapping
- request IDs/correlation IDs
- bounded timeouts
- retry only where operations are idempotent
- network/connectivity awareness
- structured error mapping
- no automatic mutation retry unless the contract explicitly supports safe idempotency

Plan additive metadata for `client_type=GPOS_ANDROID`, `client_version`, and `supported_contract_versions`. Treat this as compatibility/diagnostic metadata, never authentication material.

## 6. Local cache/state

Room stores bounded last-known-good read models with metadata:

- contract version
- fetched timestamp
- source/backend status
- freshness/stale-after
- payload version/hash/etag when available

The presentation layer must distinguish LIVE, CACHED, STALE and OFFLINE. Cache is never canonical authority.

## 7. Notifications

Initial architecture supports backend-driven push when available, plus bounded read/sync fallback where needed. Notifications should use stable event IDs for deduplication and deep-link to a typed destination such as Briefing, Calendar event, Task, Follow-up, Finance alert or System health.

Do not create aggressive polling to emulate a push/event feed.

## 8. Background synchronization

WorkManager is the default mechanism for deferrable bounded reads. Jobs should honor connectivity constraints, backoff, battery-conscious cadence and freshness requirements.

Automatic background behavior may fetch lightweight canonical state. It must not implicitly trigger Gemini reasoning, HORIZON generation, PRISM ingestion, expensive RSS/news generation or unrelated mutations.

**READ AUTOMATICALLY. MUTATE EXPLICITLY.**

## 9. Offline behavior

Offline mode may render previously cached Briefing, Calendar, Tasks, Follow-ups, financial summary and alerts with freshness labels. Read failures preserve last-known-good data when safe.

Mutations while offline must either be disabled or represented as explicit pending local intent only where a future contract defines safe replay/idempotency. The app must never present an unconfirmed backend mutation as successful.

## 10. Android lifecycle

Use lifecycle-aware collection and avoid a permanent foreground service for ordinary GPOS behavior. App foregrounding may perform freshness checks. Process death must be recoverable from persisted non-sensitive state. Background reads belong in WorkManager; urgent backend events should prefer push notifications.

## 11. Versioning

Keep product version independent from backend contract versions. Proposed product sequence:

- 0.0.x — A0 shell, packaging, navigation, diagnostics
- 0.1.x — authentication + backend connectivity
- 0.2.x — Home + HORIZON read experience
- 0.3.x — Calendar / Tasks / Follow-ups
- 0.4.x — notifications + background sync
- 0.5.x — Finances / Ask AEGIS / hardening

## 12. Packaging

Installable-first is mandatory. Produce debug APKs from the beginning. Add signed internal/release packaging only after application identity and signing custody are deliberately established. The app must be installable without Android Studio on the target test device once an APK artifact is produced.

## 13. CI/CD

Add a dedicated Android workflow that runs Gradle validation/tests and packages a checkpoint APK. Do not alter the Desktop checkpoint workflow. Later stages can add lint, Compose tests, release signing and protected artifact publication.

Secrets/signing keys must be supplied through protected CI configuration and never committed.

## 14. Diagnostics/logging

Use structured, redacted logs. Record product version, client type, supported contract versions, request IDs, sync result, cache freshness, WorkManager state and notification/deep-link handling. Never log OAuth tokens, credentials, financial payloads or sensitive canonical data by default.

Provide an in-app System screen with app/build version, auth state (not tokens), backend reachability, last successful sync, cache freshness and contract compatibility.

## 15. Security

- HTTPS only for remote backend communication.
- Keystore-backed secret protection.
- No exported Android components unless required and reviewed.
- Validate all deep links and notification payloads.
- Minimize WebView use; authentication should prefer browser/custom-tab standards.
- Network security config should reject accidental cleartext production traffic.
- Apply least privilege to Android permissions.
- Do not place production private data or secrets in this repository.

## 16. Desktop compatibility

Desktop remains free to use its resident .NET Helper, loopback IPC, DPAPI, tray and Windows notifications. Android must not call the Desktop loopback API and must not require Desktop presence.

Shared changes must be additive/versioned until coordinated across clients.

## 17. PWA compatibility

AEGIS PWA remains independently deployable. Android is not a PWA wrapper. Shared backend changes must preserve PWA behavior or use explicit version/capability negotiation.

## 18. Backend improvements worth proposing

Cross-client proposals, not unilateral A0 changes:

1. capability/contract discovery endpoint
2. lightweight canonical notification/event feed
3. ETag/version-aware read endpoints
4. common health/compatibility envelope
5. typed deep-link target identifiers that clients map locally
6. explicit idempotency support for selected future mutations

## 19. Shared-backend stop conditions

Stop before implementing any change that alters required auth parameters/scopes, response semantics, mutation authorization, canonical subsystem ownership, existing endpoint behavior, or required client metadata for Desktop/PWA/automations.

Prefer additive fields, optional metadata, new versioned endpoints and backward-compatible migration.

## 20. A0 implementation plan

A0-A — Bootstrap
- Create Android Gradle project and application identity.
- Add Compose/Material/navigation/Hilt baseline.
- Add CI build + debug APK checkpoint.

A0-B — Shell
- Implement mobile-native navigation shell.
- Add placeholder Home, Briefing, Calendar, Tasks, Follow-ups, Finances, Ask AEGIS and System destinations.
- Add responsive phone layout and basic accessibility semantics.

A0-C — Data foundation
- Add API abstraction, DTO/version envelope, repository interfaces and error model.
- Add Room database skeleton and freshness metadata.
- Add connectivity/status model.

A0-D — Platform foundation
- Add secure credential-store abstraction.
- Add WorkManager sync skeleton that performs no expensive backend mutation.
- Add notification/deep-link skeleton.
- Add diagnostics/system status model.

A0-E — Validation
- Build APK in CI.
- Run unit/lint checks.
- Smoke test launch/navigation on emulator/device.
- Confirm no Desktop/Helper runtime dependency.
- Document A0 handoff and next implementation phase.

## A0 non-goals

- Reimplementing backend domain engines.
- Rebuilding HORIZON on-device.
- Creating a second financial truth system.
- Replicating the Windows Helper.
- Breaking or silently changing shared contracts.
- Aggressive polling.
- Full production mutation support before authentication/idempotency semantics are validated.

## Foundation verdict

The Android track is technically compatible with the existing GPOS architecture. The correct implementation is a native independent client over bounded shared contracts, with Android-native lifecycle, caching, secure storage, WorkManager and notification mechanisms. The Android workstream can proceed locally through the installable shell and data/platform abstractions without requiring a breaking backend change.
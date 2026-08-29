# GPOS Android A0 — Build & Test Handoff

Branch: `agent/gpos-android-foundation`
Application ID: `com.cokkles.gpos`
Initial version: `0.0.1-a0` (`versionCode=1`)

## What exists in this checkpoint

- Native Kotlin Android application under `android/`.
- Jetpack Compose + Material 3 UI foundation.
- Mobile-native Navigation Compose shell using Home / Briefing / Calendar / Tasks / More.
- Follow-ups, Finances, Ask AEGIS and System are routed through More.
- Deterministic local fixture rendering for Home, Briefing, Calendar, Tasks, Follow-ups, Finances and System.
- Canonical Android domain models for briefing, calendar, tasks, follow-ups, finances and system compatibility state.
- Read-only `CanonicalReadClient` boundary with no remote create/update/delete methods.
- Versioned transport-envelope and client-compatibility models that fail closed on unsupported contract versions.
- Cache-first `CanonicalRepository` abstraction with explicit fresh/stale/network/cache result metadata.
- Room database/entity/DAO skeleton for last-known-good canonical snapshot persistence. It is not instantiated or wired to production data yet.
- Deterministic sync-state model for Idle / Syncing / Ready / Degraded states.
- WorkManager `CanonicalSyncWorker` skeleton. It is not scheduled and its A0 implementation performs no I/O.
- Credential-store abstraction requiring Android Keystore-backed implementations; no production credentials are stored yet.
- Typed notification/deep-link contracts with an allowlisted set of GPOS routes; no notifications are posted yet.
- Diagnostics models plus Android OS connectivity-state reporting; connectivity diagnostics do not contact GPOS.
- Unit tests for cache policy, degraded fallback, read-only API surface, sync-state transitions, contract negotiation and deep-link allowlisting.
- HTTPS-only application posture (`usesCleartextTraffic=false`).
- Internet, network-state and notification permissions declared; production network/auth/notification behavior remains inactive.
- No live backend mutations or model-generation calls.
- No dependency on GPOS Desktop or the Windows Helper.
- GitHub Actions validation and debug APK packaging.

## CI checkpoint artifact

Workflow: `.github/workflows/gpos-android-checkpoint.yml`

The A0 checkpoint pins Android SDK 36 and Compose BOM `2026.06.00`. CI also uses branch concurrency so superseded Android checkpoint builds are cancelled when a newer branch commit arrives.

The workflow installs JDK 17, Android SDK 36 and Gradle 9.5.0, then runs:

```text
gradle -p android :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

On success it uploads:

`gpos-android-a0-debug`

containing:

`app-debug.apk`

## Local Android Studio

Open the repository's `android/` directory as the Gradle project using a current Android Studio release supporting AGP 9.3.

Required local toolchain:

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0 or compatible AGP-managed installation

Sync Gradle, select the `app` configuration, and run on an emulator or physical Android device.

## Device install smoke test

After obtaining the debug APK from CI or a local build:

```text
adb install -r app-debug.apk
```

Expected checks:

1. App installs as **GPOS**.
2. Launch succeeds without Desktop/Helper connectivity.
3. Home renders deterministic A0 preview data and clearly labels it as local fixture data.
4. Bottom navigation exposes Home, Briefing, Calendar, Tasks and More.
5. More exposes Follow-ups, Finances, Ask AEGIS and System.
6. Briefing, Calendar, Tasks, Follow-ups and Finances render structured fixture-backed content instead of empty placeholders.
7. System reports backend reachability as false in preview mode and separately reports Android device connectivity.
8. Android Back returns through navigation normally.
9. No backend mutation or expensive generation occurs.
10. App remains usable without a Windows PC online.

## A0-C data-boundary checks

A0-C establishes the Android-side shape of canonical data consumption without enabling production transport.

Expected invariants:

1. `CanonicalReadClient` remains read-only.
2. `current()` reads local cache only and does not touch the network.
3. `refresh()` may replace the local cache after a successful canonical read.
4. Network or contract failure does not destroy an existing cached snapshot.
5. Unsupported contract versions fail closed.
6. Room models preserve contract version, freshness timestamps, payload version/hash and ETag metadata for a last-known-good snapshot.
7. Sync state distinguishes healthy network data from degraded cached operation.
8. No Android code writes canonical backend state during A0-C.

## A0-D platform-boundary checks

A0-D establishes Android platform integration points without activating production behavior.

Expected invariants:

1. Credential material is only represented behind `CredentialStore`; concrete storage must be Android Keystore-backed.
2. `CanonicalSyncWorker` is not scheduled in A0 and performs no backend I/O.
3. Notification deep links resolve only through the typed route allowlist.
4. Unknown or URL-like notification routes fail closed.
5. Android connectivity diagnostics read OS network capabilities only and do not imply GPOS backend reachability.
6. No credentials, tokens or private payloads are exposed through diagnostics.

## A0 boundary

A0 now proves native application packaging, Android lifecycle entry, mobile navigation, fixture-driven canonical rendering, canonical model boundaries, versioned read contracts, cache policy, Room persistence skeleton, sync-state modeling, platform-security boundaries, inert background-work plumbing, typed notification routing, local connectivity diagnostics, CI validation and APK artifact production.

Still deferred: production authentication, concrete HTTP transport, wiring Room to the repository, actual WorkManager scheduling, notification posting, production canonical payload rendering and any explicit mutation command surface. Those increments must preserve the cross-client compatibility rules documented in `A0-ARCHITECTURE-ASSESSMENT.md`.

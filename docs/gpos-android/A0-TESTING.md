# GPOS Android A0 — Build & Test Handoff

Branch: `agent/gpos-android-foundation`
Application ID: `com.cokkles.gpos`
Initial version: `0.0.1-a0` (`versionCode=1`)

## What exists in this checkpoint

- Native Kotlin Android application under `android/`.
- Jetpack Compose + Material 3 UI foundation.
- Mobile-native Navigation Compose shell using Home / Briefing / Calendar / Tasks / More.
- Follow-ups, Finances, Ask AEGIS and System are routed through More.
- Canonical Android domain models for briefing, calendar, tasks, follow-ups, finances and system compatibility state.
- Read-only `CanonicalReadClient` boundary with no remote create/update/delete methods.
- Cache-first `CanonicalRepository` abstraction with explicit fresh/stale/network/cache result metadata.
- Deterministic sync-state model for Idle / Syncing / Ready / Degraded states.
- Unit tests for empty-cache behavior, refresh/cache replacement, degraded cached fallback, read-only API surface and sync-state transitions.
- HTTPS-only application posture (`usesCleartextTraffic=false`).
- Internet and notification permissions declared; no notification behavior is active yet.
- No live backend mutations, authentication flows, model-generation calls, Room persistence, or WorkManager jobs are active yet.
- No dependency on GPOS Desktop or the Windows Helper.
- GitHub Actions validation and debug APK packaging.

## CI checkpoint artifact

Workflow: `.github/workflows/gpos-android-checkpoint.yml`

The A0 checkpoint deliberately pins the stable Android SDK 36 platform and Compose BOM `2026.06.00`. API 37 is deferred until the standard CI SDK repositories expose a dependable non-preview platform package for this build path.

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
3. Home displays the A0 foundation overview.
4. Bottom navigation exposes Home, Briefing, Calendar, Tasks and More.
5. More exposes Follow-ups, Finances, Ask AEGIS and System.
6. Android Back returns through navigation normally.
7. No backend mutation or expensive generation occurs.
8. App remains usable without a Windows PC online.

## A0-C data-boundary checks

The A0-C layer is intentionally infrastructure-only. It establishes the Android-side shape of canonical data consumption without selecting or modifying a production backend transport.

Expected invariants:

1. `CanonicalReadClient` remains read-only.
2. `current()` reads local cache only and does not touch the network.
3. `refresh()` may replace the local cache after a successful canonical read.
4. Network or contract failure does not destroy an existing cached snapshot.
5. Sync state distinguishes healthy network data from degraded cached operation.
6. No Android code writes canonical backend state during A0-C.

## A0 boundary

A0 now proves native application packaging, Android lifecycle entry, mobile navigation, canonical model boundaries, read-only repository/cache policy, deterministic sync-state modeling, CI validation and APK artifact production.

Still deferred to later increments: production authentication, concrete HTTP transport, Room-backed persistence, WorkManager scheduling, notifications, canonical screen rendering, and any explicit mutation command surface. Those increments must preserve the cross-client compatibility rules documented in `A0-ARCHITECTURE-ASSESSMENT.md`.

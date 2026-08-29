# GPOS Android A0 — Build & Test Handoff

Branch: `agent/gpos-android-foundation`
Application ID: `com.cokkles.gpos`
Initial version: `0.0.1-a0` (`versionCode=1`)

## What exists in this checkpoint

- Native Kotlin Android application under `android/`.
- Jetpack Compose + Material 3 UI foundation.
- Navigation Compose route shell.
- Placeholder Home, Briefing, Calendar, Tasks, Follow-ups, Finances, Ask AEGIS and System destinations.
- HTTPS-only application posture (`usesCleartextTraffic=false`).
- Internet and notification permissions declared; no notification behavior is active yet.
- No backend mutations or model-generation calls.
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
4. Briefing, Calendar, Tasks, Follow-ups, Finances, Ask AEGIS and System cards navigate to their placeholder destinations.
5. Android Back returns through navigation normally.
6. No backend mutation or expensive generation occurs.
7. App remains usable without a Windows PC online.

## A0 boundary

This checkpoint proves application packaging, Android-native lifecycle entry, navigation and CI artifact production. Authentication, API calls, Room persistence, WorkManager synchronization, notifications and canonical domain rendering are subsequent A0 foundation increments and must preserve the cross-client compatibility rules documented in `A0-ARCHITECTURE-ASSESSMENT.md`.

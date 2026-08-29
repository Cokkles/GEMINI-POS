# GPOS Android A0-E — Validation Status

Branch: `agent/gpos-android-foundation`
Product version: `0.0.1-a0`

## Acceptance model

A0-E separates validation evidence into three categories so the project does not confuse source inspection with runtime proof.

### CI-proven

The Android checkpoint workflow must successfully execute:

```text
gradle -p android :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

A passing run proves the checked-in Android project resolves its pinned toolchain and dependencies, passes lint/unit-test tasks, compiles the application, and produces an installable debug APK artifact.

### Architecture/source-proven

Repository inspection establishes these A0 invariants:

- Android is an independent native client and contains no Desktop/Windows Helper runtime dependency.
- `CanonicalReadClient` exposes only canonical reads; no remote mutation command exists in the A0 API boundary.
- Preview screens use deterministic local fixture data and explicitly identify it as preview data.
- Unsupported contract versions fail closed through explicit compatibility evaluation.
- Room persistence is bounded to a last-known-good canonical snapshot skeleton and is not canonical authority.
- `CanonicalSyncWorker` is inert and unscheduled in A0.
- Credential persistence exists only as an abstraction requiring Keystore-backed implementation later.
- Notification/deep-link targets are locally allowlisted.
- Android connectivity diagnostics inspect OS capabilities only and do not claim backend reachability.
- Cleartext application traffic is disabled.

### Runtime smoke proof still required

CI APK assembly is not a substitute for launching the application on Android. Before A0 is considered device-validated, perform at least one emulator or physical-device smoke pass:

1. Install the latest CI `app-debug.apk`.
2. Launch GPOS from the launcher.
3. Verify Home renders without Desktop/Helper availability.
4. Navigate Home -> Briefing -> Calendar -> Tasks -> More.
5. From More, open Follow-ups, Finances, Ask AEGIS and System.
6. Verify preview-data banners are visible and no screen implies production data is live.
7. Verify System shows preview backend reachability separately from device connectivity.
8. Exercise Android Back navigation.
9. Test once with network connectivity disabled; the application should still launch and render fixtures.
10. Confirm there is no sign-in prompt, backend mutation, notification posting or model-generation activity in A0.

## A0 exit criteria

A0 can be declared complete when:

- the latest cumulative branch commit has a passing Android checkpoint workflow;
- the APK artifact is retained and installable;
- the runtime smoke pass above is recorded as PASS, or explicitly recorded as pending if no emulator/device execution environment is available;
- branch comparison against `main` confirms the Android workstream remains isolated from Desktop/Helper implementation changes;
- the next phase starts at product version `0.1.x` and treats authentication/backend connectivity as a new reviewed boundary rather than silently activating A0 abstractions.

## Next phase after A0

Per the approved architecture sequence, `0.1.x` introduces authentication and backend connectivity. That phase must determine the production mobile OAuth/OIDC + PKCE path, concrete versioned HTTP transport, contract discovery/compatibility behavior, and Room repository wiring before any mutation surface is enabled.

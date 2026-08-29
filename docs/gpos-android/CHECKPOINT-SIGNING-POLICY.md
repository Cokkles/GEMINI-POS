# GPOS Android — Checkpoint Signing Policy

Status: active for internal development checkpoints beginning with Android 0.2

## Purpose

GPOS Android checkpoint APKs are sideloaded development artifacts. Android requires two APKs with the same package name to be signed by the same certificate for an in-place upgrade.

Early A0/0.1 checkpoints relied on the default GitHub-hosted debug keystore. That identity is generated on the runner and is not a durable release mechanism. Cache-based attempts also proved nondeterministic across superseded workflow runs.

Beginning with 0.2, checkpoint builds therefore use a dedicated deterministic **non-production** development key.

## Checkpoint identity

Package:

`com.cokkles.gpos`

Keystore path:

`android/keystore/gpos-checkpoint-debug.keystore`

Alias:

`gpos-checkpoint`

Certificate fingerprints:

- SHA-1: `D2:A0:80:66:49:D0:00:B1:B8:4F:FF:45:A9:C6:DF:76:8D:FC:31:EA`
- SHA-256: `A6:CC:78:9C:3D:29:48:86:DA:B5:5D:A1:42:10:5A:AA:B0:DF:20:B4:0C:37:18:14:03:25:A8:7D:A4:61:F8:AC`

CI hard-fails if the assembled checkpoint APK does not match these fingerprints.

## Security classification

This key is intentionally **not a production secret**. It is checked into the private development repository so internal checkpoint builds are deterministic.

It MUST NOT be used for:

- Google Play production signing;
- public release signing;
- a production enterprise distribution identity;
- any trust decision beyond identifying an internal checkpoint build.

Anyone with repository access should be treated as capable of producing a checkpoint-signed development APK. Therefore the checkpoint certificate is not a production authenticity boundary.

## Production transition

Before a public/store release, establish a separate production signing and custody plan. Options may include Google Play App Signing or a separately protected release keystore/CI secret.

A production signing transition may require uninstalling the internal checkpoint application from test devices before installing the production-signed app. Do not silently reuse the checkpoint key merely to avoid that transition.

## 0.1 → 0.2 transition

The exact 0.1 APK previously distributed to device testing was signed by an ephemeral GitHub runner certificate whose private key was not retained.

Because Android signing is cryptographic, that certificate cannot be recreated from the APK. The first deterministic 0.2 checkpoint therefore requires a one-time uninstall of the original 0.1 development APK before installation if Android reports a signature mismatch.

After installing the deterministic 0.2 checkpoint, later checkpoint APKs using this policy are intended to upgrade in place.

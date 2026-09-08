# AEGIS shared backend registry — mandatory Android gate

Canonical human-readable registry: https://docs.google.com/document/d/1RaxZU2JNl5Dr4-puIvy01q8nmbiJlbx0RhOir2oWvCY/edit

Version-controlled cross-project authority: `Cokkles/ai-project-workspace`, `SHARED-BACKEND-GOVERNANCE.md` and `projects/gemini-pos/RECOVERY.md`.

Required peer mirrors: `Cokkles/GEMINI-POS/shared-backend/` and `Cokkles/AEGIS-Windows/shared-backend/`. Each mirror contains `backend-manifest.json`, `CHANGELOG.md`, and—once byte-exact source is available—the identical `Code.gs`.

## Current declared baseline

- Backend: Apps Script `2.8.0`
- Posture: additive; all Android 2.7-era contracts remain required
- Source certification: pending migration of the exact current `Code.gs` into an immutable shared-backend checkpoint
- Legacy warning: the Drive file `gemini-webhook-horizondashproject` currently contains backend `2.6.5`; it is historical and must not be used as the current source

## Required phase preflight

Every Android planning or implementation phase must:

1. read the Drive registry and master recovery record;
2. record the expected backend version and consumed capabilities;
3. verify `auth_config.backend_version` and `get_capabilities` when live validation is available;
4. compare any shared change against Windows, Android, and PWA consumers;
5. stop on any unrecorded version, scope, action, payload, response, or mutation-semantic mismatch.
6. compare both client manifests; if either is older, create a small mirror-sync branch before feature work.

## Backend change rule

Any Android-originated backend change must be additive or concurrently versioned. In the same phase it must record the exact source snapshot, repository path, commit SHA, content SHA-256, deployment identity, changed contracts, per-client impact, rollout, tests, and rollback in both the Drive registry and `Cokkles/ai-project-workspace`. Android must never deploy a private fork of the shared backend.

The change is not complete until both client repositories contain identical `Code.gs` bytes and matching manifests/hashes, even when only one client initiated it.

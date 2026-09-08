# AEGIS shared backend changelog

## 2.8.0 — declared cross-client baseline

- Compatibility posture: additive; preserve every Android 2.7-era contract.
- Added/formalized RSS source/category registry management, source testing, headliner eligibility/filtering, Task workspace/list/history capability advertisement, and structured Calendar preparation.
- Exact `Code.gs` source snapshot and SHA-256 remain pending authoritative-source migration. This directory must not contain a fabricated or reconstructed script.

## Synchronization rule

Before every client phase, compare this manifest with the Drive registry, `Cokkles/ai-project-workspace`, and `Cokkles/AEGIS-Windows/shared-backend/backend-manifest.json`. Synchronize an older mirror on a small branch before feature work.

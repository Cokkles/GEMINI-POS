# AEGIS shared backend changelog

## 2.8.1 — nutrition reliability candidate

- Added the optional `capture_nutrition` POST action while retaining the legacy `/calories` message route.
- Added Capture-ID idempotency, explicit `CONFIRMED`/`QUEUED`/`FAILED` states, retryability and write-state metadata.
- Added grounded source priority: official restaurant/manufacturer, USDA FoodData Central, Open Food Facts, component reconstruction, then transparent conservative estimation.
- Added strict JSON validation and expanded nutrition fields in additive sheet columns K:X; legacy A:J remain unchanged.
- Added high-volume handling for Gemini HTTP 429/503 and forbids zero-macro placeholder rows.
- Source is an additive module because the authoritative full 2.8.0 `Code.gs` has not yet been migrated into either Git mirror.
- Deployment status: pending Apps Script installation, new deployment version, runtime capability verification, and cross-client smoke testing.

## 2.8.0 — declared cross-client baseline

- Compatibility posture: additive; preserve every Android 2.7-era contract.
- Added/formalized RSS source/category registry management, source testing, headliner eligibility/filtering, Task workspace/list/history capability advertisement, and structured Calendar preparation.
- Exact `Code.gs` source snapshot and SHA-256 remain pending authoritative-source migration. This directory must not contain a fabricated or reconstructed script.

## Synchronization rule

Before every client phase, compare this manifest with the Drive registry, `Cokkles/ai-project-workspace`, and `Cokkles/AEGIS-Windows/shared-backend/backend-manifest.json`. Synchronize an older mirror on a small branch before feature work.

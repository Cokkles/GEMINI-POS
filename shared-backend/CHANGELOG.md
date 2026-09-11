# AEGIS shared backend changelog

## 2.8.2 — durable nutrition processing candidate

- Added an asynchronous server-side nutrition queue with stable `capture_id` lookup, leases,
  scheduled capacity retries, explicit status polling, and operator-controlled retry.
- Android can stop resubmitting after server acceptance; Gemini latency and HTTP 429/503 no
  longer occupy the mobile request or create ambiguous client-side write state.
- Added exact input-conflict detection, cached reuse of prior confirmed identical estimates,
  post-write row-count verification, and zero-macro rejection.
- Preserves the 2.8.1 `capture_nutrition` action, legacy `/calories` behavior, and Nutrition A:X.
- Deployment status: candidate only. The 2.8.1 deployment remains the live rollback point until
  Apps Script tests, a new deployment, capability checks, and cross-client smoke tests pass.

## 2.8.1 — deployed nutrition reliability checkpoint

- Added the optional `capture_nutrition` POST action while retaining the legacy `/calories` message route.
- Added Capture-ID idempotency, explicit `CONFIRMED`/`QUEUED`/`FAILED` states, retryability and write-state metadata.
- Added grounded source priority: official restaurant/manufacturer, USDA FoodData Central, Open Food Facts, component reconstruction, then transparent conservative estimation.
- Added strict JSON validation and expanded nutrition fields in additive sheet columns K:X; legacy A:J remain unchanged.
- Added high-volume handling for Gemini HTTP 429/503 and forbids zero-macro placeholder rows.
- Runtime `auth_config.backend_version` was observed as 2.8.1 and the V281 input/validation tests passed.
- Android exposed the remaining limitation: Gemini HTTP 429 leaves processing dependent on client retries;
  this deployment is the rollback point for the 2.8.2 durable queue candidate.

## 2.8.0 — declared cross-client baseline

- Compatibility posture: additive; preserve every Android 2.7-era contract.
- Added/formalized RSS source/category registry management, source testing, headliner eligibility/filtering, Task workspace/list/history capability advertisement, and structured Calendar preparation.
- Exact `Code.gs` source snapshot and SHA-256 remain pending authoritative-source migration. This directory must not contain a fabricated or reconstructed script.

## Synchronization rule

Before every client phase, compare this manifest with the Drive registry, `Cokkles/ai-project-workspace`, and `Cokkles/AEGIS-Windows/shared-backend/backend-manifest.json`. Synchronize an older mirror on a small branch before feature work.
# 2.8.3 - Device session reliability candidate

- Adds an additive, signed, server-registered 30-day AEGIS device session after successful Google AUTH-1 verification.
- Adds rolling renewal, exact-session logout revocation, per-email/device limits, scope enforcement, and tamper checks.
- Preserves Google ID-token authentication for Windows, PWA, older Android clients, and rollback.
- Android 0.7.6.1 never invokes Credential Manager except from an explicit Sign In action.
- Undeployed; authoritative Code.gs is required to complete and verify the router integration.

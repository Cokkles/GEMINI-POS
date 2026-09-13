# AEGIS shared backend changelog

## 2.8.4.1 — multi-item nutrition integrity correction

- Added deterministic comma/newline/semicolon/pipe item segmentation with a
  maximum of 20 explicit items per capture.
- Added branded-product routing for Tyson, Kirkland, Mission, Texas Pete, and
  Rice-A-Roni so the supplied fixture uses one grounded request rather than the
  ungrounded simple estimator.
- Requires provider output count to exactly equal input item count, in the same
  order, with each leading numeric quantity preserved in its portion.
- Rejects generic/combined meal names, identity mismatches, and low-confidence or
  model-only claims for recognized branded products.
- Multi-item provider validation occurs before the durable queue writes anything;
  a failed bundle remains queued with zero partial nutrition rows.
- Added the exact five-item user fixture to local and Apps Script regression tests.
- Preserves all 2.8.4 provider/quota behavior, 2.8.3 device sessions, 2.8.2 queue
  routes, 2.8.1 structured capture, legacy `/calories`, and columns A:X.
- Deployment status: candidate. Backend 2.8.4 is the rollback point.

## 2.8.4 — tiered nutrition provider and quota hardening candidate

- Added a known-food/cache-first path so common captures can complete without an
  AI request; the initial packaged-food fixture covers weighted Fig Newton input.
- Added an ungrounded `gemini-3.5-flash-lite` lane for ordinary food estimates.
- Restricted Google Search grounding to restaurant/menu inputs and added a
  conservative ungrounded fallback when only the grounded lane is unavailable.
- Preserved Gemini HTTP 429/503 provider details, classified search/daily/RPM/TPM
  limits, honored provider retry timing, and added separate simple/grounded
  circuit breakers.
- Capacity failures no longer exhaust the durable queue into a terminal failure;
  safely accepted captures remain queued with capped progressive backoff.
- Preserved 2.8.3 device sessions, 2.8.2 queue routes, 2.8.1 structured capture,
  legacy `/calories`, and nutrition columns A:X.
- Local static, mocked routing, quota, circuit-breaker, and durable-capacity
  regression tests pass. Apps Script runtime and client smoke tests remain pending.
- Backend 2.8.3 is the rollback point. Workflow remains GitHub-only; Drive is not used.

## 2.8.3 — device-session and durable-nutrition integrated candidate

- Reconciled the exact authoritative 2.8.1 `Code.gs` supplied on 2026-09-13;
  LF-normalized SHA-256 `a349a46bdab0b8f63396d0c8aac181f9446f93ccad8ecb3c1b23a0830cc78331`.
- Added the complete 2.8.3 router integration without merging implementation modules
  into `Code.gs`.
- Added optional signed, server-registered 30-day device sessions after successful
  Google AUTH-1 verification, rolling renewal, scope enforcement, and logout revocation.
- Legacy clients without a `device_id` retain the existing Google-token response and
  authorization behavior.
- Added all 2.8.2 durable nutrition queue routes and capabilities while preserving
  2.8.1 `capture_nutrition` and legacy `/calories` behavior.
- Added explicit `AEGIS_AUTH_FAILED` responses with separate diagnostic codes so
  background clients pause safely without opening interactive authentication.
- Static syntax, symbol-collision, router-contract, and mocked device-session lifecycle
  validation pass. Apps Script runtime and cross-client validation remain pending.
- Preserve the currently installed `NutritionReliability281.gs` during deployment.
  Its recorded deployed hash is authoritative; the Git convenience copy must be
  re-imported separately before byte-for-byte recovery is claimed.
- Deployment status: candidate only. Backend 2.8.1 remains live and rollback.

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

Before every client phase, compare this manifest with `Cokkles/ai-project-workspace`
and `Cokkles/AEGIS-Windows/shared-backend/backend-manifest.json`. Synchronize an
older mirror on a small branch before feature work. GitHub is the sole registry and
release-artifact authority for this project.

# AEGIS 2.6.3d — Direct Runtime Bootstrap

Status: implementation complete; production browser validation pending.

## Defect

AEGIS `index.html` still directly references the legacy v2.4.1 base assets. Newer AQ-1/AQ-2/compatibility/auth modules had been supplied through service-worker substitution of the `v2.4.1.js` request. When a browser was not controlled by the expected service-worker generation, it fell back to the legacy dashboard: old backend-version warning, AQ-1 read-only Ask AEGIS, and no Calendar mode.

## Remediation

The directly referenced `quotes-extra.js` now installs `runtime-bootstrap-v2.6.3d.js`. The bootstrap runs after page load and, when AQ-2 Calendar is not already present, explicitly loads the current modules in order:

- aq1-hotfix.js
- aq2-calendar.js
- aq2-stabilization.js
- model-routing-telemetry.js
- compatibility-v2.6.3.js
- calendar-safety-bridge-v2.6.3.js
- auth-bootstrap-recovery-v2.6.3c.js

This provides a current-runtime path independent of service-worker interception while remaining compatible with a browser already running the current bundled runtime.

## Expected production indicators

- Calendar tab exists in Ask AEGIS.
- Ask AEGIS Calendar mode advertises preview/confirmation semantics rather than AQ-1 read-only semantics.
- stale backend-version equality warning is cleared by the compatibility layer.
- Schedule Calendar quick command uses the AQ-2 safety contract.
- `document.documentElement.dataset.aegisRuntime` reports `2.6.3d` after direct bootstrap.

No Apps Script redeploy is required. Backend remains 2.6.3.

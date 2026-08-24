# AEGIS AQ-2.3 Closeout Status

Current state: IMPLEMENTED / PRODUCTION VALIDATION PENDING.

## Completed

- Apps Script backend 2.6.3 deployed.
- AUTH-1 remains enforced.
- Calendar deterministic parser handles common READ / CREATE / UPDATE / DELETE paths without Gemini usage.
- Calendar ambiguous-language fallback isolated to `gemini-3.5-flash-lite`.
- Global heavier Gemini model remains reserved for reasoning workloads.
- AEGIS frontend compatibility moved from strict version equality to capability-based checks.
- Canonical frontend capability matrix introduced for auth, AI query, Calendar, HORIZON, Tasks, notifications, finance, intelligence, and reverse geocoding.
- Legacy backend-version-only critical alerts retired.
- System UI gains Compatibility & Model Routing diagnostics.
- Calendar transport telemetry records operation, parser source, model used, confirmation state, and mutation state.
- PWA runtime/cache generation rotated to frontend 2.6.3.

## Required production exit tests

AQ-2.3 is not COMPLETE until all pass:

1. MOVE: one 3 PM test event previews UPDATE / DETERMINISTIC, remains unchanged before confirmation, and becomes exactly one 4 PM event after confirmation.
2. DELETE: previews DELETE / DETERMINISTIC, remains present before confirmation, and is removed only after confirmation.
3. Replay/expiry: reused or expired confirmation token fails closed with no mutation.
4. Ambiguous target: multiple unresolved matches require clarification and no confirmation token is issued.

## Next infrastructure after AQ-2.3 closure

GPOS Helper POC remains the next planned infrastructure phase: one lightweight `gpos-helper.exe` process for Windows tray/background operation and native notification bridging, avoiding multiple independent services.

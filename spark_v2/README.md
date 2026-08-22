# SPARK V2

Repository-native implementation of the SPARK epistemic engine and bounded HORIZON projection.

Architecture: `v2.0.1-frozen`
Schema version: `2.0.1`
Status: **RECONSTRUCTED / VALIDATED**

## Implemented controls

- 48-hour current-state freshness boundary
- `SELF_REPORTED` / `EXPLICIT_USER_INTERPRETATION` isolation
- `SYSTEM_HYPOTHESIS` exclusion from self-reported state
- conservative repeated-pattern support
- reference-strategy applicability gating
- provenance output
- leading-prefix ACTIVE_NOTE_FILTER
- structured source adapters
- `SPARK_TO_HORIZON_V2` projection
- no raw Journal prose in HORIZON-facing output
- no synthetic affect to force a HORIZON card

Repository-wide regression coverage is in `tests/test_reconstructed_baseline.py`.

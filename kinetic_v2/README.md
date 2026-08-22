# KINETIC V2

Repository-native implementation of KINETIC daily state, HORIZON projection, and bounded seven-day trend aggregation.

Architecture: `v2.0.1-frozen`
Schema version: `2.0.1`
Status: **RECONSTRUCTED / VALIDATED**

## Implemented controls

- `null` for absent observations vs numeric `0` for verified zero
- dynamic calorie/protein target extraction from configuration text
- ISO and M/D/YYYY date normalization
- bounded T-6 through T seven-day trends
- configured/unconfigured target semantics
- unsupported optional modules remain `UNTRACKED`
- `KINETIC_TO_HORIZON_V2` display projection
- no production source mutation

Repository-wide regression coverage is in `tests/test_reconstructed_baseline.py`.

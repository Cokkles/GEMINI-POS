# ASSESS V2

Repository-native implementation of the cross-domain `:assess` coordinator, TypedClaim engine, and renderer.

Architecture: `v2.0.1-frozen`
Schema version: `2.0.1`
Status: **RECONSTRUCTED / VALIDATED**

## Boundaries

- consumes `SPARK_STATE_V2` and `KINETIC_TREND_V2` rather than raw Journal or nutrition rows;
- every substantive analytical claim carries an epistemic tag and evidence references;
- cross-domain correlation remains `HYPOTHESIS`, not causal `FACT`;
- recommendations have no mutation/execution side effects;
- `immediate_moves.micro_actions` contains exactly three recommendation claims;
- renderer formats canonical claims but does not invent new ones.

Repository-wide regression coverage is in `tests/test_reconstructed_baseline.py`.

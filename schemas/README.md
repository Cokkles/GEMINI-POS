# Schemas

This directory contains the repository-native canonical GPOS V2 contract baseline.

Architecture: `v2.0.1-frozen`
Contract schema version: `2.0.1`
Standard: JSON Schema Draft 2020-12

## Canonical contracts

- `spark/spark-state-v2.schema.json`
- `spark/spark-to-horizon-v2.schema.json`
- `kinetic/kinetic-state-v2.schema.json`
- `kinetic/kinetic-trend-v2.schema.json`
- `kinetic/kinetic-to-horizon-v2.schema.json`
- `assess/assess-input-v2.schema.json`
- `assess/assess-output-v2.schema.json`

`registry.json` records contract identity and ownership. `consumer_registry.json` records producer/consumer boundaries.

The reconstructed contracts validate under Draft 2020-12 and are covered by `tests/test_reconstructed_baseline.py` plus the repository GitHub Actions baseline workflow.

Future contract changes must follow the documented SemVer governance rather than editing adopted semantics silently.

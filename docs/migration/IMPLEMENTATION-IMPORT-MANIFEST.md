# GPOS Implementation Import Manifest

Status: OPEN
Purpose: controlled migration of already-tested V2.1–V2.4 implementation artifacts into `Cokkles/GEMINI-POS`.

## Important finding

A GitHub code search across repositories accessible under `Cokkles` did not locate the validated local implementation files by names such as `spark_engine.py` or `kinetic_engine.py`. The implementation reports indicate those artifacts exist in the working environment used for the V2 phases, but they are not currently recoverable from an existing GitHub repository through the connected GitHub index.

Therefore this repository must **not reconstruct production code from prose reports**. The tested local artifacts should be imported from their real source by Codex/local tooling.

## Artifacts to import unchanged first

### Schemas

- `schemas/registry.json`
- `schemas/consumer_registry.json`
- `schemas/spark/spark-state-v2.schema.json`
- `schemas/spark/spark-to-horizon-v2.schema.json`
- `schemas/kinetic/kinetic-state-v2.schema.json`
- `schemas/kinetic/kinetic-trend-v2.schema.json`
- `schemas/kinetic/kinetic-to-horizon-v2.schema.json`
- `schemas/assess/assess-input-v2.schema.json`
- `schemas/assess/assess-output-v2.schema.json`
- synthetic fixtures and schema tests from V2.1

### KINETIC V2

- `kinetic_v2/kinetic_engine.py`
- existing V2.2 test suite
- read-only production-validation harness where safe to version-control

### SPARK V2

- `spark_v2/evidence.py`
- `spark_v2/provenance.py`
- `spark_v2/epistemic.py`
- `spark_v2/projections.py`
- `spark_v2/spark_engine.py`
- `spark_v2/source_adapters/`
- V2.3 and V2.3A tests

### ASSESS V2

- `assess_v2/claims.py`
- `assess_v2/coordinator.py`
- `assess_v2/assess_engine.py`
- `assess_v2/render.py`
- V2.4 tests

## Migration procedure

1. Locate the original local working tree containing the validated artifacts.
2. Copy artifacts without semantic modification into the matching GPOS directories.
3. Exclude credentials, production exports, journal text, finance records, and sensitive fixtures.
4. Run the original schema/unit/adversarial tests from the GPOS repository.
5. Fix only environment/path assumptions required to make the same tests run; document every such change.
6. Compare test counts/results to the phase reports.
7. Only after parity is demonstrated should GPOS become the working repository for V2.5.

## Required parity gates

- Seven schemas validate under Draft 2020-12.
- V2.1 positive/negative contract fixtures pass.
- KINETIC V2.2 tests pass.
- SPARK V2.3 + V2.3A tests pass.
- ASSESS V2.4 tests pass.
- no secrets or raw personal source data are committed.

## Authority rule

Phase-history documents describe validated checkpoints. They are not substitutes for source code. The imported source and tests become repository authority only after parity validation succeeds.

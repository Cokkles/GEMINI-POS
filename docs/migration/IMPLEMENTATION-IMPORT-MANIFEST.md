# GPOS V2.1–V2.4 Repository-Native Baseline Reconstruction

Status: APPROVED / READY FOR EXECUTION
Purpose: establish `Cokkles/GEMINI-POS` as the durable engineering authority by reconstructing the accepted V2.1–V2.4 implementation from the frozen architecture and validated phase records, then revalidating the complete baseline before V2.5.

## Authority clarification

There is no separate local SPARK/KINETIC/ASSESS implementation tree to import. The accepted implementation history exists in the Gemini/Workspace workstream and its validated phase reports. The Obsidian Project Notebook is documentation and is not an implementation authority.

This work is therefore a **controlled repository-native reconstruction**, not a parity import from a hidden local codebase.

## Reconstruction source hierarchy

1. Frozen architecture authority: `v2.0.1-frozen`.
2. Canonical contract definitions: schema version `2.0.1`.
3. Accepted V2.0–V2.4 phase reports and recorded errata/hardening decisions.
4. Existing GPOS architecture, boundary, epistemic, roadmap, and phase-history documentation.

When a report and frozen contract disagree, the frozen contract/accepted errata wins. No new semantics may be invented merely to make tests pass.

## Repository-native artifacts to reconstruct

### Schemas — V2.1

- `schemas/registry.json`
- `schemas/consumer_registry.json`
- `schemas/spark/spark-state-v2.schema.json`
- `schemas/spark/spark-to-horizon-v2.schema.json`
- `schemas/kinetic/kinetic-state-v2.schema.json`
- `schemas/kinetic/kinetic-trend-v2.schema.json`
- `schemas/kinetic/kinetic-to-horizon-v2.schema.json`
- `schemas/assess/assess-input-v2.schema.json`
- `schemas/assess/assess-output-v2.schema.json`
- synthetic fixtures and positive/negative/cross-contract tests

### KINETIC V2 — V2.2

- `kinetic_v2/kinetic_engine.py`
- null-vs-zero handling
- dynamic target configuration parsing
- bounded 7-day trend aggregation
- schema-bound producer validation
- unit/adversarial tests using synthetic data only

### SPARK V2 — V2.3 / V2.3A

- `spark_v2/evidence.py`
- `spark_v2/provenance.py`
- `spark_v2/epistemic.py`
- `spark_v2/projections.py`
- `spark_v2/spark_engine.py`
- `spark_v2/source_adapters/`
- freshness, provenance, self-report/hypothesis isolation, ACTIVE_NOTE_FILTER, repeated-pattern safeguards
- unit/adversarial tests using synthetic evidence only

### ASSESS V2 — V2.4

- `assess_v2/claims.py`
- `assess_v2/coordinator.py`
- `assess_v2/assess_engine.py`
- `assess_v2/render.py`
- ASSESS_INPUT_V2 -> ASSESS_OUTPUT_V2 typed-claim pipeline
- evidence-reference and epistemic-tag validation
- unit/adversarial tests using synthetic data only

## Reconstruction procedure

1. Reconstruct the seven schemas exactly from the frozen 2.0.1 contracts.
2. Recreate registries and synthetic fixtures.
3. Recreate KINETIC V2 behavior from the accepted V2.2 specification/report.
4. Recreate SPARK V2 behavior from V2.3 plus accepted V2.3A hardening decisions.
5. Recreate ASSESS V2 behavior from the accepted V2.4 specification/report.
6. Run Draft 2020-12 schema validation and the complete synthetic positive/negative/adversarial suite.
7. Compare behavior and test outcomes with recorded phase acceptance criteria.
8. Record any unavoidable ambiguity as an explicit reconstruction note; do not silently invent behavior.
9. Commit only after the reconstructed baseline passes its repository-native validation gates.
10. Mark the baseline `GPOS_V2_4_RECONSTRUCTED_BASELINE` (or equivalent repository tag/checkpoint) before beginning V2.5.

## Required validation gates

- Seven schemas validate under JSON Schema Draft 2020-12.
- Producer/consumer boundaries match the frozen registry.
- Self-reported state admits only `SELF_REPORTED` and `EXPLICIT_USER_INTERPRETATION` bases.
- `SYSTEM_HYPOTHESIS` cannot enter SPARK self-reported state.
- SPARK stale/empty/insufficient states remain null-safe.
- ACTIVE_NOTE_FILTER uses leading-prefix semantics and passes collision tests.
- KINETIC distinguishes absent observation (`null`) from verified numeric zero (`0`).
- KINETIC trend window remains bounded to seven days.
- ASSESS emits structurally typed claims and exactly three immediate micro-actions.
- No raw journal prose leaks into HORIZON-facing contracts.
- No secrets, API keys, raw journal data, financial records, production exports, or sensitive production fixtures are committed.

## Production safety

Reconstruction is repository-only. It must not mutate Google Workspace production documents, ledgers, AEGIS runtime behavior, HORIZON output, calendar data, or live shortcut routing. Production wiring remains deferred to the approved V2.5+ sequence.

## Completion condition

When all reconstruction gates pass, `Cokkles/GEMINI-POS` becomes the version-controlled engineering authority for the reconstructed V2.1–V2.4 baseline. V2.5 HORIZON integration then proceeds from this repository-native baseline rather than from narrative reports alone.

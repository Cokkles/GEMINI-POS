# GPOS V2.1–V2.4 Repository-Native Baseline Reconstruction

Status: **COMPLETE / VALIDATED**
Architecture: `v2.0.1-frozen`
Contract schema version: `2.0.1`
Purpose: establish `Cokkles/GEMINI-POS` as the durable engineering authority for the reconstructed V2.1–V2.4 baseline before V2.5.

## Authority clarification

There is no separate local SPARK/KINETIC/ASSESS implementation tree to import. The accepted implementation history originated in the Gemini/Workspace workstream and its accepted phase reports. The Obsidian Project Notebook remains documentation, not implementation authority.

The private GPOS repository now contains a controlled **repository-native reconstruction** based on the frozen architecture and accepted errata.

## Reconstruction source hierarchy

1. `v2.0.1-frozen` architecture authority.
2. Canonical contract definitions, schema version `2.0.1`.
3. Accepted V2.0–V2.4 phase reports, including V2.3A hardening.
4. GPOS architecture, boundary, epistemic, roadmap, and phase-history documentation.

Frozen contract/accepted errata takes precedence over narrative examples.

## Reconstructed artifacts

### V2.1 — Contracts

- `schemas/registry.json`
- `schemas/consumer_registry.json`
- seven Draft 2020-12 contract schemas under `schemas/spark`, `schemas/kinetic`, and `schemas/assess`

### V2.2 — KINETIC

- `kinetic_v2/kinetic_engine.py`
- null-vs-zero semantics
- dynamic calorie/protein configuration extraction
- date normalization for ISO and M/D/YYYY forms without UTC day shifting
- bounded T-6 through T seven-day trend calculation
- `KINETIC_TO_HORIZON_V2` projection
- optional unsupported modules remain `UNTRACKED`

### V2.3 / V2.3A — SPARK

- `spark_v2/spark_engine.py`
- evidence/provenance/epistemic/projection facades
- structured source adapters
- 48-hour current-state freshness boundary
- self-report vs hypothesis isolation
- leading-prefix `ACTIVE_NOTE_FILTER`
- conservative repeated-pattern confidence
- reference-strategy applicability gate
- HORIZON projection refuses to synthesize missing affect merely to satisfy the downstream schema

### V2.4 — ASSESS

- `assess_v2/assess_engine.py`
- `claims.py`, `coordinator.py`, and `render.py`
- TypedClaim enforcement
- evidence references on substantive claims
- cautious cross-domain hypothesis handling
- exactly three recommendation micro-actions
- no mutation/execution side effects

## Validation

The reconstructed source was independently assembled in an isolated test workspace and run against `jsonschema.Draft202012Validator` plus the repository-native pytest suite.

Result at reconstruction close: **11 tests passed**.

Validated controls include:

- all seven schemas pass Draft 2020-12 meta-schema checks;
- KINETIC absent observation remains `null`, while verified zero remains numeric `0`;
- KINETIC trend excludes rows outside the seven-day window;
- date/configuration parsing matches the accepted V2.2 contract behavior;
- SPARK stale evidence cannot establish current state;
- `SYSTEM_HYPOTHESIS` cannot enter self-reported state;
- two-observation repeated patterns are capped at tentative confidence `0.50`;
- reference frameworks require applicability evidence;
- Notes lifecycle matching uses the leading marker and passes collision cases;
- SPARK-to-HORIZON does not fabricate an affect field when none was reported;
- ASSESS output contains typed claims, provenance references, and exactly three micro-actions.

A GitHub Actions workflow is included at `.github/workflows/reconstructed-baseline.yml` to reproduce the suite in repository CI. At close of this reconstruction pass, a connector-visible Actions run had not yet appeared, so CI execution is not claimed here; the isolated validation result above is the confirmed test result.

## Reconstruction note

The accepted historical reports described Workspace-specific read-only production adapters. The repository baseline intentionally keeps external Workspace connectivity outside the pure domain engines. Production adapters and runtime wiring remain part of controlled integration phases rather than embedding credentials or production records into the reconstructed baseline.

## Production safety

No Google Workspace production document, ledger, HORIZON output, AEGIS runtime, calendar object, or live shortcut route was modified by this reconstruction.

No raw Journal data, financial data, credentials, API keys, production exports, or sensitive production fixtures were committed.

## Completion condition

**SATISFIED.**

`Cokkles/GEMINI-POS` is now the version-controlled engineering authority for the reconstructed V2.1–V2.4 implementation baseline. Forward development should proceed from this repository state.

Next approved runtime phase: **V2.5 — HORIZON Integration Migration**.

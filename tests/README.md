# Tests

Repository-wide validation for GPOS contracts and reconstructed domain boundaries lives here.

Current baseline suite:

- `test_reconstructed_baseline.py`

It validates:

- all seven Draft 2020-12 schemas;
- KINETIC null-vs-zero behavior, date/config parsing, bounded trend windows, and HORIZON projection;
- SPARK freshness, self-report/hypothesis separation, pattern calibration, reference-strategy applicability, ACTIVE_NOTE_FILTER collisions, and no synthetic HORIZON affect;
- ASSESS input/output schema conformance, evidence references, and exactly three immediate micro-actions.

The same suite is executed by `.github/workflows/reconstructed-baseline.yml`.

Latest reconstruction validation: isolated test run passed and GitHub Actions completed successfully.

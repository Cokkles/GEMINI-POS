# Contract Architecture

Current schema version: `2.0.1`

## Frozen SPARK + KINETIC V2 contracts

| Contract | Producer | Allowed consumers |
|---|---|---|
| `SPARK_STATE_V2` | SPARK_EPISTEMIC_ENGINE | SPARK_CORE, SPARK_EPISTEMIC_ENGINE, ASSESS_ENGINE |
| `SPARK_TO_HORIZON_V2` | SPARK_EPISTEMIC_ENGINE | HORIZON_DISPATCHER |
| `KINETIC_STATE_V2` | KINETIC_INGESTION_ENGINE | KINETIC_CORE |
| `KINETIC_TREND_V2` | KINETIC_ANALYTICS_ENGINE | ASSESS_ENGINE |
| `KINETIC_TO_HORIZON_V2` | KINETIC_INGESTION_ENGINE | HORIZON_DISPATCHER |
| `ASSESS_INPUT_V2` | ASSESS_COORDINATOR | ASSESS_ENGINE |
| `ASSESS_OUTPUT_V2` | ASSESS_ENGINE | SPARK_CORE, GEMINI_CHAT |

## Invariants

- Schema defines structure; configuration defines values.
- Missing observation is `null`; verified numeric zero is `0`.
- Consumer modules may not bypass bounded presentation/analysis contracts.
- Raw journal prose and raw nutrition rows do not leak into downstream contracts.
- Contract changes follow explicit versioning; breaking semantic changes require migration planning.

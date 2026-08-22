# Epistemic Model

GPOS separates evidence, interpretation, patterns, hypotheses, and recommendations so model inference cannot silently become user fact.

## SPARK basis taxonomy

- `SELF_REPORTED` — direct first-person observation.
- `EXPLICIT_USER_INTERPRETATION` — the user's own interpretation/causal framing.
- `REPEATED_PATTERN` — bounded pattern supported by independent observations.
- `SYSTEM_HYPOTHESIS` — probabilistic system interpretation; never self-report.
- `EXPLICIT_PREFERENCE` — directly stated durable preference/strategy.
- `REFERENCE_FRAMEWORK` — authorized reference model/strategy.

## Core rules

- System hypotheses must never be represented as self-report.
- Current SPARK self-reported state uses a 48-hour freshness boundary.
- Stale evidence cannot establish current state.
- Historical evidence may contribute to longitudinal patterns when properly bounded.
- Reference availability does not imply current applicability.
- Raw journal capture is evidence, not derived canonical state.
- `:assess` distinguishes `FACT`, `SELF_REPORT`, `PATTERN`, `HYPOTHESIS`, and `RECOMMENDATION` through structural TypedClaims.

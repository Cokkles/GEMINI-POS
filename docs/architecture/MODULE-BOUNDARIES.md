# Module Boundaries

## Boundary rule

Each domain owns its canonical state. Other modules consume only explicitly approved interfaces.

| Module | Owns | Must not own |
|---|---|---|
| AEGIS | UI, capture intent, display, command routing | Canonical domain intelligence or private state |
| HORIZON | Executive briefing selection/orchestration | Raw SPARK interpretation, nutrition math, financial truth |
| SPARK | Reflective/cognitive canonical state and provenance | Financial or nutrition truth |
| KINETIC | Nutrition/physical canonical state and bounded trends | Psychological interpretation |
| SENTINEL-FIN | Personal financial truth, budgeting, cash flow | Investment thesis/research ownership |
| SENTINEL-ATLAS | Planned investment/portfolio/wealth intelligence | Independent household financial truth |
| COMPASS | Planned career state/strategy | Silent inference of durable strengths without evidence |
| PRISM | Assigned ingestion/provenance | Executive presentation authority |
| HELPER | Diagnostics/RCA/recovery checks | Domain business-state authority |

## Important interface rules

- HORIZON consumes `SPARK_TO_HORIZON_V2`, not internal `SPARK_STATE_V2`.
- HORIZON consumes `KINETIC_TO_HORIZON_V2`, not internal `KINETIC_STATE_V2`.
- `:assess` consumes `SPARK_STATE_V2` and `KINETIC_TREND_V2`, not raw Journal or raw nutrition rows.
- SENTINEL-ATLAS should consume approved SENTINEL-FIN state rather than recalculate household investable cash independently.
- AEGIS should route intent to domain services instead of embedding their reasoning logic.

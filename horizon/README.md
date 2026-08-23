# HORIZON

GPOS executive orchestration and briefing integration area.

## V2.5 contract boundary

Repository-native V2.5 introduces `HorizonV25Integrator` in `horizon/integration_v25.py`.

Approved domain inputs:

- `KINETIC_TO_HORIZON_V2` for daily nutrition presentation;
- `SPARK_TO_HORIZON_V2` for optional bounded SPARK presentation;
- bounded current SENTINEL-FIN presentation summaries;
- active Notes & Ideas candidates only through the leading-prefix `ACTIVE_NOTE_FILTER`;
- other independently verified current sections supplied by their authoritative producers.

Explicitly prohibited inputs:

- raw Journal Pad prose;
- raw Calorie & Nutrition Tracking Log rows inside HORIZON;
- PRISM processing internals;
- previous `latest_horizon_briefing` contents as factual evidence;
- `horizon_data.json`;
- `refreshHorizonDataFeed()`;
- `pruneHorizonJsonFile()`;
- legacy `Planner.md` flows;
- remembered or hard-coded fallback values.

The existing production HORIZON runtime remains outside this repository-native consumer until a controlled deployment/cutover is available. Repository V2.5 therefore supports synthetic and read-only production comparison without silently claiming that the external runtime has already been rewired.

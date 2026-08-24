# GEMINI-POS Model Routing Policy

Status: PROVISIONAL — introduced during AEGIS AQ-2.3

GEMINI-POS should not use one Gemini model for every workload. Model selection is a subsystem concern and should reflect task complexity, latency, quota, and cost.

## Current policy
- Deterministic code first when a task can be resolved safely without AI.
- Lightweight extraction/classification/parsing: use a Flash-Lite class model.
- General conversational reasoning and system analysis: use a Flash class model appropriate to current capabilities/quota.
- High-value complex reasoning should not share avoidable quota with trivial parsing workloads.
- Each subsystem should expose its selected model in telemetry where practical.
- Per-subsystem Script Properties should allow model changes without code edits.

## Initial assignments
- AEGIS Calendar AQ-2.3: deterministic-first; fallback `gemini-3.5-flash-lite` via `AEGIS_CALENDAR_MODEL`.
- Existing global `GEMINI_MODEL`: remains unchanged for HORIZON/AQ-1 until separate workload-specific reviews are completed.

## Future review candidates
- HORIZON generation
- AQ conversational modes
- KINETIC macro extraction
- Finance/receipt extraction
- future career/ATLAS analysis

The goal is quality-per-request, not merely minimizing model size.
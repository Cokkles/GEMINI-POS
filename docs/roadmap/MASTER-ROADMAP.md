# GPOS Master Roadmap

## Current stabilization stream — SPARK + KINETIC V2

- [x] V2.0 — Authority Cleanup & Forensic Deprecation
- [x] V2.1 — Canonical Contract Implementation & Schema Registry Lock
- [x] V2.2 — KINETIC Boundary Engine & 7-Day Trend Aggregator
- [x] V2.3 — SPARK Epistemic Engine & Provenance Pipeline
- [x] V2.3A — Epistemic Hardening
- [x] V2.4 — `:assess` Cross-Domain Typed-Claim Engine
- [ ] Repository Migration Checkpoint — import original validated V2.1–V2.4 implementation artifacts and reproduce test parity inside `Cokkles/GEMINI-POS`
- [ ] V2.5 — HORIZON Integration Migration
- [ ] V2.6 — AEGIS Command/UI Alignment
- [ ] V2.7 — End-to-End Validation & Runtime Lock

The repository migration checkpoint is packaging/version-control work, not a new architecture phase. It exists to prevent V2.5 from being implemented against reconstructed or report-derived code.

## High-priority post-stabilization AEGIS work

1. **AEGIS Conversational Query Gateway**
   - backend-only Gemini credentials
   - Quick Ask / Contextual Ask / bounded Conversation modes
   - minimum-sufficient-context routing
   - visible context indicators
   - no automatic conversation-to-canonical-state persistence
2. **AEGIS Calendar Gateway**
   - read/search calendar
   - create/update/reschedule/delete events
   - free/busy and conversational scheduling
   - confirmation for consequential/ambiguous writes
3. **Unified GPOS command surface**
   - Journal, Vent, Reflect, Check-In, Note, Task, Calories
   - Assess, Pulse, Triage, Focus, Reset, Debrief, Review
   - Horizon, Status, Why?

See `AEGIS-HIGH-PRIORITY.md` for the detailed requirements.

## Planned domain expansions

- **SENTINEL-ATLAS** — investment, portfolio, assets, market intelligence, opportunity research, and long-term wealth planning.
- **COMPASS** — career strategy, opportunity tracking, mentorship, skills, accomplishments, and growth planning.

## Guiding principles

- Stabilize domain authority and contracts before adding new intelligence modules or write-capable user interfaces.
- Preserve source-vs-derived-state boundaries.
- Do not reconstruct validated implementation code from narrative reports when the original tested artifacts can be imported.
- Keep AEGIS independently deployable and free of private canonical GPOS state.

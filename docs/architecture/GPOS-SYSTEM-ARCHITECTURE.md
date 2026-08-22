# GPOS System Architecture

Status: Foundation bootstrap  
Architecture baseline: `v2.0.1-frozen`

## Purpose

GEMINI Personal Operating System (GPOS) is the private umbrella architecture coordinating specialized domain modules through bounded contracts rather than one monolithic assistant.

## Domain hierarchy

- **AEGIS** — interaction, display, and command routing client.
- **HORIZON** — executive orchestration and briefing presentation.
- **SPARK** — reflective/cognitive state, journaling evidence processing, epistemic provenance, and strategy support.
- **KINETIC** — nutrition and physical telemetry state/trends.
- **SENTINEL** — financial intelligence family.
  - **SENTINEL-FIN** — personal finance, budgeting, cash flow, liabilities, and canonical financial state.
  - **SENTINEL-ATLAS** — planned investment, portfolio, asset, market, and wealth-growth intelligence.
- **COMPASS** — planned career strategy, opportunities, mentorship, skills, and growth intelligence.
- **PRISM** — source ingestion/provenance where assigned.
- **HELPER** — diagnostics, RCA, health checks, and recovery support.

## Core architectural pattern

`raw evidence -> domain-owned canonical state -> bounded consumer contract -> orchestration/presentation`

Downstream systems must consume approved interfaces and must not bypass domain boundaries by independently rebuilding canonical state from raw source records.

## Current implementation focus

SPARK + KINETIC V2 is the current reference implementation of this pattern. Architecture is frozen at `v2.0.1-frozen`, schemas are `2.0.1`, and implementation has progressed through V2.4. V2.5 will migrate HORIZON to the bounded SPARK/KINETIC presentation contracts.

## Repository boundary

This private repository holds GPOS core code, schemas, tests, architecture, and integrations. It must not contain production private data, journal text, financial records, credentials, or API keys.

AEGIS remains separately deployable/public-facing and should integrate through bounded APIs/contracts.

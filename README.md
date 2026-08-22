# GEMINI-POS

**GEMINI Personal Operating System (GPOS)** is a private, modular personal intelligence and orchestration platform.

This repository is the private core for GPOS architecture, schemas, domain engines, tests, and integration contracts. Public-facing clients such as AEGIS remain independently deployable and consume GPOS through bounded interfaces rather than duplicating domain logic or storing private canonical state.

## Current architecture

- Architecture baseline: `v2.0.1-frozen`
- SPARK + KINETIC V2 status: implementation complete through V2.4; V2.5 HORIZON integration is next
- Contract schema version: `2.0.1`

## Core domains

- **AEGIS** — interaction/UI client and command surface
- **HORIZON** — executive orchestration and briefing presentation
- **SPARK** — reflective/cognitive state and epistemic processing
- **KINETIC** — nutrition/physical telemetry and bounded trends
- **SENTINEL-FIN** — personal finance and canonical financial state
- **SENTINEL-ATLAS** — planned investment, portfolio, asset, and wealth-growth intelligence
- **COMPASS** — planned career strategy, opportunity, mentorship, and growth domain
- **PRISM** — ingestion/provenance support where applicable
- **HELPER** — diagnostics, RCA, and recovery support

## Repository authority

This repository is authoritative for version-controlled GPOS code, contracts, schemas, tests, and implementation documentation. It is **not** a storage location for private production data, secrets, journal contents, financial records, or API keys.

Google Workspace artifacts retain authority where explicitly assigned by GPOS architecture. The Project Notebook/Obsidian vault is a human-readable engineering knowledge base and does not replace runtime authority.

## Security

Never commit credentials, API keys, private journal data, financial statements, production exports, generated sensitive state, or local environment files.

See `docs/architecture/` and `docs/roadmap/` for the system design and implementation roadmap.

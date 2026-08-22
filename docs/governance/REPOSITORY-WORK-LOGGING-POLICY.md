# GPOS Repository-First Work Logging Policy

Status: CURRENT / REQUIRED
Applies to: All GEMINI-POS architecture, implementation, remediation, testing, roadmap, integration, incident, and design work
Repository: `Cokkles/GEMINI-POS`

## Policy

All future GEMINI-POS work must be recorded in the private `Cokkles/GEMINI-POS` repository in the appropriate subsystem folder and documentation hierarchy.

Chat, Gemini/Workspace execution, browser analysis, Codex, AEGIS, Google Workspace artifacts, and the Obsidian Project Notebook may participate in the workflow, but none of them replace the repository as the durable engineering record.

## Required recording behavior

Every meaningful GPOS change or decision must leave an appropriate repository artifact, including as applicable:

- architecture specifications and errata;
- implementation source;
- schemas and contracts;
- tests and fixtures;
- phase reports and completion records;
- remediation and RCA records;
- roadmap additions and priority changes;
- integration contracts;
- AEGIS UX / control-plane requirements;
- security and authority decisions;
- discovered defects and deferred work;
- validation evidence and runtime-cutover notes.

## Folder placement

Work should be stored under the owning subsystem or concern wherever possible, for example:

- `spark_v2/` — SPARK implementation
- `kinetic_v2/` — KINETIC implementation
- `assess_v2/` — ASSESS implementation
- `horizon/` — HORIZON implementation/integration
- `sentinel/` — SENTINEL family implementation
- `integrations/aegis/` — private GPOS-side AEGIS integration contracts/adapters
- `schemas/` — machine-readable contracts
- `tests/` — cross-system test infrastructure
- `docs/architecture/` — system architecture
- `docs/decisions/` — architecture/authority decisions
- `docs/roadmap/` — planned and prioritized work
- `docs/phase-history/` — accepted phase history
- `docs/incidents/` — RCA/remediation records
- `docs/governance/` — policies such as this one

Do not create duplicate canonical documents when an existing appropriate file should be updated.

## Authority boundary

The repository is the version-controlled engineering authority for GPOS source, schemas, tests, architecture records, roadmap, and implementation history.

This does not change runtime data authority. Google Calendar, Gmail, Google Tasks, Workspace ledgers/docs, and other assigned production sources remain authoritative for their runtime data domains.

The Obsidian Project Notebook may mirror or summarize repository content for human navigation, but it is not a competing engineering or runtime authority.

## Work completion rule

A GPOS work item is not considered fully documented until its repository record is updated to reflect:

1. what was changed or decided;
2. owning subsystem;
3. implementation/runtime impact;
4. validation state;
5. remaining work or blockers;
6. phase/roadmap status when applicable.

## Effective rule

Beginning August 22, 2026, all new GEMINI-POS work must follow this repository-first documentation policy.

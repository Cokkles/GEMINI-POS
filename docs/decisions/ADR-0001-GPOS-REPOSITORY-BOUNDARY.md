# ADR-0001 — GPOS Private Core / AEGIS Public Client Boundary

Status: Accepted for bootstrap

## Decision

Maintain GPOS core in the private `Cokkles/GEMINI-POS` repository while AEGIS remains independently deployable in its existing public-facing repository.

## Rationale

GPOS contains architecture, schemas, domain engines, tests, private integrations, financial-domain logic, cognitive-state processing, and future investment/career intelligence that should not be coupled to a public PWA codebase.

AEGIS is a client/control surface. It should consume bounded GPOS APIs/contracts rather than reproduce private domain logic.

## Security boundary

The public AEGIS repository must not contain:

- API keys or credentials
- private journal contents
- private financial records
- production canonical state
- investment/asset data
- sensitive local configuration

## Consequences

- GPOS can evolve privately without forcing AEGIS deployment changes.
- AEGIS remains portable and independently deployable.
- Integration contracts become explicit architectural interfaces.
- Cross-repository version compatibility must eventually be tracked and tested.

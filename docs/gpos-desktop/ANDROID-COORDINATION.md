# GPOS Desktop — Android Coordination Notes

This document is the handoff from the Windows Desktop track to the concurrent Android track.

## Stable assumptions Android may rely on

- Android is a first-class independent GPOS client.
- Android talks directly to the canonical GPOS backend.
- Android never requires GPOS Desktop or the Windows Helper to be online.
- Windows-only concerns (DPAPI, tray lifecycle, localhost Helper API, Windows toast implementation) are not shared backend contracts.
- Canonical subsystem authority remains in the backend.
- Ordinary client sync is read-oriented; expensive generation/ingestion is explicit/policy-controlled.

## Proposed shared metadata — not yet a breaking requirement

Plan for `client_type=GPOS_ANDROID`, Android client version and supported contract versions. Desktop uses the corresponding `GPOS_DESKTOP` identity. Adoption should be additive/optional until all affected clients and backend are coordinated.

## Proposed backend capabilities for cross-client review

- contract/capability discovery
- lightweight canonical notification feed
- version/etag-aware read sync
- consistent compatibility/health envelope

None are implemented as unilateral backend changes by the Desktop D0 milestone.

## Backend change stop condition

If Desktop work discovers a backend change that would affect Android authentication, required parameters, response semantics, mutation behavior or canonical subsystem behavior, Desktop documents the proposal and stops before the breaking backend implementation pending cross-client review.

## Optional future Desktop/Android integration

Presence, handoff, local capability discovery and Windows-side launch may be added opportunistically. They must remain optional enhancements and never become dependencies for core Android operation.
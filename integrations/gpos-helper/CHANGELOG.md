# GPOS Helper Changelog

## Unreleased

- Fixed the control surface request budget so authenticated AEGIS health, dashboard and Calendar reads can use the helper's full bounded upstream window instead of being cancelled by the five-second local-status timeout.
- Validated the production Google OAuth callback, identity allowlist and local session flow with the intended account.

## 0.2.0 - 2026-08-25

Production-validation candidate. This release does not claim production credential validation.

- Added the PWA-aligned helper dashboard, Connection, System, Activity, Snapshot and Calendar read/query surfaces.
- Added Google OAuth PKCE boundaries, encrypted credential lifecycle, refresh and bounded best-effort revocation.
- Added the isolated browser compatibility adapter and AEGIS fallback bridge without integrating or modifying the PWA.
- Added liveness/readiness probes, production preflight, release manifests, package integrity verification and expanded Windows smoke gates.
- Added loopback Host validation, trusted mutation origins, authenticated logout, authentication rate limits, bounded session/login stores and payload byte ceilings.
- Added restart identity and stale-session recovery across the helper dashboard and compatibility adapter.

Remaining production gates are real Google OAuth refresh/revocation validation and deployed Apps Script AUTH-1 validation with the intended allowlisted account.

## 0.1.0 - 2026-08-24

- Initial loopback Windows helper proof of concept.


# GPOS Helper Changelog

## Unreleased

- Added a per-user Windows installer that verifies and copies the packaged executable, stores non-secret production settings locally, protects the OAuth client secret with Windows DPAPI, and creates one-click Desktop/Start Menu launch and stop shortcuts.
- Fixed installed-launcher DPAPI decoding by writing ciphertext without a trailing line ending and tolerating line endings from installations made before the correction.
- Added a protected installed-secret update command and suppressed verbose ASP.NET/HTTP framework logs in one-click mode so OAuth callback query values are not retained in installed log files.
- Added a Windows notification-area controller with Open Dashboard and Exit GPOS Helper actions.
- Report only allowlisted Google OAuth token-exchange error categories so production credential failures can be diagnosed without exposing codes, tokens, secrets or provider descriptions.
- Added a local-only Desktop OAuth client JSON importer that updates the installed client pair together and protects the client value with Windows DPAPI.
- Fixed the control surface request budget so authenticated AEGIS health, dashboard and Calendar reads can use the helper's full bounded upstream window instead of being cancelled by the five-second local-status timeout.
- Made Windows publishing clean its strictly validated artifact directory and added a packaged JavaScript freshness gate so stale dashboard assets cannot pass release smoke validation.
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


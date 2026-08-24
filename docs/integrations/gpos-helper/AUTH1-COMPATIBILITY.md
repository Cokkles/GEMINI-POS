# AEGIS AUTH-1 Compatibility Review

Review date: 2026-08-24  
Read-only AEGIS baseline: `Cokkles/aegis-itinerary-project` commit `892c14382eb43bb893cf71f2393ca5b6244740e8`  
Reviewed frontend contract: `aegis-core.js` blob `4689ac4f55f9c2f7831c1d78a640aed9eb516f94`

## Result

The helper-side read contract is compatible with the current AUTH-1 action names. No subsystem contract redesign is required.

| Current AEGIS behavior | AUTH-1 action | Helper route |
|---|---|---|
| Dashboard/HORIZON load | `get_dashboard` | `GET /api/v1/aegis/dashboard` |
| Calendar natural-language read | `calendar_ai` | `POST /api/v1/aegis/calendar/query` |
| Backend health | `get_health` | `GET /api/v1/aegis/health` |

The current frontend obtains a Google ID token in the browser, places it in the AUTH-1 envelope and retains it for session validation. The proposed helper path changes only credential custody: Google credentials remain in `gpos-helper`, while the browser receives an opaque, finite helper session. Apps Script AUTH-1 remains authoritative upstream because the helper continues sending the current Google ID token in the existing envelope.

## Adoption boundaries

- Keep the existing Apps Script functions as exactly-once fallbacks.
- Add the bridge to the existing AEGIS initialization path; do not add a second bootstrap or periodic worker.
- Migrate dashboard and Calendar read operations first.
- Do not route HORIZON generation, task mutation, Calendar confirmation/write, finance, notifications or other actions through the helper until each typed route exists and is independently reviewed.
- Do not advertise capabilities for fallback-only operations.
- Treat any change to the AEGIS repository as a separate, explicitly reviewed compatibility change.

## Browser-origin requirements

The allowed origin is `https://cokkles.github.io`. Preflight must return that exact origin and permit `X-GPOS-Session`; wildcard origin is forbidden. The helper session exchange does not require third-party cookies. The adapter accepts only HTTP loopback helper URLs and applies a finite timeout to every request.

## Remaining live validation

1. Register the Desktop OAuth client and configure the exact loopback callback.
2. Configure the deployed Apps Script endpoint and intended email allowlist.
3. Run the helper in production mode and authenticate from the system browser.
4. Exercise start, callback, exchange and authenticated dashboard read from the deployed GitHub Pages origin.
5. Confirm AUTH-1 identity allowlist behavior and Google refresh-token rotation with real credentials.

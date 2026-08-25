# GPOS Helper PWA Client Integration

Status: compatibility artifact implemented in GEMINI-POS; AEGIS production files remain unchanged.

`integrations/gpos-helper/client/gpos-helper-client.mjs` is a browser-native ES module for a future, explicitly approved AEGIS integration. It accepts only HTTP loopback helper URLs and provides finite operations for discovery, PKCE authentication, dashboard reads, Calendar reads and logout.

## Proposed PWA flow

1. Import `GposHelperClient` from a versioned copy of the compatibility module.
2. Create one client instance during the existing AEGIS initialization path. Do not add a second bootstrap worker.
3. Call `detect()`. A terminal `UNAVAILABLE` result leaves the existing Apps Script path unchanged.
4. On an explicit user action, call `beginLogin()`. The adapter retains only the PKCE verifier in `sessionStorage` during navigation.
5. On return, call `completeLoginFromFragment()` once. It removes `gpos_code` from the address bar and stores only the opaque helper session for the current tab.
6. Send bounded helper calls through the adapter. Never copy Google tokens into the PWA.
7. Call `logout()` to revoke and clear the local session.

The adapter records only the helper's non-secret process instance identifier. When it changes, the adapter clears the stale helper session and any pending PKCE verifier before another authenticated request can be made.

The adapter is not yet referenced by the AEGIS repository. Production adoption requires a small, separately reviewed compatibility change, fallback validation against the existing Apps Script path, and browser tests from the deployed GitHub Pages origin.

## AEGIS bridge artifact

`gpos-helper-aegis-bridge.mjs` wraps the low-level client with the migration policy expected by AEGIS:

- `initialize()` performs one bounded helper check and does not create a polling worker.
- Signed-out or unavailable helper states retain the current Apps Script path.
- `dashboard(fallback)` and `calendarQuery(..., fallback)` call the helper only when its local session is authenticated.
- A helper terminal failure changes the bridge to `apps_script` mode and invokes the supplied existing operation exactly once.
- The bridge does not reinterpret, merge or manufacture subsystem data.

The future AEGIS compatibility change should create one bridge instance inside the existing initialization path and pass the current Apps Script functions as fallbacks. It must not wrap existing functions recursively and must not change write routes in this stage.

## Validation

```powershell
node integrations/gpos-helper/client/gpos-helper-client.test.mjs
node integrations/gpos-helper/client/gpos-helper-aegis-bridge.test.mjs
```

The offline tests cover loopback enforcement, helper discovery, restart-driven stale-session clearing, S256 challenge creation, one-time code exchange, fragment cleanup, authenticated request headers, remote-base rejection, signed-out fallback, unavailable fallback, helper preference and exactly-once fallback after a terminal helper failure.


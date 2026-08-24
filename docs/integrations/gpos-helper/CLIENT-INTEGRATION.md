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

The adapter is not yet referenced by the AEGIS repository. Production adoption requires a small, separately reviewed compatibility change, fallback validation against the existing Apps Script path, and browser tests from the deployed GitHub Pages origin.

## Validation

```powershell
node integrations/gpos-helper/client/gpos-helper-client.test.mjs
```

The offline test covers loopback enforcement, helper discovery, S256 challenge creation, one-time code exchange, fragment cleanup, authenticated request headers and remote-base rejection.

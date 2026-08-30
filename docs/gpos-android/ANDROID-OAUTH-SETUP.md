# GPOS Android — Native Google OAuth Provisioning

## Purpose

GPOS Android uses Android Credential Manager / Sign in with Google to obtain a Google ID token, then submits that ID token to the existing AUTH-1 backend for validation. Android does not use the Windows Helper session and does not bypass AUTH-1.

## Required Google Cloud identities

The Google Cloud project must contain both:

1. The existing **Web application / server OAuth client** used as the ID-token audience.
2. An **Android OAuth client** identifying the native APK by package name and signing SHA-1.

### Checkpoint Android identity

- Google Cloud project number: `441009275873`
- Package name: `com.cokkles.gpos`
- Checkpoint signing SHA-1: `D2:A0:80:66:49:D0:00:B1:B8:4F:FF:45:A9:C6:DF:76:8D:FC:31:EA`
- Existing Web/server client ID used by Android: `441009275873-qnf9c9n1o3l9tl9c76t2821hm8tectfl.apps.googleusercontent.com`

The deterministic checkpoint key is development-only. A future Play Store / production signing identity will require its own Android OAuth client/signing-certificate registration.

## Google Cloud setup

In the same Cloud project that owns the Web/server client:

Google Cloud Console -> **Google Auth Platform** -> **Clients** -> **Create Client** -> **Android**

Enter:

- Name: `GPOS Android Checkpoint` (informational)
- Package name: `com.cokkles.gpos`
- SHA-1 certificate fingerprint: `D2:A0:80:66:49:D0:00:B1:B8:4F:FF:45:A9:C6:DF:76:8D:FC:31:EA`

The generated Android OAuth client ID is an identity registration for the native APK. Do **not** replace the Web/server client ID passed to `setServerClientId` with this Android client ID.

## Expected flow

1. User taps **Sign in with Google**.
2. Android Credential Manager launches Google identity UI.
3. Google recognizes `com.cokkles.gpos` + the installed APK signing certificate.
4. Google issues an ID token whose audience is the existing Web/server OAuth client.
5. Android POSTs `{ action: "auth_login", auth_token: <ID token> }` to AUTH-1.
6. AUTH-1 validates issuer, expiry, audience, verified email, and allowlist.
7. Android stores the still-expiring ID token using Android Keystore-backed encryption and immediately refreshes canonical data.
8. WorkManager later performs bounded read-only refresh while the credential remains valid.

## 0.3.1 native flow

The 0.3.1 client follows both current Credential Manager patterns:

- explicit button flow: `GetSignInWithGoogleOption`;
- account-selector fallback: `GetGoogleIdOption` with `filterByAuthorizedAccounts=false` and auto-select disabled.

Credential Manager receives an activity-based `MutableContextWrapper`.

The fallback is bounded to one attempt and is used only when the explicit flow reports cancellation so quickly that it is unlikely a user could have intentionally dismissed Google UI.

## Failure-layer diagnostics

The client distinguishes:

- `USER_CANCELLED` — Google UI was actually available long enough to be dismissed;
- `IMMEDIATE_PROVIDER_ABORT` — both native identity flows terminate immediately before token issuance; strongly verify Android OAuth package/SHA provisioning;
- `NO_CREDENTIAL` — no viable Google credential/account is available;
- `PROVIDER_CONFIGURATION` — Credential Manager/provider configuration mismatch;
- `CREDENTIAL_MANAGER_UNSUPPORTED` — Credential Manager is unsupported/disabled;
- `CREDENTIAL_INTERRUPTED` — transient interruption; retry is safe;
- `TOKEN_PARSE_FAILED` — googleid credential parsing failure;
- `ANDROID_OAUTH_CONFIGURATION` — provider error text indicates developer/OAuth configuration failure before AUTH-1;
- `CREDENTIAL_UNKNOWN` / `CREDENTIAL_UNEXPECTED` — native identity failed before AUTH-1 and the visible message includes a bounded diagnostic;
- AUTH-1 rejection — Google produced a token, but backend authentication/authorization rejected it.

Diagnostic codes are included directly in the on-device error text so screenshots can identify the failure layer precisely.

## Important boundaries

- Do not replace the Web/server client ID with the Android client ID in `setServerClientId`.
- Do not weaken AUTH-1 to make Android sign-in work.
- Do not place OAuth secrets in the APK. OAuth client IDs and certificate fingerprints are identifiers, not client secrets.
- Do not treat the development checkpoint certificate as a production signing identity.
- Do not use an embedded WebView for Google sign-in.

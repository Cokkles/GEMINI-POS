# GPOS Android — Native Google OAuth Provisioning

## Purpose

GPOS Android uses Android Credential Manager / Sign in with Google to obtain a Google ID token, then submits that ID token to the existing AUTH-1 backend for validation. Android does not use the Windows Helper session and does not bypass AUTH-1.

## Required Google Cloud identities

The Google Cloud project should contain both:

1. The existing **Web application / server OAuth client** used as the ID-token audience.
2. An **Android OAuth client** identifying the native APK by package name and signing SHA-1.

### Checkpoint Android identity

- Package name: `com.cokkles.gpos`
- Checkpoint signing SHA-1: `D2:A0:80:66:49:D0:00:B1:B8:4F:FF:45:A9:C6:DF:76:8D:FC:31:EA`
- Existing Web/server client ID used by Android: `441009275873-qnf9c9n1o3l9tl9c76t2821hm8tectfl.apps.googleusercontent.com`

The deterministic checkpoint key is development-only. A future Play Store / production signing identity will require its own Android OAuth client or signing-certificate registration.

## Expected flow

1. User taps **Sign in with Google**.
2. Android Credential Manager launches Google identity UI.
3. Google recognizes `com.cokkles.gpos` + the installed APK signing certificate.
4. Google issues an ID token whose audience is the existing Web/server OAuth client.
5. Android POSTs `{ action: "auth_login", auth_token: <ID token> }` to AUTH-1.
6. AUTH-1 validates issuer, expiry, audience, verified email, and allowlist.
7. Android stores the still-expiring ID token using Android Keystore-backed encryption and enables read-only canonical data refresh.

## Failure-layer diagnostics

The 0.2.5 client distinguishes:

- `USER_CANCELLED` — Credential Manager UI was dismissed.
- `NO_CREDENTIAL` — no viable Google credential/account is currently available.
- `PROVIDER_CONFIGURATION` — Credential Manager/provider configuration mismatch.
- `CREDENTIAL_MANAGER_UNSUPPORTED` — Credential Manager is unsupported/disabled.
- `CREDENTIAL_INTERRUPTED` — transient interruption; retry is safe.
- `TOKEN_PARSE_FAILED` — googleid credential parsing failure.
- `ANDROID_OAUTH_CONFIGURATION` — likely package/SHA Android OAuth registration failure before AUTH-1.
- AUTH-1 rejection — Google produced a token but the backend rejected authentication/authorization.

## Important boundaries

- Do not replace the Web/server client ID with the Android client ID in `setServerClientId` / `GetSignInWithGoogleOption`.
- Do not weaken AUTH-1 to make Android sign-in work.
- Do not place OAuth secrets in the APK. OAuth client IDs and certificate fingerprints are identifiers, not client secrets.
- Do not treat the development checkpoint certificate as a production signing identity.

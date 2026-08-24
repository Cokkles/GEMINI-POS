# GPOS Helper Windows Setup

## Prerequisites

- Windows x64 for the packaged POC.
- .NET 8 SDK only when building from source. The published executable is self-contained.
- A Google Cloud Desktop OAuth client and the deployed AEGIS Apps Script URL for production mode.

## Development run

From `integrations/gpos-helper`:

```powershell
.\scripts\run-dev.ps1
```

This deliberately enables the mock provider and listens at `http://127.0.0.1:47831`. Never enable development mode for production use.

## Production configuration

Copy `config/appsettings.example.json` to ignored `config/appsettings.json` for non-secret settings. Prefer environment variables for sensitive values:

```powershell
$env:GPOS_Helper__AppsScriptEndpoint = 'https://script.google.com/macros/s/DEPLOYMENT_ID/exec'
$env:GPOS_Helper__GoogleOAuth__ClientId = 'DESKTOP_CLIENT_ID'
$env:GPOS_Helper__GoogleOAuth__ClientSecret = 'DESKTOP_CLIENT_SECRET'
$env:GPOS_Helper__AllowedEmails__0 = 'AUTHORIZED_ACCOUNT@example.com'
.\dist\win-x64\gpos-helper.exe
```

Do not place real values in the example file or commit a populated local configuration.

## Google Cloud OAuth registration

Create an OAuth 2.0 Client ID with application type **Desktop app**. Configure the consent screen and requested scopes (`openid email profile`). The helper uses Authorization Code flow with PKCE and the exact loopback redirect URI `http://127.0.0.1:47831/api/v1/auth/callback` by default. If the port changes, update `RedirectUri` to match. Add the intended Google account to `AllowedEmails`; an empty production allowlist denies every identity.

The OAuth client value called a client secret for an installed application cannot be treated as a confidential server secret, but this repository still requires it to be supplied at runtime and never committed.

## Build and verify

```powershell
.\scripts\build-windows.ps1
Invoke-RestMethod http://127.0.0.1:47831/api/v1/health
```

Output: `integrations/gpos-helper/dist/win-x64/gpos-helper.exe`.

Foreground execution requires no Administrator privileges. Stop with Ctrl+C for graceful cancellation. Windows Service/tray installation is intentionally deferred.


# gpos-helper

Windows production-validation candidate for GEMINI-POS. The helper is one .NET 8 process that exposes a hardened loopback HTTP API, brokers Google OAuth into local sessions, stores Google credentials with platform-protected encryption, and provides a bounded authenticated gateway to the existing AEGIS Apps Script backend.

Quick start:

```powershell
.\scripts\start-helper.ps1
```

This starts development mode in its own PowerShell window, waits for health and opens the dashboard. Use `.\scripts\start-helper.ps1 -Mode Production` after setting the production environment variables.

Install the verified Windows package for one-click use:

```powershell
.\scripts\install-windows.ps1
```

The one-time installer prompts for production settings, protects the OAuth client secret with Windows DPAPI, copies the real `gpos-helper.exe` under the current user's local Programs directory, and creates Desktop and Start Menu shortcuts. After installation, opening **GPOS Helper** starts the executable hidden and opens the dashboard; no PowerShell setup commands are needed again.

Build the self-contained executable:

```powershell
.\scripts\build-windows.ps1
```

Verify an extracted package before launch:

```powershell
.\scripts\verify-package.ps1
```

Validate production configuration without starting the helper:

```powershell
.\scripts\run-production.ps1 -Preflight
```

Run every offline validation suite and the packaged Windows gate:

```powershell
.\scripts\test-all.ps1 -IncludePackage
```

See `docs/integrations/gpos-helper/` for the API, security model, setup, and current limitations.


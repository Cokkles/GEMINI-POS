# gpos-helper

Windows production-validation candidate for GEMINI-POS. The helper is one .NET 8 process that exposes a hardened loopback HTTP API, brokers Google OAuth into local sessions, stores Google credentials with platform-protected encryption, and provides a bounded authenticated gateway to the existing AEGIS Apps Script backend.

Quick start:

```powershell
.\scripts\run-dev.ps1
```

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


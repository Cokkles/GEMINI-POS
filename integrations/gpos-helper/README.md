# gpos-helper

Phase-0 Windows host POC for GEMINI-POS. The helper is one .NET 8 process that exposes a loopback HTTP API, brokers Google OAuth into local sessions, stores Google credentials with Windows DPAPI, and provides a small authenticated gateway to the existing AEGIS Apps Script backend.

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

See `docs/integrations/gpos-helper/` for the API, security model, setup, and current limitations.


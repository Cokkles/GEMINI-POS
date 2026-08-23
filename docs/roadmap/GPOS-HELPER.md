# GPOS HELPER — Windows Host Direction

## Decision

For the first local-host implementation, GEMINI-POS will prefer a single lightweight Windows executable:

`gpos-helper.exe`

The goal is to minimize the number of separate Windows services/processes while still providing a persistent local execution and notification bridge.

## Intended HELPER-1 scope

After AEGIS AQ-2 Calendar control is complete, design a small Windows tray/background application that can:

- start with Windows;
- authenticate to the existing secured AEGIS/GEMINI-POS backend;
- poll or receive bounded notification work;
- issue native Windows notifications;
- deep-link notifications back into AEGIS;
- expose local heartbeat/health state;
- provide a future home for local-only integrations such as clipboard, hotkeys, file access, launch actions, and optional voice hooks.

## Architecture constraint

Do not embed Google credentials or the Gemini API key into the Windows executable. Service credentials remain server-side. HELPER should use a device/session authorization model against the secured AEGIS backend.

## Packaging direction

Primary: standalone/tray `gpos-helper.exe` for Windows.

Future optional packaging: containerized/headless build for NAS/Linux/server environments if useful. Docker is not the primary Windows notification runtime because interactive Windows toast/session integration is better handled by a native user-session process.

## Priority

Deferred until AEGIS AQ-2 Calendar conversational read/write with preview-and-confirmation is completed and stabilized.

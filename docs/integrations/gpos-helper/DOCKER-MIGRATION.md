# GPOS Helper Docker Migration

The POC includes a multi-stage .NET 8 `Dockerfile` as a portability proof; it is not a production deployment declaration.

Core HTTP, session, gateway, retry, logging, and worker code is platform-neutral. `ISecretStore` isolates Windows DPAPI. Before Linux/container deployment, add a fail-closed implementation backed by an orchestrator secret, protected mounted file, or managed secret service. Do not emulate DPAPI with plaintext JSON.

The Dockerfile binds `0.0.0.0` because container loopback would not be reachable through a published port. A production deployment must add TLS or a trusted reverse proxy, explicit network policy, a precise frontend-origin allowlist, health checks, non-root execution review, and secret injection. LAN/Internet exposure is not approved by this POC.

Suggested Phase-1 validation:

1. Implement and test `ISecretStore` for the selected host.
2. Build the image and run the same automated harness on Linux.
3. Add container health checks against `/api/v1/health`.
4. Verify SIGTERM graceful shutdown and worker cancellation.
5. Threat-model reverse-proxy headers, TLS termination, CSRF and session cookie settings.

No Registry dependency or Windows-only business logic blocks this migration.


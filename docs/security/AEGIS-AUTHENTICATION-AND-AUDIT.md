# AEGIS Authentication & Audit Architecture

Status: PLANNED / HIGH PRIORITY
Owner: GPOS / AEGIS Security Boundary

## Objective

AEGIS does not need to be hidden from the public internet merely for secrecy. The primary requirement is that personal GPOS data and consequential actions are available only after authenticated, authorized access.

The security model therefore prioritizes **application authentication and authorization** over obscurity of the URL.

## Core model

Internet / PWA URL
    -> Authentication
    -> Session validation
    -> Authorization policy
    -> AEGIS UI
    -> GPOS gateways / APIs

A public hostname may exist. Possession of the URL must never grant access to private GPOS state or write capabilities.

## Required capabilities

### Identity

- Prefer identity-based login rather than a shared site password.
- Initial deployment may be single-user with an explicit identity/email allowlist.
- Architecture should permit additional explicitly authorized users later without redesigning the system.
- MFA/passkey support should be possible through the selected identity provider.

### Sessions

- Secure authenticated sessions.
- Server-side or cryptographically verifiable session validation.
- Session expiration and reauthentication policy.
- Logout and ability to revoke active sessions.
- Do not store privileged credentials or reusable backend secrets in browser-accessible code.

### Authorization

Authentication answers **who is this?** Authorization answers **what may this identity do?**

All sensitive backend operations must enforce authorization independently of frontend visibility, including future:

- Gmail / Mail Gateway reads and mutations;
- Google Calendar reads and mutations;
- GPOS conversational queries using private context;
- SPARK and Journal access;
- SENTINEL financial access;
- future SENTINEL-ATLAS investment data;
- task/note mutations;
- system administrative actions.

A browser-side login screen alone is not a security boundary.

## Authentication audit trail

Design the authentication layer so GPOS can later expose a security history view.

Recommended audit-event model:

- event_id
- occurred_at
- event_type
- authenticated_identity / user_id
- success / failure
- session_id
- source IP or privacy-preserving network metadata when available
- user agent / device family
- coarse location or provider risk signal only when legitimately available
- authentication provider
- failure reason category
- logout / expiration / revocation state

Potential event types:

- LOGIN_SUCCESS
- LOGIN_FAILURE
- MFA_CHALLENGE
- SESSION_CREATED
- SESSION_REFRESHED
- SESSION_EXPIRED
- LOGOUT
- SESSION_REVOKED
- AUTHORIZATION_DENIED
- SENSITIVE_ACTION_APPROVED
- SENSITIVE_ACTION_REJECTED

Do not log passwords, tokens, OAuth authorization codes, message bodies, journal contents, or other sensitive payloads in the authentication log.

## Future AEGIS security UX

AEGIS may eventually expose a Security / Sessions screen showing:

- recent successful logins;
- recent failed attempts;
- approximate device/browser;
- session start and last-active time;
- currently active sessions;
- ability to revoke a session;
- unusual-access warnings where supported by the identity provider.

The audit log must remain append-oriented and should not be silently rewritten by the frontend.

## Hosting independence

Authentication should be designed so GPOS is not permanently coupled to a single frontend host.

GitHub Pages, Cloudflare Pages, another static host, or a future custom deployment may serve AEGIS. Authorization at the GPOS/API boundary must remain effective even if the frontend hosting mechanism changes.

A future personal custom domain is compatible with this architecture but is not a prerequisite for defining authentication correctly.

## Relationship to edge authentication

Cloudflare Access or another edge identity layer may be used as an additional first gate. It should not be considered a substitute for authorization on sensitive GPOS backend APIs once AEGIS gains Gmail, Calendar, financial, journal, or other consequential capabilities.

Defense in depth target:

1. Optional edge/application access gate.
2. Authenticated application session.
3. Backend authorization on every protected operation.
4. Explicit confirmation for high-impact mutations where required.
5. Security/audit event recording.

## Implementation priority

This security foundation should be implemented before broad production enablement of Gmail write capabilities, Calendar mutation through AI, or other sensitive GPOS control-plane features.

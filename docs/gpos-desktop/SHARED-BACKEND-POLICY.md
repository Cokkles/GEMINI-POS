# GPOS Shared Backend Coordination Policy

Windows Desktop, Android and the optional AEGIS PWA are independent clients of the same canonical GPOS backend.

## Rule

**PROPOSE FREELY. BREAK NOTHING SILENTLY. VERSION DELIBERATELY. COORDINATE ALL CLIENTS.**

A client track may identify and document backend improvements. It may implement client-local work freely. It must stop before a breaking shared-backend contract, authentication behavior, authorization rule, or response-semantic change unless explicit cross-client review authorizes implementation.

## Required change proposal

Any proposed shared change documents:

1. problem and expected benefit
2. affected endpoint/contract
3. Desktop impact
4. Android impact
5. PWA impact
6. existing Helper compatibility impact
7. backward-compatible or versioned design
8. required client changes
9. rollout order
10. rollback strategy

## Client-local changes that do not require shared approval

Examples: Windows tray behavior, DPAPI storage, local cache implementation, dashboard window lifecycle, Android local storage, platform notification presentation, client-only diagnostics, and UI navigation.

## Shared changes requiring coordination

Examples: AUTH-1 trusted audience semantics, required request metadata, endpoint removal/renaming, response field removal/type/meaning changes, mutation semantics, finance/HORIZON/PRISM canonical behavior, or changes that alter another client's ability to authenticate/read/mutate.

## Compatibility preference

Prefer additive fields, optional metadata, capability discovery and explicit contract versions. When a breaking change is justified, support old and new contracts concurrently through a defined migration window whenever practical.

## Client identity

Target non-secret metadata:

- `GPOS_DESKTOP`
- `GPOS_ANDROID`
- `AEGIS_PWA`

with client version and supported contract versions. This metadata is for diagnostics, compatibility and rollout safety; it is not an authentication secret.

## Authority boundary

Clients may cache canonical data but must not become canonical authority for HORIZON, PRISM, SENTINEL-FIN, Calendar, Tasks, Notes, Follow-ups, SPARK or KINETIC. Android must not depend on a Windows PC or Windows Helper.
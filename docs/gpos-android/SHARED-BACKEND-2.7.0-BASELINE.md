# Shared AEGIS Apps Script 2.7.0 — Android reference

Verified from the user's supplied source on September 4, 2026. Source SHA-256: `0cc7fdca4e96dcd545161eae4d838fb974b1ba583818ddd7de1ecbf88b4a03e1` (original CRLF bytes). This verifies a supplied source baseline, not the version currently running in the user's deployment; runtime capability discovery remains authoritative.

Android 0.6.1 changes no backend code or deployment. Android, Windows, PWA, and the optional Helper share canonical contracts; Android never depends on Windows availability.

## Verified additions for the next workspace phase

| Advertised contract | Relevant source actions | Required Android behavior |
| --- | --- | --- |
| `task_workspace_v1` | `get_task_workspace` | Preserve list IDs and titles; all-list view plus filters |
| `task_crud_v1` | `create_task`, `update_task`, `delete_task` | Explicit user actions; retain list identity; confirm deletion |
| `tasks_history_v1` | `get_task_history`, `restore_task` | 7/30-day history; restore to original list |
| `task_lists_v1` | `create_task_list`, `rename_task_list` | Explicit create/rename; no list deletion UI |
| `calendar_prepare_v1` | `calendar_prepare`, existing `calendar_confirm` | Deterministic prepare → exact preview → explicit confirm → refresh |

Reads require `tasks.read`; the task/list mutation routes require `tasks.write`. `calendar_prepare` requires `calendar.write`. Earlier task completion, Follow-ups, Calendar AI, and canonical reads remain available under their existing contracts. These observations are not substitutes for reviewing each complete request/response mapping before implementing 0.7.

## Authentication

`auth_config` provides the primary public `client_id`, plus `trusted_audience_count` and `additional_audiences_configured`. It does not reveal the full trusted audience list. Therefore a different primary ID with advertised additional audiences is not sufficient evidence to reject the Android client locally. The token still goes through the existing AUTH-1 validation and allowlist/scopes. Android does not create its own indefinite session or override Google token expiration.

## Standing cross-client rule

**PROPOSE FREELY. BREAK NOTHING SILENTLY. VERSION DELIBERATELY. COORDINATE ALL CLIENTS.**

A shared change records the problem/benefit, endpoint/contract, Windows/Android/PWA/Helper impact, compatibility/versioning, client changes, rollout order, and rollback. Prefer additive fields, optional client metadata, capability discovery and concurrent versioned contracts. Breaking authentication, authorization, request, response, mutation or canonical subsystem changes require explicit cross-client review before implementation.

Reference policy: `Cokkles/AEGIS-Windows`, `codex/0.3.6-d2.7-tasks-followups`, `docs/gpos-desktop/SHARED-BACKEND-POLICY.md` (blob `5f2b96c36b8267c5b6cb63978fd54c70446cfce4`). Full parity requirements are preserved in `WINDOWS-PARITY-BRIEF-2026-09-03.md`.

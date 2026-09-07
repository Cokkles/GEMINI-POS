# AEGIS_CAPTURE_RELIABILITY_V1 Proposal

This is an additive cross-client proposal. It is not part of Apps Script 2.7.0 and must not be deployed silently.

## Capability

`get_capabilities` may advertise:

```json
{"ux_contracts":{"capture_reliability_v1":true}}
```

## Safe transient failure

A Gemini-dependent request that definitely performed no durable write may return:

```json
{
  "status":"error",
  "contract":"AEGIS_CAPTURE_RELIABILITY_V1",
  "code":"GEMINI_HIGH_VOLUME",
  "error":"Gemini is temporarily at capacity.",
  "retryable":true,
  "write_state":"NOT_STARTED"
}
```

Clients must not retry when `write_state` is absent or differs from `NOT_STARTED`.

## Idempotency requirement

Before advertising the capability, the server must durably deduplicate `submission_id` across every capture destination. The same ID must return the original confirmed result without appending a second row or journal entry.

## Android policy

- Only Meal / Calories and Receipt / Finance are Gemini-dependent.
- Retry after 60 seconds, then 120 seconds; stop after three total attempts.
- Preserve the pending payload only in the encrypted local ledger.
- Notify on confirmed completion or terminal failure, not on each intermediate retry.

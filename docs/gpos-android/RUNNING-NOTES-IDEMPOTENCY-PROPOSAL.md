# Shared backend proposal: durable submission deduplication

Status: proposal only. No shared implementation or deployment authorized by this Android phase.

Problem/benefit: Windows and Android attach stable submission IDs to canonical notes but the supplied Apps Script 2.7.0 does not deduplicate them. A lost success response can leave an uncertain result. Android preserves the original submission and requires explicit review; automatic retries are disabled.

Proposed contract: additive capability `capture_idempotency_v1` on existing note/journal capture envelopes, with an identity- and operation-bound durable receipt lookup action. Record the submission ID, payload hash and confirmed canonical outcome. Reusing an ID with a different payload must fail explicitly. Serialize concurrent claims; never report success before a canonical append is confirmed. Design recovery for a crash between append and receipt persistence before implementation. Do not assume a PropertiesService cache alone provides exactly-once behavior.

Client impacts: Windows may safely reconcile its existing stable IDs only once the capability is advertised; Android can replace manual uncertain-result review with receipt lookup. PWA/Helper requests without the optional field retain their current semantics. No new authorization scope or canonical journal authority. Other capture kinds (especially finance/nutrition) require separate subsystem review before opting in.

Versioning/rollout: validate on a non-production journal, document envelope and failure semantics, deploy capability-gated additive backend support, then independently enable Windows/Android/PWA handling. Keep 2.7.0-compatible clients supported. Coordinate capability/version release in each client repository and the master registry before deployment.

Rollback: disable the capability, retain the durable receipt history, and return clients to explicit uncertain-result review. Never delete deduplication history or automatically replay retained submissions during rollback.

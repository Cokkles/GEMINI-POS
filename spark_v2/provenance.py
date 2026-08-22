from __future__ import annotations
from .spark_engine import EvidenceItem

def provenance_rows(evidence: list[EvidenceItem]) -> list[dict]:
    return [{"doc_id": e.source_id, "timestamp": e.timestamp, "basis": e.basis} for e in evidence]

def provenance_summary(rows: list[dict]) -> dict:
    latest = max((r["timestamp"] for r in rows), default=None)
    return {"evidence_items": len(rows), "latest_entry_timestamp": latest}

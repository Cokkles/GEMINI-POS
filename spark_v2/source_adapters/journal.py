from __future__ import annotations
from ..spark_engine import EvidenceItem

def evidence_from_structured_entry(entry: dict) -> EvidenceItem:
    """Convert an already-segmented Journal record into SPARK evidence without inferring state."""
    required=("evidence_id","source_id","timestamp","locator","evidence_class","normalized_observation","basis")
    missing=[k for k in required if k not in entry]
    if missing:
        raise ValueError(f"missing journal evidence fields: {missing}")
    return EvidenceItem(
        evidence_id=entry["evidence_id"],
        source_id=entry["source_id"],
        timestamp=entry["timestamp"],
        locator=entry["locator"],
        evidence_class=entry["evidence_class"],
        normalized_observation=entry["normalized_observation"],
        basis=entry["basis"],
        metadata=dict(entry.get("metadata",{})),
    )

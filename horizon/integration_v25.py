from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Iterable
import re

ACTIVE_PREFIX = re.compile(r"^\s*(ACTIVE|TODO|OPEN|FOLLOW_UP)\s*:\s*", re.I)
TOMBSTONE_PREFIX = re.compile(r"^\s*(DONE|COMPLETED|MARK_DONE|MARK_DOWN|IGNORE|TEST)\s*:\s*", re.I)

RETIRED_MECHANISMS = {
    "horizon_data.json",
    "refreshHorizonDataFeed",
    "refreshHorizonDataFeed()",
    "pruneHorizonJsonFile",
    "pruneHorizonJsonFile()",
    "Planner.md",
}

class HorizonSourceUnavailable(RuntimeError):
    pass

class RetiredHorizonPathBlocked(RuntimeError):
    pass


def classify_note_entry(text: str) -> str:
    """Return ACTIVE_CANDIDATE, TOMBSTONE, or ARCHIVAL_UNMARKED using leading markers only."""
    if ACTIVE_PREFIX.match(text or ""):
        return "ACTIVE_CANDIDATE"
    if TOMBSTONE_PREFIX.match(text or ""):
        return "TOMBSTONE"
    return "ARCHIVAL_UNMARKED"


def _assert_no_retired_reference(value: Any) -> None:
    if value is None:
        return
    text = str(value)
    for token in RETIRED_MECHANISMS:
        if token.lower() in text.lower():
            raise RetiredHorizonPathBlocked(f"RETIRED_HORIZON_PATH_BLOCKED: {token}")


@dataclass(frozen=True)
class HorizonV25Integrator:
    """Contract-only HORIZON consumer introduced in V2.5.

    The class accepts already-produced domain interface contracts. It does not read
    Journal Pad rows, nutrition ledger rows, PRISM internals, previous briefing
    output, or legacy JSON feed artifacts.
    """

    kinetic_contract_title: str = "KINETIC_TO_HORIZON_V2"
    spark_contract_title: str = "SPARK_TO_HORIZON_V2"

    def consume_kinetic(self, payload: Mapping[str, Any] | None) -> dict[str, Any]:
        if payload is None:
            return {"status": "UNAVAILABLE", "display": "Nutrition unavailable", "source": self.kinetic_contract_title}
        status = payload.get("adherence_status")
        if status == "NO_ENTRIES":
            return {
                "status": "CURRENT",
                "display": "No nutrition logged yet today",
                "calories": None,
                "protein_g": None,
                "carbs_g": None,
                "fat_g": None,
                "sodium_mg": None,
                "target": payload.get("calorie_target_display", ""),
                "protein_target": payload.get("protein_target_display", ""),
                "source": self.kinetic_contract_title,
            }
        if status not in {"ON_TRACK", "UNDER_TARGET", "EXCEEDED", "UNCONFIGURED"}:
            raise ValueError(f"invalid KINETIC_TO_HORIZON_V2 adherence_status: {status!r}")
        return {
            "status": "CURRENT",
            "display": status,
            "calories": payload.get("calories_consumed"),
            "protein_g": payload.get("protein_consumed_g"),
            "carbs_g": payload.get("carbs_consumed_g"),
            "fat_g": payload.get("fat_consumed_g"),
            "sodium_mg": payload.get("sodium_consumed_mg"),
            "target": payload.get("calorie_target_display", ""),
            "protein_target": payload.get("protein_target_display", ""),
            "source": self.kinetic_contract_title,
        }

    def consume_spark(self, payload: Mapping[str, Any] | None) -> dict[str, Any] | None:
        if payload is None:
            return None
        status = payload.get("state_status")
        if status in {"NOT_MATERIAL", "STALE", "OMITTED"}:
            return None
        if status != "AVAILABLE":
            raise ValueError(f"invalid SPARK_TO_HORIZON_V2 state_status: {status!r}")
        state = payload.get("current_self_reported_state")
        if not state:
            raise ValueError("AVAILABLE SPARK contract requires grounded current_self_reported_state")
        # Only bounded fields from the presentation contract are accepted.
        return {
            "status": "CURRENT",
            "energy": state.get("energy_level"),
            "sensory_load": state.get("sensory_load"),
            "affect": state.get("affect"),
            "supported_patterns": list(payload.get("supported_patterns") or []),
            "strategy_observations": list(payload.get("strategy_observations") or []),
            "action_candidates": list(payload.get("action_candidates") or []),
            "provenance_summary": dict(payload.get("provenance_summary") or {}),
            "source": self.spark_contract_title,
        }

    def filter_active_notes(self, entries: Iterable[str]) -> list[str]:
        return [entry for entry in entries if classify_note_entry(entry) == "ACTIVE_CANDIDATE"]

    def consume_sentinel_fin(self, payload: Mapping[str, Any] | None) -> dict[str, Any]:
        """Pass through bounded SENTINEL-FIN summary without reading PRISM internals."""
        if payload is None:
            return {"status": "UNAVAILABLE", "source": "SENTINEL_FIN"}
        if any(k.lower().startswith("prism") for k in payload):
            raise ValueError("PRISM processing internals are prohibited HORIZON presentation input")
        return {"status": "CURRENT", "source": "SENTINEL_FIN", "summary": dict(payload)}

    def assemble_context(
        self,
        *,
        kinetic: Mapping[str, Any] | None,
        spark: Mapping[str, Any] | None,
        sentinel_fin: Mapping[str, Any] | None,
        active_notes: Iterable[str] = (),
        other_current_sections: Mapping[str, Any] | None = None,
        previous_briefing: Any = None,
        source_failures: Iterable[str] = (),
    ) -> dict[str, Any]:
        """Assemble clean-room HORIZON context from approved current interfaces.

        previous_briefing is accepted only so callers can explicitly prove that it
        is discarded. Its value never enters the returned context.
        """
        del previous_briefing
        failures = list(source_failures)
        for failure in failures:
            _assert_no_retired_reference(failure)
        sections = dict(other_current_sections or {})
        for key, value in sections.items():
            _assert_no_retired_reference(key)
            _assert_no_retired_reference(value)
        return {
            "lifecycle": "CURRENT",
            "kinetic": self.consume_kinetic(kinetic),
            "spark": self.consume_spark(spark),
            "sentinel_fin": self.consume_sentinel_fin(sentinel_fin),
            "active_notes": self.filter_active_notes(active_notes),
            "sections": sections,
            "unavailable_sources": failures,
            "clean_room": {
                "previous_briefing_used_as_input": False,
                "raw_journal_access": False,
                "raw_kinetic_row_math": False,
                "prism_internal_access": False,
                "legacy_horizon_access": False,
            },
        }

    def block_retired_mechanism(self, mechanism: str) -> None:
        _assert_no_retired_reference(mechanism)
        raise RetiredHorizonPathBlocked(f"RETIRED_HORIZON_PATH_BLOCKED: {mechanism}")

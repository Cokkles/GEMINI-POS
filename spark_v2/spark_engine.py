from __future__ import annotations
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from typing import Any, Iterable, Mapping

ALLOWED_SELF_BASES={"SELF_REPORTED","EXPLICIT_USER_INTERPRETATION"}

@dataclass(frozen=True)
class EvidenceItem:
    evidence_id:str
    source_id:str
    timestamp:str
    locator:str
    evidence_class:str
    normalized_observation:str
    basis:str
    metadata:dict=field(default_factory=dict)

class SparkEngine:
    def __init__(self, freshness_hours:int=48):
        self.freshness=timedelta(hours=freshness_hours)

    def build_state(self, evidence:Iterable[EvidenceItem], now_iso:str, patterns=None, strategies=None)->dict:
        evidence=list(evidence)
        now=datetime.fromisoformat(now_iso.replace("Z","+00:00"))
        current=[]
        stale_relevant=False
        for e in evidence:
            ts=datetime.fromisoformat(e.timestamp.replace("Z","+00:00"))
            if e.basis in ALLOWED_SELF_BASES and e.metadata.get("state_fragment"):
                if now-ts <= self.freshness:
                    current.append(e)
                else:
                    stale_relevant=True
        if not evidence:
            status="EMPTY"; state=None
        elif not current:
            status="STALE" if stale_relevant else "INSUFFICIENT_EVIDENCE"; state=None
        else:
            merged={}
            basis_map={}
            conf_map={}
            affect=None
            for e in sorted(current,key=lambda x:x.timestamp):
                frag=e.metadata["state_fragment"]
                if "energy" in frag:
                    merged["energy"]=frag["energy"]; basis_map["energy"]=e.basis; conf_map["energy"]=1.0
                if "sensory_load" in frag:
                    merged["sensory_load"]=frag["sensory_load"]; basis_map["sensory_load"]=e.basis; conf_map["sensory_load"]=1.0
                if "affect" in frag:
                    affect=(frag["affect"],e.basis)
            if "energy" not in merged or "sensory_load" not in merged:
                status="INSUFFICIENT_EVIDENCE"; state=None
            else:
                status="AVAILABLE"
                state={
                    "energy":{"value":merged["energy"],"basis":basis_map["energy"],"confidence":conf_map["energy"]},
                    "sensory_load":{"value":merged["sensory_load"],"basis":basis_map["sensory_load"],"confidence":conf_map["sensory_load"]},
                }
                if affect:
                    state["affect"]={"description":affect[0],"basis":affect[1]}
        pats=list(patterns or [])
        strats=list(strategies or [])
        prov=[{"doc_id":e.source_id,"timestamp":e.timestamp,"basis":e.basis} for e in evidence]
        start=(now-timedelta(days=7)).isoformat().replace("+00:00","Z")
        return {
            "generated_at":now.isoformat().replace("+00:00","Z"),
            "state_status":status,
            "source_window":{"start":start,"end":now.isoformat().replace("+00:00","Z")},
            "self_reported_state":state,
            "supported_patterns":pats,
            "strategy_observations":strats,
            "provenance":prov
        }

    @staticmethod
    def pattern(pattern_id:str, description:str, supporting_evidence:list[EvidenceItem])->dict|None:
        unique_dates={e.timestamp[:10] for e in supporting_evidence}
        if len(supporting_evidence)<2 or len(unique_dates)<2:
            return None
        n=len(unique_dates)
        confidence=min(0.50 + 0.10*(n-2),0.85)
        return {"pattern_id":pattern_id,"description":description,"basis":"REPEATED_PATTERN","confidence":confidence}

    @staticmethod
    def strategy(strategy_id:str, observation:str, basis:str, applicability_refs:list[str]|None=None)->dict|None:
        if basis=="REFERENCE_FRAMEWORK" and not applicability_refs:
            return None
        if basis not in {"EXPLICIT_PREFERENCE","REFERENCE_FRAMEWORK"}:
            return None
        return {"strategy_id":strategy_id,"observation":observation,"basis":basis}

    def project_to_horizon(self,state:Mapping[str,Any],action_candidates=None,material=True)->dict:
        ss=state["state_status"]
        if ss=="AVAILABLE" and material and state["self_reported_state"] and "affect" in state["self_reported_state"]:
            hs="AVAILABLE"
            src=state["self_reported_state"]
            cur={
                "energy_level":src["energy"],
                "sensory_load":src["sensory_load"],
                "affect":src["affect"]
            }
        elif ss=="AVAILABLE":
            hs="NOT_MATERIAL"; cur=None
        elif ss=="STALE":
            hs="STALE"; cur=None
        else:
            hs="NOT_MATERIAL"; cur=None
        patterns=[{"pattern_id":p["pattern_id"],"summary":p["description"],"basis":p["basis"],"confidence":p["confidence"]} for p in state["supported_patterns"]]
        latest=max((p["timestamp"] for p in state["provenance"]),default=None)
        return {
            "generated_at":state["generated_at"],"state_status":hs,"source_window":state["source_window"],
            "current_self_reported_state":cur,"supported_patterns":patterns,
            "strategy_observations":state["strategy_observations"],
            "action_candidates":list(action_candidates or []),
            "provenance_summary":{"evidence_items":len(state["provenance"]),"latest_entry_timestamp":latest}
        }

from __future__ import annotations
from typing import Mapping, Any

ALLOWED={"FACT","SELF_REPORT","PATTERN","HYPOTHESIS","RECOMMENDATION"}

def claim(claim_id,text,tag,evidence_refs,confidence=None):
    if tag not in ALLOWED:
        raise ValueError("invalid epistemic tag")
    if not evidence_refs:
        raise ValueError("substantive claims require evidence")
    if confidence is not None and not (0<=confidence<=1):
        raise ValueError("confidence out of range")
    return {"claim_id":claim_id,"text":text,"epistemic_tag":tag,"confidence":confidence,"evidence_refs":evidence_refs}

class AssessEngine:
    def build(self, assess_input:Mapping[str,Any], generated_at:str)->dict:
        s=assess_input["spark_state"]
        k=assess_input["kinetic_trend"]
        goals=assess_input.get("active_goals",[])
        tasks=assess_input.get("active_tasks",[])
        idx=[]; som=[]; hyp=[]; car=[]; tac=[]
        n=1
        def add(section,text,tag,refs,confidence=None):
            nonlocal n
            c=claim(f"ASM-{n:03d}",text,tag,refs,confidence)
            n+=1; section.append(c); idx.append(c); return c
        add(som,f"SPARK state status is {s['state_status']}.","FACT",[{"source_id":"SPARK_STATE_V2","locator":"state_status"}])
        add(som,f"KINETIC logging completeness is {k['logging_completeness']} across {k['days_logged']} logged days.","FACT",[{"source_id":"KINETIC_TREND_V2","locator":"logging_completeness"}])
        if s["state_status"]=="AVAILABLE" and s.get("self_reported_state"):
            e=s["self_reported_state"]["energy"]
            add(som,f"Current self-reported energy is {e['value'].lower()}.","SELF_REPORT",[{"source_id":"SPARK_STATE_V2","locator":"self_reported_state.energy"}],e.get("confidence"))
        for p in s.get("supported_patterns",[]):
            add(hyp,p["description"],"PATTERN",[{"source_id":"SPARK_STATE_V2","locator":f"supported_patterns:{p['pattern_id']}"}],p.get("confidence"))
        if goals:
            add(car,f"{len(goals)} active career/goal records are available for review.","FACT",[{"source_id":"ACTIVE_GOALS","locator":"count"}])
        if tasks:
            add(car,f"{len(tasks)} active task records are available.","FACT",[{"source_id":"ACTIVE_TASKS","locator":"count"}])
        if s["state_status"]=="AVAILABLE" and k["logging_completeness"]!="EMPTY":
            add(som,"Current reflective state and nutrition-trend evidence overlap in time; any relationship remains unestablished.","HYPOTHESIS",[{"source_id":"SPARK_STATE_V2","locator":"self_reported_state"},{"source_id":"KINETIC_TREND_V2","locator":"window"}],0.4)
        if s.get("strategy_observations"):
            st=s["strategy_observations"][0]
            rec=claim(f"ASM-{n:03d}",st["observation"],"RECOMMENDATION",[{"source_id":"SPARK_STATE_V2","locator":f"strategy_observations:{st['strategy_id']}"}],None); n+=1
        else:
            rec=claim(f"ASM-{n:03d}","Use only strategies explicitly supported by current evidence; no somatic technique is selected automatically.","RECOMMENDATION",[{"source_id":"SPARK_STATE_V2","locator":"strategy_observations"}],None); n+=1
        idx.append(rec)
        bottleneck=claim(f"ASM-{n:03d}","The most useful immediate constraint to review is the current set of active commitments, not an inferred diagnosis.","HYPOTHESIS",[{"source_id":"ACTIVE_TASKS","locator":"count"},{"source_id":"ACTIVE_GOALS","locator":"count"}],0.5); n+=1; idx.append(bottleneck)
        micro=[]
        for text,refs in [
          ("Choose one bounded active task as the next action.",[{"source_id":"ACTIVE_TASKS","locator":"active"}]),
          ("Protect one short block for the highest-priority active goal.",[{"source_id":"ACTIVE_GOALS","locator":"active"}]),
          ("Reassess after that block using current evidence rather than historical assumptions.",[{"source_id":"SPARK_STATE_V2","locator":"generated_at"}])
        ]:
            c=claim(f"ASM-{n:03d}",text,"RECOMMENDATION",refs,None); n+=1; micro.append(c); idx.append(c)
        return {
          "generated_at":generated_at,
          "audit_window":{"start_date":k["window"]["start_date"],"end_date":k["window"]["end_date"]},
          "somatic_pulse":{"claims":som,"recommended_somatic_exercise":rec},
          "hyperfocus_analysis":{"claims":hyp},
          "career_steering":{"claims":car},
          "tactile_alignment":{"claims":tac},
          "immediate_moves":{"primary_bottleneck":bottleneck,"micro_actions":micro},
          "claims_index":idx
        }

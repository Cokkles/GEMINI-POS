from __future__ import annotations
from datetime import date, timedelta, datetime
from statistics import mean
from typing import Iterable, Mapping, Any
import re

NUTRIENTS = ("calories", "protein_g", "carbs_g", "fat_g", "sodium_mg")
_DATE_PATTERNS = ("%Y-%m-%d", "%m/%d/%Y", "%m/%d/%y")

def normalize_date(value: Any) -> str:
    if isinstance(value, date):
        return value.isoformat()
    text=str(value).strip()
    for fmt in _DATE_PATTERNS:
        try:
            return datetime.strptime(text, fmt).date().isoformat()
        except ValueError:
            pass
    m=re.fullmatch(r"(\d{1,2})/(\d{1,2})/(\d{4})", text)
    if m:
        return date(int(m.group(3)),int(m.group(1)),int(m.group(2))).isoformat()
    raise ValueError(f"unsupported date format: {value!r}")

def parse_targets_from_text(text: str, configuration_source: str) -> dict:
    out={}
    cal=re.search(r"Caloric\s+Intake\s+Target\s*:\s*([\d,]+)\s*[-–]\s*([\d,]+)\s*kcal", text, re.I)
    prot=re.search(r"Protein\s+Target\s*:\s*~?\s*([\d,]+)\s*g", text, re.I)
    if cal:
        out["calorie_target"]={"min":float(cal.group(1).replace(",","")),"max":float(cal.group(2).replace(",","")),"unit":"kcal","configuration_source":configuration_source}
    if prot:
        out["protein_target"]={"target":float(prot.group(1).replace(",","")),"unit":"g","configuration_source":configuration_source}
    return out

def _num(v):
    if v is None or v == "":
        return None
    if isinstance(v, bool):
        raise ValueError("boolean is not numeric nutrition data")
    return float(v)

class KineticEngine:
    def __init__(self, source_sheet_id: str = "KINETIC_SOURCE"):
        self.source_sheet_id = source_sheet_id

    def build_state(self, day: str, rows: Iterable[Mapping[str, Any]], config: Mapping[str, Any] | None = None,
                    source_freshness: str | None = None, complete: bool = False) -> dict:
        day = normalize_date(day)
        rows = [r for r in rows if normalize_date(r.get("date")) == day]
        if not rows:
            totals = {k: None for k in NUTRIENTS}
            logging_state = "EMPTY"
        else:
            totals = {}
            for key in NUTRIENTS:
                vals = [_num(r.get(key)) for r in rows]
                vals = [v for v in vals if v is not None]
                totals[key] = sum(vals) if vals else 0.0
            logging_state = "COMPLETE" if complete else "PARTIAL"
        cfg = config or {}
        cal = cfg.get("calorie_target")
        prot = cfg.get("protein_target")
        cal_obj = {"status":"UNCONFIGURED"} if not cal else {"status":"CONFIGURED", **cal}
        prot_obj = {"status":"UNCONFIGURED"} if not prot else {"status":"CONFIGURED", **prot}
        return {
            "date": day,
            "logging_state": logging_state,
            "calories_consumed": totals["calories"],
            "calorie_target": cal_obj,
            "protein_consumed_g": totals["protein_g"],
            "protein_target": prot_obj,
            "carbs_consumed_g": totals["carbs_g"],
            "fat_consumed_g": totals["fat_g"],
            "sodium_consumed_mg": totals["sodium_mg"],
            "optional_modules":{
                "hydration":{"status":"UNTRACKED","liters":None},
                "weight":{"status":"UNTRACKED","current_lbs":None},
                "exercise":{"status":"UNTRACKED"},
                "sleep":{"status":"UNTRACKED"},
            },
            "source_freshness": source_freshness,
        }

    def project_to_horizon(self, state: Mapping[str, Any], tracker_link: str) -> dict:
        if state["logging_state"] == "EMPTY":
            adherence = "NO_ENTRIES"
        elif state["calorie_target"]["status"] != "CONFIGURED" or state["protein_target"]["status"] != "CONFIGURED":
            adherence = "UNCONFIGURED"
        else:
            c = state["calories_consumed"]
            lo, hi = state["calorie_target"]["min"], state["calorie_target"]["max"]
            adherence = "UNDER_TARGET" if c < lo else ("EXCEEDED" if c > hi else "ON_TRACK")
        ct = state["calorie_target"]
        pt = state["protein_target"]
        cal_display = "" if ct["status"] != "CONFIGURED" else f'{ct["min"]:g}–{ct["max"]:g} {ct.get("unit","kcal")}'
        prot_display = "" if pt["status"] != "CONFIGURED" else f'{pt["target"]:g}{pt.get("unit","g")}'
        return {
            "date": state["date"],
            "calories_consumed": state["calories_consumed"],
            "calorie_target_display": cal_display,
            "protein_consumed_g": state["protein_consumed_g"],
            "protein_target_display": prot_display,
            "carbs_consumed_g": state["carbs_consumed_g"],
            "fat_consumed_g": state["fat_consumed_g"],
            "sodium_consumed_mg": state["sodium_consumed_mg"],
            "adherence_status": adherence,
            "tracker_link": tracker_link,
        }

    def build_trend(self, end_date: str, rows: Iterable[Mapping[str, Any]], config: Mapping[str, Any] | None = None,
                    source_freshness: str | None = None) -> dict:
        end = date.fromisoformat(normalize_date(end_date))
        start = end - timedelta(days=6)
        by_day = {}
        total_rows = 0
        for r in rows:
            try:
                d = date.fromisoformat(normalize_date(r.get("date")))
            except Exception:
                continue
            if not (start <= d <= end):
                continue
            total_rows += 1
            by_day.setdefault(d.isoformat(), []).append(r)
        daily = [self.build_state(ds, rs, config=config) for ds, rs in by_day.items()]
        n = len(daily)
        comp = "EMPTY" if n == 0 else ("SPARSE" if n <= 2 else ("PARTIAL" if n < 7 else "COMPLETE"))
        def avg(field):
            vals=[d[field] for d in daily if d[field] is not None]
            return None if not vals else mean(vals)
        cfg=config or {}
        cav=avg("calories_consumed"); pav=avg("protein_consumed_g")
        if not daily:
            cs="NO_DATA"
        elif not cfg.get("calorie_target"):
            cs="UNCONFIGURED"
        else:
            lo,hi=cfg["calorie_target"]["min"],cfg["calorie_target"]["max"]
            cs="UNDER_TARGET" if cav < lo else ("EXCEEDED" if cav > hi else "ON_TRACK")
        if not daily:
            ps="NO_DATA"
        elif not cfg.get("protein_target"):
            ps="UNCONFIGURED"
        else:
            target=cfg["protein_target"]["target"]
            ps="DEFICIT" if pav < target else "MET"
        return {
            "window":{"start_date":start.isoformat(),"end_date":end.isoformat()},
            "days_logged":n,"logging_completeness":comp,
            "calorie_average":cav,"protein_average_g":pav,
            "carbs_average_g":avg("carbs_consumed_g"),"fat_average_g":avg("fat_consumed_g"),
            "sodium_average_mg":avg("sodium_consumed_mg"),
            "target_comparison":{"calorie_status":cs,"protein_status":ps},
            "weight_trend":{"status":"UNTRACKED","rolling_avg_lbs":None,"delta_vs_baseline_lbs":None},
            "exercise_trend":{"status":"UNTRACKED","total_sessions_completed":None},
            "source_freshness":source_freshness,
            "provenance":{"source_sheet_id":self.source_sheet_id,"total_rows_evaluated":total_rows},
        }

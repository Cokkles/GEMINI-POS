import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator
from kinetic_v2 import KineticEngine, normalize_date, parse_targets_from_text
from spark_v2 import SparkEngine, EvidenceItem
from spark_v2.source_adapters.notes import classify_note_entry
from assess_v2 import AssessEngine

ROOT=Path(__file__).resolve().parents[1]

def load(p): return json.loads((ROOT/p).read_text())

def validate(path,payload):
    schema=load(path)
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(payload)

def cfg():
    return {
      "calorie_target":{"min":2000,"max":2300,"unit":"kcal","configuration_source":"CONFIG"},
      "protein_target":{"target":200,"unit":"g","configuration_source":"CONFIG"}
    }

def test_all_schemas_meta_validate():
    for p in (ROOT/"schemas").rglob("*.schema.json"):
        Draft202012Validator.check_schema(json.loads(p.read_text()))

def test_kinetic_null_vs_zero_and_horizon():
    e=KineticEngine("SHEET")
    empty=e.build_state("2026-08-22",[],cfg())
    assert empty["calories_consumed"] is None
    validate("schemas/kinetic/kinetic-state-v2.schema.json",empty)
    zero=e.build_state("2026-08-22",[{"date":"2026-08-22","calories":0,"protein_g":0,"carbs_g":0,"fat_g":0,"sodium_mg":0}],cfg())
    assert zero["calories_consumed"]==0
    hz=e.project_to_horizon(empty,"https://example.invalid/tracker")
    assert hz["adherence_status"]=="NO_ENTRIES"
    validate("schemas/kinetic/kinetic-to-horizon-v2.schema.json",hz)

def test_kinetic_trend_bounded():
    e=KineticEngine("SHEET")
    rows=[
      {"date":"2026-08-15","calories":100,"protein_g":10,"carbs_g":1,"fat_g":1,"sodium_mg":1},
      {"date":"2026-08-17","calories":2100,"protein_g":205,"carbs_g":100,"fat_g":60,"sodium_mg":2000},
      {"date":"2026-08-22","calories":2200,"protein_g":210,"carbs_g":110,"fat_g":65,"sodium_mg":2100},
    ]
    tr=e.build_trend("2026-08-22",rows,cfg())
    assert tr["days_logged"]==2 and tr["provenance"]["total_rows_evaluated"]==2
    validate("schemas/kinetic/kinetic-trend-v2.schema.json",tr)

def test_kinetic_date_and_configuration_parsing():
    assert normalize_date("8/22/2026")=="2026-08-22"
    assert normalize_date("08/22/2026")=="2026-08-22"
    cfg2=parse_targets_from_text("Caloric Intake Target: 2,000 - 2,300 kcal/day\nProtein Target: ~200 g/day","CONFIG_DOC")
    assert cfg2["calorie_target"]["min"]==2000
    assert cfg2["protein_target"]["target"]==200

def test_spark_boundaries_stale_and_pattern():
    eng=SparkEngine()
    now="2026-08-22T12:00:00Z"
    old=EvidenceItem("e1","J","2026-08-18T12:00:00Z","entry1","REFLECTION","low","SELF_REPORTED",{"state_fragment":{"energy":"LOW","sensory_load":"MODERATE"}})
    st=eng.build_state([old],now)
    assert st["state_status"]=="STALE" and st["self_reported_state"] is None
    validate("schemas/spark/spark-state-v2.schema.json",st)
    a=EvidenceItem("a","J","2026-08-20T10:00:00Z","a","REFLECTION","x","SELF_REPORTED",{})
    b=EvidenceItem("b","J","2026-08-21T10:00:00Z","b","REFLECTION","x","SELF_REPORTED",{})
    p=eng.pattern("P1","Repeated observation",[a,b])
    assert p["confidence"]==0.50
    assert eng.pattern("P2","no",[a,a]) is None

def test_spark_available_and_projection():
    eng=SparkEngine()
    ev=EvidenceItem("e","J","2026-08-22T10:00:00Z","entry","REFLECTION","state","SELF_REPORTED",{"state_fragment":{"energy":"LOW","sensory_load":"SATURATED","affect":"tired"}})
    st=eng.build_state([ev],"2026-08-22T12:00:00Z")
    assert st["state_status"]=="AVAILABLE"
    validate("schemas/spark/spark-state-v2.schema.json",st)
    hz=eng.project_to_horizon(st)
    validate("schemas/spark/spark-to-horizon-v2.schema.json",hz)

def test_note_filter_collision():
    assert classify_note_entry("TODO: Verify Done: parsing")=="ACTIVE_CANDIDATE"
    assert classify_note_entry("Done: completed TODO: item")=="TOMBSTONE"
    assert classify_note_entry("random thought")=="ARCHIVAL_UNMARKED"

def test_reference_strategy_requires_applicability():
    assert SparkEngine.strategy("s","try it","REFERENCE_FRAMEWORK",[]) is None
    assert SparkEngine.strategy("s","try it","REFERENCE_FRAMEWORK",["e1"])["basis"]=="REFERENCE_FRAMEWORK"

def test_assess_typed_claims_and_three_actions():
    k=KineticEngine("S").build_trend("2026-08-22",[],cfg())
    s=SparkEngine().build_state([],"2026-08-22T12:00:00Z")
    inp={"spark_state":s,"kinetic_trend":k,"active_goals":[{"category":"Career","goal":"Grow","timeline":"6m"}],"active_tasks":[{"id":"t1","title":"One"}]}
    validate("schemas/assess/assess-input-v2.schema.json",inp)
    out=AssessEngine().build(inp,"2026-08-22T12:00:00Z")
    assert len(out["immediate_moves"]["micro_actions"])==3
    assert all(c["evidence_refs"] for c in out["claims_index"])
    validate("schemas/assess/assess-output-v2.schema.json",out)

def test_system_hypothesis_cannot_be_self_reported():
    eng=SparkEngine()
    ev=EvidenceItem("e","J","2026-08-22T10:00:00Z","entry","REFLECTION","x","SYSTEM_HYPOTHESIS",{"state_fragment":{"energy":"LOW","sensory_load":"SATURATED"}})
    st=eng.build_state([ev],"2026-08-22T12:00:00Z")
    assert st["self_reported_state"] is None

def test_spark_horizon_does_not_synthesize_affect():
    eng=SparkEngine()
    ev=EvidenceItem("e","J","2026-08-22T10:00:00Z","entry","REFLECTION","state","SELF_REPORTED",{"state_fragment":{"energy":"LOW","sensory_load":"SATURATED"}})
    st=eng.build_state([ev],"2026-08-22T12:00:00Z")
    assert st["state_status"]=="AVAILABLE"
    hz=eng.project_to_horizon(st)
    assert hz["state_status"]=="NOT_MATERIAL"
    assert hz["current_self_reported_state"] is None
    validate("schemas/spark/spark-to-horizon-v2.schema.json",hz)

import pytest
from horizon import HorizonV25Integrator, RetiredHorizonPathBlocked, classify_note_entry
from kinetic_v2 import KineticEngine
from spark_v2 import SparkEngine, EvidenceItem


def cfg():
    return {
        "calorie_target":{"min":2000,"max":2300,"unit":"kcal","configuration_source":"CONFIG"},
        "protein_target":{"target":200,"unit":"g","configuration_source":"CONFIG"},
    }


def kinetic_payload(rows, config=None):
    eng=KineticEngine("SHEET")
    st=eng.build_state("2026-08-22", rows, config=config)
    return eng.project_to_horizon(st,"https://example.invalid/tracker")


def spark_payload(status="AVAILABLE"):
    eng=SparkEngine()
    if status == "AVAILABLE":
        ev=EvidenceItem("e","J","2026-08-22T18:00:00Z","entry","REFLECTION","state","SELF_REPORTED",{"state_fragment":{"energy":"MODERATE","sensory_load":"CALM","affect":"steady"}})
        st=eng.build_state([ev],"2026-08-22T20:00:00Z")
        return eng.project_to_horizon(st,material=True)
    if status == "STALE":
        ev=EvidenceItem("e","J","2026-08-18T18:00:00Z","entry","REFLECTION","state","SELF_REPORTED",{"state_fragment":{"energy":"LOW","sensory_load":"SATURATED","affect":"tired"}})
        st=eng.build_state([ev],"2026-08-22T20:00:00Z")
        return eng.project_to_horizon(st)
    p=spark_payload("AVAILABLE")
    p["state_status"]=status
    p["current_self_reported_state"]=None
    return p


def test_kinetic_populated_day_contract_only():
    hz=HorizonV25Integrator()
    p=kinetic_payload([{"date":"2026-08-22","calories":2100,"protein_g":205,"carbs_g":100,"fat_g":60,"sodium_mg":1900}],cfg())
    out=hz.consume_kinetic(p)
    assert out["calories"]==2100 and out["display"]=="ON_TRACK"
    assert out["source"]=="KINETIC_TO_HORIZON_V2"


def test_kinetic_no_entries_is_not_zero():
    out=HorizonV25Integrator().consume_kinetic(kinetic_payload([],cfg()))
    assert out["display"]=="No nutrition logged yet today"
    assert out["calories"] is None and out["protein_g"] is None


def test_kinetic_verified_numeric_zero_survives():
    p=kinetic_payload([{"date":"2026-08-22","calories":0,"protein_g":0,"carbs_g":0,"fat_g":0,"sodium_mg":0}],cfg())
    out=HorizonV25Integrator().consume_kinetic(p)
    assert out["calories"]==0


def test_kinetic_unconfigured_is_explicit():
    p=kinetic_payload([{"date":"2026-08-22","calories":500,"protein_g":40,"carbs_g":50,"fat_g":20,"sodium_mg":600}],{})
    assert HorizonV25Integrator().consume_kinetic(p)["display"]=="UNCONFIGURED"


def test_spark_available_and_optional_states():
    hz=HorizonV25Integrator()
    assert hz.consume_spark(spark_payload("AVAILABLE"))["source"]=="SPARK_TO_HORIZON_V2"
    for status in ("NOT_MATERIAL","STALE","OMITTED"):
        assert hz.consume_spark(spark_payload(status)) is None


def test_previous_briefing_contamination_is_discarded():
    ctx=HorizonV25Integrator().assemble_context(
        kinetic=kinetic_payload([],cfg()), spark=None, sentinel_fin={},
        previous_briefing="STALE SECRET HISTORICAL BRIEFING",
    )
    assert ctx["clean_room"]["previous_briefing_used_as_input"] is False
    assert "STALE SECRET HISTORICAL BRIEFING" not in repr(ctx)


def test_active_note_filter_collision_cases():
    assert classify_note_entry("TODO: Verify Done: parsing") == "ACTIVE_CANDIDATE"
    assert classify_note_entry("Done: Completed TODO: item") == "TOMBSTONE"
    assert classify_note_entry("ordinary archival idea") == "ARCHIVAL_UNMARKED"
    out=HorizonV25Integrator().filter_active_notes(["TODO: Verify Done: parsing","Done: old","ordinary"])
    assert out==["TODO: Verify Done: parsing"]


def test_unavailable_source_does_not_fabricate():
    ctx=HorizonV25Integrator().assemble_context(
        kinetic=None,spark=None,sentinel_fin=None,source_failures=["weather unavailable"]
    )
    assert ctx["kinetic"]["status"]=="UNAVAILABLE"
    assert ctx["sentinel_fin"]["status"]=="UNAVAILABLE"
    assert ctx["unavailable_sources"]==["weather unavailable"]


def test_sentinel_fin_rejects_prism_internal_state():
    with pytest.raises(ValueError):
        HorizonV25Integrator().consume_sentinel_fin({"PRISM_processing_ledger":123})


def test_retired_horizon_artifacts_are_blocked():
    hz=HorizonV25Integrator()
    for name in ("horizon_data.json","refreshHorizonDataFeed()","pruneHorizonJsonFile()","Planner.md"):
        with pytest.raises(RetiredHorizonPathBlocked):
            hz.block_retired_mechanism(name)


def test_clean_room_flags_explicitly_block_raw_domain_paths():
    ctx=HorizonV25Integrator().assemble_context(kinetic=kinetic_payload([],cfg()),spark=spark_payload("NOT_MATERIAL"),sentinel_fin={})
    assert ctx["clean_room"] == {
        "previous_briefing_used_as_input": False,
        "raw_journal_access": False,
        "raw_kinetic_row_math": False,
        "prism_internal_access": False,
        "legacy_horizon_access": False,
    }

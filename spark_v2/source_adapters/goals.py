def active_goals_from_records(records: list[dict]) -> list[dict]:
    """Return explicit active goal records only; does not infer goals from journal or notes."""
    return [dict(r) for r in records if r.get("status","ACTIVE").upper()=="ACTIVE"]

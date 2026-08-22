def applicable_reference_strategy(strategy_id: str, observation: str, applicability_refs: list[str]) -> dict | None:
    """Reference availability alone is never sufficient for canonical strategy emission."""
    if not applicability_refs:
        return None
    return {
        "strategy_id": strategy_id,
        "observation": observation,
        "basis": "REFERENCE_FRAMEWORK",
        "applicability_refs": list(applicability_refs),
    }

from __future__ import annotations
from .spark_engine import SparkEngine

def project_to_horizon(state: dict, action_candidates=None, material: bool = True) -> dict:
    return SparkEngine().project_to_horizon(state, action_candidates=action_candidates, material=material)

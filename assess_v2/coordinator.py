def assemble_assess_input(spark_state: dict, kinetic_trend: dict, active_goals: list[dict], active_tasks: list[dict]) -> dict:
    return {
        "spark_state": spark_state,
        "kinetic_trend": kinetic_trend,
        "active_goals": list(active_goals),
        "active_tasks": list(active_tasks),
    }

def render_sections(output: dict) -> str:
    """Human-readable rendering only; never creates new analytical claims."""
    lines=[]
    for key in ("somatic_pulse","hyperfocus_analysis","career_steering","tactile_alignment"):
        claims=output[key].get("claims",[])
        if claims:
            lines.append(key.replace("_"," ").title())
            for c in claims:
                lines.append(f"[{c['epistemic_tag']}] {c['text']}")
    lines.append("Immediate Moves")
    for c in output["immediate_moves"]["micro_actions"]:
        lines.append(f"[ACTION/{c['epistemic_tag']}] {c['text']}")
    return "\n".join(lines)

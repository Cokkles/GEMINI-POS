package com.cokkles.gpos.ui.parity

internal fun humanizeToken(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    return normalized
        .lowercase()
        .split('_', '-', ' ')
        .filter(String::isNotBlank)
        .joinToString(" ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

internal fun countLabel(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"

package com.cokkles.gpos.platform.notifications

enum class GposDeepLinkTarget(val route: String) {
    BRIEFING("briefing"),
    CALENDAR("calendar"),
    TASKS("tasks"),
    FOLLOW_UPS("followups"),
    FINANCES("finances"),
    AEGIS("aegis"),
    SYSTEM("system");

    companion object {
        fun fromRoute(route: String): GposDeepLinkTarget? = entries.firstOrNull { it.route == route }
    }
}

data class NotificationIntentSpec(
    val stableEventId: String,
    val title: String,
    val body: String,
    val target: GposDeepLinkTarget,
    val entityId: String? = null,
)

object GposNotificationChannels {
    const val GENERAL = "gpos-general"
    const val TASKS = "gpos-tasks"
    const val SYSTEM = "gpos-system"
}

package com.cokkles.gpos.data

object CanonicalCachePolicy {
    const val DATABASE_NAME = "gpos-cache.db"

    const val DASHBOARD_KEY = "dashboard_v1"
    const val DASHBOARD_CONTRACT = "AUTH-1/get_dashboard/pwa-bounded-v1"
    const val DASHBOARD_FRESHNESS_MS = 30L * 60L * 1000L

    const val HORIZON_KEY = "latest_horizon"
    const val HORIZON_CONTRACT = "AUTH-1/get_latest_horizon/plain_text"
    const val HORIZON_FRESHNESS_MS = 24L * 60L * 60L * 1000L

    const val FINANCE_KEY = "recent_finance_72h_v1"
    const val FINANCE_CONTRACT = "AUTH-1/get_recent_finance/pwa-bounded-v1"
    const val FINANCE_FRESHNESS_MS = 30L * 60L * 1000L

    const val NOTIFICATIONS_KEY = "notifications_v1"
    const val NOTIFICATIONS_CONTRACT = "AUTH-1/get_notifications/pwa-bounded-v1"
    const val NOTIFICATIONS_FRESHNESS_MS = 5L * 60L * 1000L

    const val CALENDAR_RANGE_KEY = "calendar_range_v1"
    const val CALENDAR_RANGE_CONTRACT = "AUTH-1/get_calendar_range/v1"
    const val CALENDAR_RANGE_FRESHNESS_MS = 30L * 60L * 1000L

    const val INTELLIGENCE_KEY = "intelligence_v24"
    const val INTELLIGENCE_CONTRACT = "AUTH-1/get_intelligence/v24"
    const val INTELLIGENCE_FRESHNESS_MS = 30L * 60L * 1000L

    const val NUTRITION_KEY = "nutrition_history_v1"
    const val NUTRITION_CONTRACT = "AEGIS_NUTRITION_HISTORY_V1"
    const val NUTRITION_FRESHNESS_MS = 30L * 60L * 1000L

    const val TASK_HISTORY_KEY = "task_history_v1"
    const val TASK_HISTORY_CONTRACT = "AEGIS_TASK_HISTORY_V1"
    const val TASK_HISTORY_FRESHNESS_MS = 15L * 60L * 1000L
}

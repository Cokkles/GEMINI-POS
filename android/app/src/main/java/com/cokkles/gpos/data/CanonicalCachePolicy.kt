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
}

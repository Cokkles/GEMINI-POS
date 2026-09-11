package com.cokkles.gpos.platform.sync

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cokkles.gpos.data.CanonicalCachePolicy
import com.cokkles.gpos.data.local.CanonicalSnapshotEntity
import com.cokkles.gpos.data.local.GposDatabase
import com.cokkles.gpos.data.remote.AegisBackendClient
import com.cokkles.gpos.data.remote.AegisBackendException
import com.cokkles.gpos.data.remote.DashboardPayloadMapper
import com.cokkles.gpos.data.remote.FinancePayloadMapper
import com.cokkles.gpos.data.remote.NotificationSeverity
import com.cokkles.gpos.data.remote.NotificationsPayloadMapper
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.BackgroundAuthenticationPolicy
import org.json.JSONObject

/**
 * Lightweight periodic read refresh only.
 *
 * This worker never launches interactive authentication, never generates HORIZON, never invokes
 * Gemini/PRISM/RSS refresh, and never performs a canonical mutation. It exits quietly when no
 * still-valid protected credential is available.
 */
class CanonicalSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val credentialStore = AndroidKeystoreCredentialStore(applicationContext)
        val credential = credentialStore.read() ?: return Result.success()
        val now = System.currentTimeMillis()
        if (BackgroundAuthenticationPolicy.requiresForegroundRenewal(credential.expiresAtEpochMs, now)) {
            AuthenticationRecovery(applicationContext).requireForegroundRenewal()
            return Result.success()
        }

        val backend = AegisBackendClient()
        val dao = openDao()
        return try {
            val dashboardJson = backend.readDashboard(credential.authToken)
            DashboardPayloadMapper.map(dashboardJson)
            cache(
                dao = dao,
                key = CanonicalCachePolicy.DASHBOARD_KEY,
                contract = CanonicalCachePolicy.DASHBOARD_CONTRACT,
                freshnessMs = CanonicalCachePolicy.DASHBOARD_FRESHNESS_MS,
                json = dashboardJson,
                fetchedAt = now,
            )

            val financeJson = backend.readRecentFinance(credential.authToken, FINANCE_HOURS)
            FinancePayloadMapper.map(financeJson, FINANCE_HOURS)
            cache(
                dao = dao,
                key = CanonicalCachePolicy.FINANCE_KEY,
                contract = CanonicalCachePolicy.FINANCE_CONTRACT,
                freshnessMs = CanonicalCachePolicy.FINANCE_FRESHNESS_MS,
                json = financeJson,
                fetchedAt = now,
            )

            val previousNotificationIds = dao.read(CanonicalCachePolicy.NOTIFICATIONS_KEY)
                ?.payloadJson
                ?.let { runCatching { NotificationsPayloadMapper.map(JSONObject(it)) }.getOrNull() }
                ?.snapshotActiveCriticalIds()
                .orEmpty()
            val notificationsJson = backend.readNotifications(credential.authToken)
            val notifications = NotificationsPayloadMapper.map(notificationsJson)
            cache(
                dao = dao,
                key = CanonicalCachePolicy.NOTIFICATIONS_KEY,
                contract = CanonicalCachePolicy.NOTIFICATIONS_CONTRACT,
                freshnessMs = CanonicalCachePolicy.NOTIFICATIONS_FRESHNESS_MS,
                json = notificationsJson,
                fetchedAt = now,
            )
            notifications.active
                .firstOrNull {
                    it.severity == NotificationSeverity.CRITICAL && it.id !in previousNotificationIds
                }
                ?.let { GposNotificationPublisher(applicationContext).publishGenericCriticalAlert(it.id) }

            val horizonCached = dao.read(CanonicalCachePolicy.HORIZON_KEY)
            if (horizonCached == null || horizonCached.staleAfterEpochMs <= now) {
                val horizonJson = backend.readLatestHorizon(credential.authToken)
                if (horizonJson.optString("plain_text").isBlank()) {
                    throw IllegalStateException("Canonical HORIZON response contained no plain_text briefing.")
                }
                cache(
                    dao = dao,
                    key = CanonicalCachePolicy.HORIZON_KEY,
                    contract = CanonicalCachePolicy.HORIZON_CONTRACT,
                    freshnessMs = CanonicalCachePolicy.HORIZON_FRESHNESS_MS,
                    json = horizonJson,
                    fetchedAt = now,
                )
            }

            Result.success()
        } catch (error: AegisBackendException) {
            if (BackgroundAuthenticationPolicy.isAuthenticationFailure(error)) {
                AuthenticationRecovery(applicationContext).requireForegroundRenewal()
                Result.success()
            } else if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    private fun openDao() = Room.databaseBuilder(
        applicationContext,
        GposDatabase::class.java,
        CanonicalCachePolicy.DATABASE_NAME,
    ).build().canonicalSnapshotDao()

    private suspend fun cache(
        dao: com.cokkles.gpos.data.local.CanonicalSnapshotDao,
        key: String,
        contract: String,
        freshnessMs: Long,
        json: JSONObject,
        fetchedAt: Long,
    ) {
        dao.replace(
            CanonicalSnapshotEntity(
                cacheKey = key,
                contractVersion = contract,
                fetchedAtEpochMs = fetchedAt,
                staleAfterEpochMs = fetchedAt + freshnessMs,
                payloadVersion = json.optString("version").takeIf { it.isNotBlank() },
                payloadJson = json.toString(),
            ),
        )
    }

    private fun com.cokkles.gpos.data.remote.NotificationsSnapshot.snapshotActiveCriticalIds(): Set<String> =
        active.filter { it.severity == NotificationSeverity.CRITICAL }.mapTo(mutableSetOf()) { it.id }

    companion object {
        const val UNIQUE_WORK_NAME = "gpos-canonical-read-sync"
        private const val FINANCE_HOURS = 72
        private const val MAX_RETRIES = 2
    }
}


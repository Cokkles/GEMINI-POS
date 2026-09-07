package com.cokkles.gpos.platform.sync

import android.content.Context
import com.cokkles.gpos.data.local.LocalReceiptState
import com.cokkles.gpos.data.local.ProtectedLocalLedger
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationChannels
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher

/**
 * Bridges expiring Google ID tokens and durable local work.
 *
 * Credential Manager renewal requires a resumed Activity, so workers never attempt interactive
 * authentication. They preserve account-bound work and post one stable attention notification.
 * A successful foreground renewal calls [resumePending] and drains the preserved work.
 */
class AuthenticationRecovery(context: Context) {
    private val appContext = context.applicationContext
    private val ledger = ProtectedLocalLedger(appContext)
    private val notifications = GposNotificationPublisher(appContext)

    fun requireForegroundRenewal() {
        notifications.publishOutcome(
            stableEventId = NOTIFICATION_ID,
            title = "AEGIS needs Google authorization",
            body = "Open AEGIS to renew the protected session. Queued changes remain safely stored.",
            target = GposDeepLinkTarget.SYSTEM,
            isError = false,
            channelId = GposNotificationChannels.SYSTEM,
        )
    }

    fun resumePending() {
        clearAttention()
        val current = ledger.read()
        if (current.deferredMutations.isNotEmpty()) {
            DeferredMutationQueue(appContext).scheduleNow()
        }
        current.pendingTasks.forEach { pending ->
            TaskQueueSyncScheduler(appContext).schedulePending(pending.id, 0L)
        }
        val now = System.currentTimeMillis()
        current.receipts
            .filter { receipt ->
                receipt.payload != null &&
                    receipt.state == LocalReceiptState.QUEUED
            }
            .forEach { receipt ->
                val delay = (receipt.nextRetryAtEpochMs ?: now) - now
                CaptureRetryScheduler(appContext).schedule(receipt.id, delay.coerceAtLeast(0L))
            }
    }

    fun clearAttention() = notifications.cancel(NOTIFICATION_ID)

    companion object {
        const val NOTIFICATION_ID = "aegis-auth-renewal-required"
    }
}

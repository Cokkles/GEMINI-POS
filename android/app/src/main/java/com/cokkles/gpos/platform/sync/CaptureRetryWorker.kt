package com.cokkles.gpos.platform.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cokkles.gpos.data.command.AegisCommandClient
import com.cokkles.gpos.data.command.CaptureKind
import com.cokkles.gpos.data.command.CaptureReliabilityPolicy
import com.cokkles.gpos.data.interaction.AegisInteractionClient
import com.cokkles.gpos.data.local.LocalAlert
import com.cokkles.gpos.data.local.LocalReceiptState
import com.cokkles.gpos.data.local.ProtectedLocalLedger
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.BackgroundAuthenticationPolicy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CaptureProcessOutcome {
    data object Confirmed : CaptureProcessOutcome
    data object Queued : CaptureProcessOutcome
    data object Failed : CaptureProcessOutcome
    data object Ignored : CaptureProcessOutcome
}

class CaptureRetryScheduler(context: Context) {
    private val work = WorkManager.getInstance(context.applicationContext)

    fun schedule(receiptId: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<CaptureRetryWorker>()
            .setInputData(workDataOf(KEY_RECEIPT_ID to receiptId))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(uniqueName(receiptId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(receiptId: String) = work.cancelUniqueWork(uniqueName(receiptId))
    fun cancelAll() = work.cancelAllWorkByTag(TAG)

    companion object {
        const val KEY_RECEIPT_ID = "receipt_id"
        private const val TAG = "aegis-capture-retry"
        private fun uniqueName(id: String) = "aegis-capture-retry-$id"
    }
}

class CaptureRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(CaptureRetryScheduler.KEY_RECEIPT_ID) ?: return Result.success()
        CaptureSubmissionProcessor(applicationContext).process(id)
        return Result.success()
    }
}

class CaptureSubmissionProcessor(
    context: Context,
    private val commandClient: AegisCommandClient = AegisCommandClient(),
    private val interactionClient: AegisInteractionClient = AegisInteractionClient(),
) {
    private val appContext = context.applicationContext
    private val ledgerStore = ProtectedLocalLedger(appContext)
    private val credentialStore = AndroidKeystoreCredentialStore(appContext)
    private val scheduler = CaptureRetryScheduler(appContext)
    private val notifications = GposNotificationPublisher(appContext)

    suspend fun process(receiptId: String, manual: Boolean = false): CaptureProcessOutcome = mutex.withLock {
        val receipt = ledgerStore.read().receipts.firstOrNull { it.id == receiptId } ?: return@withLock CaptureProcessOutcome.Ignored
        if (receipt.state == LocalReceiptState.CONFIRMED || receipt.payload.isNullOrBlank()) return@withLock CaptureProcessOutcome.Ignored
        if (manual && !receipt.manualRetryAllowed) return@withLock CaptureProcessOutcome.Ignored
        val kind = CaptureKind.entries.firstOrNull { it.wireName == receipt.kind } ?: return@withLock CaptureProcessOutcome.Ignored
        val credential = credentialStore.read() ?: return@withLock CaptureProcessOutcome.Ignored
        if (BackgroundAuthenticationPolicy.requiresForegroundRenewal(credential.expiresAtEpochMs, System.currentTimeMillis())) {
            return@withLock deferForAuthentication(receiptId)
        }

        val attempt = receipt.attempts + 1
        ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
            if (it.id == receiptId) it.copy(state = LocalReceiptState.SENDING, attempts = attempt,
                nextRetryAtEpochMs = null, manualRetryAllowed = false, updatedAtEpochMs = System.currentTimeMillis(), error = null) else it
        }) }

        val result = runCatching { commandClient.submitCapture(credential.idToken, kind, receipt.payload, receipt.id) }
        if (credentialStore.read() == null || ledgerStore.read().receipts.none { it.id == receiptId }) return@withLock CaptureProcessOutcome.Ignored
        result.getOrNull()?.let { confirmed ->
            if (CaptureReliabilityPolicy.isUnconfirmedResult(confirmed.message)) {
                return@withLock failIfStillOwned(
                    receiptId,
                    kind,
                    "AEGIS returned an unconfirmed ${kind.displayName.lowercase()} result: ${confirmed.message.take(240)}",
                    attempt,
                    diagnosticCode = "CAPTURE_UNCONFIRMED_RESULT",
                    requestDurationMs = confirmed.durationMs,
                )
            }
            val now = System.currentTimeMillis()
            ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
                if (it.id == receiptId) it.copy(state = LocalReceiptState.CONFIRMED, updatedAtEpochMs = now,
                    result = confirmed.message.take(500), error = null, nextRetryAtEpochMs = null,
                    manualRetryAllowed = false, diagnosticCode = "CONFIRMED",
                    requestDurationMs = confirmed.durationMs) else it
            }) }
            scheduler.cancel(receiptId)
            notifications.publishOutcome(receiptId, "${kind.displayName} submitted", confirmed.message, GposDeepLinkTarget.ALERTS, false)
            return@withLock CaptureProcessOutcome.Confirmed
        }

        val error = result.exceptionOrNull() ?: return@withLock CaptureProcessOutcome.Failed
        if (BackgroundAuthenticationPolicy.isAuthenticationFailure(error)) {
            return@withLock deferForAuthentication(receiptId)
        }
        val certified = CaptureReliabilityPolicy.isGeminiDependent(kind) &&
            CaptureReliabilityPolicy.isCertifiedSafeCapacityFailure(error) &&
            runCatching { interactionClient.readCapabilities(credential.idToken).captureReliabilityV1 }.getOrDefault(false)
        val delay = if (certified) CaptureReliabilityPolicy.retryDelayMs(attempt) else null
        if (delay != null) {
            val next = System.currentTimeMillis() + delay
            ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
                if (it.id == receiptId) it.copy(state = LocalReceiptState.QUEUED, updatedAtEpochMs = System.currentTimeMillis(),
                    error = error.message?.take(500), attempts = attempt, nextRetryAtEpochMs = next, manualRetryAllowed = false) else it
            }) }
            scheduler.schedule(receiptId, delay)
            return@withLock CaptureProcessOutcome.Queued
        }
        failIfStillOwned(
            receiptId,
            kind,
            error.message ?: "Capture submission failed.",
            attempt,
            certified,
            diagnosticCode = CaptureReliabilityPolicy.diagnosticCode(error),
        )
    }

    private suspend fun failIfStillOwned(receiptId: String, kind: CaptureKind, message: String, attempts: Int,
        manualRetryAllowed: Boolean = false,
        diagnosticCode: String? = null,
        requestDurationMs: Long? = null,
    ): CaptureProcessOutcome {
        if (credentialStore.read() == null || ledgerStore.read().receipts.none { it.id == receiptId }) return CaptureProcessOutcome.Ignored
        val now = System.currentTimeMillis()
        val alert = LocalAlert(UUID.randomUUID().toString(), "error", "${kind.displayName} failed", message.take(500), now)
        ledgerStore.update { current -> current.copy(
            receipts = current.receipts.map { if (it.id == receiptId) it.copy(state = LocalReceiptState.FAILED,
                updatedAtEpochMs = now, error = message.take(500), attempts = attempts,
                nextRetryAtEpochMs = null, manualRetryAllowed = manualRetryAllowed,
                diagnosticCode = diagnosticCode, requestDurationMs = requestDurationMs) else it },
            alerts = current.alerts + alert,
        ) }
        scheduler.cancel(receiptId)
        notifications.publishOutcome(receiptId, "Submission failed", message, GposDeepLinkTarget.ALERTS, true)
        return CaptureProcessOutcome.Failed
    }

    private fun deferForAuthentication(receiptId: String): CaptureProcessOutcome {
        val now = System.currentTimeMillis()
        ledgerStore.update { current -> current.copy(receipts = current.receipts.map { receipt ->
            if (receipt.id == receiptId) receipt.copy(
                state = LocalReceiptState.QUEUED,
                updatedAtEpochMs = now,
                result = "Waiting for Google authorization; capture remains queued.",
                error = null,
                nextRetryAtEpochMs = null,
                manualRetryAllowed = false,
            ) else receipt
        }) }
        AuthenticationRecovery(appContext).requireForegroundRenewal()
        return CaptureProcessOutcome.Queued
    }

    companion object {
        private val mutex = Mutex()
    }
}

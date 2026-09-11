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
import com.cokkles.gpos.data.local.LocalReceipt
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

    fun schedule(receiptId: String, delayMs: Long, manual: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<CaptureRetryWorker>()
            .setInputData(workDataOf(KEY_RECEIPT_ID to receiptId, KEY_MANUAL to manual))
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
        const val KEY_MANUAL = "manual"
        private const val TAG = "aegis-capture-retry"
        private fun uniqueName(id: String) = "aegis-capture-retry-$id"
    }
}

class CaptureRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(CaptureRetryScheduler.KEY_RECEIPT_ID) ?: return Result.success()
        CaptureSubmissionProcessor(applicationContext).process(
            receiptId = id,
            manual = inputData.getBoolean(CaptureRetryScheduler.KEY_MANUAL, false),
        )
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
        if (receipt.state in setOf(LocalReceiptState.CONFIRMED, LocalReceiptState.CANCELLED) || receipt.payload.isNullOrBlank()) {
            return@withLock CaptureProcessOutcome.Ignored
        }
        if (manual && !receipt.manualRetryAllowed && !receipt.serverManaged) return@withLock CaptureProcessOutcome.Ignored
        val kind = CaptureKind.entries.firstOrNull { it.wireName == receipt.kind } ?: return@withLock CaptureProcessOutcome.Ignored
        val credential = credentialStore.read() ?: return@withLock CaptureProcessOutcome.Ignored
        if (BackgroundAuthenticationPolicy.requiresForegroundRenewal(credential.expiresAtEpochMs, System.currentTimeMillis())) {
            return@withLock deferForAuthentication(receiptId)
        }

        if (receipt.serverManaged && kind == CaptureKind.CALORIES) {
            return@withLock checkServerManagedCapture(receipt, kind, credential.authToken, manual)
        }

        val attempt = (receipt.attempts + 1).coerceAtMost(ASYNC_ENQUEUE_MAX_ATTEMPTS)
        ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
            if (it.id == receiptId) it.copy(state = LocalReceiptState.SENDING, attempts = attempt,
                nextRetryAtEpochMs = null, manualRetryAllowed = false,
                updatedAtEpochMs = System.currentTimeMillis(), error = null,
                result = "Submitting to AEGIS…") else it
        }) }

        val asyncNutrition = if (kind == CaptureKind.CALORIES) {
            runCatching { interactionClient.readCapabilities(credential.authToken) }
        } else {
            null
        }
        asyncNutrition?.exceptionOrNull()?.let { error ->
            if (BackgroundAuthenticationPolicy.isAuthenticationFailure(error)) {
                return@withLock deferForAuthentication(receiptId)
            }
            return@withLock queueSafeSubmissionRetry(receipt, kind, attempt, error)
        }
        val useAsyncNutrition = asyncNutrition?.getOrNull()?.let {
            it.nutritionCaptureAsyncV1 && it.nutritionCaptureStatusV1
        } == true
        val result = runCatching {
            if (useAsyncNutrition) {
                commandClient.enqueueNutritionCapture(credential.authToken, receipt.payload, receipt.id)
            } else {
                commandClient.submitCapture(credential.authToken, kind, receipt.payload, receipt.id)
            }
        }
        if (credentialStore.read() == null || ledgerStore.read().receipts.none { it.id == receiptId }) return@withLock CaptureProcessOutcome.Ignored
        result.getOrNull()?.let { confirmed ->
            if (useAsyncNutrition || confirmed.serverManaged) {
                return@withLock applyServerManagedResult(receipt, kind, confirmed, statusCheck = false)
            }
            if (confirmed.captureStatus.equals("QUEUED", ignoreCase = true)) {
                val delay = CaptureReliabilityPolicy.retryDelayMs(attempt)
                    ?: return@withLock failIfStillOwned(
                        receiptId,
                        kind,
                        "AEGIS could not complete the capture after ${CaptureReliabilityPolicy.MAX_ATTEMPTS} attempts.",
                        attempt,
                        manualRetryAllowed = true,
                        diagnosticCode = "CAPTURE_RETRY_EXHAUSTED",
                        requestDurationMs = confirmed.durationMs,
                    )
                val next = System.currentTimeMillis() + delay
                ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
                    if (it.id == receiptId) it.copy(
                        state = LocalReceiptState.QUEUED,
                        updatedAtEpochMs = System.currentTimeMillis(),
                        result = "AEGIS is temporarily busy. Retry ${attempt + 1}/${CaptureReliabilityPolicy.MAX_ATTEMPTS} is queued.",
                        error = null,
                        attempts = attempt,
                        nextRetryAtEpochMs = next,
                        manualRetryAllowed = false,
                    ) else it
                }) }
                scheduler.schedule(receiptId, delay)
                return@withLock CaptureProcessOutcome.Queued
            }
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
            val status = confirmed.captureStatus?.uppercase()
            if (status != null && status != "CONFIRMED") {
                return@withLock failIfStillOwned(
                    receiptId,
                    kind,
                    "AEGIS returned an unsupported capture state ($status). The item was not marked confirmed.",
                    attempt,
                    manualRetryAllowed = true,
                    diagnosticCode = confirmed.diagnosticCode ?: "CAPTURE_STATUS_UNSUPPORTED",
                    requestDurationMs = confirmed.durationMs,
                )
            }
            return@withLock confirmIfStillOwned(receiptId, kind, confirmed)
        }

        val error = result.exceptionOrNull() ?: return@withLock CaptureProcessOutcome.Failed
        if (BackgroundAuthenticationPolicy.isAuthenticationFailure(error)) {
            return@withLock deferForAuthentication(receiptId)
        }
        if (useAsyncNutrition) {
            return@withLock queueSafeSubmissionRetry(receipt, kind, attempt, error)
        }
        val certified = CaptureReliabilityPolicy.isGeminiDependent(kind) &&
            CaptureReliabilityPolicy.isCertifiedSafeCapacityFailure(error) &&
            runCatching { interactionClient.readCapabilities(credential.authToken).captureReliabilityV1 }.getOrDefault(false)
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

    private suspend fun checkServerManagedCapture(
        receipt: LocalReceipt,
        kind: CaptureKind,
        idToken: String,
        manual: Boolean,
    ): CaptureProcessOutcome {
        val checking = receipt.copy(
            state = LocalReceiptState.WAITING,
            updatedAtEpochMs = System.currentTimeMillis(),
            result = if (manual && receipt.manualRetryAllowed) {
                "Requesting another server-side processing attempt…"
            } else {
                "Checking the server-side capture…"
            },
            error = null,
            nextRetryAtEpochMs = null,
            manualRetryAllowed = false,
        )
        ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
            if (it.id == receipt.id) checking else it
        }) }

        val result = runCatching {
            if (manual && receipt.manualRetryAllowed) {
                commandClient.retryNutritionCapture(idToken, receipt.id)
            } else {
                commandClient.readNutritionCaptureStatus(idToken, receipt.id)
            }
        }
        if (credentialStore.read() == null || ledgerStore.read().receipts.none { it.id == receipt.id }) {
            return CaptureProcessOutcome.Ignored
        }
        result.getOrNull()?.let {
            return applyServerManagedResult(receipt, kind, it, statusCheck = true)
        }
        val error = result.exceptionOrNull() ?: return CaptureProcessOutcome.Failed
        if (BackgroundAuthenticationPolicy.isAuthenticationFailure(error)) {
            return deferForAuthentication(receipt.id)
        }

        // Once the server has accepted a stable capture ID, a transport failure must never cause
        // Android to submit the meal again. Keep polling that same server record instead.
        val delay = SERVER_STATUS_ERROR_RETRY_MS
        val next = System.currentTimeMillis() + delay
        ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
            if (it.id == receipt.id) it.copy(
                state = LocalReceiptState.WAITING,
                updatedAtEpochMs = System.currentTimeMillis(),
                result = "Capture is stored by AEGIS. Status check will retry when the connection recovers.",
                error = error.message?.take(500),
                nextRetryAtEpochMs = next,
                manualRetryAllowed = false,
                statusChecks = receipt.statusChecks + 1,
                diagnosticCode = CaptureReliabilityPolicy.diagnosticCode(error),
            ) else it
        }) }
        scheduler.schedule(receipt.id, delay)
        return CaptureProcessOutcome.Queued
    }

    private suspend fun applyServerManagedResult(
        receipt: LocalReceipt,
        kind: CaptureKind,
        response: com.cokkles.gpos.data.command.CaptureSubmissionResult,
        statusCheck: Boolean,
    ): CaptureProcessOutcome {
        val status = response.captureStatus?.uppercase().orEmpty()
        return when (status) {
            "CONFIRMED" -> confirmIfStillOwned(receipt.id, kind, response)
            "ACCEPTED", "AI_PENDING", "PROCESSING", "RETRY_SCHEDULED", "QUEUED" -> {
                val delay = (response.retryAfterMs ?: DEFAULT_SERVER_STATUS_POLL_MS)
                    .coerceIn(MIN_SERVER_STATUS_POLL_MS, MAX_SERVER_STATUS_POLL_MS)
                val next = System.currentTimeMillis() + delay
                val now = System.currentTimeMillis()
                ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
                    if (it.id == receipt.id) it.copy(
                        state = if (status == "ACCEPTED") LocalReceiptState.ACCEPTED else LocalReceiptState.WAITING,
                        updatedAtEpochMs = now,
                        result = response.message.take(1000),
                        error = null,
                        nextRetryAtEpochMs = next,
                        manualRetryAllowed = false,
                        diagnosticCode = response.diagnosticCode,
                        requestDurationMs = response.durationMs,
                        serverManaged = true,
                        serverAcceptedAtEpochMs = it.serverAcceptedAtEpochMs ?: now,
                        statusChecks = it.statusChecks + if (statusCheck) 1 else 0,
                        lastServerStatus = status,
                    ) else it
                }) }
                scheduler.schedule(receipt.id, delay)
                CaptureProcessOutcome.Queued
            }
            "NEEDS_REVIEW", "FAILED", "FAILED_PERMANENT" -> {
                val now = System.currentTimeMillis()
                val message = response.message.ifBlank { "The server retained this capture but could not confirm nutrition." }
                ledgerStore.update { current -> current.copy(
                    receipts = current.receipts.map {
                        if (it.id == receipt.id) it.copy(
                            state = LocalReceiptState.NEEDS_REVIEW,
                            updatedAtEpochMs = now,
                            result = message.take(1000),
                            error = message.take(500),
                            nextRetryAtEpochMs = null,
                            manualRetryAllowed = true,
                            diagnosticCode = response.diagnosticCode ?: status,
                            requestDurationMs = response.durationMs,
                            serverManaged = true,
                            serverAcceptedAtEpochMs = it.serverAcceptedAtEpochMs ?: now,
                            statusChecks = it.statusChecks + if (statusCheck) 1 else 0,
                            lastServerStatus = status,
                        ) else it
                    },
                    alerts = current.alerts + LocalAlert(
                        UUID.randomUUID().toString(),
                        "warning",
                        "${kind.displayName} needs review",
                        message.take(500),
                        now,
                    ),
                ) }
                scheduler.cancel(receipt.id)
                notifications.publishOutcome(receipt.id, "Capture needs review", message, GposDeepLinkTarget.ALERTS, true)
                CaptureProcessOutcome.Failed
            }
            else -> {
                failIfStillOwned(
                    receipt.id,
                    kind,
                    "AEGIS returned an unrecognized server capture state. The item was not marked confirmed.",
                    receipt.attempts,
                    manualRetryAllowed = true,
                    diagnosticCode = response.diagnosticCode ?: "CAPTURE_STATUS_MISSING",
                    requestDurationMs = response.durationMs,
                )
            }
        }
    }

    private suspend fun queueSafeSubmissionRetry(
        receipt: LocalReceipt,
        kind: CaptureKind,
        attempt: Int,
        error: Throwable,
    ): CaptureProcessOutcome {
        val delay = asyncEnqueueRetryDelayMs(attempt)
            ?: return failIfStillOwned(
                receipt.id,
                kind,
                "AEGIS did not acknowledge the stable capture ID after $ASYNC_ENQUEUE_MAX_ATTEMPTS attempts. The meal remains on this device for manual retry.",
                attempt,
                manualRetryAllowed = true,
                diagnosticCode = CaptureReliabilityPolicy.diagnosticCode(error),
            )
        val next = System.currentTimeMillis() + delay
        ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
            if (it.id == receipt.id) it.copy(
                state = LocalReceiptState.QUEUED,
                updatedAtEpochMs = System.currentTimeMillis(),
                result = "Waiting to deliver the stable capture ID to AEGIS. Retrying is duplicate-safe.",
                error = error.message?.take(500),
                attempts = attempt,
                nextRetryAtEpochMs = next,
                manualRetryAllowed = false,
                diagnosticCode = CaptureReliabilityPolicy.diagnosticCode(error),
            ) else it
        }) }
        scheduler.schedule(receipt.id, delay)
        return CaptureProcessOutcome.Queued
    }

    private suspend fun confirmIfStillOwned(
        receiptId: String,
        kind: CaptureKind,
        confirmed: com.cokkles.gpos.data.command.CaptureSubmissionResult,
    ): CaptureProcessOutcome {
        if (CaptureReliabilityPolicy.isUnconfirmedResult(confirmed.message)) {
            val receipt = ledgerStore.read().receipts.firstOrNull { it.id == receiptId }
                ?: return CaptureProcessOutcome.Ignored
            return failIfStillOwned(
                receiptId,
                kind,
                "AEGIS returned an unconfirmed ${kind.displayName.lowercase()} result: ${confirmed.message.take(240)}",
                receipt.attempts,
                diagnosticCode = "CAPTURE_UNCONFIRMED_RESULT",
                requestDurationMs = confirmed.durationMs,
            )
        }
        val now = System.currentTimeMillis()
        ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
            if (it.id == receiptId) it.copy(
                state = LocalReceiptState.CONFIRMED,
                updatedAtEpochMs = now,
                result = buildString {
                    append(confirmed.message)
                    confirmed.confidence?.let { append(" • Confidence: ").append(it.replace('_', ' ')) }
                    confirmed.lookupDepth?.let { append(" • Lookup depth: ").append(it) }
                    if (confirmed.deduplicated) append(" • Duplicate safely ignored")
                }.take(1000),
                error = null,
                nextRetryAtEpochMs = null,
                manualRetryAllowed = false,
                diagnosticCode = "CONFIRMED",
                requestDurationMs = confirmed.durationMs,
                serverManaged = it.serverManaged || confirmed.serverManaged,
                lastServerStatus = "CONFIRMED",
            ) else it
        }) }
        scheduler.cancel(receiptId)
        notifications.publishOutcome(receiptId, "${kind.displayName} submitted", confirmed.message, GposDeepLinkTarget.ALERTS, false)
        return CaptureProcessOutcome.Confirmed
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
                state = if (receipt.serverManaged) LocalReceiptState.WAITING else LocalReceiptState.QUEUED,
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
        private const val ASYNC_ENQUEUE_MAX_ATTEMPTS = 6
        private const val MIN_SERVER_STATUS_POLL_MS = 30_000L
        private const val DEFAULT_SERVER_STATUS_POLL_MS = 60_000L
        private const val MAX_SERVER_STATUS_POLL_MS = 15 * 60_000L
        private const val SERVER_STATUS_ERROR_RETRY_MS = 2 * 60_000L

        private fun asyncEnqueueRetryDelayMs(attemptsCompleted: Int): Long? = when (attemptsCompleted) {
            1 -> 60_000L
            2 -> 2 * 60_000L
            3 -> 5 * 60_000L
            4 -> 15 * 60_000L
            5 -> 30 * 60_000L
            else -> null
        }
    }
}

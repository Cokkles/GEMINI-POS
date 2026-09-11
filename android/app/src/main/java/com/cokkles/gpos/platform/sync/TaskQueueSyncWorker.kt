package com.cokkles.gpos.platform.sync

import android.content.Context
import com.cokkles.gpos.data.interaction.AegisInteractionClient
import com.cokkles.gpos.data.workspace.workspaceOwner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cokkles.gpos.data.command.AegisCommandClient
import com.cokkles.gpos.data.local.LocalAlert
import com.cokkles.gpos.data.local.LocalReceiptState
import com.cokkles.gpos.data.local.PendingTaskMutation
import com.cokkles.gpos.data.local.ProtectedLocalLedger
import com.cokkles.gpos.data.remote.AegisBackendClient
import com.cokkles.gpos.data.remote.DashboardPayloadMapper
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationChannels
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.BackgroundAuthenticationPolicy
import java.util.UUID
import java.util.concurrent.TimeUnit

class TaskQueueSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val result = TaskQueueProcessor(applicationContext).flushDue(force = false)
        return if (result.retrySuggested && runAttemptCount < MAX_WORK_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "gpos-task-queue-periodic"
        const val DUE_WORK_PREFIX = "gpos-task-queue-due-"
        private const val MAX_WORK_RETRIES = 2
    }
}

data class TaskQueueProcessResult(
    val completed: Int = 0,
    val failed: Int = 0,
    val retrySuggested: Boolean = false,
)

class TaskQueueProcessor(
    private val context: Context,
) {
    private val ledgerStore = ProtectedLocalLedger(context)
    private val credentialStore = AndroidKeystoreCredentialStore(context)
    private val commands = AegisCommandClient()
    private val reads = AegisBackendClient()
    private val notifications = GposNotificationPublisher(context)

    suspend fun flushDue(force: Boolean): TaskQueueProcessResult = flushLock.withLock { processDue(force) }

    private suspend fun processDue(force: Boolean): TaskQueueProcessResult {
        val credential = credentialStore.read() ?: return TaskQueueProcessResult()
        if (BackgroundAuthenticationPolicy.requiresForegroundRenewal(credential.expiresAtEpochMs, System.currentTimeMillis())) {
            AuthenticationRecovery(context).requireForegroundRenewal()
            return TaskQueueProcessResult()
        }
        val now = System.currentTimeMillis()
        val owner = credential.workspaceOwner()
        val due = ledgerStore.read().pendingTasks.filter { (it.owner.isBlank() || it.owner == owner) && (force || it.syncAfterEpochMs <= now) }
        var completed = 0
        var failed = 0
        var retry = false

        for (pending in due) {
            if (credentialStore.read()?.workspaceOwner() != owner) break
            var claimed = false
            ledgerStore.update { ledger ->
                if (ledger.pendingTasks.none { it.id == pending.id }) return@update ledger
                claimed = true
                ledger.copy(receipts = ledger.receipts.map {
                    if (it.id == pending.id) it.copy(state = LocalReceiptState.SENDING) else it
                })
            }
            if (!claimed) continue
            val outcome = runCatching {
                commands.completeTasks(credential.authToken, listOf(pending.taskId), pending.taskListId)
                if (pending.taskListId == "@default") {
                    val activeIds = DashboardPayloadMapper.map(reads.readDashboard(credential.authToken)).tasks.mapNotNull { it.canonicalId }
                    check(pending.taskId !in activeIds) { "Task remains active after completion." }
                } else {
                    val workspace = AegisInteractionClient().readTaskWorkspace(credential.authToken)
                    check(workspace.lists.any { it.id == pending.taskListId }) { "Original task list was not returned; completion cannot be verified." }
                    check(workspace.tasks.none { it.id == pending.taskId && it.listId == pending.taskListId }) { "Task remains active after completion." }
                }
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (outcome.isSuccess) {
                completed += 1
                markConfirmed(pending)
            } else {
                val failure = outcome.exceptionOrNull()
                if (BackgroundAuthenticationPolicy.isAuthenticationFailure(failure)) {
                    markWaitingForAuthentication(pending)
                    AuthenticationRecovery(context).requireForegroundRenewal()
                    break
                }
                val nextAttempt = pending.attempts + 1
                val message = failure?.message
                    ?.takeIf(String::isNotBlank)
                    ?: "Task synchronization could not be verified."
                if (nextAttempt >= MAX_TASK_ATTEMPTS) {
                    failed += 1
                    markFailed(pending, message)
                } else {
                    retry = true
                    val nextSync = System.currentTimeMillis() + RETRY_DELAY_MS
                    ledgerStore.update { ledger ->
                        ledger.copy(
                            pendingTasks = ledger.pendingTasks.map { item ->
                                if (item.id == pending.id) {
                                    item.copy(attempts = nextAttempt, syncAfterEpochMs = nextSync)
                                } else item
                            },
                            receipts = ledger.receipts.map { receipt ->
                                if (receipt.id == pending.id) {
                                    receipt.copy(
                                        state = LocalReceiptState.QUEUED,
                                        updatedAtEpochMs = System.currentTimeMillis(),
                                        result = "Retry ${nextAttempt + 1} scheduled after a transient verification failure.",
                                    )
                                } else receipt
                            },
                        )
                    }
                    TaskQueueSyncScheduler(context).schedulePending(pending.id, RETRY_DELAY_MS)
                }
            }
        }
        return TaskQueueProcessResult(completed, failed, retry)
    }

    private fun markConfirmed(pending: PendingTaskMutation) {
        val now = System.currentTimeMillis()
        ledgerStore.update { ledger ->
            ledger.copy(
                pendingTasks = ledger.pendingTasks.filterNot { it.id == pending.id },
                receipts = ledger.receipts.map { receipt ->
                    if (receipt.id == pending.id) {
                        receipt.copy(
                            state = LocalReceiptState.CONFIRMED,
                            updatedAtEpochMs = now,
                            result = "Task completion confirmed by a fresh canonical read.",
                            error = null,
                        )
                    } else receipt
                },
            )
        }
        notifications.publishOutcome(
            stableEventId = pending.id,
            title = "Task completed",
            body = pending.title,
            target = GposDeepLinkTarget.TASKS,
            isError = false,
            channelId = GposNotificationChannels.TASKS,
        )
    }

    private fun markWaitingForAuthentication(pending: PendingTaskMutation) {
        val now = System.currentTimeMillis()
        ledgerStore.update { ledger -> ledger.copy(
            receipts = ledger.receipts.map { receipt ->
                if (receipt.id == pending.id) receipt.copy(
                    state = LocalReceiptState.QUEUED,
                    updatedAtEpochMs = now,
                    result = "Waiting for Google authorization; task completion remains queued.",
                    error = null,
                ) else receipt
            },
        ) }
    }

    private fun markFailed(pending: PendingTaskMutation, message: String) {
        val now = System.currentTimeMillis()
        val alert = LocalAlert(
            id = UUID.randomUUID().toString(),
            severity = "error",
            title = "Task synchronization failed",
            detail = "${pending.title}: $message".take(500),
            createdAtEpochMs = now,
        )
        ledgerStore.update { ledger ->
            ledger.copy(
                pendingTasks = ledger.pendingTasks.filterNot { it.id == pending.id },
                receipts = ledger.receipts.map { receipt ->
                    if (receipt.id == pending.id) {
                        receipt.copy(
                            state = LocalReceiptState.FAILED,
                            updatedAtEpochMs = now,
                            error = message.take(500),
                        )
                    } else receipt
                },
                alerts = ledger.alerts + alert,
            )
        }
        notifications.publishOutcome(
            stableEventId = pending.id,
            title = "Task sync failed",
            body = pending.title,
            target = GposDeepLinkTarget.ALERTS,
            isError = true,
            channelId = GposNotificationChannels.TASKS,
        )
    }

    private companion object {
        val flushLock = Mutex()
        const val MAX_TASK_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 5L * 60L * 1000L
    }
}

class TaskQueueSyncScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context)
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<TaskQueueSyncWorker>(
            PERIODIC_MINUTES,
            TimeUnit.MINUTES,
        )
            .addTag("gpos-task-queue")
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            TaskQueueSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun schedulePending(localId: String, delayMs: Long = GRACE_MS) {
        val request = OneTimeWorkRequestBuilder<TaskQueueSyncWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag("gpos-task-queue")
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            TaskQueueSyncWorker.DUE_WORK_PREFIX + localId,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelPending(localId: String) {
        workManager.cancelUniqueWork(TaskQueueSyncWorker.DUE_WORK_PREFIX + localId)
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag("gpos-task-queue")
        workManager.cancelUniqueWork(TaskQueueSyncWorker.PERIODIC_WORK_NAME)
    }

    companion object {
        const val GRACE_MS = 2L * 60L * 1000L
        const val PERIODIC_MINUTES = 15L
    }
}


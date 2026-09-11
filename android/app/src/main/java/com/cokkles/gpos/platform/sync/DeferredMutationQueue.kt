package com.cokkles.gpos.platform.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cokkles.gpos.data.interaction.AegisInteractionClient
import com.cokkles.gpos.data.local.DeferredMutation
import com.cokkles.gpos.data.local.DeferredMutationType
import com.cokkles.gpos.data.local.LocalAlert
import com.cokkles.gpos.data.local.LocalReceipt
import com.cokkles.gpos.data.local.LocalReceiptState
import com.cokkles.gpos.data.local.ProtectedLocalLedger
import com.cokkles.gpos.data.workspace.WorkspaceTask
import com.cokkles.gpos.data.workspace.workspaceOwner
import com.cokkles.gpos.platform.security.BackgroundAuthenticationPolicy
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeferredMutationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DeferredMutationProcessor(applicationContext).flush(force = false)
        return Result.success()
    }
}

data class DeferredMutationResult(val confirmed: Int = 0, val failed: Int = 0, val remaining: Int = 0)

class DeferredMutationProcessor(private val context: Context) {
    private val ledger = ProtectedLocalLedger(context)
    private val credentials = AndroidKeystoreCredentialStore(context)
    private val client = AegisInteractionClient()

    suspend fun flush(force: Boolean): DeferredMutationResult = lock.withLock {
        val credential = credentials.read() ?: return@withLock DeferredMutationResult(remaining = ledger.read().deferredMutations.size)
        if (BackgroundAuthenticationPolicy.requiresForegroundRenewal(credential.expiresAtEpochMs, System.currentTimeMillis())) {
            AuthenticationRecovery(context).requireForegroundRenewal()
            return@withLock DeferredMutationResult(remaining = ledger.read().deferredMutations.size)
        }
        val owner = credential.workspaceOwner()
        val now = System.currentTimeMillis()
        val items = ledger.read().deferredMutations
            .filter { it.owner == owner && (force || it.syncAfterEpochMs <= now) }
            .sortedBy { it.createdAtEpochMs }
        var confirmed = 0
        var failed = 0
        for (item in items) {
            if (credentials.read()?.workspaceOwner() != owner) break
            val outcome = runCatching { send(credential.authToken, item) }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (outcome.isSuccess) {
                confirmed++
                finish(item, true, "Synced to Google.")
            } else {
                val error = outcome.exceptionOrNull()
                if (BackgroundAuthenticationPolicy.isAuthenticationFailure(error)) {
                    AuthenticationRecovery(context).requireForegroundRenewal()
                    markWaitingForAuthentication(item)
                    break
                }
                failed++
                finish(item, false, error?.message ?: "Background synchronization failed.")
            }
        }
        DeferredMutationResult(confirmed, failed, ledger.read().deferredMutations.count { it.owner == owner })
    }

    private suspend fun send(token: String, item: DeferredMutation) {
        when (item.type) {
            DeferredMutationType.TASK_CREATE -> client.saveWorkspaceTask(token, item.listId, null, item.title, item.notes, item.due, item.id)
            DeferredMutationType.TASK_UPDATE -> client.saveWorkspaceTask(token, item.listId, item.entityId, item.title, item.notes, item.due, item.id)
            DeferredMutationType.TASK_DELETE -> client.deleteWorkspaceTask(token, WorkspaceTask(item.entityId, item.listId, "", item.title))
            DeferredMutationType.TASK_RESTORE -> client.restoreWorkspaceTask(token, WorkspaceTask(item.entityId, item.listId, "", item.title))
            DeferredMutationType.LIST_CREATE -> client.saveWorkspaceList(token, item.title, null)
            DeferredMutationType.LIST_RENAME -> client.saveWorkspaceList(token, item.title, item.entityId)
            DeferredMutationType.FOLLOWUP_RESOLVE -> client.resolveFollowup(token, item.entityId, item.title)
            DeferredMutationType.FOLLOWUP_DISMISS -> client.dismissFollowup(token, item.entityId, item.title)
            DeferredMutationType.FOLLOWUP_PROMOTE -> client.promoteFollowupToTask(token, item.entityId, item.title, item.notes)
        }
    }

    private fun finish(item: DeferredMutation, success: Boolean, detail: String) {
        val now = System.currentTimeMillis()
        ledger.update { current ->
            current.copy(
                deferredMutations = current.deferredMutations.filterNot { it.id == item.id },
                receipts = current.receipts.map { receipt ->
                    if (receipt.id == item.id) receipt.copy(
                        state = if (success) LocalReceiptState.CONFIRMED else LocalReceiptState.FAILED,
                        updatedAtEpochMs = now,
                        result = detail.takeIf { success },
                        error = detail.takeUnless { success }?.take(500),
                    ) else receipt
                },
                alerts = if (success) current.alerts else current.alerts + LocalAlert(
                    UUID.randomUUID().toString(), "error", "Queued change failed", "${item.title}: $detail".take(500), now,
                ),
            )
        }
    }

    private fun markWaitingForAuthentication(item: DeferredMutation) {
        ledger.update { current -> current.copy(
            receipts = current.receipts.map { receipt ->
                if (receipt.id == item.id) receipt.copy(
                    state = LocalReceiptState.QUEUED,
                    updatedAtEpochMs = System.currentTimeMillis(),
                    result = "Waiting for Google authorization; the change remains queued.",
                    error = null,
                ) else receipt
            },
        ) }
    }

    private companion object { val lock = Mutex() }
}

class DeferredMutationQueue(context: Context) {
    private val ledger = ProtectedLocalLedger(context)
    private val work = WorkManager.getInstance(context.applicationContext)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun stage(item: DeferredMutation): DeferredMutation {
        val staged = item.copy(syncAfterEpochMs = System.currentTimeMillis() + DEBOUNCE_MS)
        ledger.update { current ->
            val supersededIds = current.deferredMutations.filter { existing -> DeferredMutationPolicy.superseded(existing, staged) }.map { it.id }.toSet()
            val withoutSuperseded = current.deferredMutations.filterNot { it.id in supersededIds }
            current.copy(
                deferredMutations = withoutSuperseded + staged,
                receipts = current.receipts.filterNot { it.id in supersededIds } +
                    LocalReceipt(staged.id, "sync", staged.title.take(120), LocalReceiptState.QUEUED,
                        staged.createdAtEpochMs, staged.createdAtEpochMs, result = "Pending automatic sync"),
            )
        }
        schedule()
        return staged
    }

    fun schedule() {
        val request = OneTimeWorkRequestBuilder<DeferredMutationWorker>()
            .setInitialDelay(DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleNow() {
        val request = OneTimeWorkRequestBuilder<DeferredMutationWorker>()
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun removeForEntity(owner: String, entityId: String) {
        ledger.update { current ->
            val removedIds = current.deferredMutations.filter { it.owner == owner && it.entityId == entityId }.map { it.id }.toSet()
            current.copy(
                deferredMutations = current.deferredMutations.filterNot { it.id in removedIds },
                receipts = current.receipts.filterNot { it.id in removedIds },
            )
        }
    }

    fun cancelAll() = work.cancelAllWorkByTag(WORK_TAG)

    companion object {
        const val DEBOUNCE_MS = 2L * 60L * 1000L
        const val WORK_NAME = "gpos-deferred-mutation-sync"
        const val WORK_TAG = "gpos-deferred-mutations"
    }
}

internal object DeferredMutationPolicy {
    fun superseded(old: DeferredMutation, next: DeferredMutation): Boolean {
        if (old.owner != next.owner) return false
        if (old.type == DeferredMutationType.TASK_CREATE && next.type == DeferredMutationType.TASK_CREATE && old.entityId == next.entityId) return true
        if (old.type == DeferredMutationType.TASK_UPDATE && next.type == DeferredMutationType.TASK_UPDATE && old.entityId == next.entityId && old.listId == next.listId) return true
        if (old.type == DeferredMutationType.LIST_RENAME && next.type == DeferredMutationType.LIST_RENAME && old.entityId == next.entityId) return true
        return old.entityId.isNotBlank() && old.entityId == next.entityId && old.type.name.startsWith("FOLLOWUP") && next.type.name.startsWith("FOLLOWUP")
    }
}


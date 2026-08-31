package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.local.LocalLedger
import com.cokkles.gpos.data.local.LocalReceipt
import com.cokkles.gpos.data.local.LocalReceiptState
import com.cokkles.gpos.data.local.PendingTaskMutation
import com.cokkles.gpos.data.local.ProtectedLocalLedger
import com.cokkles.gpos.platform.sync.TaskQueueProcessor
import com.cokkles.gpos.platform.sync.TaskQueueSyncScheduler
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskQueueUiState(
    val ledger: LocalLedger = LocalLedger(),
    val syncing: Boolean = false,
    val lastMessage: String? = null,
)

class TaskQueueViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val ledgerStore = ProtectedLocalLedger(application)
    private val scheduler = TaskQueueSyncScheduler(application)
    private val processor = TaskQueueProcessor(application)

    private val _state = MutableStateFlow(TaskQueueUiState(ledger = ledgerStore.read()))
    val state: StateFlow<TaskQueueUiState> = _state.asStateFlow()

    init {
        scheduler.schedulePeriodic()
    }

    fun refresh() {
        _state.update { it.copy(ledger = ledgerStore.read()) }
    }

    fun stage(taskId: String?, title: String) {
        val canonicalId = taskId?.trim()?.takeIf(String::isNotBlank) ?: return
        val current = ledgerStore.read()
        if (current.pendingTasks.any { it.taskId == canonicalId }) return
        val now = System.currentTimeMillis()
        val localId = UUID.randomUUID().toString()
        val pending = PendingTaskMutation(
            id = localId,
            taskId = canonicalId,
            title = title.trim().ifBlank { "Google Task" },
            stagedAtEpochMs = now,
            syncAfterEpochMs = now + TaskQueueSyncScheduler.GRACE_MS,
        )
        val receipt = LocalReceipt(
            id = localId,
            kind = "task",
            summary = pending.title.take(120),
            state = LocalReceiptState.QUEUED,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            result = "Undo is available for five minutes.",
        )
        val updated = ledgerStore.update { ledger ->
            ledger.copy(
                pendingTasks = ledger.pendingTasks + pending,
                receipts = ledger.receipts + receipt,
            )
        }
        scheduler.schedulePending(localId)
        _state.value = TaskQueueUiState(
            ledger = updated,
            lastMessage = "Task queued. Undo is available for five minutes.",
        )
    }

    fun undo(localId: String) {
        val now = System.currentTimeMillis()
        val updated = ledgerStore.update { ledger ->
            ledger.copy(
                pendingTasks = ledger.pendingTasks.filterNot { it.id == localId },
                receipts = ledger.receipts.map { receipt ->
                    if (receipt.id == localId && receipt.state == LocalReceiptState.QUEUED) {
                        receipt.copy(
                            state = LocalReceiptState.CANCELLED,
                            updatedAtEpochMs = now,
                            result = "Task completion cancelled during the five-minute grace window.",
                        )
                    } else receipt
                },
            )
        }
        scheduler.cancelPending(localId)
        _state.value = TaskQueueUiState(
            ledger = updated,
            lastMessage = "Task completion undone.",
        )
    }

    fun syncNow(onCanonicalRefreshRequested: () -> Unit) {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, lastMessage = null) }
            val result = processor.flushDue(force = true)
            _state.value = TaskQueueUiState(
                ledger = ledgerStore.read(),
                syncing = false,
                lastMessage = when {
                    result.completed > 0 || result.failed > 0 ->
                        "Task sync: ${result.completed} confirmed, ${result.failed} failed. Canonical Tasks refreshed."
                    else -> "No pending task changes. Canonical Google Tasks refreshed."
                },
            )
            onCanonicalRefreshRequested()
        }
    }

    fun clearProtectedLedger() {
        scheduler.cancelAll()
        ledgerStore.clear()
        _state.value = TaskQueueUiState()
    }
}

package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.command.CaptureInputNormalizer
import com.cokkles.gpos.data.command.CaptureKind
import com.cokkles.gpos.data.local.LocalLedger
import com.cokkles.gpos.data.local.LocalReceipt
import com.cokkles.gpos.data.local.LocalReceiptState
import com.cokkles.gpos.data.local.ProtectedLocalLedger
import com.cokkles.gpos.platform.sync.CaptureRetryScheduler
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureUiState(
    val ledger: LocalLedger = LocalLedger(),
    val submitting: Boolean = false,
    val lastMessage: String? = null,
    val error: String? = null,
)

class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    private val ledgerStore = ProtectedLocalLedger(application)
    private val scheduler = CaptureRetryScheduler(application)
    private val _state = MutableStateFlow(CaptureUiState(ledger = ledgerStore.read()))
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun refreshLedger() = _state.update { it.copy(ledger = ledgerStore.read()) }

    fun submit(kind: CaptureKind, text: String) {
        val body = CaptureInputNormalizer.normalize(kind, text)
        if (body.isBlank() || _state.value.submitting) return
        val now = System.currentTimeMillis()
        _state.update { it.copy(submitting = true, error = null) }
        val duplicate = ledgerStore.read().receipts.firstOrNull {
            it.kind == kind.wireName &&
                it.payload == body &&
                it.state !in setOf(LocalReceiptState.CONFIRMED, LocalReceiptState.CANCELLED) &&
                now - it.createdAtEpochMs <= DUPLICATE_TAP_WINDOW_MS
        }
        if (duplicate != null) {
            scheduler.schedule(duplicate.id, 0L)
            _state.value = CaptureUiState(
                ledger = ledgerStore.read(),
                submitting = false,
                lastMessage = "This capture is already queued. Reusing its original ID (${duplicate.id.take(8)}).",
            )
            return
        }
        val id = UUID.randomUUID().toString()
        val sending = LocalReceipt(
            id = id,
            kind = kind.wireName,
            summary = body.replace(Regex("\\s+"), " ").take(120),
            state = LocalReceiptState.QUEUED,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            payload = body,
        )
        val ledger = ledgerStore.update { it.copy(receipts = it.receipts + sending) }
        scheduler.schedule(id, 0L)
        _state.value = CaptureUiState(
            ledger = ledger,
            submitting = false,
            lastMessage = "Queued ${kind.displayName.lowercase()} in the background. AEGIS will notify you when it is confirmed.",
        )
    }

    fun retry(id: String) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        val target = ledgerStore.read().receipts.firstOrNull { it.id == id }
        if (target == null || (!target.manualRetryAllowed && !target.serverManaged)) {
            _state.update { it.copy(submitting = false) }
            return
        }
        val updated = ledgerStore.update { current -> current.copy(receipts = current.receipts.map {
            if (it.id == id) it.copy(
                state = if (it.serverManaged) LocalReceiptState.WAITING else LocalReceiptState.QUEUED,
                updatedAtEpochMs = System.currentTimeMillis(),
                result = if (it.serverManaged) "Server status check queued." else "Manual retry queued with the same capture ID.",
                error = null,
                attempts = if (it.serverManaged) it.attempts else 0,
            ) else it
        }) }
        scheduler.schedule(id, 0L, manual = true)
        _state.value = CaptureUiState(
            updated,
            false,
            if (target.serverManaged) "Checking the existing server capture." else "Retry queued with the original duplicate-safe ID.",
        )
    }

    fun acknowledgeLocalAlert(id: String) {
        val updated = ledgerStore.update { current -> current.copy(alerts = current.alerts.map {
            if (it.id == id) it.copy(acknowledged = true) else it
        }) }
        _state.update { it.copy(ledger = updated) }
    }

    fun clearProtectedLedger() {
        scheduler.cancelAll()
        ledgerStore.clear()
        _state.value = CaptureUiState()
    }

    private companion object {
        const val DUPLICATE_TAP_WINDOW_MS = 15_000L
    }
}

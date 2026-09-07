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
import com.cokkles.gpos.platform.sync.CaptureProcessOutcome
import com.cokkles.gpos.platform.sync.CaptureRetryScheduler
import com.cokkles.gpos.platform.sync.CaptureSubmissionProcessor
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
    private val processor = CaptureSubmissionProcessor(application)
    private val scheduler = CaptureRetryScheduler(application)
    private val _state = MutableStateFlow(CaptureUiState(ledger = ledgerStore.read()))
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun refreshLedger() = _state.update { it.copy(ledger = ledgerStore.read()) }

    fun submit(kind: CaptureKind, text: String) {
        val body = CaptureInputNormalizer.normalize(kind, text)
        if (body.isBlank() || _state.value.submitting) return
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val sending = LocalReceipt(
            id = id,
            kind = kind.wireName,
            summary = body.replace(Regex("\\s+"), " ").take(120),
            state = LocalReceiptState.SENDING,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            payload = body,
        )
        val ledger = ledgerStore.update { it.copy(receipts = it.receipts + sending) }
        _state.value = CaptureUiState(ledger, true, "Submitting ${kind.displayName.lowercase()} to canonical AEGIS…")
        viewModelScope.launch {
            val outcome = processor.process(id)
            val current = ledgerStore.read()
            _state.value = when (outcome) {
                CaptureProcessOutcome.Confirmed -> CaptureUiState(current, false, "Capture confirmed.")
                CaptureProcessOutcome.Queued -> CaptureUiState(current, false, "Gemini is busy. AEGIS will retry automatically.")
                CaptureProcessOutcome.Failed -> CaptureUiState(current, false, error = current.receipts.firstOrNull { it.id == id }?.error)
                CaptureProcessOutcome.Ignored -> CaptureUiState(current, false, error = "Capture was not sent. Reconnect and try again.")
            }
        }
    }

    fun retry(id: String) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null, lastMessage = "Retrying capture…") }
        viewModelScope.launch {
            processor.process(id, manual = true)
            _state.update { it.copy(ledger = ledgerStore.read(), submitting = false) }
        }
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
}

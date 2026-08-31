package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.command.AegisCommandClient
import com.cokkles.gpos.data.command.CaptureKind
import com.cokkles.gpos.data.local.LocalAlert
import com.cokkles.gpos.data.local.LocalLedger
import com.cokkles.gpos.data.local.LocalReceipt
import com.cokkles.gpos.data.local.LocalReceiptState
import com.cokkles.gpos.data.local.ProtectedLocalLedger
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
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

class CaptureViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val commandClient = AegisCommandClient()
    private val credentialStore = AndroidKeystoreCredentialStore(application)
    private val ledgerStore = ProtectedLocalLedger(application)
    private val notifications = GposNotificationPublisher(application)

    private val _state = MutableStateFlow(CaptureUiState(ledger = ledgerStore.read()))
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun refreshLedger() {
        _state.update { it.copy(ledger = ledgerStore.read()) }
    }

    fun submit(kind: CaptureKind, text: String) {
        val body = text.trim()
        if (body.isBlank() || _state.value.submitting) return
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val summary = body.replace(Regex("\\s+"), " ").take(120)
        val sending = LocalReceipt(
            id = id,
            kind = kind.wireName,
            summary = summary,
            state = LocalReceiptState.SENDING,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        val stagedLedger = ledgerStore.update { current ->
            current.copy(receipts = current.receipts + sending)
        }
        _state.value = CaptureUiState(
            ledger = stagedLedger,
            submitting = true,
            lastMessage = "Submitting ${kind.displayName.lowercase()} to canonical AEGIS…",
        )

        viewModelScope.launch {
            val credential = credentialStore.read()
            if (credential == null || credential.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true) {
                finishFailure(id, kind, "A current authenticated session is required before submitting.")
                return@launch
            }

            runCatching { commandClient.submitCapture(credential.idToken, kind, body, id) }
                .onSuccess { result ->
                    val completedAt = System.currentTimeMillis()
                    val updated = ledgerStore.update { current ->
                        current.copy(
                            receipts = current.receipts.map { receipt ->
                                if (receipt.id == id) {
                                    receipt.copy(
                                        state = LocalReceiptState.CONFIRMED,
                                        updatedAtEpochMs = completedAt,
                                        result = result.message.take(500),
                                        error = null,
                                    )
                                } else receipt
                            },
                        )
                    }
                    _state.value = CaptureUiState(
                        ledger = updated,
                        submitting = false,
                        lastMessage = result.message,
                    )
                    notifications.publishOutcome(
                        stableEventId = id,
                        title = "${kind.displayName} submitted",
                        body = result.message,
                        target = GposDeepLinkTarget.ALERTS,
                        isError = false,
                    )
                }
                .onFailure { error ->
                    finishFailure(id, kind, error.message ?: "Capture submission failed.")
                }
        }
    }

    fun acknowledgeLocalAlert(id: String) {
        val updated = ledgerStore.update { current ->
            current.copy(
                alerts = current.alerts.map { alert ->
                    if (alert.id == id) alert.copy(acknowledged = true) else alert
                },
            )
        }
        _state.update { it.copy(ledger = updated) }
    }

    fun clearProtectedLedger() {
        ledgerStore.clear()
        _state.value = CaptureUiState()
    }

    private fun finishFailure(id: String, kind: CaptureKind, message: String) {
        val completedAt = System.currentTimeMillis()
        val alert = LocalAlert(
            id = UUID.randomUUID().toString(),
            severity = "error",
            title = "${kind.displayName} failed",
            detail = message.take(500),
            createdAtEpochMs = completedAt,
        )
        val updated = ledgerStore.update { current ->
            current.copy(
                receipts = current.receipts.map { receipt ->
                    if (receipt.id == id) {
                        receipt.copy(
                            state = LocalReceiptState.FAILED,
                            updatedAtEpochMs = completedAt,
                            error = message.take(500),
                        )
                    } else receipt
                },
                alerts = current.alerts + alert,
            )
        }
        _state.value = CaptureUiState(
            ledger = updated,
            submitting = false,
            error = message,
        )
        notifications.publishOutcome(
            stableEventId = id,
            title = "Submission failed",
            body = message,
            target = GposDeepLinkTarget.ALERTS,
            isError = true,
        )
    }
}

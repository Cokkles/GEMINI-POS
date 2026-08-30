package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.command.AegisCommandClient
import com.cokkles.gpos.data.command.NotificationCommandRuntimeState
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Foreground-only server-notification acknowledgement controller.
 *
 * Nothing in this class is invoked by notification delivery, deep-link handling, or WorkManager.
 */
class NotificationCommandViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val commandClient = AegisCommandClient()
    private val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(application)

    private val _state = MutableStateFlow(NotificationCommandRuntimeState())
    val state: StateFlow<NotificationCommandRuntimeState> = _state.asStateFlow()

    fun acknowledge(notificationId: String, onCanonicalRefreshRequested: () -> Unit) {
        val id = notificationId.trim()
        if (id.isBlank() || id in _state.value.submittingIds) return

        viewModelScope.launch {
            val credential = credentialStore.read()
            val valid = credential != null &&
                credential.expiresAtEpochMs?.let { it > System.currentTimeMillis() } != false
            if (!valid) {
                _state.update {
                    it.copy(error = "A current authenticated session is required to acknowledge alerts.")
                }
                return@launch
            }

            _state.update {
                it.copy(
                    submittingIds = it.submittingIds + id,
                    error = null,
                    lastAcknowledgedId = null,
                )
            }
            runCatching { commandClient.acknowledgeNotification(credential.idToken, id) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            submittingIds = it.submittingIds - id,
                            lastAcknowledgedId = if (result.acknowledged) id else null,
                            error = if (result.acknowledged) null
                            else "AEGIS did not confirm notification acknowledgement.",
                        )
                    }
                    if (result.acknowledged) onCanonicalRefreshRequested()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            submittingIds = it.submittingIds - id,
                            error = error.message?.takeIf(String::isNotBlank)
                                ?: "Notification acknowledgement could not be confirmed.",
                        )
                    }
                }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(lastAcknowledgedId = null, error = null) }
    }
}

package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.command.AegisCommandClient
import com.cokkles.gpos.data.command.CalendarCommandProgress
import com.cokkles.gpos.data.command.CalendarCommandRuntimeState
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Foreground-only two-step Calendar mutation controller.
 *
 * resolve() obtains a proposal but does not create anything. createResolved() can only submit the
 * exact proposal currently held in state, after the UI has presented it and requested confirmation.
 */
class CalendarCommandViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val commandClient = AegisCommandClient()
    private val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(application)

    private val _state = MutableStateFlow(CalendarCommandRuntimeState())
    val state: StateFlow<CalendarCommandRuntimeState> = _state.asStateFlow()

    fun resolve(sourceText: String) {
        val text = sourceText.trim()
        if (text.isBlank() || _state.value.progress in BUSY_STATES) return

        viewModelScope.launch {
            val credential = currentCredentialOrReport() ?: return@launch
            _state.update {
                CalendarCommandRuntimeState(
                    sourceText = text,
                    progress = CalendarCommandProgress.RESOLVING,
                )
            }
            runCatching { commandClient.resolveCalendarEvent(credential.idToken, text) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            proposal = result.event,
                            progress = CalendarCommandProgress.READY_TO_CONFIRM,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            proposal = null,
                            progress = CalendarCommandProgress.IDLE,
                            error = error.safeMessage(),
                        )
                    }
                }
        }
    }

    fun cancelProposal() {
        if (_state.value.progress == CalendarCommandProgress.CREATING) return
        _state.value = CalendarCommandRuntimeState()
    }

    fun createResolved(onCanonicalRefreshRequested: () -> Unit) {
        val current = _state.value
        val proposal = current.proposal ?: return
        if (current.progress != CalendarCommandProgress.READY_TO_CONFIRM) return

        viewModelScope.launch {
            val credential = currentCredentialOrReport() ?: return@launch
            _state.update { it.copy(progress = CalendarCommandProgress.CREATING, error = null) }
            runCatching { commandClient.createCalendarEvent(credential.idToken, proposal) }
                .onSuccess { result ->
                    _state.value = CalendarCommandRuntimeState(
                        progress = CalendarCommandProgress.IDLE,
                        lastMessage = result.message ?: "Calendar event created.",
                    )
                    onCanonicalRefreshRequested()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            progress = CalendarCommandProgress.READY_TO_CONFIRM,
                            error = error.safeMessage(),
                        )
                    }
                }
        }
    }

    private suspend fun currentCredentialOrReport(): com.cokkles.gpos.platform.security.StoredCredential? {
        val credential = credentialStore.read()
        val valid = credential != null &&
            credential.expiresAtEpochMs?.let { it > System.currentTimeMillis() } != false
        if (!valid) {
            _state.update {
                it.copy(
                    progress = CalendarCommandProgress.IDLE,
                    error = "A current authenticated session is required for Calendar actions.",
                )
            }
            return null
        }
        return credential
    }

    private companion object {
        val BUSY_STATES = setOf(
            CalendarCommandProgress.RESOLVING,
            CalendarCommandProgress.CREATING,
        )
    }
}

private fun Throwable.safeMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "The Calendar action could not be completed."

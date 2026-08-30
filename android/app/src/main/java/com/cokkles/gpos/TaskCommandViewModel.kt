package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.command.AegisCommandClient
import com.cokkles.gpos.data.command.CommandProgress
import com.cokkles.gpos.data.command.TaskCommandRuntimeState
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns only explicit task-completion intent and submission state.
 *
 * Staging is local and side-effect free. The network mutation occurs only through completeStaged,
 * which is called after foreground confirmation. Failed submissions preserve staged IDs.
 */
class TaskCommandViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val commandClient = AegisCommandClient()
    private val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(application)

    private val _state = MutableStateFlow(TaskCommandRuntimeState())
    val state: StateFlow<TaskCommandRuntimeState> = _state.asStateFlow()

    fun setStaged(canonicalTaskId: String?, staged: Boolean) {
        val id = canonicalTaskId?.trim()?.takeIf { it.isNotBlank() } ?: return
        _state.update { current ->
            if (current.progress == CommandProgress.SUBMITTING) return@update current
            val updated = current.stagedCanonicalIds.toMutableSet().apply {
                if (staged) add(id) else remove(id)
            }
            current.copy(
                stagedCanonicalIds = updated,
                lastMessage = null,
                error = null,
            )
        }
    }

    fun clearStaged() {
        _state.update { current ->
            if (current.progress == CommandProgress.SUBMITTING) current
            else current.copy(stagedCanonicalIds = emptySet(), lastMessage = null, error = null)
        }
    }

    fun completeStaged(onCanonicalRefreshRequested: () -> Unit) {
        val ids = _state.value.stagedCanonicalIds
        if (ids.isEmpty() || _state.value.progress == CommandProgress.SUBMITTING) return

        viewModelScope.launch {
            _state.update { it.copy(progress = CommandProgress.SUBMITTING, error = null, lastMessage = null) }
            val credential = credentialStore.read()
            if (credential == null || credential.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true) {
                _state.update {
                    it.copy(
                        progress = CommandProgress.IDLE,
                        error = "A current authenticated session is required before completing tasks.",
                    )
                }
                return@launch
            }

            runCatching { commandClient.completeTasks(credential.idToken, ids) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            stagedCanonicalIds = emptySet(),
                            progress = CommandProgress.IDLE,
                            lastMessage = result.message,
                            error = null,
                        )
                    }
                    onCanonicalRefreshRequested()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            progress = CommandProgress.IDLE,
                            error = error.message?.takeIf(String::isNotBlank)
                                ?: "Task completion could not be confirmed.",
                        )
                    }
                }
        }
    }
}

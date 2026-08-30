package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.command.AegisCommandClient
import com.cokkles.gpos.data.command.CommandProgress
import com.cokkles.gpos.data.command.TaskCommandRuntimeState
import com.cokkles.gpos.data.remote.AegisBackendClient
import com.cokkles.gpos.data.remote.DashboardPayloadMapper
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
 * Staging is local and side-effect free. A successful mark_done response is treated only as
 * submission acknowledgement because the legacy backend can swallow individual Google Tasks patch
 * failures. Completion is not shown as confirmed until a fresh authenticated canonical dashboard
 * proves the selected task IDs are no longer active.
 */
class TaskCommandViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val commandClient = AegisCommandClient()
    private val readClient = AegisBackendClient()
    private val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(application)

    private val _state = MutableStateFlow(TaskCommandRuntimeState())
    val state: StateFlow<TaskCommandRuntimeState> = _state.asStateFlow()

    fun setStaged(canonicalTaskId: String?, staged: Boolean) {
        val id = canonicalTaskId?.trim()?.takeIf { it.isNotBlank() } ?: return
        _state.update { current ->
            if (current.progress != CommandProgress.IDLE) return@update current
            val updated = current.stagedCanonicalIds.toMutableSet().apply {
                if (staged) add(id) else remove(id)
            }
            current.copy(
                stagedCanonicalIds = updated,
                verificationNotBeforeEpochMs = null,
                lastMessage = null,
                error = null,
            )
        }
    }

    fun clearStaged() {
        _state.update { current ->
            if (current.progress != CommandProgress.IDLE) current
            else TaskCommandRuntimeState()
        }
    }

    fun completeStaged(onCanonicalRefreshRequested: () -> Unit) {
        val ids = _state.value.stagedCanonicalIds
        if (ids.isEmpty() || _state.value.progress != CommandProgress.IDLE) return

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
                .onSuccess {
                    val submittedAt = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            progress = CommandProgress.VERIFYING,
                            verificationNotBeforeEpochMs = submittedAt,
                            lastMessage = "Completion request accepted; verifying against a fresh canonical task read.",
                            error = null,
                        )
                    }

                    val verification = runCatching {
                        val dashboardJson = readClient.readDashboard(credential.idToken)
                        DashboardPayloadMapper.map(dashboardJson)
                            .tasks
                            .mapNotNull { task -> task.canonicalId }
                            .toSet()
                    }
                    verification.onSuccess { activeIds ->
                        reconcileCanonical(
                            activeCanonicalTaskIds = activeIds,
                            fetchedAtEpochMs = System.currentTimeMillis(),
                            isLive = true,
                        )
                        onCanonicalRefreshRequested()
                    }.onFailure { error ->
                        _state.update {
                            it.copy(
                                progress = CommandProgress.IDLE,
                                verificationNotBeforeEpochMs = null,
                                lastMessage = null,
                                error = "Completion request was accepted, but Android could not verify the canonical task state: ${error.safeTaskMessage()} The selected tasks were preserved and the mutation was not retried.",
                            )
                        }
                        onCanonicalRefreshRequested()
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            progress = CommandProgress.IDLE,
                            verificationNotBeforeEpochMs = null,
                            error = error.safeTaskMessage(),
                        )
                    }
                }
        }
    }

    private fun reconcileCanonical(
        activeCanonicalTaskIds: Set<String>,
        fetchedAtEpochMs: Long,
        isLive: Boolean,
    ) {
        val current = _state.value
        if (current.progress != CommandProgress.VERIFYING || !isLive) return
        val threshold = current.verificationNotBeforeEpochMs ?: return
        if (fetchedAtEpochMs < threshold) return

        val remaining = current.stagedCanonicalIds.intersect(activeCanonicalTaskIds)
        _state.value = if (remaining.isEmpty()) {
            TaskCommandRuntimeState(
                lastMessage = "Task completion confirmed by a fresh canonical live task read.",
            )
        } else {
            TaskCommandRuntimeState(
                stagedCanonicalIds = remaining,
                error = "${remaining.size} selected task${if (remaining.size == 1) " is" else "s are"} still active after the completion request. The unconfirmed selection was preserved and the mutation was not retried.",
            )
        }
    }
}

private fun Throwable.safeTaskMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Task completion could not be confirmed."

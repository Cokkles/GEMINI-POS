package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.interaction.AegisFollowup
import com.cokkles.gpos.data.interaction.AegisInteractionClient
import com.cokkles.gpos.data.interaction.AiChatMessage
import com.cokkles.gpos.data.interaction.CalendarInteractionResult
import com.cokkles.gpos.data.interaction.InteractionCapabilities
import com.cokkles.gpos.data.local.DeferredMutation
import com.cokkles.gpos.data.local.DeferredMutationType
import com.cokkles.gpos.data.local.ProtectedLocalLedger
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.sync.DeferredMutationQueue
import com.cokkles.gpos.data.workspace.workspaceOwner
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarInteractionUiState(
    val submitting: Boolean = false,
    val answer: String? = null,
    val result: CalendarInteractionResult? = null,
    val error: String? = null,
)

data class AegisInteractionUiState(
    val capabilities: InteractionCapabilities? = null,
    val capabilityError: String? = null,
    val followups: List<AegisFollowup> = emptyList(),
    val followupsLoading: Boolean = false,
    val followupsError: String? = null,
    val pendingFollowupIds: Set<String> = emptySet(),
    val taskSubmitting: Boolean = false,
    val taskMessage: String? = null,
    val taskError: String? = null,
    val aiMessages: List<AiChatMessage> = emptyList(),
    val aiSubmitting: Boolean = false,
    val aiError: String? = null,
    val aiMode: String = "general",
    val calendar: CalendarInteractionUiState = CalendarInteractionUiState(),
)

class AegisInteractionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val client = AegisInteractionClient()
    private val credentials = AndroidKeystoreCredentialStore(application)
    private val notifications = GposNotificationPublisher(application)
    private val deferredQueue = DeferredMutationQueue(application)
    private val localLedger = ProtectedLocalLedger(application)

    private val _state = MutableStateFlow(AegisInteractionUiState())
    val state: StateFlow<AegisInteractionUiState> = _state.asStateFlow()

    fun refreshCapabilitiesAndFollowups() {
        viewModelScope.launch {
            val token = currentToken() ?: return@launch
            val capabilities = runCatching { client.readCapabilities(token) }
            capabilities.onSuccess { caps ->
                _state.update { it.copy(capabilities = caps, capabilityError = null) }
                if (caps.followupsV1) refreshFollowupsInternal(token)
                else _state.update {
                    it.copy(
                        followups = emptyList(),
                        followupsError = "Follow-ups are not advertised by this backend.",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(capabilityError = error.safeInteractionMessage()) }
            }
        }
    }

    fun refreshFollowups() {
        viewModelScope.launch {
            val token = currentToken() ?: return@launch
            if (_state.value.capabilities?.followupsV1 != true) {
                val caps = runCatching { client.readCapabilities(token) }.getOrNull()
                if (caps != null) _state.update { it.copy(capabilities = caps, capabilityError = null) }
                if (caps?.followupsV1 != true) {
                    _state.update { it.copy(followupsError = "Follow-ups are not available from the current backend.") }
                    return@launch
                }
            }
            refreshFollowupsInternal(token)
        }
    }

    fun createTask(title: String, notes: String, onSuccess: () -> Unit = {}) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank() || _state.value.taskSubmitting) return
        viewModelScope.launch {
            val token = currentToken() ?: return@launch
            if (_state.value.capabilities?.taskActionV1 != true) {
                _state.update { it.copy(taskError = "The backend has not advertised generic Task creation.") }
                return@launch
            }
            _state.update { it.copy(taskSubmitting = true, taskMessage = null, taskError = null) }
            val localId = UUID.randomUUID().toString()
            runCatching { client.createTask(token, cleanTitle, notes, localId) }
                .onSuccess { result ->
                    val message = "Added ${result.task.title} to Google Tasks."
                    _state.update { it.copy(taskSubmitting = false, taskMessage = message, taskError = null) }
                    notifications.publishOutcome(
                        stableEventId = "task-create-${result.task.id}",
                        title = "Task added",
                        body = message,
                        target = GposDeepLinkTarget.TASKS,
                        isError = false,
                    )
                    onSuccess()
                }
                .onFailure { error ->
                    val message = error.safeInteractionMessage()
                    _state.update { it.copy(taskSubmitting = false, taskError = message) }
                    notifications.publishOutcome(
                        stableEventId = "task-create-$localId",
                        title = "Task could not be added",
                        body = message,
                        target = GposDeepLinkTarget.TASKS,
                        isError = true,
                    )
                }
        }
    }

    fun resolveFollowup(followup: AegisFollowup) {
        queueFollowup(followup, DeferredMutationType.FOLLOWUP_RESOLVE, "resolved")
    }

    fun dismissFollowup(followup: AegisFollowup) {
        queueFollowup(followup, DeferredMutationType.FOLLOWUP_DISMISS, "dismissed")
    }

    fun promoteFollowup(followup: AegisFollowup, onSuccess: () -> Unit = {}) {
        if (_state.value.capabilities?.taskActionV1 != true) {
            _state.update { it.copy(followupsError = "Task promotion is not advertised by this backend.") }
            return
        }
        queueFollowup(followup, DeferredMutationType.FOLLOWUP_PROMOTE, "promoted")
        onSuccess()
    }

    fun setAiMode(mode: String) {
        val normalized = mode.lowercase().takeIf { it in AI_MODES } ?: "general"
        _state.update { it.copy(aiMode = normalized) }
    }

    fun askAegis(question: String) {
        val clean = question.trim()
        if (clean.isBlank() || _state.value.aiSubmitting) return
        viewModelScope.launch {
            val token = currentToken() ?: return@launch
            if (_state.value.capabilities?.aiQueryV1 != true) {
                _state.update { it.copy(aiError = "Ask AEGIS is not advertised by this backend.") }
                return@launch
            }
            val prior = _state.value.aiMessages.takeLast(8)
            val userMessage = AiChatMessage("user", clean)
            _state.update {
                it.copy(
                    aiMessages = (it.aiMessages + userMessage).takeLast(MAX_CHAT_MESSAGES),
                    aiSubmitting = true,
                    aiError = null,
                )
            }
            runCatching { client.askAegis(token, clean, _state.value.aiMode, prior) }
                .onSuccess { result ->
                    val assistant = AiChatMessage("assistant", result.answer)
                    _state.update {
                        it.copy(
                            aiMessages = (it.aiMessages + assistant).takeLast(MAX_CHAT_MESSAGES),
                            aiSubmitting = false,
                            aiError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(aiSubmitting = false, aiError = error.safeInteractionMessage()) }
                }
        }
    }

    fun clearAiChat() {
        _state.update { it.copy(aiMessages = emptyList(), aiError = null) }
    }

    fun prepareCalendar(question: String) {
        val clean = question.trim()
        if (clean.isBlank() || _state.value.calendar.submitting) return
        viewModelScope.launch {
            val token = currentToken() ?: return@launch
            if (_state.value.capabilities?.calendarAiV2 != true) {
                _state.update {
                    it.copy(calendar = CalendarInteractionUiState(error = "Conversational Calendar V2 is not advertised by this backend."))
                }
                return@launch
            }
            _state.update { it.copy(calendar = CalendarInteractionUiState(submitting = true)) }
            runCatching { client.prepareCalendar(token, clean) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            calendar = CalendarInteractionUiState(
                                submitting = false,
                                answer = result.answer,
                                result = result,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(calendar = CalendarInteractionUiState(error = error.safeInteractionMessage()))
                    }
                }
        }
    }

    fun confirmCalendar(onSuccess: () -> Unit = {}) {
        val pending = _state.value.calendar.result
        val tokenValue = pending?.confirmationToken
        if (tokenValue.isNullOrBlank() || pending.confirmationRequired != true || _state.value.calendar.submitting) return
        viewModelScope.launch {
            val authToken = currentToken() ?: return@launch
            _state.update { it.copy(calendar = it.calendar.copy(submitting = true, error = null)) }
            runCatching { client.confirmCalendar(authToken, tokenValue) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            calendar = CalendarInteractionUiState(
                                submitting = false,
                                answer = result.answer,
                                result = null,
                            ),
                        )
                    }
                    notifications.publishOutcome(
                        stableEventId = "calendar-${UUID.randomUUID()}",
                        title = "Calendar updated",
                        body = result.answer,
                        target = GposDeepLinkTarget.CALENDAR,
                        isError = false,
                    )
                    onSuccess()
                }
                .onFailure { error ->
                    _state.update { it.copy(calendar = it.calendar.copy(submitting = false, error = error.safeInteractionMessage())) }
                }
        }
    }

    fun clearCalendarProposal() {
        _state.update { it.copy(calendar = CalendarInteractionUiState()) }
    }

    private fun queueFollowup(followup: AegisFollowup, type: DeferredMutationType, verb: String) {
        if (followup.id in _state.value.pendingFollowupIds) return
        _state.update { it.copy(pendingFollowupIds = it.pendingFollowupIds + followup.id) }
        viewModelScope.launch {
            val owner = credentials.read()?.workspaceOwner().orEmpty()
            if (owner.isBlank() || _state.value.capabilities?.followupsV1 != true) {
                _state.update { it.copy(
                    pendingFollowupIds = it.pendingFollowupIds - followup.id,
                    followupsError = "Follow-up actions require a connected Google account.",
                ) }
                return@launch
            }
            val now = System.currentTimeMillis()
            deferredQueue.stage(DeferredMutation(
                id = UUID.randomUUID().toString(), owner = owner, type = type, entityId = followup.id,
                title = followup.title, notes = followup.summary, createdAtEpochMs = now, syncAfterEpochMs = now,
            ))
            _state.update { current -> current.copy(
                followups = current.followups.filterNot { it.id == followup.id },
                followupsLoading = false,
                followupsError = null,
                taskMessage = if (type == DeferredMutationType.FOLLOWUP_PROMOTE) "Promoted locally • sync pending" else current.taskMessage,
            ) }
            notifications.publishOutcome(
                stableEventId = "followup-queued-${followup.id}", title = "Follow-up $verb",
                body = "Saved locally; Google sync is pending.", target = GposDeepLinkTarget.FOLLOW_UPS, isError = false,
            )
        }
    }

    private suspend fun refreshFollowupsInternal(token: String) {
        _state.update { it.copy(followupsLoading = true, followupsError = null) }
        runCatching { client.readFollowups(token) }
            .onSuccess { result ->
                val owner = credentials.read()?.workspaceOwner().orEmpty()
                val pending = localLedger.read().deferredMutations
                    .filter { it.owner == owner && it.type.name.startsWith("FOLLOWUP") }
                    .map { it.entityId }
                    .toSet()
                _state.update {
                    it.copy(
                        followups = result.items.filterNot { item -> item.id in pending },
                        pendingFollowupIds = pending,
                        followupsLoading = false,
                        followupsError = null,
                    )
                }
            }
            .onFailure { error ->
                _state.update { it.copy(followupsLoading = false, followupsError = error.safeInteractionMessage()) }
            }
    }

    private suspend fun currentToken(): String? {
        val credential = credentials.read()
        if (credential == null || credential.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true) {
            _state.update { current ->
                current.copy(
                    taskSubmitting = false,
                    taskError = "A current authenticated session is required.",
                    aiSubmitting = false,
                    aiError = "A current authenticated session is required.",
                    calendar = current.calendar.copy(submitting = false, error = "A current authenticated session is required."),
                )
            }
            return null
        }
        return credential.authToken
    }

    private companion object {
        val AI_MODES = setOf("general", "career", "finance", "logistics", "system")
        const val MAX_CHAT_MESSAGES = 20
    }
}

private fun Throwable.safeInteractionMessage(): String =
    message?.takeIf(String::isNotBlank) ?: "AEGIS is temporarily unavailable."


package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.interaction.AegisInteractionClient
import com.cokkles.gpos.data.interaction.InteractionCapabilities
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.workspace.*
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

data class TaskWorkspaceUiState(
    val owner: String = "", val workspace: TaskWorkspace = TaskWorkspace(),
    val capabilities: InteractionCapabilities = InteractionCapabilities(),
    val selectedList: String? = null, val days: Int = 7, val history: List<WorkspaceTask> = emptyList(),
    val loading: Boolean = false, val mutating: Boolean = false, val cached: Boolean = false,
    val message: String? = null, val error: String? = null, val historyError: String? = null,
    val canWrite: Boolean = false,
)
class TaskWorkspaceViewModel(app: Application) : AndroidViewModel(app) {
    private val credentials = AndroidKeystoreCredentialStore(app)
    private val storage = ProtectedWorkspaceStore(app)
    private val client = AegisInteractionClient()
    private val _state = MutableStateFlow(TaskWorkspaceUiState())
    val state = _state.asStateFlow()
    private var activationJob: Job? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var generation = 0
    private var readSequence = 0

    fun activate(auth: AuthState) {
        if (auth == AuthState.Restoring || auth == AuthState.Authenticating) return
        if (auth !is AuthState.Authenticated && auth !is AuthState.OfflineRestored) { activationJob?.cancel(); detach(); return }
        activationJob?.cancel()
        activationJob = viewModelScope.launch {
            val owner = credentials.read()?.workspaceOwner().orEmpty()
            if (owner.isBlank()) { detach(); return@launch }
            if (_state.value.owner != owner) {
                resetState()
                _state.value = TaskWorkspaceUiState(owner = owner)
                val currentGeneration = generation
                try {
                    val saved = withContext(Dispatchers.IO) { storage.read(owner, "tasks") }
                    if (generation == currentGeneration && saved != null) _state.update {
                        it.copy(workspace = parseTaskWorkspace(JSONObject(saved)), cached = true)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (generation == currentGeneration) _state.update { it.copy(error = "Saved tasks could not be read.") }
                }
            }
            _state.update { it.copy(canWrite = auth is AuthState.Authenticated) }
            if (auth is AuthState.Authenticated) refresh()
        }
    }
    fun detach() { activationJob?.cancel(); resetState() }
    private fun resetState() { generation++; readJob?.cancel(); writeJob?.cancel(); _state.value = TaskWorkspaceUiState() }
    fun selectList(id: String?) { _state.update { it.copy(selectedList = id) } }
    fun historyDays(days: Int) { if (days in listOf(7, 30)) { _state.update { it.copy(days = days) }; refresh(force = true) } }
    fun refresh(force: Boolean = false) {
        if (_state.value.owner.isBlank() || (!force && readJob?.isActive == true)) return
        if (force) readJob?.cancel()
        val owner = _state.value.owner
        val stamp = generation
        val days = _state.value.days
        val sequence = ++readSequence
        readJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val token = token(owner)
                val caps = client.readCapabilities(token)
                if ((stamp != generation || sequence != readSequence)) return@launch
                _state.update { it.copy(capabilities = caps) }
                check(caps.taskWorkspaceV1) { "This deployment does not advertise task workspace support. Check the Apps Script 2.7.0 deployment." }
                val json = client.readTaskWorkspaceJson(token)
                val workspace = parseTaskWorkspace(json)
                withContext(Dispatchers.IO) { storage.write(owner, "tasks", json.toString()) }
                if ((stamp != generation || sequence != readSequence)) return@launch
                _state.update { it.copy(workspace = workspace, cached = false,
                    selectedList = it.selectedList?.takeIf { id -> workspace.lists.any { list -> list.id == id } }) }
                if (caps.taskHistoryV1) {
                    try {
                        val history = client.readWorkspaceHistory(token, days)
                        if (stamp == generation && sequence == readSequence) _state.update { it.copy(history = history, historyError = null) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (stamp == generation && sequence == readSequence) _state.update { it.copy(historyError = "Completed history could not refresh; previous results retained.") }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (stamp == generation && sequence == readSequence) _state.update { it.copy(cached = true, error = e.message ?: "Tasks unavailable.") }
            } finally { if (stamp == generation && sequence == readSequence) _state.update { it.copy(loading = false) } }
        }
    }
    fun saveTask(listId: String, task: WorkspaceTask?, title: String, notes: String, due: String, onSuccess: () -> Unit) =
        mutate("Task saved", _state.value.capabilities.taskCrudV1, onSuccess) { token ->
            client.saveWorkspaceTask(token, listId, task?.id, title, notes, due, UUID.randomUUID().toString())
        }
    fun deleteTask(task: WorkspaceTask) = mutate("Task deleted", _state.value.capabilities.taskCrudV1) { client.deleteWorkspaceTask(it, task) }
    fun restoreTask(task: WorkspaceTask) = mutate("Task restored to ${task.listTitle}", _state.value.capabilities.taskHistoryV1) { client.restoreWorkspaceTask(it, task) }
    fun saveList(title: String, id: String?, onSuccess: () -> Unit) = mutate("Task list saved", _state.value.capabilities.taskListsV1, onSuccess) { client.saveWorkspaceList(it, title, id) }
    private fun mutate(message: String, available: Boolean, onSuccess: () -> Unit = {}, action: suspend (String) -> Unit) {
        if (!available || !_state.value.canWrite || _state.value.mutating) return
        val owner = _state.value.owner
        val stamp = generation
        _state.update { it.copy(mutating = true, error = null, message = null) }
        writeJob = viewModelScope.launch {
            try {
                action(token(owner))
                if (stamp == generation) { _state.update { it.copy(message = message) }; onSuccess(); refresh(force = true) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (stamp == generation) _state.update { it.copy(error = "${e.message ?: "Change not confirmed"} Check Tasks with Refresh before retrying.") }
            } finally { if (stamp == generation) _state.update { it.copy(mutating = false) } }
        }
    }
    private suspend fun token(owner: String): String {
        val credential = credentials.read() ?: error("Connect Google to continue.")
        check(credential.workspaceOwner() == owner) { "Account changed. Reopen Tasks." }
        check((credential.expiresAtEpochMs ?: 0) > System.currentTimeMillis()) { "Reconnect Google to refresh or change Tasks." }
        return credential.idToken
    }
}

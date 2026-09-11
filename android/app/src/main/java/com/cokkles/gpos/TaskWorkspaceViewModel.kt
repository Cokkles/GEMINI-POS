package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.interaction.AegisInteractionClient
import com.cokkles.gpos.data.interaction.InteractionCapabilities
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.local.DeferredMutation
import com.cokkles.gpos.data.local.DeferredMutationType
import com.cokkles.gpos.data.workspace.*
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.sync.DeferredMutationQueue
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
    private val queue = DeferredMutationQueue(app)
    private val _state = MutableStateFlow(TaskWorkspaceUiState())
    val state = _state.asStateFlow()
    private var activationJob: Job? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var generation = 0
    private var readSequence = 0

    fun activate(auth: AuthState) {
        if (auth == AuthState.Restoring || auth == AuthState.Authenticating) return
        if (auth !is AuthState.Authenticated && auth !is AuthState.OfflineRestored && auth !is AuthState.ReconnectRequired) { activationJob?.cancel(); detach(); return }
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
            _state.update { it.copy(canWrite = auth is AuthState.Authenticated || auth is AuthState.OfflineRestored || auth is AuthState.ReconnectRequired) }
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
    fun saveTask(listId: String, task: WorkspaceTask?, title: String, notes: String, due: String, onSuccess: () -> Unit) {
        if (!_state.value.capabilities.taskCrudV1 || !_state.value.canWrite || title.trim().isBlank()) return
        val now = System.currentTimeMillis()
        val localEntityId = task?.id ?: "local:${UUID.randomUUID()}"
        val listTitle = _state.value.workspace.lists.firstOrNull { it.id == listId }?.title.orEmpty()
        val optimistic = WorkspaceTask(localEntityId, listId, listTitle, title.trim(), notes, due)
        _state.update { current ->
            val tasks = current.workspace.tasks.filterNot { it.key == optimistic.key } + optimistic
            current.copy(workspace = current.workspace.copy(tasks = tasks), message = "Saved locally • sync pending", error = null)
        }
        persistWorkspace()
        queue.stage(DeferredMutation(
            id = UUID.randomUUID().toString(), owner = _state.value.owner,
            type = if (task == null || task.id.startsWith("local:")) DeferredMutationType.TASK_CREATE else DeferredMutationType.TASK_UPDATE,
            entityId = localEntityId, listId = listId, title = title.trim(), notes = notes, due = due,
            createdAtEpochMs = now, syncAfterEpochMs = now,
        ))
        onSuccess()
    }

    fun deleteTask(task: WorkspaceTask) {
        if (!_state.value.capabilities.taskCrudV1 || !_state.value.canWrite) return
        _state.update { it.copy(workspace = it.workspace.copy(tasks = it.workspace.tasks.filterNot { candidate -> candidate.key == task.key }), message = "Deleted locally • sync pending") }
        persistWorkspace()
        if (task.id.startsWith("local:")) queue.removeForEntity(_state.value.owner, task.id)
        else stage(DeferredMutationType.TASK_DELETE, task.id, task.listId, task.title)
    }

    fun restoreTask(task: WorkspaceTask) {
        if (!_state.value.capabilities.taskHistoryV1 || !_state.value.canWrite) return
        _state.update { it.copy(history = it.history.filterNot { candidate -> candidate.key == task.key }, message = "Restored locally • sync pending") }
        stage(DeferredMutationType.TASK_RESTORE, task.id, task.listId, task.title)
    }

    fun saveList(title: String, id: String?, onSuccess: () -> Unit) {
        if (!_state.value.capabilities.taskListsV1 || !_state.value.canWrite || title.trim().isBlank()) return
        val entityId = id ?: "local-list:${UUID.randomUUID()}"
        _state.update { current -> current.copy(
            workspace = current.workspace.copy(lists = current.workspace.lists.filterNot { it.id == entityId } + WorkspaceList(entityId, title.trim())),
            message = "Task list saved locally • sync pending", error = null,
        ) }
        persistWorkspace()
        stage(if (id == null) DeferredMutationType.LIST_CREATE else DeferredMutationType.LIST_RENAME, entityId, title = title.trim())
        onSuccess()
    }

    private fun stage(type: DeferredMutationType, entityId: String, listId: String = "", title: String = "") {
        val now = System.currentTimeMillis()
        queue.stage(DeferredMutation(UUID.randomUUID().toString(), _state.value.owner, type, entityId, listId,
            title, createdAtEpochMs = now, syncAfterEpochMs = now))
    }

    private fun persistWorkspace() {
        val owner = _state.value.owner
        val workspace = _state.value.workspace
        if (owner.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val json = JSONObject().put("contract", "AEGIS_TASK_WORKSPACE_V1")
                .put("lists", org.json.JSONArray(workspace.lists.map { JSONObject().put("id", it.id).put("title", it.title) }))
                .put("tasks", org.json.JSONArray(workspace.tasks.map { JSONObject()
                    .put("id", it.id).put("task_list_id", it.listId).put("task_list_title", it.listTitle)
                    .put("title", it.title).put("notes", it.notes).put("due", it.due).put("completed", it.completed) }))
            storage.write(owner, "tasks", json.toString())
        }
    }
    private suspend fun token(owner: String): String {
        val credential = credentials.read() ?: error("Connect Google to continue.")
        check(credential.workspaceOwner() == owner) { "Account changed. Reopen Tasks." }
        check((credential.expiresAtEpochMs ?: 0) > System.currentTimeMillis()) { "Reconnect Google to refresh or change Tasks." }
        return credential.authToken
    }
}


package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cokkles.gpos.data.command.AegisCommandClient
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.workspace.*
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.AuthContinuityPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class RunningNotesUiState(val owner: String = "", val document: RunningNotesDocument = RunningNotesDocument(),
    val ready: Boolean = false, val saving: Boolean = false, val syncing: Boolean = false,
    val error: String? = null, val message: String? = null)

class RunningNotesViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = ProtectedWorkspaceStore(app)
    private val credentials = AndroidKeystoreCredentialStore(app)
    private val client = AegisCommandClient()
    private val continuity = AuthContinuityPreferences(app)
    private val _state = MutableStateFlow(RunningNotesUiState())
    val state = _state.asStateFlow()
    private var activation: Job? = null
    private var syncJob: Job? = null
    private var generation = 0
    private data class Save(val owner: String, val doc: RunningNotesDocument, val result: CompletableDeferred<Unit>)
    // Drain accepted local saves even when the Activity/ViewModel is finally disposed.
    private val saves = Channel<Save>(Channel.UNLIMITED)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        writer.launch {
            for (save in saves) {
                try { storage.write(save.owner, "running_notes", save.doc.toJson()); save.result.complete(Unit) }
                catch (e: Exception) { save.result.completeExceptionally(e) }
            }
            writer.cancel()
        }
    }
    private var lastSave: Deferred<Unit>? = null
    fun activate(auth: AuthState) {
        if (auth == AuthState.Restoring || auth == AuthState.Authenticating) return
        val localOnly = auth == AuthState.SignedOut && continuity.wasAuthenticated()
        if (auth !is AuthState.Authenticated && auth !is AuthState.OfflineRestored && auth !is AuthState.ReconnectRequired && !localOnly) { detach(); return }
        activation?.cancel()
        activation = viewModelScope.launch {
            val credentialOwner = credentials.read()?.workspaceOwner().orEmpty()
            val owner = try {
                if (credentialOwner.isNotBlank()) {
                    withContext(Dispatchers.IO) { storage.write("device", "running_notes_owner", credentialOwner) }
                    credentialOwner
                } else if (localOnly) withContext(Dispatchers.IO) { storage.read("device", "running_notes_owner").orEmpty() }
                else ""
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = "Local workspace identity could not be opened. Drafts have been preserved.") }
                return@launch
            }
            if (localOnly && !continuity.wasAuthenticated()) { detach(); return@launch }
            if (owner.isBlank()) { detach(); return@launch }
            if (owner == _state.value.owner && _state.value.ready) return@launch
            generation++
            _state.value = RunningNotesUiState(owner = owner)
            try {
                lastSave?.await()
                val raw = withContext(Dispatchers.IO) { storage.read(owner, "running_notes") }
                _state.value = RunningNotesUiState(owner, raw?.let(RunningNotesDocument::parse) ?: RunningNotesDocument(), ready = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = "Protected draft could not be opened. Existing data has been preserved.") }
            }
        }
    }
    fun detach() {
        generation++; activation?.cancel(); syncJob?.cancel()
        _state.value = RunningNotesUiState()
    }
    fun edit(text: String) {
        if (!_state.value.ready || _state.value.syncing) return
        save(_state.value.document.copy(text = text.take(48000), updatedAt = System.currentTimeMillis()))
    }
    fun checkpoint() { if (_state.value.ready && !_state.value.syncing) save(_state.value.document.checkpoint()) }
    fun restore(id: String) { if (_state.value.ready && !_state.value.syncing) save(_state.value.document.restore(id)) }
    private fun save(doc: RunningNotesDocument): Deferred<Unit> {
        val owner = _state.value.owner
        val stamp = generation
        val result = CompletableDeferred<Unit>()
        _state.update { it.copy(document = doc, saving = true, error = null, message = null) }
        check(saves.trySend(Save(owner, doc, result)).isSuccess)
        lastSave = result
        viewModelScope.launch {
            try {
                result.await()
                if (stamp == generation && lastSave === result) _state.update { it.copy(saving = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (stamp == generation) _state.update { it.copy(saving = false, error = e.message ?: "Local save failed. Copy your draft before leaving.") }
            }
        }
        return result
    }
    fun sync() {
        val current = _state.value
        if (!current.ready || current.syncing || (current.document.text.isBlank() && current.document.pendingId == null)) return
        val stamp = generation
        val owner = current.owner
        _state.update { it.copy(syncing = true) }
        syncJob = viewModelScope.launch {
            try {
                val credential = credentials.read() ?: error("Connect Google to sync.")
                check(credential.workspaceOwner() == owner) { "Account changed. Reopen Running Notes." }
                check((credential.expiresAtEpochMs ?: 0) > System.currentTimeMillis()) { "Reconnect Google before syncing." }
                val pending = _state.value.document.beginSync()
                save(pending).await()
                client.submitRunningNotes(credential.idToken, pending)
                val confirmed = pending.confirmed()
                withContext(NonCancellable + Dispatchers.IO) { storage.write(owner, "running_notes", confirmed.toJson()) }
                if (stamp == generation) _state.update { it.copy(document = confirmed, message = if (confirmed.text.isBlank()) "Saved to Notes Journal. A fresh section is ready." else "Previous section saved. Review the remaining draft before syncing again.", error = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (stamp == generation) _state.update { it.copy(error = "${e.message ?: "Sync was not confirmed."} Your draft and submission are preserved. Check the journal before retrying.") }
            } finally { if (stamp == generation) _state.update { it.copy(syncing = false, saving = false) } }
        }
    }
    fun markPendingAsSynced() {
        if (_state.value.document.pendingId != null && !_state.value.syncing) save(_state.value.document.confirmed())
    }
    override fun onCleared() { saves.close(); super.onCleared() }
}

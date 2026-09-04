package com.cokkles.gpos.ui.daily

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.cokkles.gpos.*
import com.cokkles.gpos.data.workspace.*
import com.cokkles.gpos.data.local.LocalReceiptState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val CalendarAccent = Color(0xFF78B7FF)
internal val TaskAccent = Color(0xFF58CCB5)
internal val NotesAccent = Color(0xFFE8B764)
internal val NewsAccent = Color(0xFFC2A3F5)

@Composable
internal fun AegisCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    accent: Color = MaterialTheme.colorScheme.outline, content: @Composable ColumnScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.surface
    val colors = CardDefaults.cardColors(containerColor = lerp(background, accent, 0.055f))
    val border = BorderStroke(1.dp, accent.copy(alpha = .34f))
    if (onClick != null) Card(onClick = onClick, modifier = modifier, colors = colors, border = border, content = content)
    else Card(modifier = modifier, colors = colors, border = border, content = content)
}

@Composable
internal fun WorkspaceHeading(title: String, icon: ImageVector, accent: Color, onOpen: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val color = if (MaterialTheme.colorScheme.background == Color(0xFFFFFBFF)) MaterialTheme.colorScheme.primary else accent
    Column(modifier.then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(top = 12.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = color)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (onOpen != null) IconButton(onClick = onOpen) { Icon(Icons.Outlined.KeyboardArrowRight, "Open $title", tint = color) }
        }
        HorizontalDivider(color = accent.copy(alpha = .45f), thickness = 1.dp)
    }
}

@Composable
internal fun TaskListPicker(lists: List<WorkspaceList>, selected: String?, includeAll: Boolean, onSelect: (String?) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("task_list_picker")) {
            Icon(Icons.Outlined.List, null)
            Text(lists.firstOrNull { it.id == selected }?.title ?: if (includeAll) "All lists" else "Choose list", Modifier.weight(1f).padding(horizontal = 8.dp))
            Icon(Icons.Outlined.ArrowDropDown, "Choose task list")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (includeAll) DropdownMenuItem(text = { Text("All lists") }, onClick = { onSelect(null); open = false })
            lists.forEach { list -> DropdownMenuItem(text = { Text(list.title) }, onClick = { onSelect(list.id); open = false }) }
        }
    }
}

@Composable
internal fun WorkspaceTasksScreen(state: TaskWorkspaceUiState, queue: TaskQueueUiState, vm: TaskWorkspaceViewModel,
    stage: (WorkspaceTask) -> Unit, undo: (String) -> Unit, sync: () -> Unit) {
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkspaceTask?>(null) }
    var deleting by remember { mutableStateOf<WorkspaceTask?>(null) }
    var listEditor by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<WorkspaceList?>(null) }
    var listName by rememberSaveable { mutableStateOf("") }
    val available = state.canWrite && !state.mutating
    val tasks = state.workspace.tasks.filter { state.selectedList == null || it.listId == state.selectedList }
    val pending = queue.ledger.pendingTasks.filter { it.owner == state.owner || it.owner.isBlank() }
    LazyColumn(Modifier.fillMaxSize().testTag("tasks_workspace"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            WorkspaceHeading("Google Tasks", Icons.Outlined.CheckCircle, TaskAccent)
            Text(if (state.cached) "Saved on this device · refresh to check changes" else "${state.workspace.tasks.size} active · ${state.workspace.lists.size} lists", style = MaterialTheme.typography.bodySmall)
        }
        item { TaskListPicker(state.workspace.lists, state.selectedList, true, vm::selectList) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = null; editor = true }, enabled = available && state.capabilities.taskCrudV1 && state.workspace.lists.isNotEmpty(), modifier = Modifier.weight(1f).testTag("add_task")) { Text("Add task") }
                OutlinedButton(onClick = { renaming = null; listName = ""; listEditor = true }, enabled = available && state.capabilities.taskListsV1, modifier = Modifier.weight(1f)) { Text("New list") }
            }
        }
        state.workspace.lists.firstOrNull { it.id == state.selectedList }?.let { selected ->
            item { TextButton(onClick = { renaming = selected; listName = selected.title; listEditor = true }, enabled = available && state.capabilities.taskListsV1) { Text("Rename ${selected.title}") } }
        }
        if (state.loading || state.mutating) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (tasks.isEmpty()) item { Text(if (state.owner.isBlank()) "Connect Google in Settings to load your task lists." else "No active tasks in this view.") }
        items(tasks, key = { it.key }) { task ->
            val queued = pending.firstOrNull { it.taskId == task.id && it.taskListId == task.listId }
            val sending = queued?.let { p -> queue.ledger.receipts.any { it.id == p.id && it.state == LocalReceiptState.SENDING } } == true
            AegisCard(Modifier.fillMaxWidth(), accent = TaskAccent) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = queued != null, onCheckedChange = { if (queued == null) stage(task) }, enabled = available && state.capabilities.taskActionV1 && queued == null && !queue.syncing)
                    Column(Modifier.weight(1f).padding(top = 10.dp, end = 8.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text(task.listTitle + task.due.takeIf { it.isNotBlank() }?.let { " · Due ${it.take(10)}" }.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (queued != null) Text(if (sending) "Syncing completion…" else "Completion queued · five-minute Undo", style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (queued != null) TextButton(onClick = { undo(queued.id) }, enabled = !sending) { Text("Undo") }
                            else {
                                TextButton(onClick = { editing = task; editor = true }, enabled = available && state.capabilities.taskCrudV1) { Text("Edit") }
                                TextButton(onClick = { deleting = task }, enabled = available && state.capabilities.taskCrudV1) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refresh() }, enabled = state.canWrite && !state.loading, modifier = Modifier.weight(1f)) { Text("Refresh lists") }
                Button(onClick = sync, enabled = available && !queue.syncing, modifier = Modifier.weight(1f)) { Text("Sync queued (${pending.size})") }
            }
            Text("Sync queued sends completions now. Refresh only reads your lists.", style = MaterialTheme.typography.bodySmall)
        }
        queue.lastMessage?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item { WorkspaceHeading("Completed history", Icons.Outlined.History, TaskAccent) }
        if (state.capabilities.taskHistoryV1) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(7,30).forEach { days -> FilterChip(state.days == days, { vm.historyDays(days) }, label = { Text("$days days") }) } } }
            state.historyError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            val history = state.history.filter { state.selectedList == null || it.listId == state.selectedList }
            if (history.isEmpty()) item { Text("No completed tasks in this period.") }
            items(history, key = { "completed/${it.key}" }) { task ->
                AegisCard(Modifier.fillMaxWidth(), accent = TaskAccent) {
                    Column(Modifier.padding(14.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text("${task.listTitle} · ${task.completed.take(10)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.restoreTask(task) }, enabled = available) { Text("Restore to ${task.listTitle}") }
                    }
                }
            }
        } else item { Text("Completed history is available when advertised by your backend.", style = MaterialTheme.typography.bodySmall) }
    }
    if (editor) TaskEditor(editing, state, { editor = false }) { listId, title, notes, due -> vm.saveTask(listId, editing, title, notes, due) { editor = false } }
    deleting?.let { task -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete task?") }, text = { Text("${task.title}\nFrom ${task.listTitle}. This cannot be undone.") }, confirmButton = { TextButton(onClick = { deleting = null; vm.deleteTask(task) }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    if (listEditor) AlertDialog(onDismissRequest = { if (!state.mutating) listEditor = false }, title = { Text(if (renaming == null) "New task list" else "Rename list") }, text = {
        Column { OutlinedTextField(listName, { listName = it.take(100) }, enabled = !state.mutating, label = { Text("List name") }); state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }, confirmButton = { TextButton(onClick = { vm.saveList(listName, renaming?.id) { listEditor = false } }, enabled = available && listName.isNotBlank()) { Text(if (state.mutating) "Saving…" else "Save") } }, dismissButton = { TextButton(onClick = { listEditor = false }, enabled = !state.mutating) { Text("Cancel") } })
}

@Composable
private fun TaskEditor(task: WorkspaceTask?, state: TaskWorkspaceUiState, dismiss: () -> Unit, save: (String, String, String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(task?.title.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(task?.notes.orEmpty()) }
    var due by rememberSaveable { mutableStateOf(task?.due?.take(10).orEmpty()) }
    var listId by rememberSaveable { mutableStateOf(task?.listId ?: state.selectedList ?: state.workspace.lists.firstOrNull()?.id) }
    val validDate = due.isBlank() || runCatching { LocalDate.parse(due) }.isSuccess
    AlertDialog(onDismissRequest = { if (!state.mutating) dismiss() }, title = { Text(if (task == null) "Add task" else "Edit task") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { if (task == null) TaskListPicker(state.workspace.lists, listId, false, { listId = it }, enabled = !state.mutating) else Text(task.listTitle) }
            item { OutlinedTextField(title, { title = it.take(1024) }, enabled = !state.mutating, label = { Text("Title") }, modifier = Modifier.testTag("task_title")) }
            item { OutlinedTextField(notes, { notes = it.take(8000) }, enabled = !state.mutating, label = { Text("Notes") }, minLines = 3) }
            item { OutlinedTextField(due, { due = it.take(10) }, enabled = !state.mutating, label = { Text("Due date · YYYY-MM-DD") }, supportingText = { Text("Leave empty for no due date") }, isError = !validDate, singleLine = true) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }, confirmButton = { TextButton(onClick = { listId?.let { save(it, title, notes, due) } }, enabled = state.canWrite && !state.mutating && title.isNotBlank() && listId != null && validDate) { Text(if (state.mutating) "Saving…" else "Save") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !state.mutating) { Text("Cancel") } })
}

@Composable
internal fun RunningNotesScreen(state: RunningNotesUiState, vm: RunningNotesViewModel, canSync: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var confirm by remember { mutableStateOf(false) }
    var reconcile by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf<NoteRevision?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    val doc = state.document
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            WorkspaceHeading("Running Notes", Icons.Outlined.EditNote, NotesAccent)
            Text("A place to think throughout your day. Saved on this device; sync when you're ready.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text(when { state.syncing -> "Syncing to Notes Journal…"; state.saving -> "Saving locally…"; !state.ready -> "Connect Google to open your protected draft"; doc.pendingId != null -> "Previous sync needs review · draft preserved"; doc.text.isBlank() && doc.lastSyncedAt > 0 -> "Synced ${noteTime(doc.lastSyncedAt)} · ready for a new section"; doc.updatedAt > 0 -> "Saved locally · ${noteTime(doc.updatedAt)}"; else -> "Local autosave ready" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it) }
        }
        item {
            OutlinedTextField(value = doc.text, onValueChange = vm::edit, modifier = Modifier.fillMaxWidth().testTag("running_notes_editor"),
                minLines = 12, label = { Text("Your working draft") }, enabled = state.ready && !state.syncing,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                supportingText = { Text("${doc.text.length} / 48,000 · local autosave") })
        }
        item {
            Button(onClick = { confirm = true }, enabled = canSync && state.ready && !state.syncing && (doc.text.isNotBlank() || doc.pendingId != null), modifier = Modifier.fillMaxWidth()) { Text(if (doc.pendingId != null) "Review pending sync" else "Sync to Notes Journal") }
            if (doc.pendingId != null) TextButton(onClick = { reconcile = true }, enabled = !state.syncing) { Text("I verified this entry is already in my journal") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::checkpoint, enabled = state.ready && !state.syncing && doc.text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Checkpoint") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(doc.text)) }, enabled = doc.text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, doc.text), "Share Running Notes")) }, enabled = doc.text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Share") }
            }
        }
        item { TextButton(onClick = { history = !history }) { Text("${if (history) "Hide" else "Show"} recovery history · ${doc.revisions.size}") } }
        if (history) items(doc.revisions, key = { it.id }) { revision ->
            AegisCard(Modifier.fillMaxWidth(), accent = NotesAccent) { Column(Modifier.padding(14.dp)) {
                Text("${revision.kind} · ${noteTime(revision.time)}", style = MaterialTheme.typography.labelLarge)
                Text(revision.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { restore = revision }, enabled = !state.syncing) { Text("Restore copy") }
            } }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (doc.pendingId == null) "Sync this section?" else "Retry previous submission?") }, text = {
        Text(if (doc.pendingId == null) "Append this section to your Notes Journal. A recovery copy stays on this device; a fresh section begins after confirmed success."
        else "The previous request may already be in your journal. Check it before retrying, since the backend cannot prevent duplicates. Retry sends the original pending section (${doc.pendingText?.length ?: 0} characters); newer edits remain in the editor.")
    }, confirmButton = { TextButton(onClick = { confirm = false; vm.sync() }) { Text(if (doc.pendingId == null) "Sync section" else "Retry original section") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    if (reconcile) AlertDialog(onDismissRequest = { reconcile = false }, title = { Text("Confirm entry exists in journal?") }, text = { Text("Only confirm after you have checked the previous entry. This records it as synced locally without sending it again.") }, confirmButton = { TextButton(onClick = { reconcile = false; vm.markPendingAsSynced() }) { Text("Entry verified") } }, dismissButton = { TextButton(onClick = { reconcile = false }) { Text("Cancel") } })
    restore?.let { revision -> AlertDialog(onDismissRequest = { restore = null }, title = { Text("Restore a recovery copy?") }, text = { Text("Your current draft is checkpointed first. Restoring does not submit anything to the journal.") }, confirmButton = { TextButton(onClick = { vm.restore(revision.id); restore = null }) { Text("Restore copy") } }, dismissButton = { TextButton(onClick = { restore = null }) { Text("Cancel") } }) }
}
private fun noteTime(time: Long): String = DateTimeFormatter.ofPattern("MMM d · h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

package com.cokkles.gpos.data.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Composite identity is required even when different lists return the same task ID. */
data class WorkspaceTask(val id: String, val listId: String, val listTitle: String, val title: String,
    val notes: String = "", val due: String = "", val completed: String = "") {
    val key: String get() = "$listId/$id"
}
data class WorkspaceList(val id: String, val title: String)
data class TaskWorkspace(val lists: List<WorkspaceList> = emptyList(), val tasks: List<WorkspaceTask> = emptyList())

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)
internal fun parseWorkspaceTasks(array: JSONArray?): List<WorkspaceTask> = array.objects().map { t ->
    WorkspaceTask(t.string("id"), t.string("task_list_id"), t.string("task_list_title"),
        t.string("title"), t.string("notes"), t.string("due"), t.string("completed"))
}.filter { it.id.isNotBlank() && it.listId.isNotBlank() }.distinctBy { it.key }
internal fun parseTaskWorkspace(json: JSONObject): TaskWorkspace {
    require(json.optString("contract") == "AEGIS_TASK_WORKSPACE_V1") { "Task workspace contract is unavailable." }
    return TaskWorkspace(json.optJSONArray("lists").objects().map {
        WorkspaceList(it.string("id"), it.string("title"))
    }.filter { it.id.isNotBlank() }, parseWorkspaceTasks(json.optJSONArray("tasks")))
}

internal object WorkspacePayloads {
    fun task(action: String, listId: String, taskId: String = ""): JSONObject {
        require(action in setOf("create_task", "update_task", "delete_task", "restore_task"))
        require(listId.isNotBlank()) { "Choose a task list." }
        require(action == "create_task" || taskId.isNotBlank()) { "Task ID is required." }
        return JSONObject().put("action", action).put("task_list_id", listId).apply {
            if (taskId.isNotBlank()) put("task_id", taskId)
        }
    }
    fun save(listId: String, taskId: String?, title: String, notes: String, due: String, localId: String): JSONObject {
        require(title.trim().isNotEmpty() && title.trim().length <= 1024) { "Enter a title of 1–1,024 characters." }
        require(notes.length <= 8000) { "Task notes must be at most 8,000 characters." }
        val date = due.trim().takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).toString() + "T00:00:00.000Z" }
        return task(if (taskId == null) "create_task" else "update_task", listId, taskId.orEmpty())
            .put("title", title.trim()).put("notes", notes).put("local_id", localId).apply {
                if (date != null) put("due", date) else if (taskId != null) put("clear_due", true)
            }
    }
    fun list(title: String, id: String?): JSONObject {
        require(title.trim().length in 1..100) { "List title must be 1–100 characters." }
        require(id == null || id.isNotBlank())
        return JSONObject().put("action", if (id == null) "create_task_list" else "rename_task_list")
            .put("title", title.trim()).apply { if (id != null) put("task_list_id", id) }
    }
}

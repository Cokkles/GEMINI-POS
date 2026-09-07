package com.cokkles.gpos.data.workspace

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object HomeTaskVisibilityPolicy {
    fun visibleLists(lists: List<WorkspaceList>, hiddenIds: Set<String>): List<WorkspaceList> =
        lists.filterNot { it.id in hiddenIds }

    fun groupedTasks(workspace: TaskWorkspace, hiddenIds: Set<String>): List<Pair<WorkspaceList, List<WorkspaceTask>>> =
        visibleLists(workspace.lists, hiddenIds).mapNotNull { list ->
            workspace.tasks.filter { it.listId == list.id }.takeIf { it.isNotEmpty() }?.let { list to it }
        }
}

class HomeTaskPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aegis_home_tasks", Context.MODE_PRIVATE)
    private val _hiddenListIds = MutableStateFlow(prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet())
    val hiddenListIds = _hiddenListIds.asStateFlow()

    fun setVisible(listId: String, visible: Boolean) {
        val next = if (visible) _hiddenListIds.value - listId else _hiddenListIds.value + listId
        prefs.edit().putStringSet(KEY_HIDDEN, next).apply()
        _hiddenListIds.value = next
    }

    private companion object { const val KEY_HIDDEN = "hidden_list_ids" }
}

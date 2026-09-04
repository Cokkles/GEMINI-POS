package com.cokkles.gpos.data.workspace

import com.cokkles.gpos.data.command.AegisCommandPayloads
import com.cokkles.gpos.data.remote.IntelligenceItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceContractsTest {
    @Test fun workspaceRetainsSameTaskIdInDifferentListsAndNullFields() {
        val workspace = parseTaskWorkspace(JSONObject("""{"contract":"AEGIS_TASK_WORKSPACE_V1","lists":[{"id":"a","title":"Work"},{"id":"b","title":"Home"}],"tasks":[{"id":"same","task_list_id":"a","title":"One","due":null},{"id":"same","task_list_id":"b","title":"Two"}]}"""))
        assertEquals(2, workspace.tasks.size)
        assertNotEquals(workspace.tasks[0].key, workspace.tasks[1].key)
        assertEquals("", workspace.tasks[0].due)
    }
    @Test fun everyTaskMutationKeepsListIdentityAndDueClearingIsExplicit() {
        val update = WorkspacePayloads.save("other-list", "task", "Title", "notes", "", "local")
        assertEquals("other-list", update.getString("task_list_id"))
        assertTrue(update.getBoolean("clear_due"))
        assertEquals("2026-09-10T00:00:00.000Z", WorkspacePayloads.save("other-list", null, "Title", "", "2026-09-10", "local").getString("due"))
        listOf("delete_task", "restore_task").forEach { assertEquals("other-list", WorkspacePayloads.task(it, "other-list", "task").getString("task_list_id")) }
        assertEquals("other-list", AegisCommandPayloads.completeTasks(listOf("task"), "other-list").getString("task_list_id"))
        assertEquals("other-list", WorkspacePayloads.list("Renamed", "other-list").getString("task_list_id"))
    }
    @Test(expected = IllegalArgumentException::class) fun missingListCannotFallBackToDefault() { WorkspacePayloads.task("delete_task", "", "task") }
    @Test fun runningNotesRoundTripPendingRetryAndEditedDraft() {
        val pending = RunningNotesDocument(text = "Morning plan").beginSync()
        val reopened = RunningNotesDocument.parse(pending.toJson())
        assertEquals(pending, reopened)
        assertEquals(pending.pendingId, reopened.beginSync().pendingId)
        val edited = reopened.copy(text = "Morning plan\nLater idea")
        assertEquals("Later idea", edited.confirmed().text)
        assertEquals("Rewritten plan", reopened.copy(text = "Rewritten plan").confirmed().text)
        assertEquals(pending.pendingId, reopened.confirmed().revisions.first().submissionId)
        assertEquals("", reopened.confirmed().text)
        assertTrue(reopened.confirmed().revisions.any { it.text == "Morning plan" && it.kind == "Synced" })
        val payload = runningNotesPayload(reopened)
        assertEquals(pending.pendingId, payload.getString("submission_id"))
        assertTrue(payload.getString("message").startsWith("/note [RUNNING_NOTES]"))
    }
    @Test fun restorePreservesCurrentDraftAsRecoveryCopy() {
        val saved = RunningNotesDocument(text = "Original").checkpoint().copy(text = "Newer")
        val restored = saved.restore(saved.revisions.first().id)
        assertEquals("Original", restored.text)
        assertTrue(restored.revisions.any { it.text == "Newer" })
    }
    @Test fun dealsNeverLeakThroughFallbackAndPreferencePrecedesLimit() {
        fun story(category: String, title: String) = IntelligenceItem(category = category, title = title, link = "https://example.com/$title", source = "Test", publishedAtEpochMs = null)
        val items = listOf(story("Deals", "sale"), story("Tech", "news"), story("Deals & Savings", "offer"))
        assertEquals(listOf("news"), HeadlinerPolicy.select(items, emptyMap(), 5).map { it.title })
        assertTrue(HeadlinerPolicy.select(items, mapOf("tech" to false), 5).isEmpty())
        assertEquals("sale", HeadlinerPolicy.select(items, mapOf("deals" to true), 1).first().title)
    }
}

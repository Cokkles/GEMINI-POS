package com.cokkles.gpos.data.interaction

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AegisInteractionBoundaryTest {
    @Test
    fun `task creation uses only the advertised task action contract`() {
        val payload = InteractionPayloads.createTask(
            title = "Buy filters",
            notes = "For the office",
            localId = "local-1",
        )

        assertEquals("create_task", payload.getString("action"))
        assertEquals("Buy filters", payload.getString("title"))
        assertEquals("For the office", payload.getString("notes"))
        assertEquals("local-1", payload.getString("local_id"))
        assertFalse(payload.has("auth_token"))
    }

    @Test
    fun `ai query is read only shaped and bounds session history`() {
        val history = (1..12).map { index ->
            AiChatMessage(if (index % 2 == 0) "assistant" else "user", "message-$index")
        }
        val payload = InteractionPayloads.aiQuery(
            question = "What should I focus on today?",
            mode = "general",
            history = history,
        )

        assertEquals("ai_query", payload.getString("action"))
        assertEquals("general", payload.getString("mode"))
        assertEquals(8, payload.getJSONArray("history").length())
        assertFalse(payload.has("auth_token"))
    }

    @Test
    fun `calendar confirmation requires a one shot backend token envelope`() {
        val payload = InteractionPayloads.calendarConfirm("token-123")

        assertEquals("calendar_confirm", payload.getString("action"))
        assertEquals("token-123", payload.getString("confirmation_token"))
        assertFalse(payload.has("auth_token"))
    }

    @Test
    fun `interaction client exposes only finite typed shared contracts`() {
        val publicDeclaredMethods = AegisInteractionClient::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                "readCapabilities",
                "readTaskWorkspace",
                "readTaskWorkspaceJson",
                "readWorkspaceHistory",
                "saveWorkspaceTask",
                "deleteWorkspaceTask",
                "restoreWorkspaceTask",
                "saveWorkspaceList",
                "readFollowups",
                "createTask",
                "resolveFollowup",
                "dismissFollowup",
                "promoteFollowupToTask",
                "askAegis",
                "prepareCalendar",
                "confirmCalendar",
            ),
            publicDeclaredMethods,
        )
        assertTrue(publicDeclaredMethods.none { it.equals("execute", ignoreCase = true) })
        assertTrue(publicDeclaredMethods.none { it.equals("action", ignoreCase = true) })
    }
}

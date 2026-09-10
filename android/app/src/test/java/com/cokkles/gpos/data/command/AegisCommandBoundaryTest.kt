package com.cokkles.gpos.data.command

import java.lang.reflect.Modifier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AegisCommandBoundaryTest {
    @Test
    fun `task completion payload contains only explicit production envelope`() {
        val payload = AegisCommandPayloads.completeTasks(listOf(" task-1 ", "task-1", "task-2"))

        assertEquals("mark_done", payload.getString("action"))
        assertEquals("mark_done:", payload.getString("message"))
        val ids = payload.getJSONArray("completedTasks")
        assertEquals(2, ids.length())
        assertEquals("task-1", ids.getString(0))
        assertEquals("task-2", ids.getString(1))
        assertFalse(payload.has("auth_token"))
    }

    @Test
    fun `task completion rejects empty selection`() {
        assertThrows(IllegalArgumentException::class.java) {
            AegisCommandPayloads.completeTasks(listOf(" ", ""))
        }
    }

    @Test
    fun `calendar resolution rejects blank text`() {
        assertThrows(IllegalArgumentException::class.java) {
            AegisCommandPayloads.resolveCalendar("   ")
        }
    }

    @Test
    fun `calendar proposal round trips only reviewed fields`() {
        val original = ResolvedCalendarEvent(
            title = "Design review",
            start = "2026-09-01T14:00:00-04:00",
            end = "2026-09-01T15:00:00-04:00",
            location = "Office",
            description = "Review the proposal",
        )

        assertEquals(original, ResolvedCalendarEvent.fromJson(original.toJson()))
        assertEquals("create_calendar_event", AegisCommandPayloads.createCalendar(original).getString("action"))
    }

    @Test
    fun `notification acknowledgement rejects blank identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            AegisCommandPayloads.acknowledgeNotification(" ")
        }
    }

    @Test
    fun `notification acknowledgement requires success status and matching id`() {
        val success = AegisCommandPayloads.parseNotificationAcknowledgement(
            JSONObject("{\"status\":\"success\",\"notificationId\":\"n-1\"}"),
            "n-1",
        )
        val notFound = AegisCommandPayloads.parseNotificationAcknowledgement(
            JSONObject("{\"status\":\"not_found\",\"notificationId\":\"n-1\"}"),
            "n-1",
        )
        val mismatched = AegisCommandPayloads.parseNotificationAcknowledgement(
            JSONObject("{\"status\":\"success\",\"notificationId\":\"n-2\"}"),
            "n-1",
        )

        assertTrue(success.acknowledged)
        assertFalse(notFound.acknowledged)
        assertFalse(mismatched.acknowledged)
    }

    @Test
    fun `command client has a finite public mutation surface`() {
        val publicDeclaredMethods = AegisCommandClient::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                "completeTasks",
                "resolveCalendarEvent",
                "createCalendarEvent",
                "submitCapture",
                "enqueueNutritionCapture",
                "readNutritionCaptureStatus",
                "retryNutritionCapture",
                "submitRunningNotes",
                "acknowledgeNotification",
            ),
            publicDeclaredMethods,
        )
        assertTrue(publicDeclaredMethods.none { it.contains("execute", ignoreCase = true) })
        assertTrue(publicDeclaredMethods.none { it.contains("action", ignoreCase = true) })
    }
}

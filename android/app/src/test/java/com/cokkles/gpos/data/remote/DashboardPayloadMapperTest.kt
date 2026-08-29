package com.cokkles.gpos.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPayloadMapperTest {
    @Test
    fun `maps only bounded dashboard fields used by production PWA`() {
        val json = JSONObject(
            """
            {
              "status":"success",
              "calendar":{
                "today":[{"title":"Morning sync","time":"9:00 AM","note":"Daily"}],
                "tomorrow":[{"id":"cal-2","title":"Review","time":"2:30 PM"}]
              },
              "tasks":[
                {"id":"task-1","title":"Validate Android checkpoint","time":"Google Task"},
                {"title":"Prepare notes"}
              ],
              "briefing":{
                "plain_text":"# HORIZON\nCurrent briefing body",
                "last_updated":"2026-08-29T14:00:00Z"
              },
              "system_metadata":{
                "last_updated":"2026-08-29T14:05:00Z",
                "horizon_generation":{
                  "last_success":"2026-08-29T13:55:00Z",
                  "mode":"scheduled"
                }
              },
              "unproven_future_field":{"must":"be ignored"}
            }
            """.trimIndent(),
        )

        val mapped = DashboardPayloadMapper.map(json)

        assertEquals(1, mapped.todayEvents.size)
        assertEquals("Morning sync", mapped.todayEvents.single().title)
        assertEquals("9:00 AM", mapped.todayEvents.single().timeLabel)
        assertEquals(DashboardDay.TODAY, mapped.todayEvents.single().day)
        assertTrue(mapped.todayEvents.single().id.isNotBlank())

        assertEquals("cal-2", mapped.tomorrowEvents.single().id)
        assertEquals(2, mapped.tasks.size)
        assertEquals("task-1", mapped.tasks.first().id)
        assertNotEquals("", mapped.tasks.last().id)

        assertEquals("# HORIZON\nCurrent briefing body", mapped.briefingPlainText)
        assertEquals("scheduled", mapped.horizonMode)
        assertEquals(1788012000000L, mapped.briefingUpdatedAtEpochMs)
        assertEquals(1788011700000L, mapped.horizonLastSuccessAtEpochMs)
        assertEquals(1788012300000L, mapped.backendReportedUpdatedAtEpochMs)
    }

    @Test
    fun `missing optional dashboard sections degrade to empty collections`() {
        val mapped = DashboardPayloadMapper.map(JSONObject("{\"status\":\"success\"}"))

        assertTrue(mapped.todayEvents.isEmpty())
        assertTrue(mapped.tomorrowEvents.isEmpty())
        assertTrue(mapped.tasks.isEmpty())
        assertNull(mapped.briefingPlainText)
        assertNull(mapped.horizonMode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `error payload fails closed`() {
        DashboardPayloadMapper.map(
            JSONObject("{\"status\":\"error\",\"error\":\"not authorized\"}"),
        )
    }
}

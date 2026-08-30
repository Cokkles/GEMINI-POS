package com.cokkles.gpos.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationsPayloadMapperTest {
    @Test
    fun mapsServerAlertsAndKeepsAcknowledgementReadOnly() {
        val json = JSONObject(
            """
            {
              "status":"success",
              "notifications":[
                {
                  "id":"alert-1",
                  "title":"Calendar attention",
                  "message":"Review upcoming appointment",
                  "severity":"critical",
                  "type":"calendar",
                  "createdAt":"2026-08-30T01:00:00Z",
                  "acknowledged":false,
                  "detail":"Server-provided detail"
                },
                {
                  "title":"Already handled",
                  "message":"Old item",
                  "severity":"info",
                  "type":"system",
                  "createdAt":"2026-08-29T01:00:00Z",
                  "acknowledged":true
                }
              ]
            }
            """.trimIndent(),
        )

        val value = NotificationsPayloadMapper.map(json)

        assertEquals(2, value.notifications.size)
        assertEquals(1, value.active.size)
        assertEquals(1, value.activeCriticalCount)
        assertEquals(NotificationSeverity.CRITICAL, value.active.single().severity)
        assertFalse(value.active.single().acknowledged)
    }
}

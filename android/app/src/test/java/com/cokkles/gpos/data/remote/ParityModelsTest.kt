package com.cokkles.gpos.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParityModelsTest {
    @Test
    fun capabilitiesFailClosedWhenOptionalContractsAreAbsent() {
        val snapshot = CapabilityPayloadMapper.map(
            JSONObject("""{"status":"success","features":{"intelligence_v24":true}}"""),
        )
        assertFalse(snapshot.tasksHistoryV1)
        assertFalse(snapshot.nutritionHistoryV1)
        assertTrue("intelligence_v24" in snapshot.advertisedFeatures)
    }

    @Test
    fun capabilitiesAdvertiseOptionalContractsExplicitly() {
        val snapshot = CapabilityPayloadMapper.map(
            JSONObject(
                """{"ux_contracts":{"tasks_history_v1":true,"nutrition_history_v1":true}}""",
            ),
        )
        assertTrue(snapshot.tasksHistoryV1)
        assertTrue(snapshot.nutritionHistoryV1)
    }

    @Test
    fun nutritionPreservesMissingVersusVerifiedZero() {
        val snapshot = NutritionPayloadMapper.map(
            JSONObject(
                """{
                    "days":7,
                    "today":{"date":"2026-08-31","calories":0,"protein":null},
                    "daily":[],
                    "meals":[]
                }""",
            ),
            7,
        )
        assertEquals(0.0, snapshot.today?.calories ?: -1.0, 0.0)
        assertNull(snapshot.today?.protein)
    }

    @Test
    fun calendarRangeMapsBoundedEventShape() {
        val snapshot = CalendarRangePayloadMapper.map(
            JSONObject(
                """{
                    "events":[{
                        "id":"e1",
                        "title":"Dentist",
                        "start":"2026-09-02T14:00:00-04:00",
                        "end":"2026-09-02T15:00:00-04:00",
                        "local_date":"2026-09-02",
                        "local_time":"2:00 PM",
                        "all_day":false
                    }]
                }""",
            ),
            "2026-08-31",
            "2026-10-12",
        )
        assertEquals(1, snapshot.events.size)
        assertEquals("2026-09-02", snapshot.events.single().localDate)
        assertEquals("Dentist", snapshot.events.single().title)
    }
}

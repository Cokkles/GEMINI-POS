package com.cokkles.gpos.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinancePayloadMapperTest {
    @Test
    fun mapsOnlyProvenRecentFinanceFields() {
        val json = JSONObject(
            """
            {
              "status":"success",
              "updated":"2026-08-30T01:00:00Z",
              "summary":{"purchaseTotal":42.5,"creditTotal":-7.25,"futureField":999},
              "transactions":[
                {
                  "vendor":"Example Store",
                  "amount":21.25,
                  "category":"Household",
                  "paymentSource":"Card",
                  "occurredAt":"2026-08-30T00:30:00Z",
                  "notes":"Test row",
                  "unknown":"ignored"
                }
              ]
            }
            """.trimIndent(),
        )

        val value = FinancePayloadMapper.map(json, 72)

        assertEquals(42.5, value.summary.purchaseTotal!!, 0.001)
        assertEquals(-7.25, value.summary.creditTotal!!, 0.001)
        assertEquals(1, value.transactions.size)
        assertEquals("Example Store", value.transactions.single().vendor)
        assertEquals("Household", value.transactions.single().category)
        assertEquals(72, value.hours)
    }

    @Test
    fun permitsMissingOptionalSummaryNumbers() {
        val value = FinancePayloadMapper.map(JSONObject("{\"transactions\":[],\"summary\":{}}"))
        assertNull(value.summary.purchaseTotal)
        assertNull(value.summary.creditTotal)
    }
}

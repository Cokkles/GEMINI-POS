package com.cokkles.gpos.data.remote

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class FinanceTransaction(
    val vendor: String,
    val amount: Double,
    val category: String,
    val paymentSource: String,
    val occurredAtEpochMs: Long?,
    val notes: String?,
)

data class FinanceSummary(
    val purchaseTotal: Double?,
    val creditTotal: Double?,
)

data class FinanceSnapshot(
    val transactions: List<FinanceTransaction>,
    val summary: FinanceSummary,
    val updatedAtEpochMs: Long?,
    val hours: Int = 72,
)

object FinancePayloadMapper {
    fun map(json: JSONObject, hours: Int = 72): FinanceSnapshot {
        if (json.optString("status").equals("error", ignoreCase = true)) {
            throw IllegalArgumentException(
                json.optString("error").ifBlank { "Finance response reported an error." },
            )
        }
        val transactions = json.optJSONArray("transactions")
            ?: throw IllegalArgumentException("Finance response did not contain transactions.")
        val summaryJson = json.optJSONObject("summary") ?: JSONObject()
        return FinanceSnapshot(
            transactions = mapTransactions(transactions),
            summary = FinanceSummary(
                purchaseTotal = summaryJson.numberOrNull("purchaseTotal"),
                creditTotal = summaryJson.numberOrNull("creditTotal"),
            ),
            updatedAtEpochMs = parseInstant(json.optString("updated")),
            hours = hours,
        )
    }

    private fun mapTransactions(array: JSONArray): List<FinanceTransaction> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val vendor = item.optString("vendor").trim().ifBlank { "Unknown vendor" }
            add(
                FinanceTransaction(
                    vendor = vendor,
                    amount = item.numberOrNull("amount") ?: 0.0,
                    category = item.optString("category").trim().ifBlank { "Uncategorized" },
                    paymentSource = item.optString("paymentSource").trim().ifBlank { "Unknown source" },
                    occurredAtEpochMs = parseInstant(item.optString("occurredAt")),
                    notes = item.optString("notes").trim().takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    private fun JSONObject.numberOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    private fun parseInstant(value: String?): Long? =
        value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

data class FinanceRuntimeState(
    val snapshot: FinanceSnapshot,
    val source: RuntimeDataSource,
    val fetchedAtEpochMs: Long,
    val error: String? = null,
)

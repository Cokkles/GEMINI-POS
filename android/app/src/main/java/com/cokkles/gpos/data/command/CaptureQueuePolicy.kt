package com.cokkles.gpos.data.command

import com.cokkles.gpos.data.local.LocalReceipt
import com.cokkles.gpos.data.local.LocalReceiptState

internal object CaptureQueuePolicy {
    private val resolvedStates = setOf(LocalReceiptState.CONFIRMED, LocalReceiptState.CANCELLED)

    fun isActive(receipt: LocalReceipt): Boolean = receipt.state !in resolvedStates

    fun isResolved(receipt: LocalReceipt): Boolean = receipt.state in resolvedStates

    fun canUserClear(receipt: LocalReceipt): Boolean =
        receipt.state != LocalReceiptState.SENDING && receipt.state != LocalReceiptState.CONFIRMED

    fun userCleared(receipt: LocalReceipt, now: Long): LocalReceipt {
        require(canUserClear(receipt)) { "An in-flight or confirmed receipt cannot be cleared." }
        return receipt.copy(
            state = LocalReceiptState.CANCELLED,
            updatedAtEpochMs = now,
            result = "Cleared by you. No automatic retry is scheduled.",
            error = null,
            payload = null,
            nextRetryAtEpochMs = null,
            manualRetryAllowed = false,
        )
    }
}

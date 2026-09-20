package com.cokkles.gpos.data.command

import com.cokkles.gpos.data.local.LocalReceipt
import com.cokkles.gpos.data.local.LocalReceiptState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQueuePolicyTest {
    private fun receipt(state: LocalReceiptState) = LocalReceipt(
        id = "capture-1",
        kind = "note",
        summary = "A note",
        state = state,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        payload = "private body",
        manualRetryAllowed = true,
    )

    @Test
    fun queuedAndReviewReceiptsCanBeClearedWithoutRetainingPayload() {
        listOf(LocalReceiptState.QUEUED, LocalReceiptState.WAITING, LocalReceiptState.NEEDS_REVIEW, LocalReceiptState.FAILED)
            .forEach { state ->
                val target = receipt(state)
                assertTrue(CaptureQueuePolicy.isActive(target))
                assertTrue(CaptureQueuePolicy.canUserClear(target))
                val cleared = CaptureQueuePolicy.userCleared(target, 99)
                assertTrue(CaptureQueuePolicy.isResolved(cleared))
                assertNull(cleared.payload)
                assertFalse(cleared.manualRetryAllowed)
            }
    }

    @Test
    fun sendingAndConfirmedReceiptsCannotBeCleared() {
        assertFalse(CaptureQueuePolicy.canUserClear(receipt(LocalReceiptState.SENDING)))
        assertFalse(CaptureQueuePolicy.canUserClear(receipt(LocalReceiptState.CONFIRMED)))
    }
}

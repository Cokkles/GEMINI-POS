package com.cokkles.gpos.platform.sync

import com.cokkles.gpos.data.local.DeferredMutation
import com.cokkles.gpos.data.local.DeferredMutationType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredMutationPolicyTest {
    private fun item(type: DeferredMutationType, entity: String, owner: String = "me", list: String = "list") =
        DeferredMutation("id-$type", owner, type, entity, list, "Title", createdAtEpochMs = 1, syncAfterEpochMs = 2)

    @Test fun `newer task edit supersedes older edit for same account task and list`() {
        assertTrue(DeferredMutationPolicy.superseded(item(DeferredMutationType.TASK_UPDATE, "t1"), item(DeferredMutationType.TASK_UPDATE, "t1")))
        assertFalse(DeferredMutationPolicy.superseded(item(DeferredMutationType.TASK_UPDATE, "t1"), item(DeferredMutationType.TASK_UPDATE, "t2")))
        assertFalse(DeferredMutationPolicy.superseded(item(DeferredMutationType.TASK_UPDATE, "t1", "one"), item(DeferredMutationType.TASK_UPDATE, "t1", "two")))
    }

    @Test fun `only one followup outcome can remain queued`() {
        assertTrue(DeferredMutationPolicy.superseded(item(DeferredMutationType.FOLLOWUP_PROMOTE, "f1"), item(DeferredMutationType.FOLLOWUP_DISMISS, "f1")))
        assertFalse(DeferredMutationPolicy.superseded(item(DeferredMutationType.FOLLOWUP_PROMOTE, "f1"), item(DeferredMutationType.FOLLOWUP_DISMISS, "f2")))
    }
}

package com.cokkles.gpos

import android.app.Application
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.workspace.*
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.StoredCredential
import com.cokkles.gpos.platform.security.AuthContinuityPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspacePersistenceTest {
    @Test fun encryptedDraftSurvivesReopenAndStaysWithOriginalAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = ProtectedWorkspaceStore(context)
        val owner = "storage-test-${System.nanoTime()}@example.invalid"
        val pending = RunningNotesDocument(text = "Private daily scratchpad\nSecond line").beginSync()
        store.write(owner, "running_notes", pending.toJson())
        val reopened = ProtectedWorkspaceStore(context).read(owner, "running_notes")!!
        assertEquals(pending, RunningNotesDocument.parse(reopened))
        assertNull(store.read("other-$owner", "running_notes"))
        assertFalse(context.getSharedPreferences("aegis_workspace_v1", 0).all.values.any { it.toString().contains("Private daily scratchpad") })
    }
    @Test fun viewModelAutosavesWithoutLeavingScreenAndReopensOffline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val credentials = AndroidKeystoreCredentialStore(app)
        val continuity = AuthContinuityPreferences(app)
        continuity.markAuthenticated()
        val owner = "autosave-${System.nanoTime()}@example.invalid"
        val body = Base64.encodeToString("{\"email\":\"$owner\"}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        credentials.replace(StoredCredential("test.$body.test", System.currentTimeMillis() + 60000))
        lateinit var vm: RunningNotesViewModel
        instrumentation.runOnMainSync { vm = RunningNotesViewModel(app); vm.activate(AuthState.OfflineRestored(System.currentTimeMillis() + 60000, "test offline")) }
        await { vm.state.value.ready }
        instrumentation.runOnMainSync { vm.edit("Write during the day"); vm.edit("Write during the day\nKeep the latest edit") }
        await { !vm.state.value.saving }
        // Simulate the existing runtime clearing an expired credential while offline.
        credentials.clear()
        lateinit var reopened: RunningNotesViewModel
        instrumentation.runOnMainSync { vm.detach(); reopened = RunningNotesViewModel(app); reopened.activate(AuthState.SignedOut) }
        await { reopened.state.value.ready }
        assertEquals("Write during the day\nKeep the latest edit", reopened.state.value.document.text)
        continuity.clear()
        instrumentation.runOnMainSync { reopened.activate(AuthState.SignedOut) }
        assertFalse(reopened.state.value.ready)
    }
    private fun await(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(25)
        assertTrue("Workspace state did not settle", check())
    }
}

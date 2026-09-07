package com.cokkles.gpos

import android.app.Application
import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.cokkles.gpos.data.interaction.InteractionCapabilities
import com.cokkles.gpos.data.workspace.*
import com.cokkles.gpos.ui.daily.*
import com.cokkles.gpos.ui.theme.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkspaceVisualTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    @Test fun taskWorkspaceRendersMultipleListFixture() {
        val vm = TaskWorkspaceViewModel(app)
        val state = TaskWorkspaceUiState(owner = "fixture@example.invalid", canWrite = true,
            capabilities = InteractionCapabilities(taskActionV1 = true, taskWorkspaceV1 = true, taskCrudV1 = true, taskListsV1 = true, taskHistoryV1 = true),
            workspace = TaskWorkspace(listOf(WorkspaceList("work", "Work"), WorkspaceList("home", "Personal")), listOf(
                WorkspaceTask("1", "work", "Work", "Review the weekly plan", "Check milestones and next steps", "2026-09-08"),
                WorkspaceTask("2", "home", "Personal", "Pick up groceries", "Coffee, fruit and something for dinner"))))
        compose.setContent { GposTheme(GposThemeOption.NORD) { WorkspaceTasksScreen(state, TaskQueueUiState(), vm, {}, {}, {}) } }
        screenshot("tasks")
    }
    @Test fun runningNotesRendersLongFormFixture() {
        val vm = RunningNotesViewModel(app)
        val state = RunningNotesUiState(owner = "fixture@example.invalid", ready = true,
            document = RunningNotesDocument(text = "Friday thoughts\n\nA few things to work through today.\n\nPlan the afternoon, collect ideas, and keep room for anything that comes up.\n\nTomorrow\nRevisit the ideas worth keeping.", updatedAt = 1788523200000))
        compose.setContent { GposTheme(GposThemeOption.NORD) { NotesScreen(state, vm, false, CaptureUiState(), { _, _ -> }, {}) } }
        screenshot("running-notes")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AEGIS-Tests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(app.contentResolver.openOutputStream(uri)).use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
}

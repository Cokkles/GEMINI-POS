package com.cokkles.gpos.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyCustomizationPolicyTest {
    private val lists = listOf(WorkspaceList("g", "Grocery"), WorkspaceList("f", "Follow Ups"), WorkspaceList("w", "Work"))

    @Test fun `hidden home task lists are excluded without merging the rest`() {
        val workspace = TaskWorkspace(lists, listOf(
            WorkspaceTask("1", "g", "Grocery", "Milk"), WorkspaceTask("2", "f", "Follow Ups", "Call"),
        ))
        assertEquals(listOf("Follow Ups"), HomeTaskVisibilityPolicy.groupedTasks(workspace, setOf("g")).map { it.first.title })
    }

    @Test fun `news sections retain user order and append new sources`() {
        assertEquals(listOf("Work", "Deals", "Local"), NewsSectionOrder.arrange(listOf("Deals", "Work", "Local"), listOf("work", "deals")))
    }

    @Test fun `news section can move one position`() {
        assertEquals(listOf("deals", "local", "work"), NewsSectionOrder.move(listOf("Deals", "Work", "Local"), listOf("deals", "work", "local"), "Local", -1))
    }
}

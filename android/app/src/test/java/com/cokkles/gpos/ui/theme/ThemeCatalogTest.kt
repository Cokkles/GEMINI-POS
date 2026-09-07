package com.cokkles.gpos.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogTest {
    @Test fun `theme storage keys are stable and unique`() {
        val keys = GposThemeOption.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
    }

    @Test fun `curated catalog includes requested distinct families`() {
        val keys = GposThemeOption.entries.map { it.storageKey }.toSet()
        assertTrue(keys.containsAll(setOf("catppuccin_mocha", "tokyo_night", "kanagawa", "gruvbox", "rose_pine",
            "everforest", "github_dark", "ayu_mirage", "flexoki_light", "synthwave", "cobalt2")))
    }
}

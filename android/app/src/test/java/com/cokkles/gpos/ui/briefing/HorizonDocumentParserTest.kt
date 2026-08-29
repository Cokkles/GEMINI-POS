package com.cokkles.gpos.ui.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonDocumentParserTest {
    @Test
    fun `parses title sections subsections and display lines without inventing semantics`() {
        val raw = """
            # Daily Executive Briefing
            Generated from canonical sources.

            ## Calendar
            - **9:00 AM** Morning sync
            ### Tomorrow
            1. Review release

            ## Active Tasks
            - [ ] Validate Android checkpoint
        """.trimIndent()

        val parsed = HorizonDocumentParser.parse(raw)

        assertEquals("Daily Executive Briefing", parsed.title)
        assertEquals(listOf("Generated from canonical sources."), parsed.preamble)
        assertEquals(listOf("Calendar", "Active Tasks"), parsed.sections.map { it.title })
        assertEquals("Tomorrow", parsed.sections.first().subsections.single().title)
        assertEquals(
            "9:00 AM Morning sync",
            HorizonDocumentParser.displayLine(parsed.sections.first().lines.first()),
        )
        assertEquals(
            "Validate Android checkpoint",
            HorizonDocumentParser.displayLine(parsed.sections.last().lines.first()),
        )
    }

    @Test
    fun `empty briefing yields empty document`() {
        val parsed = HorizonDocumentParser.parse("")
        assertEquals(null, parsed.title)
        assertTrue(parsed.preamble.isEmpty())
        assertTrue(parsed.sections.isEmpty())
    }
}

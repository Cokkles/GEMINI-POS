package com.cokkles.gpos.ui.briefing

data class HorizonDocument(
    val title: String?,
    val preamble: List<String>,
    val sections: List<HorizonSection>,
)

data class HorizonSection(
    val title: String,
    val lines: List<String>,
    val subsections: List<HorizonSubsection>,
)

data class HorizonSubsection(
    val title: String,
    val lines: List<String>,
)

object HorizonDocumentParser {
    fun parse(text: String): HorizonDocument {
        var documentTitle: String? = null
        val preamble = mutableListOf<String>()
        val sections = mutableListOf<MutableSection>()
        var currentSection: MutableSection? = null
        var currentSubsection: MutableSubsection? = null

        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .forEach { rawLine ->
                val line = rawLine.trim()
                if (line == "---") return@forEach

                when {
                    line.startsWith("# ") -> {
                        if (documentTitle == null) {
                            documentTitle = cleanInlineMarkdown(line.removePrefix("# "))
                        }
                    }

                    line.startsWith("## ") -> {
                        currentSection = MutableSection(
                            title = cleanInlineMarkdown(line.removePrefix("## ")),
                        ).also(sections::add)
                        currentSubsection = null
                    }

                    line.startsWith("### ") || line.startsWith("#### ") -> {
                        val section = currentSection
                        if (section != null) {
                            currentSubsection = MutableSubsection(
                                title = cleanInlineMarkdown(line.substringAfter(' ')),
                            ).also(section.subsections::add)
                        }
                    }

                    currentSubsection != null -> currentSubsection?.lines?.add(rawLine)
                    currentSection != null -> currentSection?.lines?.add(rawLine)
                    line.isNotBlank() -> preamble.add(rawLine)
                }
            }

        return HorizonDocument(
            title = documentTitle,
            preamble = preamble,
            sections = sections.map { section ->
                HorizonSection(
                    title = section.title,
                    lines = section.lines.toList(),
                    subsections = section.subsections.map { subsection ->
                        HorizonSubsection(
                            title = subsection.title,
                            lines = subsection.lines.toList(),
                        )
                    },
                )
            },
        )
    }

    fun displayLine(raw: String): String = cleanInlineMarkdown(
        raw.trim()
            .replace(Regex("^[-*]\\s+\\[[ xX]]\\s+"), "")
            .replace(Regex("^[-*]\\s+"), "")
            .replace(Regex("^\\d+[.)]\\s+"), "")
            .removePrefix("> "),
    )

    private fun cleanInlineMarkdown(value: String): String = value
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)"), "$1")
        .trim()

    private data class MutableSection(
        val title: String,
        val lines: MutableList<String> = mutableListOf(),
        val subsections: MutableList<MutableSubsection> = mutableListOf(),
    )

    private data class MutableSubsection(
        val title: String,
        val lines: MutableList<String> = mutableListOf(),
    )
}

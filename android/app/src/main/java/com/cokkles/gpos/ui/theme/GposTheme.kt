package com.cokkles.gpos.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * First-class GPOS shell themes. These are intentionally finite and curated:
 * every palette must preserve strong foreground/background contrast and keep
 * Material semantic roles consistent across the app.
 */
enum class GposThemeOption(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    LIGHT(
        storageKey = "light",
        displayName = "GPOS Light",
        description = "Clean white Material surface — the current default.",
    ),
    DARK(
        storageKey = "dark",
        displayName = "GPOS Dark",
        description = "Neutral high-contrast dark mode.",
    ),
    MONOKAI(
        storageKey = "monokai",
        displayName = "Monokai",
        description = "Sublime-style charcoal with warm accent colors.",
    ),
    DRACULA(
        storageKey = "dracula",
        displayName = "Dracula",
        description = "Deep purple-grey surfaces with bright cool accents.",
    ),
    SOLARIZED_DARK(
        storageKey = "solarized_dark",
        displayName = "Solarized Dark",
        description = "Low-glare blue-black palette with clear text hierarchy.",
    ),
    NORD(
        storageKey = "nord",
        displayName = "Nord",
        description = "Cool arctic blue-grey palette with restrained contrast.",
    ),
    CATPPUCCIN_MOCHA("catppuccin_mocha", "Catppuccin Mocha", "Pastel accents over a soft espresso-dark canvas."),
    TOKYO_NIGHT("tokyo_night", "Tokyo Night", "Midnight blue with crisp blue and violet accents."),
    ONE_DARK("one_dark", "One Dark", "Balanced Atom-style charcoal with muted developer colors."),
    KANAGAWA("kanagawa", "Kanagawa", "Warm sumi ink with wave blue and autumn green."),
    GRUVBOX("gruvbox", "Gruvbox", "Warm retro earth tones with strong hierarchy."),
    ROSE_PINE("rose_pine", "Rosé Pine", "Soft pine, rose, foam, and gold on deep plum."),
    EVERFOREST("everforest", "Everforest", "Natural green-tinted surfaces designed for long sessions."),
    GITHUB_DARK("github_dark", "GitHub Dark", "Crisp dark surfaces, visible borders, and restrained blue."),
    CARBON_DARK("carbon_dark", "Carbon Gray 100", "Industrial near-black IBM-inspired surfaces and blue actions."),
    AYU_MIRAGE("ayu_mirage", "Ayu Mirage", "Slate-blue surfaces with sky and gold highlights."),
    FLEXOKI_LIGHT("flexoki_light", "Flexoki Light", "Warm paper and ink colors for reading and writing."),
    ZENBURN("zenburn", "Zenburn", "Low-contrast soot, parchment, sage, and clay."),
    SYNTHWAVE("synthwave", "Synthwave", "Neon cyan, magenta, and electric yellow on violet."),
    SHADES_OF_PURPLE("shades_of_purple", "Shades of Purple", "Royal violet with bright gold and lavender."),
    COBALT2("cobalt2", "Cobalt2", "Deep ocean blue with vivid blue and gold."),
    PALENIGHT("palenight", "Palenight", "Smooth Material-inspired violet with periwinkle accents.");

    companion object {
        fun fromStorageKey(value: String?): GposThemeOption =
            entries.firstOrNull { it.storageKey == value } ?: LIGHT
    }
}

@Composable
fun GposTheme(
    option: GposThemeOption,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorSchemeFor(option),
        content = content,
    )
}

fun colorSchemeFor(option: GposThemeOption): ColorScheme = when (option) {
    GposThemeOption.LIGHT -> lightColorScheme(
        primary = Color(0xFF2457A6),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8E2FF),
        onPrimaryContainer = Color(0xFF001A41),
        secondary = Color(0xFF455D92),
        onSecondary = Color.White,
        background = Color(0xFFFFFBFF),
        onBackground = Color(0xFF1B1B1F),
        surface = Color(0xFFFFFBFF),
        onSurface = Color(0xFF1B1B1F),
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44464F),
    )

    GposThemeOption.DARK -> darkColorScheme(
        primary = Color(0xFFADC6FF),
        onPrimary = Color(0xFF002E69),
        primaryContainer = Color(0xFF16458C),
        onPrimaryContainer = Color(0xFFD8E2FF),
        secondary = Color(0xFFB3C6F9),
        onSecondary = Color(0xFF1C305F),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE3E2E8),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE3E2E8),
        surfaceVariant = Color(0xFF44464F),
        onSurfaceVariant = Color(0xFFC4C6D0),
    )

    GposThemeOption.MONOKAI -> darkColorScheme(
        primary = Color(0xFFA6E22E),
        onPrimary = Color(0xFF172000),
        primaryContainer = Color(0xFF3D4C16),
        onPrimaryContainer = Color(0xFFE6F6AE),
        secondary = Color(0xFF66D9EF),
        onSecondary = Color(0xFF00363D),
        tertiary = Color(0xFFF92672),
        onTertiary = Color.White,
        background = Color(0xFF1E1F1C),
        onBackground = Color(0xFFF8F8F2),
        surface = Color(0xFF272822),
        onSurface = Color(0xFFF8F8F2),
        surfaceVariant = Color(0xFF3A3B35),
        onSurfaceVariant = Color(0xFFE6E6DC),
    )

    GposThemeOption.DRACULA -> darkColorScheme(
        primary = Color(0xFFBD93F9),
        onPrimary = Color(0xFF271638),
        primaryContainer = Color(0xFF4A3568),
        onPrimaryContainer = Color(0xFFF1E4FF),
        secondary = Color(0xFF8BE9FD),
        onSecondary = Color(0xFF00363D),
        tertiary = Color(0xFFFF79C6),
        onTertiary = Color(0xFF4A0C2F),
        background = Color(0xFF20212B),
        onBackground = Color(0xFFF8F8F2),
        surface = Color(0xFF282A36),
        onSurface = Color(0xFFF8F8F2),
        surfaceVariant = Color(0xFF44475A),
        onSurfaceVariant = Color(0xFFE8E8E2),
    )

    GposThemeOption.SOLARIZED_DARK -> darkColorScheme(
        primary = Color(0xFF2AA198),
        onPrimary = Color(0xFF002B28),
        primaryContainer = Color(0xFF155E59),
        onPrimaryContainer = Color(0xFFC4F5F0),
        secondary = Color(0xFF268BD2),
        onSecondary = Color(0xFF001F33),
        tertiary = Color(0xFFB58900),
        onTertiary = Color(0xFF282000),
        background = Color(0xFF002B36),
        onBackground = Color(0xFFEEE8D5),
        surface = Color(0xFF073642),
        onSurface = Color(0xFFEEE8D5),
        surfaceVariant = Color(0xFF164955),
        onSurfaceVariant = Color(0xFFD5D0BE),
    )

    GposThemeOption.NORD -> darkColorScheme(
        primary = Color(0xFF88C0D0),
        onPrimary = Color(0xFF17333A),
        primaryContainer = Color(0xFF3F6570),
        onPrimaryContainer = Color(0xFFE5F4F7),
        secondary = Color(0xFF81A1C1),
        onSecondary = Color(0xFF1B3042),
        tertiary = Color(0xFFA3BE8C),
        onTertiary = Color(0xFF24321C),
        background = Color(0xFF101A29),
        onBackground = Color(0xFFECEFF4),
        surface = Color(0xFF1A293C),
        onSurface = Color(0xFFECEFF4),
        surfaceVariant = Color(0xFF2C425B),
        surfaceContainer = Color(0xFF1A293C),
        surfaceContainerLow = Color(0xFF162235),
        surfaceContainerHigh = Color(0xFF22344B),
        outline = Color(0xFF8095AF),
        outlineVariant = Color(0xFF3F5771),
        onSurfaceVariant = Color(0xFFD8DEE9),
    )

    GposThemeOption.CATPPUCCIN_MOCHA -> themedDark(0xFF1E1E2E, 0xFF262637, 0xFFCDD6F4, 0xFF89B4FA, 0xFFA6E3A1, 0xFFF38BA8)
    GposThemeOption.TOKYO_NIGHT -> themedDark(0xFF1A1B26, 0xFF24283B, 0xFFA9B1D6, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF9ECE6A)
    GposThemeOption.ONE_DARK -> themedDark(0xFF21252B, 0xFF282C34, 0xFFABB2BF, 0xFF61AFEF, 0xFF98C379, 0xFFC678DD)
    GposThemeOption.KANAGAWA -> themedDark(0xFF1F1F28, 0xFF2A2A37, 0xFFDCD7BA, 0xFF7E9CD8, 0xFF98BB6C, 0xFFE6C384)
    GposThemeOption.GRUVBOX -> themedDark(0xFF1D2021, 0xFF282828, 0xFFEBDBB2, 0xFFFABD2F, 0xFFB8BB26, 0xFF83A598)
    GposThemeOption.ROSE_PINE -> themedDark(0xFF191724, 0xFF26233A, 0xFFE0DEF4, 0xFFEB6F92, 0xFF9CCFD8, 0xFFF6C177)
    GposThemeOption.EVERFOREST -> themedDark(0xFF232A2E, 0xFF2D353B, 0xFFD3C6AA, 0xFFA7C080, 0xFF7FBBB3, 0xFFE69875)
    GposThemeOption.GITHUB_DARK -> themedDark(0xFF0D1117, 0xFF161B22, 0xFFC9D1D9, 0xFF58A6FF, 0xFF3FB950, 0xFFD2A8FF)
    GposThemeOption.CARBON_DARK -> themedDark(0xFF161616, 0xFF262626, 0xFFF4F4F4, 0xFF78A9FF, 0xFF42BE65, 0xFFBE95FF)
    GposThemeOption.AYU_MIRAGE -> themedDark(0xFF1F2430, 0xFF252B38, 0xFFCBCCC6, 0xFF73D0FF, 0xFFFFCC66, 0xFFD4BFFF)
    GposThemeOption.FLEXOKI_LIGHT -> lightColorScheme(
        primary = Color(0xFF205EA6), onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE6F2), onPrimaryContainer = Color(0xFF102A43),
        secondary = Color(0xFF66800B), onSecondary = Color.White,
        tertiary = Color(0xFFAF3029), onTertiary = Color.White,
        background = Color(0xFFFFFCF0), onBackground = Color(0xFF100F0F),
        surface = Color(0xFFF2F0E5), onSurface = Color(0xFF100F0F),
        surfaceVariant = Color(0xFFE6E4D9), onSurfaceVariant = Color(0xFF403E3C),
        outline = Color(0xFF878580), outlineVariant = Color(0xFFCECDC3),
    )
    GposThemeOption.ZENBURN -> themedDark(0xFF303030, 0xFF3F3F3F, 0xFFDCDCCC, 0xFF7F9F7F, 0xFFDFaf8F, 0xFF8CD0D3)
    GposThemeOption.SYNTHWAVE -> themedDark(0xFF262335, 0xFF312E46, 0xFFF4F4F8, 0xFF36F9F6, 0xFFF92AAD, 0xFFFFE64D)
    GposThemeOption.SHADES_OF_PURPLE -> themedDark(0xFF222044, 0xFF2D2B55, 0xFFF2F1FF, 0xFFFAD000, 0xFFA599E9, 0xFFFF628C)
    GposThemeOption.COBALT2 -> themedDark(0xFF122738, 0xFF193549, 0xFFFFFFFF, 0xFF0088FF, 0xFFFFC600, 0xFFFF9D00)
    GposThemeOption.PALENIGHT -> themedDark(0xFF242735, 0xFF292D3E, 0xFFA6ACCD, 0xFF82AAFF, 0xFFC792EA, 0xFF89DDFF)
}

private fun themedDark(background: Long, surface: Long, text: Long, primary: Long, secondary: Long, tertiary: Long): ColorScheme =
    darkColorScheme(
        primary = Color(primary), onPrimary = Color(background),
        primaryContainer = Color(surface), onPrimaryContainer = Color(text),
        secondary = Color(secondary), onSecondary = Color(background),
        tertiary = Color(tertiary), onTertiary = Color(background),
        background = Color(background), onBackground = Color(text),
        surface = Color(surface), onSurface = Color(text),
        surfaceVariant = Color(surface), onSurfaceVariant = Color(text),
        outline = Color(primary), outlineVariant = Color(surface),
    )

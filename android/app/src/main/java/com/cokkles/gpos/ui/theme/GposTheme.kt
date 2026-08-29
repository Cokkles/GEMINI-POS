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
    );

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
        background = Color(0xFF242933),
        onBackground = Color(0xFFECEFF4),
        surface = Color(0xFF2E3440),
        onSurface = Color(0xFFECEFF4),
        surfaceVariant = Color(0xFF434C5E),
        onSurfaceVariant = Color(0xFFD8DEE9),
    )
}

package at.mikag.lavender.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import at.mikag.lavender.data.DarkMode
import at.mikag.lavender.data.ThemeChoice

/**
 * A theme: a name (deliberately not translated), the flag stripes it's drawn from, and three accents.
 * Lavender is the app's own default; the other seven are coded after pride flags.
 */
data class Palette(
    val choice: ThemeChoice,
    val name: String,
    val stripes: List<Color>,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
)

object Palettes {
    val all: List<Palette> = listOf(
        Palette(
            ThemeChoice.LAVENDER, "Lavender",
            listOf(Color(0xFF6E4C9E), Color(0xFFB57EDC), Color(0xFFE6D7F5)),
            Color(0xFFB57EDC), Color(0xFFE8A0BF), Color(0xFF7FC8A9),
        ),
        Palette(
            ThemeChoice.PRISM, "Prism",
            listOf(
                Color(0xFFE40303), Color(0xFFFF8C00), Color(0xFFFFED00),
                Color(0xFF008026), Color(0xFF004DFF), Color(0xFF750787),
            ),
            Color(0xFF8C5CFF), Color(0xFFFF8C00), Color(0xFF00A33A),
        ),
        Palette(
            ThemeChoice.COTTON_CANDY, "Cotton Candy",
            listOf(Color(0xFF5BCEFA), Color(0xFFF5A9B8), Color(0xFFFFFFFF), Color(0xFFF5A9B8), Color(0xFF5BCEFA)),
            Color(0xFF5BCEFA), Color(0xFFF5A9B8), Color(0xFFB8C7FF),
        ),
        Palette(
            ThemeChoice.TWILIGHT, "Twilight",
            listOf(Color(0xFFD60270), Color(0xFFD60270), Color(0xFF9B4F96), Color(0xFF0038A8), Color(0xFF0038A8)),
            Color(0xFFB45AB0), Color(0xFFD60270), Color(0xFF3F6FD8),
        ),
        Palette(
            ThemeChoice.SUNSET, "Sunset",
            listOf(
                Color(0xFFD52D00), Color(0xFFEF7627), Color(0xFFFF9A56), Color(0xFFFFFFFF),
                Color(0xFFD162A4), Color(0xFFB55690), Color(0xFFA30262),
            ),
            Color(0xFFEF7627), Color(0xFFD162A4), Color(0xFFA30262),
        ),
        Palette(
            ThemeChoice.CITRINE, "Citrine",
            listOf(Color(0xFFFCF434), Color(0xFFFFFFFF), Color(0xFF9C59D1), Color(0xFF2C2C2C)),
            Color(0xFF9C59D1), Color(0xFFFCF434), Color(0xFFBDBDBD),
        ),
        Palette(
            ThemeChoice.SHERBET, "Sherbet",
            listOf(Color(0xFFFF218C), Color(0xFFFFD800), Color(0xFF21B1FF)),
            Color(0xFFFF218C), Color(0xFF21B1FF), Color(0xFFFFD800),
        ),
        Palette(
            ThemeChoice.BLACKBERRY, "Blackberry",
            listOf(Color(0xFF000000), Color(0xFFA3A3A3), Color(0xFFFFFFFF), Color(0xFF800080)),
            Color(0xFF9B3D9B), Color(0xFFA3A3A3), Color(0xFFD9C2E0),
        ),
    )

    fun of(choice: ThemeChoice): Palette = all.first { it.choice == choice }
}

private val Ink = Color(0xFF0F0C14)
private val Paper = Color(0xFFFFFFFF)

private fun Color.onColor(): Color = if (luminance() > 0.45f) Ink else Paper

/** Brightens accents for dark backgrounds, darkens them for light ones, so text on them stays readable. */
private fun Color.forDark(): Color = if (luminance() < 0.30f) lerp(this, Paper, 0.35f) else this
private fun Color.forLight(): Color = if (luminance() > 0.22f) lerp(this, Ink, 0.38f) else this

fun Palette.colorScheme(dark: Boolean): ColorScheme = if (dark) {
    val background = lerp(primary, Ink, 0.84f)
    val surfaceVariant = lerp(primary, Ink, 0.66f)
    val p = primary.forDark()
    val s = secondary.forDark()
    val t = tertiary.forDark()
    darkColorScheme(
        primary = p,
        onPrimary = p.onColor(),
        primaryContainer = lerp(p, Ink, 0.40f),
        onPrimaryContainer = lerp(p, Paper, 0.82f),
        secondary = s,
        onSecondary = s.onColor(),
        secondaryContainer = lerp(s, Ink, 0.45f),
        onSecondaryContainer = lerp(s, Paper, 0.85f),
        tertiary = t,
        onTertiary = t.onColor(),
        tertiaryContainer = lerp(t, Ink, 0.45f),
        onTertiaryContainer = lerp(t, Paper, 0.85f),
        background = background,
        onBackground = Color(0xFFF3EEF8),
        surface = background,
        onSurface = Color(0xFFF3EEF8),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = lerp(primary, Paper, 0.78f),
        surfaceContainerLowest = lerp(primary, Ink, 0.90f),
        surfaceContainerLow = lerp(primary, Ink, 0.80f),
        surfaceContainer = lerp(primary, Ink, 0.76f),
        surfaceContainerHigh = lerp(primary, Ink, 0.70f),
        surfaceContainerHighest = lerp(primary, Ink, 0.64f),
        outline = lerp(primary, Paper, 0.30f).copy(alpha = 0.7f),
        outlineVariant = lerp(primary, Ink, 0.50f),
    )
} else {
    val background = lerp(primary, Paper, 0.86f)
    val p = primary.forLight()
    val s = secondary.forLight()
    val t = tertiary.forLight()
    lightColorScheme(
        primary = p,
        onPrimary = p.onColor(),
        primaryContainer = lerp(primary, Paper, 0.55f),
        onPrimaryContainer = lerp(p, Ink, 0.55f),
        secondary = s,
        onSecondary = s.onColor(),
        secondaryContainer = lerp(secondary, Paper, 0.58f),
        onSecondaryContainer = lerp(s, Ink, 0.55f),
        tertiary = t,
        onTertiary = t.onColor(),
        tertiaryContainer = lerp(tertiary, Paper, 0.58f),
        onTertiaryContainer = lerp(t, Ink, 0.55f),
        background = background,
        onBackground = Color(0xFF1D1A22),
        surface = background,
        onSurface = Color(0xFF1D1A22),
        surfaceVariant = lerp(primary, Paper, 0.70f),
        onSurfaceVariant = lerp(primary, Ink, 0.70f),
        surfaceContainerLowest = lerp(primary, Paper, 0.95f),
        surfaceContainerLow = lerp(primary, Paper, 0.90f),
        surfaceContainer = lerp(primary, Paper, 0.80f),
        surfaceContainerHigh = lerp(primary, Paper, 0.74f),
        surfaceContainerHighest = lerp(primary, Paper, 0.68f),
        outline = lerp(primary, Ink, 0.45f).copy(alpha = 0.7f),
        outlineVariant = lerp(primary, Paper, 0.65f),
    )
}

val LocalPalette = staticCompositionLocalOf { Palettes.of(ThemeChoice.LAVENDER) }

@Composable
fun isDark(darkMode: DarkMode): Boolean = when (darkMode) {
    DarkMode.DARK -> true
    DarkMode.LIGHT -> false
    DarkMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun LavenderTheme(theme: ThemeChoice, darkMode: DarkMode, content: @Composable () -> Unit) {
    val dark = isDark(darkMode)
    val palette = Palettes.of(theme)
    val scheme = remember(theme, dark) { palette.colorScheme(dark) }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

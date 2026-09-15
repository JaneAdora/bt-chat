package dev.jane.btchat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.jane.btchat.store.ThemeMode

/** Accent choices for each person. Dark enough to read as text on light, tinted as bubbles in both modes. */
val AccentPalette = listOf(
    "#1F5F3F", "#8A3B12", "#2E4A8F", "#6B2D5C", "#8C6A00", "#0F6E7A", "#7A1F1F", "#3F3F3F",
)

/** Placeholder color for a newly added peer, until they send their own via Hello. */
val DefaultPeerColor = AccentPalette.last()

fun parseHex(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF1F5F3F))

val LocalIsDark = compositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5F3F),
    onPrimary = Color.White,
    background = Color(0xFFFBFAF7),
    onBackground = Color(0xFF16181A),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF16181A),
    surfaceVariant = Color(0xFFEDEBE5),
    onSurfaceVariant = Color(0xFF4A4D50),
    outline = Color(0xFFB9B6AE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD3AE),
    onPrimary = Color(0xFF0B2A1B),
    background = Color(0xFF121412),
    onBackground = Color(0xFFECEAE3),
    surface = Color(0xFF121412),
    onSurface = Color(0xFFECEAE3),
    surfaceVariant = Color(0xFF23262A),
    onSurfaceVariant = Color(0xFFC3C6C9),
    outline = Color(0xFF5B5F63),
)

private val BtChatTypography = Typography(
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
)

@Composable
fun BtChatTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = BtChatTypography,
            content = content,
        )
    }
}

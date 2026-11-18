package app.inkwell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val SystemPink = Color(0xFFFF2D55)

/** iOS-style semantic colors. */
data class InkColors(
    val bg: Color,
    val card: Color,
    val label: Color,
    val secondary: Color,
    val separator: Color,
    val accent: Color,
    val dark: Boolean,
)

val LightInk = InkColors(
    bg = Color(0xFFF2F2F7), card = Color.White, label = Color.Black,
    secondary = Color.Black.copy(alpha = 0.6f), separator = Color.Black.copy(alpha = 0.16f),
    accent = SystemPink, dark = false,
)
val DarkInk = InkColors(
    bg = Color.Black, card = Color(0xFF1C1C1E), label = Color.White,
    secondary = Color.White.copy(alpha = 0.6f), separator = Color.White.copy(alpha = 0.18f),
    accent = SystemPink, dark = true,
)

val LocalInk = staticCompositionLocalOf { LightInk }

object InkType {
    // size-specific tracking: large negative, body 0, caption small positive
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, lineHeight = 41.sp)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.005).em)
    val body = TextStyle(fontSize = 17.sp, letterSpacing = 0.em)
    val callout = TextStyle(fontSize = 15.sp, letterSpacing = 0.em)
    val caption = TextStyle(fontSize = 12.sp, letterSpacing = 0.01.em)
}

val CardRadius = 12.dp
val CoverRadius = 8.dp

@Composable
fun InkwellTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ink = if (dark) DarkInk else LightInk
    val scheme = if (dark) darkColorScheme(
        primary = ink.accent, onPrimary = Color.White, background = ink.bg, onBackground = ink.label,
        surface = ink.card, onSurface = ink.label, surfaceVariant = ink.card, onSurfaceVariant = ink.secondary,
        outline = ink.separator, surfaceContainer = ink.card, surfaceContainerHigh = ink.card,
    ) else lightColorScheme(
        primary = ink.accent, onPrimary = Color.White, background = ink.bg, onBackground = ink.label,
        surface = ink.card, onSurface = ink.label, surfaceVariant = ink.card, onSurfaceVariant = ink.secondary,
        outline = ink.separator, surfaceContainer = ink.card, surfaceContainerHigh = ink.card,
    )
    CompositionLocalProvider(LocalInk provides ink) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

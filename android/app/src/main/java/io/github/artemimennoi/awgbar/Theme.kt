package io.github.artemimennoi.awgbar

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Mac's system green, for the notification accent. */
val ShieldGreen = Color(0xFF34C759)

/**
 * What every AWG Bar client colors the same way, as the Mac does: green when connected, orange
 * while connecting or disconnecting. Everything else follows Material.
 */
@Immutable
class StatusColors(val connected: Color, val busy: Color)

private val LightStatus = StatusColors(connected = ShieldGreen, busy = Color(0xFFFF9500))
private val DarkStatus = StatusColors(connected = Color(0xFF30D158), busy = Color(0xFFFF9F0A))
private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

object Status {
    val colors: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

// Graphite neutrals with a green accent: the AWG Bar look rather than Material's default purple.
private val Light = lightColorScheme(
    primary = Color(0xFF15803D), onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F2D6), onPrimaryContainer = Color(0xFF052E16),
    secondary = Color(0xFF4B5563), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E7EC), onSecondaryContainer = Color(0xFF1F2937),
    tertiary = Color(0xFF0E7490), onTertiary = Color.White,
    background = Color(0xFFF3F4F6), onBackground = Color(0xFF111318),
    surface = Color(0xFFF3F4F6), onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFE5E7EB), onSurfaceVariant = Color(0xFF5B616E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEDEEF1),
    surfaceContainerHighest = Color(0xFFE3E5E9),
    outline = Color(0xFF9AA0AB), outlineVariant = Color(0xFFDDE0E5),
    error = Color(0xFFDC2626), errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5EE08F), onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF0F5130), onPrimaryContainer = Color(0xFFC9F2D6),
    secondary = Color(0xFFB4BAC5), onSecondary = Color(0xFF1F242C),
    secondaryContainer = Color(0xFF2A2F38), onSecondaryContainer = Color(0xFFE2E5EA),
    tertiary = Color(0xFF67D4EA), onTertiary = Color(0xFF00363F),
    background = Color(0xFF0D0F12), onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF0D0F12), onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF262A31), onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceContainerLowest = Color(0xFF08090B), surfaceContainerLow = Color(0xFF121418),
    surfaceContainer = Color(0xFF181B20), surfaceContainerHigh = Color(0xFF20242A),
    surfaceContainerHighest = Color(0xFF2A2E35),
    outline = Color(0xFF6B7280), outlineVariant = Color(0xFF2E333B),
    error = Color(0xFFF87171), errorContainer = Color(0xFF4C1515), onErrorContainer = Color(0xFFFECACA),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun AppTheme(wallpaperColors: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        wallpaperColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppTypography, content = content)
    }
}

package uk.co.duncan.familyplanner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF2E5E4E)
private val Coral = Color(0xFFE07A5F)
private val Cream = Color(0xFFF8F3EE)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EFE9),
    onPrimaryContainer = Color(0xFF0F2A22),
    secondary = Coral,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE3DC),
    onSecondaryContainer = Color(0xFF4A1A0D),
    background = Cream,
    surface = Cream,
    surfaceContainer = Color(0xFFF1EAE3),
    surfaceContainerLow = Color(0xFFFFFBF8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DD1BC),
    onPrimary = Color(0xFF0F2A22),
    primaryContainer = Color(0xFF1E4136),
    secondary = Color(0xFFF2A48F),
    background = Color(0xFF151A18),
    surface = Color(0xFF151A18),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    )
}

@Composable
fun FamilyPlannerTheme(useDynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

fun parseColour(hex: String, fallback: Color = Color(0xFF607D8B)): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

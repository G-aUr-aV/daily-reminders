package dev.gaurav.dailyrhythm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Paper = lightColorScheme(
    primary = Color(0xFF713E55), onPrimary = Color.White,
    primaryContainer = Color(0xFFF4DBE4), onPrimaryContainer = Color(0xFF462235),
    secondary = Color(0xFF755642), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E3D5), onSecondaryContainer = Color(0xFF503B2D),
    tertiary = Color(0xFF426957), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7EADB), onTertiaryContainer = Color(0xFF244533),
    background = Color(0xFFFAF7F2), onBackground = Color(0xFF30282A),
    surface = Color(0xFFFAF7F2), onSurface = Color(0xFF30282A),
    surfaceVariant = Color(0xFFF1E8E3), onSurfaceVariant = Color(0xFF655954),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFCF8),
    surfaceContainer = Color(0xFFF2ECE6), surfaceContainerHigh = Color(0xFFECE4DE),
    surfaceContainerHighest = Color(0xFFE6DCD6), outline = Color(0xFF8A777C),
    outlineVariant = Color(0xFFDFD3CC), inverseSurface = Color(0xFF3E2938),
    inverseOnSurface = Color(0xFFFFF4E9), inversePrimary = Color(0xFFF7C392)
)

private val Dusk = darkColorScheme(
    primary = Color(0xFFE9B4C7), onPrimary = Color(0xFF442135),
    primaryContainer = Color(0xFF573348), onPrimaryContainer = Color(0xFFF8D9E6),
    secondary = Color(0xFFE8BFA0), onSecondary = Color(0xFF402E23),
    secondaryContainer = Color(0xFF48392F), onSecondaryContainer = Color(0xFFF6DFC9),
    tertiary = Color(0xFFA3D1B1), onTertiary = Color(0xFF193C2A),
    tertiaryContainer = Color(0xFF2B4536), onTertiaryContainer = Color(0xFFCBEDD1),
    background = Color(0xFF1B181B), onBackground = Color(0xFFF6EDE7),
    surface = Color(0xFF1B181B), onSurface = Color(0xFFF6EDE7),
    surfaceVariant = Color(0xFF392F34), onSurfaceVariant = Color(0xFFD3C1C5),
    surfaceContainerLowest = Color(0xFF141114), surfaceContainerLow = Color(0xFF262125),
    surfaceContainer = Color(0xFF2D262B), surfaceContainerHigh = Color(0xFF362D34),
    surfaceContainerHighest = Color(0xFF40353D), outline = Color(0xFFAA989D),
    outlineVariant = Color(0xFF51444D), inverseSurface = Color(0xFFEEDACB),
    inverseOnSurface = Color(0xFF392635), inversePrimary = Color(0xFF7F4058)
)

private val AgendaType = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 35.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp)
)

@Composable
internal fun AgendaTheme(dark: Boolean = isSystemInDarkTheme(), pureBlack: Boolean = false, content: @Composable () -> Unit) {
    val colors = when {
        dark && pureBlack -> Dusk.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black)
        dark -> Dusk
        else -> Paper
    }
    MaterialTheme(colorScheme = colors, typography = AgendaType, content = content)
}

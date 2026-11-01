package com.openwhen.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF783D51), onPrimary = Color(0xFFFFF8F1),
    primaryContainer = Color(0xFFF5DDE1), onPrimaryContainer = Color(0xFF492333),
    secondary = Color(0xFF65755F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9DC), onSecondaryContainer = Color(0xFF354730),
    tertiary = Color(0xFF8B704F), tertiaryContainer = Color(0xFFF3E2C8),
    background = Color(0xFFFFF8F1), onBackground = Color(0xFF3D3034),
    surface = Color(0xFFFFF8F1), onSurface = Color(0xFF3D3034),
    surfaceVariant = Color(0xFFF1E6DD), onSurfaceVariant = Color(0xFF78666C),
    surfaceDim = Color(0xFFE8DDD6), surfaceBright = Color(0xFFFFF8F1),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCF1E9),
    surfaceContainer = Color(0xFFF7EBE2), surfaceContainerHigh = Color(0xFFF1E4DC),
    surfaceContainerHighest = Color(0xFFEDDFD6),
    outline = Color(0xFFAA949A), outlineVariant = Color(0xFFE1D2D0),
    error = Color(0xFFB33E49),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEAB5C3), onPrimary = Color(0xFF492333),
    primaryContainer = Color(0xFF5E3545), onPrimaryContainer = Color(0xFFF9DDE5),
    secondary = Color(0xFFBECEB5), onSecondary = Color(0xFF2C3C29),
    secondaryContainer = Color(0xFF3D4B36), onSecondaryContainer = Color(0xFFDFEAD6),
    tertiary = Color(0xFFE3C69E), tertiaryContainer = Color(0xFF51402D),
    background = Color(0xFF211C20), onBackground = Color(0xFFF0E2E0),
    surface = Color(0xFF211C20), onSurface = Color(0xFFF0E2E0),
    surfaceVariant = Color(0xFF3C3036), onSurfaceVariant = Color(0xFFD1BDC1),
    surfaceDim = Color(0xFF211C20), surfaceBright = Color(0xFF453840),
    surfaceContainerLowest = Color(0xFF1A1519), surfaceContainerLow = Color(0xFF292228),
    surfaceContainer = Color(0xFF30272D), surfaceContainerHigh = Color(0xFF3B3037),
    surfaceContainerHighest = Color(0xFF453840),
    outline = Color(0xFF9E8990), outlineVariant = Color(0xFF504149),
    error = Color(0xFFFFB2BA),
)

private val LoveTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 42.sp, lineHeight = 48.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp),
)

@Composable
fun OpenWhenTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = LoveTypography, content = content)
}

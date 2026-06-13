package com.pistream.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PiPalette {
    val Burgundy = Color(0xFF6E1423)
    val BurgundyDeep = Color(0xFF561019)
    val BurgundyBright = Color(0xFFA12C40)
    val BurgundySoft = Color(0xFFF5E6E9)
    val BurgundyOnSoft = Color(0xFF3F0C16)

    val NeutralBackground = Color(0xFFFAFAFC)
    val NeutralSurface = Color(0xFFFFFFFF)
    val NeutralSurfaceMuted = Color(0xFFF2F2F5)
    val NeutralSurfaceMutedDark = Color(0xFFEDEDF0)
    val NeutralCharcoal = Color(0xFF1C1C1E)
    val NeutralGraphite = Color(0xFF3A3A3C)
    val NeutralSlate = Color(0xFF6E6E73)
    val NeutralLine = Color(0xFFD1D1D6)

    val DarkBackground = Color(0xFF000000)
    val DarkSurface = Color(0xFF1C1C1E)
    val DarkSurfaceElev = Color(0xFF2C2C2E)
    val DarkSurfaceMuted = Color(0xFF3A3A3C)
    val DarkOnSurface = Color(0xFFF5F5F7)
    val DarkOnSurfaceVariant = Color(0xFFAEAEB2)
    val DarkLine = Color(0xFF48484A)

    val WarningChipBg = Color(0xFFFFF4DF)
    val WarningChipFg = Color(0xFF5C3B00)
    val WarningChipDarkBg = Color(0xFF3D2A00)
    val WarningChipDarkFg = Color(0xFFFFD89E)
}

private val LightColors = lightColorScheme(
    primary = PiPalette.Burgundy,
    onPrimary = Color.White,
    primaryContainer = PiPalette.BurgundySoft,
    onPrimaryContainer = PiPalette.BurgundyOnSoft,
    secondary = PiPalette.NeutralGraphite,
    onSecondary = Color.White,
    secondaryContainer = PiPalette.NeutralSurfaceMuted,
    onSecondaryContainer = PiPalette.NeutralCharcoal,
    tertiary = PiPalette.BurgundyDeep,
    onTertiary = Color.White,
    tertiaryContainer = PiPalette.WarningChipBg,
    onTertiaryContainer = PiPalette.WarningChipFg,
    background = PiPalette.NeutralBackground,
    onBackground = PiPalette.NeutralCharcoal,
    surface = PiPalette.NeutralSurface,
    onSurface = PiPalette.NeutralCharcoal,
    surfaceVariant = PiPalette.NeutralSurfaceMuted,
    onSurfaceVariant = PiPalette.NeutralSlate,
    surfaceContainer = PiPalette.NeutralSurfaceMuted,
    surfaceContainerHigh = PiPalette.NeutralSurfaceMutedDark,
    outline = PiPalette.NeutralLine,
    outlineVariant = PiPalette.NeutralLine,
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF7A1212)
)

private val DarkColors = darkColorScheme(
    primary = PiPalette.BurgundyBright,
    onPrimary = Color.White,
    primaryContainer = PiPalette.BurgundyDeep,
    onPrimaryContainer = Color(0xFFFADCE2),
    secondary = PiPalette.DarkOnSurfaceVariant,
    onSecondary = PiPalette.NeutralCharcoal,
    secondaryContainer = PiPalette.DarkSurfaceElev,
    onSecondaryContainer = PiPalette.DarkOnSurface,
    tertiary = PiPalette.BurgundyBright,
    onTertiary = Color.White,
    tertiaryContainer = PiPalette.WarningChipDarkBg,
    onTertiaryContainer = PiPalette.WarningChipDarkFg,
    background = PiPalette.DarkBackground,
    onBackground = PiPalette.DarkOnSurface,
    surface = PiPalette.DarkSurface,
    onSurface = PiPalette.DarkOnSurface,
    surfaceVariant = PiPalette.DarkSurfaceElev,
    onSurfaceVariant = PiPalette.DarkOnSurfaceVariant,
    surfaceContainer = PiPalette.DarkSurfaceElev,
    surfaceContainerHigh = PiPalette.DarkSurfaceMuted,
    outline = PiPalette.DarkLine,
    outlineVariant = PiPalette.DarkLine,
    error = Color(0xFFFF6259),
    onError = Color.White,
    errorContainer = Color(0xFF4A0E10),
    onErrorContainer = Color(0xFFFFD3CF)
)

private val PiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val PiTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp
    )
)

@Composable
fun PiStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PiTypography,
        shapes = PiShapes,
        content = content
    )
}

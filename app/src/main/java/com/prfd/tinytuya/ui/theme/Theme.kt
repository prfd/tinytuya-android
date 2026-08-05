package com.prfd.tinytuya.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = Teal90,
    onPrimary = Teal30,
    primaryContainer = Teal30,
    onPrimaryContainer = Mint95,
    secondary = Slate80,
    onSecondary = Slate30,
    secondaryContainer = Slate30,
    onSecondaryContainer = Slate90,
    tertiary = Amber80,
    onTertiary = Amber30,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = Night,
    onBackground = Color(0xFFE4EAE6),
    surface = Night,
    onSurface = Color(0xFFE4EAE6),
    surfaceVariant = NightSurfaceRaised,
    onSurfaceVariant = Color(0xFFBDC8C2),
    outline = Color(0xFF87918C),
    outlineVariant = Color(0xFF3E4944),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Mint95,
    onPrimaryContainer = Color(0xFF002019),
    secondary = Slate40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E7DF),
    onSecondaryContainer = Color(0xFF101F1A),
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Color(0xFF241A00),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperWarm,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFF707A75),
    outlineVariant = Color(0xFFBFC9C3),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
  )

private val TinyTuyaShapes =
  Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
  )

@Composable
fun TinytuyaTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    typography = Typography,
    shapes = TinyTuyaShapes,
    content = content,
  )
}

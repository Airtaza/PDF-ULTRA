package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ElectricCyan,
    secondary = DarkCyan,
    tertiary = NeonOrange,
    background = SlateDarkBg,
    surface = SlateCardBg,
    onBackground = SoftSilver,
    onSurface = SoftSilver,
    surfaceVariant = Color(0xFF161825),
    onSurfaceVariant = SoftSilver
  )

private val LightColorScheme =
  lightColorScheme(
    primary = DarkCyan,
    secondary = ElectricCyan,
    tertiary = NeonOrange,
    background = Color(0xFFFAFAFC),
    surface = Color.White,
    onBackground = Color(0xFF0F111A),
    onSurface = Color(0xFF0F111A)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

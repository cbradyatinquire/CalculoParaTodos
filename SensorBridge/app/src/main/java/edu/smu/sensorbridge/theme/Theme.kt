package edu.smu.sensorbridge.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary            = Blue80,
    onPrimary          = Color(0xFF003087),
    primaryContainer   = Color(0xFF1565C0),
    onPrimaryContainer = Color(0xFFBBDEFB),
    secondary          = BlueGrey80,
    onSecondary        = Color(0xFF263238),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color(0xFFCFD8DC),
    tertiary           = Teal80,
    onTertiary         = Color(0xFF004D57),
    tertiaryContainer  = Color(0xFF00838F),
    onTertiaryContainer = Color(0xFFE0F7FA),
)

private val LightColorScheme = lightColorScheme(
    primary            = Blue40,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFBBDEFB),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary          = BlueGrey40,
    onSecondary        = Color.White,
    secondaryContainer = Color(0xFFCFD8DC),
    onSecondaryContainer = Color(0xFF263238),
    tertiary           = Teal40,
    onTertiary         = Color.White,
    tertiaryContainer  = Color(0xFFE0F7FA),
    onTertiaryContainer = Color(0xFF006064),
    background         = Color(0xFFF8F9FB),
    surface            = Color.White,
    surfaceVariant     = Color(0xFFE3EAF4),
    onSurfaceVariant   = Color(0xFF546E7A),
)

@Composable
fun SensorBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

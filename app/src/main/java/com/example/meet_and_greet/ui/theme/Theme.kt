package com.example.meet_and_greet.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val FlagmanDarkColorScheme = darkColorScheme(
    primary = FlagmanPrimary,
    onPrimary = FlagmanOnPrimary,
    primaryContainer = FlagmanSurfaceSelected,
    onPrimaryContainer = FlagmanTextPrimary,
    secondary = FlagmanAccentBlue,
    onSecondary = FlagmanOnPrimary,
    secondaryContainer = FlagmanSurfaceVariant,
    onSecondaryContainer = FlagmanTextPrimary,
    background = FlagmanBg,
    onBackground = FlagmanTextPrimary,
    surface = FlagmanSurface,
    onSurface = FlagmanTextPrimary,
    surfaceVariant = FlagmanSurfaceVariant,
    onSurfaceVariant = FlagmanTextSecondary,
    outline = FlagmanBorder
)

@Composable
fun MeetandGreetTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = FlagmanBg.toArgb()
                window.navigationBarColor = FlagmanBg.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = FlagmanDarkColorScheme,
        typography = Typography,
        content = content
    )
}

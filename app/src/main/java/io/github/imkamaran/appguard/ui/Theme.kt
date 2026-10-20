package io.github.imkamaran.appguard.ui

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
import io.github.imkamaran.appguard.policy.ThemeMode
import io.github.imkamaran.appguard.policy.UiSettings

private val Light = lightColorScheme(
    primary = Color(0xFF1B6B55),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2D6),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4C6359),
    secondaryContainer = Color(0xFFCEE9DB),
    onSecondaryContainer = Color(0xFF082018),
    tertiary = Color(0xFF3E6374),
    tertiaryContainer = Color(0xFFC2E8FC),
    onTertiaryContainer = Color(0xFF001F2A),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF5FBF6),
    surface = Color(0xFFF5FBF6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AD6BA),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFA6F2D6),
    secondary = Color(0xFFB3CCC0),
    secondaryContainer = Color(0xFF354B42),
    onSecondaryContainer = Color(0xFFCEE9DB),
    tertiary = Color(0xFFA6CCDF),
    tertiaryContainer = Color(0xFF254B5B),
    onTertiaryContainer = Color(0xFFC2E8FC),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF0F1512),
    surface = Color(0xFF0F1512),
)

/** Whether the app should currently render dark, given the user's choice. */
@Composable
fun isAppInDarkTheme(ui: UiSettings): Boolean = when (ui.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AppGuardTheme(ui: UiSettings, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(ui)
    val scheme = when {
        ui.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

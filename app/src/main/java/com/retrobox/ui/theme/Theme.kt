package com.retrobox.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.google.accompanist.systemuicontroller.rememberSystemUiController

/**
 * Dark cyberpunk color scheme - the primary look of RetroBox.
 * Neon purple drives `primary`, neon cyan drives `secondary`, neon pink drives `tertiary`.
 */
val RetroBoxDarkColorScheme = darkColorScheme(
    primary = NeonPurple,
    onPrimary = Color(0xFF1A0030),
    primaryContainer = DimPurple,
    onPrimaryContainer = GlowPurple,
    inversePrimary = NeonCyan,
    secondary = NeonCyan,
    onSecondary = Color(0xFF001014),
    secondaryContainer = DimCyan,
    onSecondaryContainer = GlowCyan,
    tertiary = NeonPink,
    onTertiary = Color(0xFF2A0014),
    tertiaryContainer = Color(0xFF5E0A2E),
    onTertiaryContainer = GlowPink,
    background = CyberBackground,
    onBackground = CyberOnSurface,
    surface = CyberSurface,
    onSurface = CyberOnSurface,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = CyberOnSurfaceVariant,
    surfaceTint = NeonPurple,
    inverseSurface = Color(0xFFE6E1F0),
    inverseOnSurface = Color(0xFF13111E),
    error = ErrorRed,
    onError = Color(0xFF600003),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = CyberOutlineHigh,
    outlineVariant = CyberOutline,
    scrim = Color(0xFF000000)
)

/**
 * Light scheme kept for completeness / accessibility; the app is dark-first.
 */
val RetroBoxLightColorScheme = lightColorScheme(
    primary = NeonViolet,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = GlowPurple,
    onPrimaryContainer = Color(0xFF2A0040),
    secondary = Color(0xFF0099AA),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = GlowCyan,
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFFB40057),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E0),
    onTertiaryContainer = Color(0xFF400016),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = Color(0xFFE7E0F0),
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = NeonViolet,
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF7A7585),
    outlineVariant = Color(0xFFCAC4D0)
)

/**
 * Root theme for the whole app.
 *
 * @param darkTheme Whether to use the dark cyberpunk scheme. Defaults to the
 *                  system setting, but the dark scheme is the intended look.
 * @param dynamicColor Whether to use Material You dynamic color on Android 12+.
 *                     Disabled by default so the neon brand is always preserved.
 */
@Composable
fun RetroBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color is intentionally off by default to keep the neon identity.
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) {
                androidx.compose.material3.dynamicDarkColorScheme(context)
            } else {
                androidx.compose.material3.dynamicLightColorScheme(context)
            }
        }
        darkTheme -> RetroBoxDarkColorScheme
        else -> RetroBoxLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val controller = rememberSystemUiController()
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge layout so the neon surfaces bleed under the bars.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Keep the system bars transparent and legible over dark surfaces.
            controller.setSystemBarsColor(
                color = Color.Transparent,
                darkIcons = false
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RetroBoxTypography,
        content = content
    )
}

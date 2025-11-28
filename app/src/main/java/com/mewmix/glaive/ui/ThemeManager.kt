package com.mewmix.glaive.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class GlaiveColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val error: Color
)

@Immutable
data class GlaiveShapes(
    val cornerRadius: Dp,
    val borderWidth: Dp
)

@Immutable
data class GlaiveTypography(
    val fontFamily: FontFamily,
    val name: String
)

@Immutable
data class ThemeConfig(
    val colors: GlaiveColors,
    val shapes: GlaiveShapes,
    val typography: GlaiveTypography
)

object ThemeDefaults {
    val Colors = GlaiveColors(
        background = Color(0xFF0A0A0A), // DeepSpace
        surface = Color(0xFF181818),    // SurfaceGray
        text = Color(0xFFEEEEEE),       // SoftWhite
        accent = Color(0xFF00E676),     // NeonGreen
        error = Color(0xFFD32F2F)       // DangerRed
    )
    val Shapes = GlaiveShapes(
        cornerRadius = 12.dp,
        borderWidth = 1.dp
    )
    val Typography = GlaiveTypography(
        fontFamily = FontFamily.Monospace,
        name = "Monospace"
    )
}

val LocalGlaiveTheme = staticCompositionLocalOf {
    ThemeConfig(ThemeDefaults.Colors, ThemeDefaults.Shapes, ThemeDefaults.Typography)
}

object ThemeManager {
    private const val PREFS_NAME = "glaive_theme"

    // Keys
    private const val KEY_BG = "color_bg"
    private const val KEY_SURFACE = "color_surface"
    private const val KEY_TEXT = "color_text"
    private const val KEY_ACCENT = "color_accent"
    private const val KEY_ERROR = "color_error"
    private const val KEY_RADIUS = "shape_radius"
    private const val KEY_BORDER = "shape_border"
    private const val KEY_FONT = "type_font"

    fun loadTheme(context: Context): ThemeConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val colors = GlaiveColors(
            background = Color(prefs.getInt(KEY_BG, ThemeDefaults.Colors.background.toArgb())),
            surface = Color(prefs.getInt(KEY_SURFACE, ThemeDefaults.Colors.surface.toArgb())),
            text = Color(prefs.getInt(KEY_TEXT, ThemeDefaults.Colors.text.toArgb())),
            accent = Color(prefs.getInt(KEY_ACCENT, ThemeDefaults.Colors.accent.toArgb())),
            error = Color(prefs.getInt(KEY_ERROR, ThemeDefaults.Colors.error.toArgb()))
        )

        val shapes = GlaiveShapes(
            cornerRadius = prefs.getFloat(KEY_RADIUS, 12f).dp,
            borderWidth = prefs.getFloat(KEY_BORDER, 1f).dp
        )

        val fontName = prefs.getString(KEY_FONT, "Monospace") ?: "Monospace"
        val fontFamily = when(fontName) {
            "SansSerif" -> FontFamily.SansSerif
            "Serif" -> FontFamily.Serif
            "Cursive" -> FontFamily.Cursive
            else -> FontFamily.Monospace
        }

        return ThemeConfig(colors, shapes, GlaiveTypography(fontFamily, fontName))
    }

    fun saveTheme(context: Context, config: ThemeConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_BG, config.colors.background.toArgb())
            putInt(KEY_SURFACE, config.colors.surface.toArgb())
            putInt(KEY_TEXT, config.colors.text.toArgb())
            putInt(KEY_ACCENT, config.colors.accent.toArgb())
            putInt(KEY_ERROR, config.colors.error.toArgb())
            putFloat(KEY_RADIUS, config.shapes.cornerRadius.value)
            putFloat(KEY_BORDER, config.shapes.borderWidth.value)
            putString(KEY_FONT, config.typography.name)
        }.apply()
    }
}

@Composable
fun GlaiveTheme(
    config: ThemeConfig,
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = config.colors.accent,
        onPrimary = Color.Black,
        background = config.colors.background,
        onBackground = config.colors.text,
        surface = config.colors.surface,
        onSurface = config.colors.text,
        error = config.colors.error,
        onError = Color.White
    )

    CompositionLocalProvider(
        LocalGlaiveTheme provides config
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = {
                ProvideTextStyle(
                    value = TextStyle(
                        fontFamily = config.typography.fontFamily,
                        color = config.colors.text
                    ),
                    content = content
                )
            }
        )
    }
}

package com.telematicslab.demo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.telematicslab.demo.sdk.api.EventType

data class TelemetryPalette(
    val bg: Color,
    val card: Color,
    val subCard: Color,
    val border: Color,
    val cyan: Color,
    val teal: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val purple: Color,
    val slate: Color,
    val textPrimary: Color,
    val onAccent: Color
)

private val DarkPalette = TelemetryPalette(
    bg = Color(0xFF0A0F1C),
    card = Color(0xFF131C31),
    subCard = Color(0xFF0E1627),
    border = Color(0xFF233252),
    cyan = Color(0xFF38BDF8),
    teal = Color(0xFF2DD4BF),
    green = Color(0xFF4ADE80),
    amber = Color(0xFFFBBF24),
    red = Color(0xFFF87171),
    purple = Color(0xFFC084FC),
    slate = Color(0xFF94A3B8),
    textPrimary = Color(0xFFF1F5F9),
    onAccent = Color(0xFF061322)
)

private val LightPalette = TelemetryPalette(
    bg = Color(0xFFF1F5F9),
    card = Color(0xFFFFFFFF),
    subCard = Color(0xFFE2E8F0),
    border = Color(0xFFCBD5E1),
    cyan = Color(0xFF0284C7),
    teal = Color(0xFF0D9488),
    green = Color(0xFF15803D),
    amber = Color(0xFFB45309),
    red = Color(0xFFDC2626),
    purple = Color(0xFF7E22CE),
    slate = Color(0xFF475569),
    textPrimary = Color(0xFF0F172A),
    onAccent = Color(0xFFFFFFFF)
)

object TelemetryColors {
    internal var activePalette by mutableStateOf(DarkPalette)

    val BgDark: Color get() = activePalette.bg
    val CardDark: Color get() = activePalette.card
    val SubCardDark: Color get() = activePalette.subCard
    val BorderDark: Color get() = activePalette.border

    val Cyan: Color get() = activePalette.cyan
    val Teal: Color get() = activePalette.teal
    val Green: Color get() = activePalette.green
    val Amber: Color get() = activePalette.amber
    val Red: Color get() = activePalette.red
    val Purple: Color get() = activePalette.purple
    val Slate: Color get() = activePalette.slate
    val TextPrimary: Color get() = activePalette.textPrimary
    val OnAccent: Color get() = activePalette.onAccent
}

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.cyan,
    onPrimary = DarkPalette.onAccent,
    secondary = DarkPalette.teal,
    onSecondary = Color(0xFF051A17),
    background = DarkPalette.bg,
    onBackground = DarkPalette.textPrimary,
    surface = DarkPalette.card,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.subCard,
    onSurfaceVariant = DarkPalette.slate,
    outline = DarkPalette.border,
    error = DarkPalette.red
)

private val LightScheme = lightColorScheme(
    primary = LightPalette.cyan,
    onPrimary = LightPalette.onAccent,
    secondary = LightPalette.teal,
    onSecondary = Color(0xFFFFFFFF),
    background = LightPalette.bg,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.card,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = LightPalette.subCard,
    onSurfaceVariant = LightPalette.slate,
    outline = LightPalette.border,
    error = LightPalette.red
)

private val LabTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

fun eventColor(type: EventType): Color = when (type) {
    EventType.HARD_BRAKE -> TelemetryColors.Red
    EventType.RAPID_ACCEL -> TelemetryColors.Amber
    EventType.HARSH_CORNER_LEFT, EventType.HARSH_CORNER_RIGHT -> TelemetryColors.Cyan
    EventType.SPEEDING -> Color(0xFFEA580C)
    EventType.DISTRACTION_SCREEN, EventType.DISTRACTION_HANDHELD -> TelemetryColors.Purple
    EventType.POTENTIAL_IMPACT -> Color(0xFFDC2626)
}

fun scoreColor(score: Int): Color = when {
    score >= 85 -> TelemetryColors.Green
    score >= 70 -> TelemetryColors.Amber
    else -> TelemetryColors.Red
}

@Composable
fun TelematicsLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    TelemetryColors.activePalette = if (darkTheme) DarkPalette else LightPalette
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.background.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LabTypography,
        content = content
    )
}

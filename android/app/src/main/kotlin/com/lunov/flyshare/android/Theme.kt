package com.lunov.flyshare.android

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * The desktop's palette, value for value — see ui/app.css.
 *
 * The interface is deliberately monochrome: cool paper, ink type, one
 * interactive blue. Green is not decoration either; it means a device is
 * paired and reachable, and nothing else in the app may use it.
 *
 * Sharing the numbers rather than approximating them is what makes the two
 * halves read as one product. A phone and a laptop side by side show the same
 * greys, and a colour that had drifted would be obvious.
 */
@Immutable
data class Palette(
    val paper: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val lineSoft: Color,
    val signal: Color,
    val signalInk: Color,
    val signalSoft: Color,
    val ok: Color,
    val err: Color,
)

private val LightPalette = Palette(
    paper = Color(0xFFE8ECF1),
    surface = Color(0xFFFBFCFD),
    surface2 = Color(0xFFF1F4F8),
    ink = Color(0xFF0F1922),
    ink2 = Color(0xFF55636F),
    ink3 = Color(0xFF8794A1),
    line = Color(0xFFCDD6E0),
    lineSoft = Color(0xFFE1E7ED),
    signal = Color(0xFF1F4FE0),
    signalInk = Color(0xFFFFFFFF),
    signalSoft = Color(0x171F4FE0),
    ok = Color(0xFF0E7A58),
    err = Color(0xFFB3261E),
)

private val DarkPalette = Palette(
    paper = Color(0xFF0B1119),
    surface = Color(0xFF141D27),
    surface2 = Color(0xFF1A242F),
    ink = Color(0xFFE7EDF3),
    ink2 = Color(0xFF9AA8B5),
    ink3 = Color(0xFF6B7987),
    line = Color(0xFF27333F),
    lineSoft = Color(0xFF1D2731),
    signal = Color(0xFF6C8DFF),
    signalInk = Color(0xFF0B1119),
    signalSoft = Color(0x246C8DFF),
    ok = Color(0xFF3EC99B),
    err = Color(0xFFFF6B60),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Corner radii, from the same stylesheet: 12 for cards, 8 for controls. */
object Radius {
    val card: Dp = 12.dp
    val control: Dp = 8.dp
    val dialog: Dp = 18.dp
}

/**
 * The type roles the desktop uses.
 *
 * Small capitals with wide tracking do a lot of the work there, and replacing
 * them with plain sentence case is most of what would stop a port from looking
 * like the same program.
 */
object Type {
    val eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.W600, letterSpacing = 1.65.sp)
    val peerName = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W600)
    val meta = TextStyle(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
    val action = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W600, letterSpacing = 0.72.sp)
    val status = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.W600, letterSpacing = 0.99.sp)
    val title = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.W600)
    val body = TextStyle(fontSize = 14.sp)
    val display = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.2).sp)
}

@Composable
fun FlyShareTheme(theme: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }
    val palette = if (dark) DarkPalette else LightPalette

    // Material's own components — dialogs, the progress bar — read this scheme,
    // so it is filled from the same values rather than left to default. Every
    // surface role is named on purpose: an unset one falls back to Material's
    // purple-tinted default, and an app with no purple in it gets lilac dialogs.
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.signal,
            onPrimary = palette.signalInk,
            secondary = palette.signal,
            background = palette.paper,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surface2,
            onSurfaceVariant = palette.ink2,
            surfaceContainerLowest = palette.paper,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface,
            surfaceContainerHighest = palette.surface2,
            outline = palette.line,
            outlineVariant = palette.lineSoft,
            error = palette.err,
        )
    } else {
        lightColorScheme(
            primary = palette.signal,
            onPrimary = palette.signalInk,
            secondary = palette.signal,
            background = palette.paper,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surface2,
            onSurfaceVariant = palette.ink2,
            surfaceContainerLowest = palette.surface,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface,
            surfaceContainerHighest = palette.surface2,
            outline = palette.line,
            outlineVariant = palette.lineSoft,
            error = palette.err,
        )
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * A section heading: small capitals, then a rule across the rest of the row.
 *
 * The rule is the desktop's, and it is what keeps a screen of stacked cards
 * from reading as one undifferentiated list.
 */
@Composable
fun Eyebrow(label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.eyebrow, color = if (accent) palette.signal else palette.ink3)
        Box(
            Modifier
                .padding(start = 14.dp)
                .fillMaxWidth()
                .height(1.dp)
                .drawBehind { drawRect(palette.line) },
        )
    }
}

/**
 * A one-pixel outline, dashed for a device that has not been paired yet.
 *
 * Compose has no dashed border modifier, and the distinction earns the few
 * lines: an unpaired device is an invitation, not an entry.
 */
fun Modifier.hairline(color: Color, radius: Dp, dashed: Boolean = false): Modifier =
    drawBehind {
        val width = 1.dp.toPx()
        val effect = if (dashed) {
            PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        } else {
            null
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = width, pathEffect = effect),
        )
    }

val LocalAppLocale = staticCompositionLocalOf<Locale?> { null }

/**
 * Render in a chosen language without restarting the app.
 *
 * The per-app language setting only exists from API 33 and this app supports
 * 26, so the context is localised here instead. Everything below reads its
 * strings from the overridden context, so a change takes effect on the next
 * frame rather than the next launch.
 */
@Composable
fun WithLanguage(locale: Locale?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppLocale provides locale) {
        Localized(content)
    }
}

/**
 * Apply the chosen language to everything below.
 *
 * Dialogs need this again on the inside. A Compose dialog is a separate Android
 * window with its own view, and that view provides its own LocalContext and
 * LocalConfiguration — overriding whatever the parent composition set. So a
 * language change reaches the screen behind the dialog and not the dialog
 * itself, which looks like the setting half-working.
 */
@Composable
fun Localized(content: @Composable () -> Unit) {
    val locale = LocalAppLocale.current
    if (locale == null) {
        content()
        return
    }

    val base = LocalContext.current
    val configuration = remember(locale, LocalConfiguration.current) {
        Configuration(base.resources.configuration).apply { setLocale(locale) }
    }

    // A wrapper around the Activity, not a context created from scratch.
    // Compose finds the activity result registry — the thing that owns the file
    // picker and the permission prompt — by unwrapping LocalContext until it
    // reaches an Activity. createConfigurationContext() returns a context with
    // no such chain, so that walk fails and the first launcher to be remembered
    // throws. The app renders perfectly right up until it does.
    val localized = remember(base, configuration) {
        ContextThemeWrapper(base, 0).apply { applyOverrideConfiguration(configuration) }
    }

    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides configuration,
        content = content,
    )
}

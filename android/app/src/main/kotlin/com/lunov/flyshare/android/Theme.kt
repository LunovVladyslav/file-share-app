package com.lunov.flyshare.android

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * One accent, used for one thing: a device you can act on.
 *
 * The green also marks a paired device, so the same colour means "this link is
 * established" wherever it appears. Everything else is neutral, because in an
 * app whose whole screen is a list of devices, colour is the only thing that
 * can pick one out.
 */
private val Accent = Color(0xFF17B885)
private val AccentDark = Color(0xFF3EC99B)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00382A),
    secondary = AccentDark,
    background = Color(0xFF101214),
    onBackground = Color(0xFFE6E8EA),
    surface = Color(0xFF101214),
    onSurface = Color(0xFFE6E8EA),
    surfaceVariant = Color(0xFF1C1F23),
    onSurfaceVariant = Color(0xFF9BA3AB),
    // The container roles have to be named too. Left unset they fall back to
    // Material's own purple-tinted defaults, and a dialog turns up lilac in an
    // app that has no purple in it anywhere.
    surfaceContainerLowest = Color(0xFF0B0D0F),
    surfaceContainerLow = Color(0xFF15181B),
    surfaceContainer = Color(0xFF1C1F23),
    surfaceContainerHigh = Color(0xFF22262B),
    surfaceContainerHighest = Color(0xFF292E33),
    outline = Color(0xFF3A4046),
    outlineVariant = Color(0xFF2A2F34),
    error = Color(0xFFFF8A80),
)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent,
    background = Color(0xFFF7F8F9),
    onBackground = Color(0xFF13171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF13171A),
    surfaceVariant = Color(0xFFEDEFF2),
    onSurfaceVariant = Color(0xFF5A626A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF4F6F8),
    surfaceContainerHigh = Color(0xFFEFF1F4),
    surfaceContainerHighest = Color(0xFFE9ECEF),
    outline = Color(0xFFC2C8CE),
    outlineVariant = Color(0xFFDFE3E7),
    error = Color(0xFFB3261E),
)

@Composable
fun FlyShareTheme(theme: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}

/**
 * Render in a chosen language without restarting the app.
 *
 * The per-app language setting only exists from API 33 and this app supports
 * 26, so the context is localised here instead. Everything below reads its
 * strings from the overridden context, so a change takes effect on the next
 * frame rather than the next launch.
 */
val LocalAppLocale = staticCompositionLocalOf<Locale?> { null }

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

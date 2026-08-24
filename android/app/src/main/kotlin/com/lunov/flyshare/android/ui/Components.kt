package com.lunov.flyshare.android.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunov.flyshare.android.LocalPalette
import com.lunov.flyshare.android.R
import com.lunov.flyshare.android.Radius
import com.lunov.flyshare.android.Type
import com.lunov.flyshare.android.hairline
import com.lunov.flyshare.core.SizeFormat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The bar every screen but the first one carries: back, then a title. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(Radius.control))
                .clickable(onClick = onBack)
                .padding(8.dp),
        ) {
            Icon(Glyph.Back, palette.ink2, description = stringResource(R.string.back))
        }
        Text(
            title,
            style = Type.display,
            color = palette.ink,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        action?.invoke()
    }
}

/** A tappable row: a glyph, a label with a note under it, and a chevron. */
@Composable
fun SettingRow(
    glyph: Glyph,
    label: String,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(glyph, palette.ink3, Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading?.let { Box(Modifier.padding(end = 8.dp)) { it() } }
                Text(label, style = Type.body, color = palette.ink)
            }
            value?.let {
                Text(
                    it,
                    style = Type.meta,
                    color = palette.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) trailing() else if (onClick != null) Icon(Glyph.Chevron, palette.ink3, size = 16.dp)
    }
}

/** A text action: signal-coloured, no box. */
@Composable
fun QuietButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        label,
        style = Type.body.copy(fontSize = 12.5.sp),
        color = palette.signal,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.control))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** The one filled control: the thing to do next. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        label,
        style = Type.body.copy(fontSize = 13.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W600),
        color = palette.signalInk,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(palette.signal)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(if (selected) palette.signal else palette.surface2)
            .hairline(if (selected) palette.signal else palette.line, Radius.control)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Type.body.copy(fontSize = 13.5.sp),
            textAlign = TextAlign.Center,
            // One line: chips share the width equally, and the longest word in
            // the longest language decides whether any of them wrap.
            maxLines = 1,
            color = if (selected) palette.signalInk else palette.ink,
        )
    }
}

/** Four pixels of track, exactly as on the desktop. */
@Composable
fun Track(fraction: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 4.dp) {
    val palette = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(palette.surface2),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(palette.signal),
        )
    }
}

/**
 * How long it has been running, and how long is left.
 *
 * Both, because they answer different questions. The estimate moves around
 * with the link and is a guess; the elapsed time is the one number on the card
 * that cannot be wrong, and on a transfer measured in hours it is the one
 * people actually watch.
 */
@Composable
fun Timing(
    startedAt: Long,
    remainingSeconds: Double?,
    ticking: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    if (startedAt <= 0L) return

    // A tick a second: the elapsed reading has to move on its own, not only
    // when a progress update happens to arrive. It stops when the transfer
    // does, so a paused card does not quietly count the pause as its own time.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt, ticking) {
        while (ticking) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Row(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.transfer_elapsed, formatDuration((now - startedAt) / 1000.0)),
            style = Type.meta,
            color = palette.ink3,
        )
        if (remainingSeconds != null && remainingSeconds.isFinite() && remainingSeconds >= 0) {
            Text(
                stringResource(R.string.transfer_remaining, formatDuration(remainingSeconds)),
                style = Type.meta,
                color = palette.ink3,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Seconds left, from the average rate so far.
 *
 * The average rather than the instantaneous rate: a Wi-Fi link swings enough
 * that a live figure makes the estimate jump around, and over a long transfer
 * the average is both steadier and closer to right.
 */
fun estimate(startedAt: Long, done: Long, total: Long): Double? {
    if (startedAt <= 0L || done <= 0L || total <= done) return null
    val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
    if (elapsed <= 0) return null
    return (total - done) / (done / elapsed)
}

/**
 * Decimal units, and the same rounding as the desktop.
 *
 * The two halves showing different totals for one transfer is worse than
 * either convention being wrong. See bytes() in ui/app.js — change one and you
 * must change both.
 */
@Composable
fun formatBytes(bytes: Long): String {
    val units = stringArrayResource(R.array.units_bytes)
    val (value, unit, digits) = SizeFormat.scale(bytes, units.size)
    return "%.${digits}f %s".format(value, units[unit])
}

/** The same shape as duration() in ui/app.js: seconds, then minutes, then hours. */
@Composable
fun formatDuration(seconds: Double): String {
    if (seconds < 60) {
        return stringResource(R.string.time_seconds, maxOf(1.0, seconds).roundToInt())
    }
    val minutes = (seconds / 60).toInt()
    return if (minutes < 60) {
        stringResource(R.string.time_minutes, minutes, (seconds % 60).roundToInt())
    } else {
        stringResource(R.string.time_hours, minutes / 60, minutes % 60)
    }
}

/** A card with the app's hairline outline. */
@Composable
fun OutlinedCard(
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    filled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .then(if (filled) Modifier.background(palette.surface) else Modifier)
            .hairline(palette.line, Radius.card, dashed = dashed)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        content()
    }
}

/** Small capitals with a rule running to the edge. */
@Composable
fun SectionLabel(label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.eyebrow, color = if (accent) palette.signal else palette.ink3)
        Box(
            Modifier
                .padding(start = 14.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.line),
        )
    }
}

/** An empty screen that says what would be here. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = Type.body, color = palette.ink3, textAlign = TextAlign.Center)
    }
}


/**
 * The liveness dot. A paired device pulses; an unpaired one sits still.
 *
 * It is the only motion on the screen, which is what makes it readable at a
 * glance — "that machine is there right now" — rather than decoration.
 */
@Composable
fun Beacon(paired: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    if (!paired) {
        Dot(palette.ink3, modifier)
        return
    }

    val transition = rememberInfiniteTransition(label = "beacon")
    val halo by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearOutSlowInEasing)),
        label = "halo",
    )

    Box(modifier.size(7.dp), contentAlignment = Alignment.Center) {
        Dot(palette.ok.copy(alpha = 0.45f * (1f - halo)), size = 7.dp + (9.dp * halo))
        Dot(palette.ok, size = 7.dp)
    }
}

/** Space between stacked rows, matching the desktop's ten pixels. */
val RowGap = Arrangement.spacedBy(10.dp)

/** A dot, filled when the thing it marks is live. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 7.dp) {
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(color))
}

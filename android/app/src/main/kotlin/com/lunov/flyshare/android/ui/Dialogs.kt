package com.lunov.flyshare.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunov.flyshare.android.LocalPalette
import com.lunov.flyshare.android.Localized
import com.lunov.flyshare.android.R
import com.lunov.flyshare.android.Radius
import com.lunov.flyshare.android.Type
import com.lunov.flyshare.core.IncomingOffer
import com.lunov.flyshare.core.IncomingUi
import com.lunov.flyshare.core.PairingUi

/**
 * Every dialog in the app, so the language wrapper is applied on the inside
 * exactly once rather than being forgotten in one slot out of four.
 *
 * A Compose dialog is a separate Android window with its own view, and that
 * view provides its own LocalContext — overriding whatever the parent set. So
 * a language change reaches the screen behind the dialog and not the dialog
 * itself, which looks like the setting half-working.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(Radius.dialog),
        containerColor = palette.surface,
        titleContentColor = palette.ink,
        textContentColor = palette.ink2,
        title = { Localized(title) },
        text = { Localized(text) },
        confirmButton = { Localized(confirmButton) },
        dismissButton = dismissButton?.let { button -> { Localized(button) } },
    )
}

/**
 * The offer, with the two facts worth deciding on: how much is coming, and
 * where it will end up. Nothing is written until this is answered.
 */
@Composable
fun OfferDialog(
    state: IncomingUi,
    destination: String,
    onAnswer: (Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    val offer: IncomingOffer = (state as? IncomingUi.Ask)?.offer ?: return
    val palette = LocalPalette.current

    AppDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.wants_to_send, offer.peerName), style = Type.display) },
        text = {
            Column {
                Text(
                    pluralStringResource(R.plurals.file_count, offer.files.size, offer.files.size) +
                        " · " + formatBytes(offer.totalSize),
                    style = Type.title,
                    color = palette.ink,
                )
                Text(
                    offer.files.take(4).joinToString("\n") { it.rel } +
                        if (offer.files.size > 4) {
                            "\n" + stringResource(R.string.and_more, offer.files.size - 4)
                        } else {
                            ""
                        },
                    style = Type.meta,
                    color = palette.ink3,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.control))
                        .background(palette.surface2)
                        .padding(10.dp),
                )
                Text(
                    stringResource(R.string.will_be_saved_in, destination),
                    style = Type.body.copy(fontSize = 13.sp),
                    color = palette.ink2,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { PrimaryButton(stringResource(R.string.accept)) { onAnswer(true) } },
        dismissButton = { QuietButton(stringResource(R.string.decline)) { onAnswer(false) } },
    )
}

/**
 * The code, big enough to read from arm's length — someone is comparing it with
 * another screen, which is the entire security model.
 */
@Composable
fun PairingDialog(state: PairingUi, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    if (state is PairingUi.None) return
    val palette = LocalPalette.current

    val (title, code, note) = when (state) {
        is PairingUi.Connecting ->
            Triple(state.peerName, null, stringResource(R.string.exchanging_keys))
        is PairingUi.WaitingForPeer ->
            Triple(state.peerName, state.code, stringResource(R.string.waiting_for_peer, state.peerName))
        is PairingUi.Confirm ->
            Triple(state.peerName, state.code, stringResource(R.string.make_sure_same_code, state.peerName))
        is PairingUi.Done ->
            Triple(state.peerName, null, stringResource(R.string.paired_encrypted))
        is PairingUi.Failed -> Triple(state.peerName, null, state.reason)
        PairingUi.None -> return
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    stringResource(R.string.compare_the_code),
                    style = Type.eyebrow,
                    color = palette.ink3,
                )
                Text(title, style = Type.display, modifier = Modifier.padding(top = 4.dp))
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (code != null) {
                    Text(
                        "${code.take(3)} ${code.drop(3)}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                        letterSpacing = 4.sp,
                        color = palette.ink,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                Text(note, style = Type.body, textAlign = TextAlign.Center, color = palette.ink2)
            }
        },
        confirmButton = {
            when (state) {
                is PairingUi.Confirm ->
                    PrimaryButton(stringResource(R.string.codes_match)) { onAnswer(true) }
                else -> QuietButton(stringResource(R.string.close), onDismiss)
            }
        },
        dismissButton = if (state is PairingUi.Confirm) {
            { QuietButton(stringResource(R.string.codes_differ)) { onAnswer(false) } }
        } else {
            null
        },
    )
}

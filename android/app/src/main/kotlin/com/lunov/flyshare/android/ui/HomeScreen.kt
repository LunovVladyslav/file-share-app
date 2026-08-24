package com.lunov.flyshare.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunov.flyshare.android.LocalPalette
import com.lunov.flyshare.android.R
import com.lunov.flyshare.android.Radius
import com.lunov.flyshare.android.Type
import com.lunov.flyshare.core.IncomingUi
import com.lunov.flyshare.core.OutgoingUi
import com.lunov.flyshare.core.Peer
import com.lunov.flyshare.core.SelfDescription
import com.lunov.flyshare.core.SendStatus
import com.lunov.flyshare.core.TransferStatus

/**
 * The list of devices, and whatever is happening with them.
 *
 * Everything that is not a device or a transfer moved to settings: on a phone
 * the list is the screen, and a row about where downloads go was competing
 * with it for the top of the page.
 */
@Composable
fun HomeScreen(
    self: SelfDescription,
    selfName: String,
    peers: List<Peer>,
    isPaired: (String) -> Boolean,
    waitingToSend: Int,
    incoming: IncomingUi,
    outgoing: OutgoingUi,
    onPeerTapped: (Peer) -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onOpenTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current

    Column(modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.app_name),
                    style = Type.display,
                    color = palette.ink,
                )
                Text(
                    "$selfName · ${self.id}",
                    style = Type.meta,
                    color = palette.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            IconButton(Glyph.History, stringResource(R.string.history), onHistory)
            IconButton(Glyph.Settings, stringResource(R.string.settings), onSettings)
        }

        ActiveTransfer(incoming, outgoing, onOpenTransfer)

        SectionLabel(
            label = if (waitingToSend > 0) {
                stringResource(R.string.choose_device) + " · " +
                    pluralStringResource(R.plurals.file_count, waitingToSend, waitingToSend)
            } else {
                stringResource(R.string.on_this_network)
            },
            accent = waitingToSend > 0,
            modifier = Modifier.padding(top = 24.dp, bottom = 14.dp),
        )

        if (peers.isEmpty()) {
            Text(
                stringResource(R.string.looking_for_devices),
                style = Type.body,
                color = palette.ink2,
            )
            return@Column
        }

        LazyColumn(verticalArrangement = RowGap) {
            items(peers, key = { it.id }) { peer ->
                PeerCard(peer, isPaired(peer.id)) { onPeerTapped(peer) }
            }
        }
    }
}

/**
 * One line about what is moving, and a way into the detail.
 *
 * Deliberately a summary: the home screen answers "is anything happening",
 * and the transfer screen answers "what exactly".
 */
@Composable
private fun ActiveTransfer(incoming: IncomingUi, outgoing: OutgoingUi, onOpen: () -> Unit) {
    val palette = LocalPalette.current

    val (title, status, fraction) = when {
        incoming is IncomingUi.Busy -> Triple(
            stringResource(R.string.receiving_from, incoming.progress.peerName),
            stringResource(R.string.status_receiving),
            incoming.progress.fraction,
        )
        incoming is IncomingUi.Finished -> Triple(
            when (incoming.progress.status) {
                TransferStatus.Complete -> stringResource(R.string.received_from, incoming.progress.peerName)
                TransferStatus.Declined -> stringResource(R.string.declined)
                else -> stringResource(R.string.transfer_failed)
            },
            if (incoming.progress.status == TransferStatus.Complete) {
                stringResource(R.string.status_done)
            } else {
                stringResource(R.string.status_failed)
            },
            null,
        )
        outgoing is OutgoingUi.Busy -> Triple(
            stringResource(R.string.sending_to, outgoing.progress.peerName),
            if (outgoing.progress.status == SendStatus.Paused) {
                stringResource(R.string.status_paused)
            } else {
                stringResource(R.string.status_sending)
            },
            outgoing.progress.fraction,
        )
        outgoing is OutgoingUi.Finished -> Triple(
            when (outgoing.progress.status) {
                SendStatus.Complete -> stringResource(R.string.sent_to, outgoing.progress.peerName)
                SendStatus.Declined -> stringResource(R.string.peer_declined, outgoing.progress.peerName)
                else -> stringResource(R.string.could_not_send)
            },
            if (outgoing.progress.status == SendStatus.Complete) {
                stringResource(R.string.status_done)
            } else {
                stringResource(R.string.status_failed)
            },
            null,
        )
        else -> return
    }

    val tone = when (status) {
        stringResource(R.string.status_done) -> palette.ok
        stringResource(R.string.status_failed) -> palette.err
        stringResource(R.string.status_paused) -> palette.signal
        else -> palette.ink3
    }

    OutlinedCard(Modifier.padding(top = 18.dp), onClick = onOpen) {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = Type.title,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(status, style = Type.status, color = tone, modifier = Modifier.padding(start = 10.dp))
                Icon(Glyph.Chevron, palette.ink3, Modifier.padding(start = 8.dp), size = 16.dp)
            }
            if (fraction != null) Track(fraction, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, paired: Boolean, onTap: () -> Unit) {
    val palette = LocalPalette.current

    OutlinedCard(dashed = !paired, filled = paired, onClick = onTap) {
        Box(Modifier.padding(start = 15.dp, end = 15.dp, top = 13.dp, bottom = 14.dp)) {
            Column(Modifier.padding(end = 16.dp)) {
                Text(
                    peer.name,
                    style = Type.peerName,
                    color = if (paired) palette.ink else palette.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${peer.os} · ${peer.address}:${peer.port}",
                    style = Type.meta,
                    color = palette.ink3,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    stringResource(if (paired) R.string.tap_to_send else R.string.tap_to_connect),
                    style = Type.action,
                    color = palette.signal,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Beacon(paired, Modifier.align(Alignment.TopEnd).padding(top = 2.dp))
        }
    }
}

@Composable
fun IconButton(glyph: Glyph, description: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.control))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Icon(glyph, palette.ink2, description = description)
    }
}

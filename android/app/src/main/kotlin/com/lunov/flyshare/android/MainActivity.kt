package com.lunov.flyshare.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lunov.flyshare.core.DiscoveryService
import com.lunov.flyshare.core.Identity
import com.lunov.flyshare.core.IncomingOffer
import com.lunov.flyshare.core.IncomingUi
import com.lunov.flyshare.core.PairedPeer
import com.lunov.flyshare.core.PairingUi
import com.lunov.flyshare.core.Peer
import com.lunov.flyshare.core.PeerService
import com.lunov.flyshare.core.SelfDescription
import com.lunov.flyshare.core.TransferProgress
import com.lunov.flyshare.core.TransferStatus
import com.lunov.flyshare.core.TrustStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Discovery, pairing, and receiving. Sending comes next.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = applicationContext

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val model: FlyShareViewModel = viewModel(
                    factory = viewModelFactory { initializer { FlyShareViewModel(context) } },
                )
                val peers by model.peers.collectAsStateWithLifecycle()
                val paired by model.pairedPeers.collectAsStateWithLifecycle()
                val pairing by model.pairing.collectAsStateWithLifecycle()
                val incoming by model.incoming.collectAsStateWithLifecycle()
                val destination by model.destination.collectAsStateWithLifecycle()

                val chooseFolder = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri: Uri? -> uri?.let(model::useFolder) }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        HomeScreen(
                            self = model.self,
                            peers = peers,
                            isPaired = { id -> paired.any { it.id == id } },
                            onPeerTapped = model::onPeerTapped,
                            destination = destination,
                            onChangeFolder = { chooseFolder.launch(null) },
                            incoming = incoming,
                            onDismissTransfer = model::dismissTransfer,
                            modifier = Modifier.padding(padding),
                        )
                    }
                    PairingDialog(pairing, model::answerPairing, model::dismissPairing)
                    OfferDialog(incoming, destination, model::answerOffer, model::declineOffer)
                }
            }
        }
    }
}

class FlyShareViewModel(context: Context) : ViewModel() {

    private val storage = FileStorage(context)
    private val identity = Identity(storage)
    private val trust = TrustStore(storage)
    private val folder = DownloadFolder(context)

    val self = SelfDescription(
        id = identity.deviceId,
        name = DeviceIdentity.deviceName(context),
        os = "android",
    )

    private val discovery = DiscoveryService(self, AndroidMulticastPermit(context))
    private val peerService = PeerService(
        self = self,
        identity = identity,
        trust = trust,
        scope = viewModelScope,
        downloads = folder::store,
    )

    private val _destination = MutableStateFlow(folder.label())

    val peers: StateFlow<List<Peer>> = discovery.peers
    val pairing: StateFlow<PairingUi> = peerService.pairing.state
    val pairedPeers: StateFlow<List<PairedPeer>> = peerService.pairing.pairedPeers
    val incoming: StateFlow<IncomingUi> = peerService.incoming.state
    val destination: StateFlow<String> = _destination

    init {
        discovery.start(viewModelScope)
        peerService.start()
        // Pairing and receiving are both hard to observe from outside — one
        // side blocks on a person — so the state machines say what they do.
        viewModelScope.launch {
            peerService.pairing.state.collect { android.util.Log.i("FlyShare", "pairing: $it") }
        }
        viewModelScope.launch {
            peerService.incoming.state.collect { android.util.Log.i("FlyShare", "incoming: $it") }
        }
    }

    fun onPeerTapped(peer: Peer) {
        // Pairing is the only thing a tap can do yet; once sending lands, a
        // paired device becomes a target instead.
        if (!trust.isPaired(peer.id)) peerService.pairing.pairWith(peer)
    }

    fun useFolder(uri: Uri) {
        folder.remember(uri)
        _destination.value = folder.label()
    }

    fun answerPairing(accept: Boolean) = peerService.pairing.answer(accept)

    fun dismissPairing() = peerService.pairing.dismiss()

    fun answerOffer(accept: Boolean) = peerService.incoming.answer(accept)

    fun declineOffer() = peerService.incoming.dismiss()

    fun dismissTransfer() = peerService.incoming.dismiss()

    override fun onCleared() {
        discovery.stop()
        peerService.stop()
        super.onCleared()
    }
}

@Composable
private fun HomeScreen(
    self: SelfDescription,
    peers: List<Peer>,
    isPaired: (String) -> Boolean,
    onPeerTapped: (Peer) -> Unit,
    destination: String,
    onChangeFolder: () -> Unit,
    incoming: IncomingUi,
    onDismissTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(20.dp)) {
        Text("FlyShare", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${self.name} · ${self.id}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        DestinationRow(destination, onChangeFolder)

        if (incoming !is IncomingUi.None && incoming !is IncomingUi.Ask) {
            TransferCard(incoming, onDismissTransfer)
        }

        Text(
            "ON THIS NETWORK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )

        if (peers.isEmpty()) {
            Text(
                "Looking for devices. Start FlyShare on a computer on the same "
                    + "network — it will appear here within seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            items(peers, key = { it.id }) { peer ->
                PeerCard(peer, isPaired(peer.id)) { onPeerTapped(peer) }
            }
        }
    }
}

/**
 * Where files land, said plainly. The default is app storage, which is hard to
 * browse to, so this is not a detail to bury in a settings screen.
 */
@Composable
private fun DestinationRow(destination: String, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "FILES ARRIVE IN",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(destination, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onChange) { Text("Change") }
    }
}

@Composable
private fun TransferCard(state: IncomingUi, onDismiss: () -> Unit) {
    val progress: TransferProgress = when (state) {
        is IncomingUi.Busy -> state.progress
        is IncomingUi.Finished -> state.progress
        else -> return
    }

    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (progress.status) {
                    TransferStatus.Complete -> "Received from ${progress.peerName}"
                    TransferStatus.Failed -> "Transfer failed"
                    TransferStatus.Declined -> "Declined"
                    else -> "Receiving from ${progress.peerName}"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${progress.fileCount} file(s) · ${formatBytes(progress.received)}" +
                    " of ${formatBytes(progress.totalSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            if (progress.status == TransferStatus.Receiving) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            progress.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (progress.status != TransferStatus.Receiving) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, paired: Boolean, onTap: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !paired, onClick = onTap),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${peer.os} · ${peer.address}:${peer.port}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!paired) {
                    Text(
                        "TAP TO CONNECT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    if (paired) Color(0xFF3EC99B) else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * The offer, with the two facts worth deciding on: how much is coming, and
 * where it will end up. Nothing is written until this is answered.
 */
@Composable
private fun OfferDialog(
    state: IncomingUi,
    destination: String,
    onAnswer: (Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    val offer: IncomingOffer = (state as? IncomingUi.Ask)?.offer ?: return

    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("${offer.peerName} wants to send files") },
        text = {
            Column {
                Text(
                    "${offer.files.size} file(s) · ${formatBytes(offer.totalSize)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    offer.files.take(4).joinToString("\n") { it.rel } +
                        if (offer.files.size > 4) "\n… and ${offer.files.size - 4} more" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "They will be saved in $destination.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Decline") } },
    )
}

/**
 * The code, big enough to read from arm's length — someone is comparing it with
 * another screen, which is the entire security model.
 */
@Composable
private fun PairingDialog(state: PairingUi, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    if (state is PairingUi.None) return

    val (title, code, note) = when (state) {
        is PairingUi.Connecting -> Triple(state.peerName, null, "Exchanging keys…")
        is PairingUi.WaitingForPeer ->
            Triple(state.peerName, state.code, "Waiting for confirmation on “${state.peerName}”.")
        is PairingUi.Confirm ->
            Triple(state.peerName, state.code, "Make sure “${state.peerName}” is showing the same code.")
        is PairingUi.Done -> Triple(state.peerName, null, "Connected. Transfers will be encrypted.")
        is PairingUi.Failed -> Triple(state.peerName, null, state.reason)
        PairingUi.None -> return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    "COMPARE THE CODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(title, style = MaterialTheme.typography.titleLarge)
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
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            when (state) {
                is PairingUi.Confirm -> TextButton(onClick = { onAnswer(true) }) { Text("Codes match") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (state is PairingUi.Confirm) {
                TextButton(onClick = { onAnswer(false) }) { Text("They differ") }
            }
        },
    )
}

/** Decimal units, because that is what a file manager shows. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}

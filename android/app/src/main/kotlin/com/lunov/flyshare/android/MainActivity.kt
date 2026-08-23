package com.lunov.flyshare.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
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
import com.lunov.flyshare.core.OutgoingUi
import com.lunov.flyshare.core.PairedPeer
import com.lunov.flyshare.core.PairingUi
import com.lunov.flyshare.core.Peer
import com.lunov.flyshare.core.PeerService
import com.lunov.flyshare.core.SelfDescription
import com.lunov.flyshare.core.SendProgress
import com.lunov.flyshare.core.SendStatus
import com.lunov.flyshare.core.TransferProgress
import com.lunov.flyshare.core.TransferStatus
import com.lunov.flyshare.core.TrustStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Discovery, pairing, receiving and sending.
 */
class MainActivity : ComponentActivity() {

    private val shared = MutableStateFlow<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = applicationContext
        shared.value = sharedUris(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val model: FlyShareViewModel = viewModel(
                    factory = viewModelFactory { initializer { FlyShareViewModel(context) } },
                )
                val peers by model.peers.collectAsStateWithLifecycle()
                val paired by model.pairedPeers.collectAsStateWithLifecycle()
                val pairing by model.pairing.collectAsStateWithLifecycle()
                val incoming by model.incoming.collectAsStateWithLifecycle()
                val outgoing by model.outgoing.collectAsStateWithLifecycle()
                val destination by model.destination.collectAsStateWithLifecycle()
                val waiting by model.waitingToSend.collectAsStateWithLifecycle()
                val toShare by shared.collectAsStateWithLifecycle()

                // Consumed, not just observed: sharing the same photo twice in a
                // row would otherwise leave the value unchanged, and the second
                // share would do nothing at all.
                LaunchedEffect(toShare) {
                    if (toShare.isNotEmpty()) {
                        model.stageForSending(toShare)
                        shared.value = emptyList()
                    }
                }

                val pickFiles = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris -> model.sendPicked(uris) }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        HomeScreen(
                            self = model.self,
                            peers = peers,
                            isPaired = { id -> paired.any { it.id == id } },
                            waitingToSend = waiting,
                            onPeerTapped = { peer -> model.onPeerTapped(peer) { pickFiles.launch(arrayOf("*/*")) } },
                            destination = destination,
                            onChangeFolder = { model.chooseFolderRequested() },
                            incoming = incoming,
                            outgoing = outgoing,
                            onDismissTransfer = model::dismissTransfer,
                            onCancelSend = model::cancelSend,
                            modifier = Modifier.padding(padding),
                        )
                    }
                    PairingDialog(pairing, model::answerPairing, model::dismissPairing)
                    OfferDialog(incoming, destination, model::answerOffer, model::declineOffer)
                    FolderPicker(model)
                }
            }
        }
    }

    /** A second share while the app is already open arrives here, not in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shared.value = sharedUris(intent)
    }

    private fun sharedUris(intent: Intent?): List<Uri> {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        android.util.Log.i("FlyShare", "share: action=${intent?.action} uris=$uris")
        return uris
    }
}

/** The folder chooser, kept beside the model so both paths use one launcher. */
@Composable
private fun FolderPicker(model: FlyShareViewModel) {
    val requested by model.folderRequest.collectAsStateWithLifecycle()
    val chooseFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> model.folderChosen(uri) }

    LaunchedEffect(requested) { if (requested) chooseFolder.launch(null) }
}

class FlyShareViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val storage = FileStorage(app)
    private val identity = Identity(storage)
    private val trust = TrustStore(storage)
    private val folder = DownloadFolder(app)

    val self = SelfDescription(
        id = identity.deviceId,
        name = DeviceIdentity.deviceName(app),
        os = "android",
    )

    private val discovery = DiscoveryService(self, AndroidMulticastPermit(app))
    private val peerService = PeerService(
        self = self,
        identity = identity,
        trust = trust,
        scope = viewModelScope,
        downloads = folder::store,
    )

    private val _destination = MutableStateFlow(folder.label())
    private val _folderRequest = MutableStateFlow(false)

    /** Files shared into the app, waiting for someone to choose a device. */
    private val _waitingToSend = MutableStateFlow(0)
    private var pending: List<Uri> = emptyList()

    val peers: StateFlow<List<Peer>> = discovery.peers
    val pairing: StateFlow<PairingUi> = peerService.pairing.state
    val pairedPeers: StateFlow<List<PairedPeer>> = peerService.pairing.pairedPeers
    val incoming: StateFlow<IncomingUi> = peerService.incoming.state
    val outgoing: StateFlow<OutgoingUi> = peerService.outgoing.state
    val destination: StateFlow<String> = _destination.asStateFlow()
    val folderRequest: StateFlow<Boolean> = _folderRequest.asStateFlow()
    val waitingToSend: StateFlow<Int> = _waitingToSend.asStateFlow()

    init {
        discovery.start(viewModelScope)
        peerService.start()
        // Pairing and transfers are hard to observe from outside — one side
        // blocks on a person — so the state machines say what they are doing.
        viewModelScope.launch {
            peerService.pairing.state.collect { android.util.Log.i("FlyShare", "pairing: $it") }
        }
        viewModelScope.launch {
            peerService.incoming.state.collect { android.util.Log.i("FlyShare", "incoming: $it") }
        }
        viewModelScope.launch {
            peerService.outgoing.state.collect { android.util.Log.i("FlyShare", "outgoing: $it") }
        }
    }

    /** Files arrived from the share sheet; the next paired device tapped gets them. */
    fun stageForSending(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pending = uris
        _waitingToSend.value = uris.size
    }

    fun onPeerTapped(peer: Peer, openPicker: () -> Unit) {
        if (!trust.isPaired(peer.id)) {
            peerService.pairing.pairWith(peer)
            return
        }
        if (peerService.outgoing.busy) return

        val staged = pending
        if (staged.isNotEmpty()) {
            pending = emptyList()
            _waitingToSend.value = 0
            send(peer, staged)
        } else {
            target = peer
            openPicker()
        }
    }

    /** The device chosen before the picker opened. */
    private var target: Peer? = null

    fun sendPicked(uris: List<Uri>) {
        val peer = target ?: return
        target = null
        send(peer, uris)
    }

    private fun send(peer: Peer, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val readable = uris.mapNotNull { ContentSource.of(app, it) }
        if (readable.isEmpty()) {
            peerService.outgoing.fail(
                peer.name,
                if (uris.size == 1) {
                    "That file could not be read. Try sharing it again, or pick it from Files."
                } else {
                    "None of those files could be read."
                },
                uris.size,
            )
            return
        }
        if (readable.size < uris.size) {
            android.util.Log.w("FlyShare", "skipping ${uris.size - readable.size} unreadable file(s)")
        }
        peerService.outgoing.sendTo(peer, ContentSource.distinct(readable))
    }

    fun cancelSend() = peerService.outgoing.cancel()

    fun chooseFolderRequested() { _folderRequest.value = true }

    fun folderChosen(uri: Uri?) {
        _folderRequest.value = false
        if (uri == null) return
        folder.remember(uri)
        _destination.value = folder.label()
    }

    fun answerPairing(accept: Boolean) = peerService.pairing.answer(accept)

    fun dismissPairing() = peerService.pairing.dismiss()

    fun answerOffer(accept: Boolean) = peerService.incoming.answer(accept)

    fun declineOffer() = peerService.incoming.dismiss()

    fun dismissTransfer() {
        peerService.incoming.dismiss()
        peerService.outgoing.dismiss()
    }

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
    waitingToSend: Int,
    onPeerTapped: (Peer) -> Unit,
    destination: String,
    onChangeFolder: () -> Unit,
    incoming: IncomingUi,
    outgoing: OutgoingUi,
    onDismissTransfer: () -> Unit,
    onCancelSend: () -> Unit,
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
            ReceivingCard(incoming, onDismissTransfer)
        }
        SendingCard(outgoing, onDismissTransfer, onCancelSend)

        Text(
            if (waitingToSend > 0) {
                "CHOOSE A DEVICE FOR $waitingToSend FILE(S)"
            } else {
                "ON THIS NETWORK"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (waitingToSend > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
private fun ReceivingCard(state: IncomingUi, onDismiss: () -> Unit) {
    val progress: TransferProgress = when (state) {
        is IncomingUi.Busy -> state.progress
        is IncomingUi.Finished -> state.progress
        else -> return
    }

    TransferCard(
        title = when (progress.status) {
            TransferStatus.Complete -> "Received from ${progress.peerName}"
            TransferStatus.Failed -> "Transfer failed"
            TransferStatus.Declined -> "Declined"
            else -> "Receiving from ${progress.peerName}"
        },
        line = "${progress.fileCount} file(s) · ${formatBytes(progress.received)}" +
            " of ${formatBytes(progress.totalSize)}",
        fraction = progress.fraction.takeIf { progress.status == TransferStatus.Receiving },
        detail = progress.detail,
        onDismiss = onDismiss.takeIf { progress.status != TransferStatus.Receiving },
    )
}

@Composable
private fun SendingCard(state: OutgoingUi, onDismiss: () -> Unit, onCancel: () -> Unit) {
    val progress: SendProgress = when (state) {
        is OutgoingUi.Busy -> state.progress
        is OutgoingUi.Finished -> state.progress
        OutgoingUi.None -> return
    }

    val running = progress.status == SendStatus.Sending ||
        progress.status == SendStatus.Connecting ||
        progress.status == SendStatus.Waiting

    TransferCard(
        title = when (progress.status) {
            SendStatus.Complete -> "Sent to ${progress.peerName}"
            SendStatus.Declined -> "${progress.peerName} declined"
            SendStatus.Failed -> "Could not send"
            SendStatus.Waiting -> "Waiting for ${progress.peerName}"
            SendStatus.Connecting -> "Connecting to ${progress.peerName}"
            SendStatus.Sending -> "Sending to ${progress.peerName}"
        },
        line = "${progress.fileCount} file(s) · ${formatBytes(progress.sent)}" +
            " of ${formatBytes(progress.totalSize)}",
        fraction = progress.fraction.takeIf { progress.status == SendStatus.Sending },
        detail = progress.detail,
        onDismiss = if (running) null else onDismiss,
        onCancel = onCancel.takeIf { running },
    )
}

@Composable
private fun TransferCard(
    title: String,
    line: String,
    fraction: Float?,
    detail: String?,
    onDismiss: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (onDismiss != null || onCancel != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    onCancel?.let { TextButton(onClick = it) { Text("Cancel") } }
                    onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, paired: Boolean, onTap: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
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
                Text(
                    if (paired) "TAP TO SEND FILES" else "TAP TO CONNECT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
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

// The typed getters arrived in API 33; the app supports 26.
@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Intent.parcelable(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        getParcelableExtra(name) as? T
    }

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Intent.parcelableList(name: String): List<T> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, T::class.java).orEmpty()
    } else {
        getParcelableArrayListExtra<T>(name).orEmpty()
    }

package com.lunov.flyshare.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lunov.flyshare.core.IncomingOffer
import com.lunov.flyshare.core.IncomingUi
import com.lunov.flyshare.core.OutgoingUi
import com.lunov.flyshare.core.PairedPeer
import com.lunov.flyshare.core.PairingUi
import com.lunov.flyshare.core.Peer
import com.lunov.flyshare.core.SelfDescription
import com.lunov.flyshare.core.SendProgress
import com.lunov.flyshare.core.SendStatus
import com.lunov.flyshare.core.TransferProgress
import com.lunov.flyshare.core.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The whole interface: a list of devices, and what is happening with them.
 */
class MainActivity : ComponentActivity() {

    private val shared = MutableStateFlow<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = applicationContext
        shared.value = sharedUris(intent)

        setContent {
            val model: FlyShareViewModel = viewModel(
                factory = viewModelFactory { initializer { FlyShareViewModel(context) } },
            )
            val theme by model.theme.collectAsStateWithLifecycle()
            val language by model.language.collectAsStateWithLifecycle()

            FlyShareTheme(theme) {
                WithLanguage(language.tag?.let(Locale::forLanguageTag)) {
                    App(model, shared)
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
        android.util.Log.i("FlyShare", "share: ${intent?.action} with ${uris.size} file(s)")
        return uris
    }
}

@Composable
private fun App(model: FlyShareViewModel, shared: MutableStateFlow<List<Uri>>) {
    val context = LocalContext.current
    val peers by model.peers.collectAsStateWithLifecycle()
    val paired by model.pairedPeers.collectAsStateWithLifecycle()
    val pairing by model.pairing.collectAsStateWithLifecycle()
    val incoming by model.incoming.collectAsStateWithLifecycle()
    val outgoing by model.outgoing.collectAsStateWithLifecycle()
    val chosenFolder by model.destination.collectAsStateWithLifecycle()
    val waiting by model.waitingToSend.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val toShare by shared.collectAsStateWithLifecycle()

    val destination = chosenFolder ?: stringResource(R.string.app_storage)

    var settingsOpen by remember { mutableStateOf(false) }

    // Consumed, not just observed: sharing the same photo twice in a row would
    // otherwise leave the value unchanged, and the second share do nothing.
    LaunchedEffect(toShare) {
        if (toShare.isNotEmpty()) {
            model.stageForSending(toShare)
            shared.value = emptyList()
        }
    }

    // Bytes are moving, so keep the process alive even if the person leaves.
    LaunchedEffect(busy) { if (busy) TransferService.start(context) }

    // Announcing costs battery; only do it while someone is watching the list.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.onUiVisible()
                Lifecycle.Event.ON_STOP -> model.onUiHidden()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* a refusal is survivable: the service still runs, just unseen */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> model.sendPicked(uris) }

    val chooseFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> model.folderChosen(uri) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            HomeScreen(
                self = model.self,
                peers = peers,
                isPaired = { id -> paired.any { it.id == id } },
                waitingToSend = waiting,
                onPeerTapped = { peer -> model.onPeerTapped(peer) { pickFiles.launch(arrayOf("*/*")) } },
                destination = destination,
                onChangeFolder = { chooseFolder.launch(null) },
                onSettings = { settingsOpen = true },
                incoming = incoming,
                outgoing = outgoing,
                onDismissTransfer = model::dismissTransfer,
                onCancelSend = model::cancelSend,
                modifier = Modifier.padding(padding),
            )
        }
        PairingDialog(pairing, model::answerPairing, model::dismissPairing)
        OfferDialog(incoming, destination, model::answerOffer, model::declineOffer)
        if (settingsOpen) SettingsDialog(model) { settingsOpen = false }
    }
}

class FlyShareViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val engine = FlyShareApp.engineOf(app)

    val self: SelfDescription = engine.self

    private val _destination = MutableStateFlow(engine.folder.treeLabel())
    private val _waitingToSend = MutableStateFlow(0)
    private var pending: List<Uri> = emptyList()
    private var target: Peer? = null

    val peers: StateFlow<List<Peer>> = engine.discovery.peers
    val pairing: StateFlow<PairingUi> = engine.peers.pairing.state
    val pairedPeers: StateFlow<List<PairedPeer>> = engine.peers.pairing.pairedPeers
    val incoming: StateFlow<IncomingUi> = engine.peers.incoming.state
    val outgoing: StateFlow<OutgoingUi> = engine.peers.outgoing.state
    val busy: StateFlow<Boolean> = engine.busy
    val theme: StateFlow<ThemeChoice> = engine.settings.theme
    val language: StateFlow<Language> = engine.settings.language
    /** Null means the built-in default; the screen names it in its own language. */
    val destination: StateFlow<String?> = _destination.asStateFlow()
    val waitingToSend: StateFlow<Int> = _waitingToSend.asStateFlow()

    init {
        engine.start()
    }

    fun onUiVisible() = engine.onUiVisible()

    fun onUiHidden() = engine.onUiHidden()

    /** Files arrived from the share sheet; the next paired device tapped gets them. */
    fun stageForSending(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pending = uris
        _waitingToSend.value = uris.size
    }

    fun onPeerTapped(peer: Peer, openPicker: () -> Unit) {
        if (!engine.trust.isPaired(peer.id)) {
            engine.peers.pairing.pairWith(peer)
            return
        }
        if (engine.peers.outgoing.busy) return

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

    fun sendPicked(uris: List<Uri>) {
        val peer = target ?: return
        target = null
        send(peer, uris)
    }

    private fun send(peer: Peer, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val readable = uris.mapNotNull { ContentSource.of(app, it) }
        if (readable.isEmpty()) {
            engine.peers.outgoing.fail(
                peer.name,
                app.getString(
                    if (uris.size == 1) R.string.file_unreadable else R.string.files_unreadable,
                ),
                uris.size,
            )
            return
        }
        if (readable.size < uris.size) {
            android.util.Log.w("FlyShare", "skipping ${uris.size - readable.size} unreadable file(s)")
        }
        engine.peers.outgoing.sendTo(peer, ContentSource.distinct(readable))
    }

    fun cancelSend() = engine.peers.outgoing.cancel()

    fun folderChosen(uri: Uri?) {
        if (uri == null) return
        engine.folder.remember(uri)
        _destination.value = engine.folder.treeLabel()
    }

    fun setTheme(choice: ThemeChoice) = engine.settings.setTheme(choice)

    fun setLanguage(choice: Language) = engine.settings.setLanguage(choice)

    fun answerPairing(accept: Boolean) = engine.peers.pairing.answer(accept)

    fun dismissPairing() = engine.peers.pairing.dismiss()

    fun answerOffer(accept: Boolean) = engine.peers.incoming.answer(accept)

    fun declineOffer() = engine.peers.incoming.dismiss()

    fun dismissTransfer() {
        engine.peers.incoming.dismiss()
        engine.peers.outgoing.dismiss()
    }
}


/**
 * Every dialog in the app, so the language wrapper is applied on the inside
 * exactly once rather than being forgotten in one slot out of four.
 */
@Composable
private fun AppDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Localized(title) },
        text = { Localized(text) },
        confirmButton = { Localized(confirmButton) },
        dismissButton = dismissButton?.let { button -> { Localized(button) } },
    )
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
    onSettings: () -> Unit,
    incoming: IncomingUi,
    outgoing: OutgoingUi,
    onDismissTransfer: () -> Unit,
    onCancelSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${self.name} · ${self.id}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
        }

        DestinationRow(destination, onChangeFolder, Modifier.padding(top = 16.dp))

        if (incoming !is IncomingUi.None && incoming !is IncomingUi.Ask) {
            ReceivingCard(incoming, onDismissTransfer)
        }
        SendingCard(outgoing, onDismissTransfer, onCancelSend)

        Text(
            if (waitingToSend > 0) {
                stringResource(R.string.choose_device) + " · " +
                    pluralStringResource(R.plurals.file_count, waitingToSend, waitingToSend)
            } else {
                stringResource(R.string.on_this_network)
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
                stringResource(R.string.looking_for_devices),
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
private fun DestinationRow(destination: String, onChange: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.files_arrive_in),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(destination, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onChange) { Text(stringResource(R.string.change)) }
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
            TransferStatus.Complete -> stringResource(R.string.received_from, progress.peerName)
            TransferStatus.Failed -> stringResource(R.string.transfer_failed)
            TransferStatus.Declined -> stringResource(R.string.declined)
            else -> stringResource(R.string.receiving_from, progress.peerName)
        },
        line = countAndSize(progress.fileCount, progress.received, progress.totalSize),
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
            SendStatus.Complete -> stringResource(R.string.sent_to, progress.peerName)
            SendStatus.Declined -> stringResource(R.string.peer_declined, progress.peerName)
            SendStatus.Failed -> stringResource(R.string.could_not_send)
            SendStatus.Waiting -> stringResource(R.string.waiting_for, progress.peerName)
            SendStatus.Connecting -> stringResource(R.string.connecting_to, progress.peerName)
            SendStatus.Sending -> stringResource(R.string.sending_to, progress.peerName)
        },
        line = countAndSize(progress.fileCount, progress.sent, progress.totalSize),
        fraction = progress.fraction.takeIf { progress.status == SendStatus.Sending },
        detail = progress.detail,
        onDismiss = if (running) null else onDismiss,
        onCancel = onCancel.takeIf { running },
    )
}

@Composable
private fun countAndSize(files: Int, done: Long, total: Long): String =
    pluralStringResource(R.plurals.file_count, files, files) + " · " +
        stringResource(R.string.progress_of, formatBytes(done), formatBytes(total))

@Composable
private fun TransferCard(
    title: String,
    line: String,
    fraction: Float?,
    detail: String?,
    onDismiss: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
) {
    Card(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
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
                    onCancel?.let { TextButton(onClick = it) { Text(stringResource(R.string.cancel)) } }
                    onDismiss?.let { TextButton(onClick = it) { Text(stringResource(R.string.dismiss)) } }
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, paired: Boolean, onTap: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                    stringResource(if (paired) R.string.tap_to_send else R.string.tap_to_connect),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    if (paired) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            )
        }
    }
}

@Composable
private fun SettingsDialog(model: FlyShareViewModel, onClose: () -> Unit) {
    val theme by model.theme.collectAsStateWithLifecycle()
    val language by model.language.collectAsStateWithLifecycle()

    AppDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.appearance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeChoice.entries.forEach { choice ->
                        Chip(
                            label = stringResource(
                                when (choice) {
                                    ThemeChoice.System -> R.string.theme_system
                                    ThemeChoice.Light -> R.string.theme_light
                                    ThemeChoice.Dark -> R.string.theme_dark
                                },
                            ),
                            selected = choice == theme,
                            modifier = Modifier.weight(1f),
                        ) { model.setTheme(choice) }
                    }
                }

                Text(
                    stringResource(R.string.language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Language.entries.forEach { choice ->
                    // Each language is written in itself: someone looking for
                    // their own will not find it listed in one they cannot read.
                    Chip(
                        label = if (choice == Language.System) {
                            stringResource(R.string.theme_system)
                        } else {
                            choice.label
                        },
                        selected = choice == language,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { model.setLanguage(choice) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            // One line: three chips share the width equally, and the longest
            // word in the longest language decides whether any of them wrap.
            maxLines = 1,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp).fillMaxWidth(),
        )
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

    AppDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.wants_to_send, offer.peerName)) },
        text = {
            Column {
                Text(
                    pluralStringResource(R.plurals.file_count, offer.files.size, offer.files.size) +
                        " · " + formatBytes(offer.totalSize),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    offer.files.take(4).joinToString("\n") { it.rel } +
                        if (offer.files.size > 4) {
                            "\n" + stringResource(R.string.and_more, offer.files.size - 4)
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    stringResource(R.string.will_be_saved_in, destination),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.accept)) }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.decline)) }
        },
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
                is PairingUi.Confirm -> TextButton(onClick = { onAnswer(true) }) {
                    Text(stringResource(R.string.codes_match))
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = if (state is PairingUi.Confirm) {
            { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.codes_differ)) } }
        } else {
            null
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

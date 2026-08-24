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
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
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
import com.lunov.flyshare.core.SizeFormat
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

    val palette = LocalPalette.current
    Surface(Modifier.fillMaxSize(), color = palette.paper) {
        Scaffold(containerColor = palette.paper) { padding ->
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
    val palette = LocalPalette.current

    Column(modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = Type.display, color = palette.ink)
                Text(
                    "${self.name} · ${self.id}",
                    style = Type.meta,
                    color = palette.ink3,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            QuietButton(stringResource(R.string.settings), onSettings)
        }

        DestinationRow(destination, onChangeFolder, Modifier.padding(top = 18.dp))

        if (incoming !is IncomingUi.None && incoming !is IncomingUi.Ask) {
            ReceivingCard(incoming, onDismissTransfer)
        }
        SendingCard(outgoing, onDismissTransfer, onCancelSend)

        Eyebrow(
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

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.files_arrive_in), style = Type.eyebrow, color = palette.ink3)
            Text(
                destination,
                style = Type.body,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        QuietButton(stringResource(R.string.change), onChange)
    }
}

@Composable
private fun ReceivingCard(state: IncomingUi, onDismiss: () -> Unit) {
    val progress: TransferProgress = when (state) {
        is IncomingUi.Busy -> state.progress
        is IncomingUi.Finished -> state.progress
        else -> return
    }
    val palette = LocalPalette.current

    TransferCard(
        title = stringResource(
            when (progress.status) {
                TransferStatus.Complete -> R.string.received_from
                TransferStatus.Failed -> R.string.transfer_failed
                TransferStatus.Declined -> R.string.declined
                else -> R.string.receiving_from
            },
            progress.peerName,
        ),
        status = when (progress.status) {
            TransferStatus.Complete -> stringResource(R.string.status_done) to palette.ok
            TransferStatus.Failed -> stringResource(R.string.status_failed) to palette.err
            TransferStatus.Declined -> stringResource(R.string.declined) to palette.ink3
            else -> stringResource(R.string.status_receiving) to palette.ink3
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
    val palette = LocalPalette.current

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
        status = when (progress.status) {
            SendStatus.Complete -> stringResource(R.string.status_done) to palette.ok
            SendStatus.Failed, SendStatus.Declined ->
                stringResource(R.string.status_failed) to palette.err
            SendStatus.Sending -> stringResource(R.string.status_sending) to palette.ink3
            else -> stringResource(R.string.status_waiting) to palette.ink3
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
    status: Pair<String, Color>,
    line: String,
    fraction: Float?,
    detail: String?,
    onDismiss: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current

    Column(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(palette.surface)
            .hairline(palette.line, Radius.card)
            .padding(15.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = Type.title,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                status.first,
                style = Type.status,
                color = status.second,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        Text(line, style = Type.meta, color = palette.ink3, modifier = Modifier.padding(top = 4.dp))

        if (fraction != null) Track(fraction, Modifier.padding(top = 12.dp))

        detail?.let {
            Text(it, style = Type.body, color = palette.err, modifier = Modifier.padding(top = 8.dp))
        }

        if (onDismiss != null || onCancel != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                onCancel?.let { QuietButton(stringResource(R.string.cancel), it) }
                onDismiss?.let {
                    Box(Modifier.padding(start = 16.dp)) {
                        QuietButton(stringResource(R.string.dismiss), it)
                    }
                }
            }
        }
    }
}

/** Four pixels of track, exactly as on the desktop. */
@Composable
private fun Track(fraction: Float, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val width by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(220),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.surface2),
    ) {
        Box(
            Modifier
                .fillMaxWidth(width)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.signal),
        )
    }
}

@Composable
private fun PeerCard(peer: Peer, paired: Boolean, onTap: () -> Unit) {
    val palette = LocalPalette.current

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .then(if (paired) Modifier.background(palette.surface) else Modifier)
            .hairline(palette.line, Radius.card, dashed = !paired)
            .clickable(onClick = onTap)
            .padding(start = 15.dp, end = 15.dp, top = 13.dp, bottom = 14.dp),
    ) {
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

/**
 * The liveness dot. A paired device pulses; an unpaired one sits still.
 *
 * It is the only motion on the screen, which is what makes it readable at a
 * glance — "that machine is there right now" — rather than decoration.
 */
@Composable
private fun Beacon(paired: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    if (!paired) {
        Box(modifier.size(7.dp).clip(CircleShape).background(palette.ink3))
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
        Box(
            Modifier
                .size(7.dp + (9.dp * halo))
                .clip(CircleShape)
                .background(palette.ok.copy(alpha = 0.45f * (1f - halo))),
        )
        Box(Modifier.size(7.dp).clip(CircleShape).background(palette.ok))
    }
}

/** A text action: ink until touched, signal underneath. */
@Composable
private fun QuietButton(label: String, onClick: () -> Unit) {
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

@Composable
private fun SettingsDialog(model: FlyShareViewModel, onClose: () -> Unit) {
    val theme by model.theme.collectAsStateWithLifecycle()
    val language by model.language.collectAsStateWithLifecycle()

    AppDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.settings), style = Type.display) },
        text = {
            Column {
                Eyebrow(stringResource(R.string.appearance))
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
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

                Eyebrow(stringResource(R.string.language), Modifier.padding(top = 22.dp))
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
        confirmButton = { QuietButton(stringResource(R.string.done), onClose) },
    )
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
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
            // One line: three chips share the width equally, and the longest
            // word in the longest language decides whether any of them wrap.
            maxLines = 1,
            color = if (selected) palette.signalInk else palette.ink,
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
    val palette = LocalPalette.current

    AppDialog(
        onDismissRequest = onDecline,
        title = {
            Text(stringResource(R.string.wants_to_send, offer.peerName), style = Type.display)
        },
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
private fun PairingDialog(state: PairingUi, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
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
                Text(
                    note,
                    style = Type.body,
                    textAlign = TextAlign.Center,
                    color = palette.ink2,
                )
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

/** The one filled control: the thing to do next. */
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        label,
        style = Type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.W600),
        color = palette.signalInk,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(palette.signal)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/**
 * Decimal units, and the same rounding as the desktop.
 *
 * The two halves showing different totals for one transfer is worse than
 * either convention being wrong: a phone that said 81.6 GB beside a laptop
 * that said 76 GB looked exactly like files going missing, when both were
 * counting the same bytes. The unit labels come from resources so a Ukrainian
 * screen says ГБ rather than GB. See bytes() in ui/app.js — change one and
 * you must change both.
 */
@Composable
private fun formatBytes(bytes: Long): String {
    val units = stringArrayResource(R.array.units_bytes)
    val (value, unit, digits) = SizeFormat.scale(bytes, units.size)
    return "%.${digits}f %s".format(value, units[unit])
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

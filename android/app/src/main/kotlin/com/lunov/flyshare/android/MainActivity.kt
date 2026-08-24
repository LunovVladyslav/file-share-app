package com.lunov.flyshare.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lunov.flyshare.android.ui.HistoryScreen
import com.lunov.flyshare.android.ui.HomeScreen
import com.lunov.flyshare.android.ui.OfferDialog
import com.lunov.flyshare.android.ui.PairingDialog
import com.lunov.flyshare.android.ui.SettingsScreen
import com.lunov.flyshare.android.ui.TransferScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Where the person is. Four screens need no navigation library. */
private enum class Screen { Home, Settings, History, Transfer }

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
    val palette = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val peers by model.peers.collectAsStateWithLifecycle()
    val paired by model.pairedPeers.collectAsStateWithLifecycle()
    val pairing by model.pairing.collectAsStateWithLifecycle()
    val incoming by model.incoming.collectAsStateWithLifecycle()
    val outgoing by model.outgoing.collectAsStateWithLifecycle()
    val chosenFolder by model.destination.collectAsStateWithLifecycle()
    val chosenName by model.deviceName.collectAsStateWithLifecycle()
    val myName by model.effectiveName.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val waiting by model.waitingToSend.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val toShare by shared.collectAsStateWithLifecycle()

    val destination = chosenFolder ?: stringResource(R.string.app_storage)
    var screen by remember { mutableStateOf(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    // Consumed, not just observed: sharing the same photo twice in a row would
    // otherwise leave the value unchanged, and the second share do nothing.
    LaunchedEffect(toShare) {
        if (toShare.isNotEmpty()) {
            model.stageForSending(toShare)
            shared.value = emptyList()
            screen = Screen.Home
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

    Surface(Modifier.fillMaxSize(), color = palette.paper) {
        Scaffold(containerColor = palette.paper) { padding ->
            Box(Modifier.padding(padding)) {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        selfName = myName,
                        selfAddress = model.address,
                        peers = peers,
                        isPaired = { id -> paired.any { it.id == id } },
                        waitingToSend = waiting,
                        incoming = incoming,
                        outgoing = outgoing,
                        onPeerTapped = { peer ->
                            model.onPeerTapped(peer) { pickFiles.launch(arrayOf("*/*")) }
                        },
                        onSettings = { screen = Screen.Settings },
                        onHistory = { screen = Screen.History },
                        onOpenTransfer = { screen = Screen.Transfer },
                    )

                    Screen.Settings -> SettingsScreen(
                        deviceName = myName,
                        isCustomName = chosenName != null,
                        destination = destination,
                        paired = paired,
                        isReachable = model::isReachable,
                        theme = model.theme.value,
                        language = model.language.value,
                        onBack = { screen = Screen.Home },
                        onRename = model::rename,
                        onChangeFolder = { chooseFolder.launch(null) },
                        onForget = { model.forgetPeer(it.id) },
                        onTheme = model::setTheme,
                        onLanguage = model::setLanguage,
                    )

                    Screen.History -> HistoryScreen(
                        entries = history,
                        onBack = { screen = Screen.Home },
                        onClear = model::clearHistory,
                    )

                    Screen.Transfer -> TransferScreen(
                        incoming = incoming,
                        outgoing = outgoing,
                        canPause = model.canPause,
                        onBack = { screen = Screen.Home },
                        onCancel = model::cancelSend,
                        onPause = model::pauseSend,
                        onResume = model::resumeSend,
                        onDismiss = model::dismissTransfer,
                        onOpenFile = model::openFile,
                        canOpen = model::canOpen,
                    )
                }

                Notice(notice, model::dismissNotice, Modifier.align(Alignment.BottomCenter))
            }
        }

        PairingDialog(pairing, model::answerPairing, model::dismissPairing)
        OfferDialog(incoming, destination, model::answerOffer, model::declineOffer)
    }
}

/**
 * A line for something that would otherwise fail silently — a file that has
 * been moved away, a type no app on the phone opens. It clears itself, because
 * a message about a tap that went nowhere should not need dismissing too.
 */
@Composable
private fun Notice(text: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current

    LaunchedEffect(text) {
        if (text != null) {
            delay(4000)
            onDismiss()
        }
    }

    AnimatedVisibility(visible = text != null, modifier = modifier) {
        Text(
            text.orEmpty(),
            style = Type.body,
            color = palette.ink,
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.control))
                .background(palette.surface2)
                .hairline(palette.line, Radius.control)
                .padding(14.dp),
        )
    }
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

package com.lunov.flyshare.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.lunov.flyshare.core.PairedPeer
import com.lunov.flyshare.core.PairingManager
import com.lunov.flyshare.core.PairingUi
import com.lunov.flyshare.core.Peer
import com.lunov.flyshare.core.SelfDescription
import com.lunov.flyshare.core.TrustStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Discovery and pairing. Transfers come next.
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

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        DeviceList(
                            self = model.self,
                            peers = peers,
                            isPaired = { id -> paired.any { it.id == id } },
                            onPeerTapped = model::onPeerTapped,
                            modifier = Modifier.padding(padding),
                        )
                    }
                    PairingDialog(pairing, model::answer, model::dismiss)
                }
            }
        }
    }
}

class FlyShareViewModel(context: Context) : ViewModel() {

    private val storage = FileStorage(context)
    private val identity = Identity(storage)
    private val trust = TrustStore(storage)

    val self = SelfDescription(
        id = identity.deviceId,
        name = DeviceIdentity.deviceName(context),
        os = "android",
    )

    private val discovery = DiscoveryService(self, AndroidMulticastPermit(context))
    private val pairingManager = PairingManager(self, identity, trust, viewModelScope)

    val peers: StateFlow<List<Peer>> = discovery.peers
    val pairing: StateFlow<PairingUi> = pairingManager.state
    val pairedPeers: StateFlow<List<PairedPeer>> = pairingManager.pairedPeers

    init {
        discovery.start(viewModelScope)
        pairingManager.start()
        // Pairing is hard to observe from outside — one side blocks on a person
        // — so the state machine says what it is doing.
        viewModelScope.launch {
            pairingManager.state.collect { android.util.Log.i("FlyShare", "pairing state: $it") }
        }
    }

    fun onPeerTapped(peer: Peer) {
        android.util.Log.i("FlyShare", "peer tapped: ${peer.name} paired=${trust.isPaired(peer.id)}")
        // Pairing is the only thing a tap can do yet; once transfers land, a
        // paired device becomes a send target instead.
        if (!trust.isPaired(peer.id)) pairingManager.pairWith(peer)
    }

    fun answer(accept: Boolean) = pairingManager.answer(accept)

    fun dismiss() = pairingManager.dismiss()

    override fun onCleared() {
        discovery.stop()
        pairingManager.stop()
        super.onCleared()
    }
}

@Composable
private fun DeviceList(
    self: SelfDescription,
    peers: List<Peer>,
    isPaired: (String) -> Boolean,
    onPeerTapped: (Peer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(20.dp)) {
        Text("FlyShare", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${self.name} · ${self.id}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        Text(
            "ON THIS NETWORK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

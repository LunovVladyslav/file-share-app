package com.lunov.flyshare.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider
import android.content.Context
import com.lunov.flyshare.core.DiscoveryService
import com.lunov.flyshare.core.Peer
import com.lunov.flyshare.core.SelfDescription
import kotlinx.coroutines.flow.StateFlow

/**
 * Milestone 2: prove the phone and the desktop can see each other.
 *
 * Deliberately just the device list. Pairing, transfers and the real interface
 * come next; putting a screen in front of discovery now is what makes it
 * testable on a real network.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val self = DeviceIdentity.describe(this)
        val context = applicationContext

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val model: DiscoveryViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { DiscoveryViewModel(context, self) }
                    },
                )
                val peers by model.peers.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        DeviceList(self, peers, Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

class DiscoveryViewModel(context: Context, self: SelfDescription) : ViewModel() {

    private val discovery = DiscoveryService(self, AndroidMulticastPermit(context))
    val peers: StateFlow<List<Peer>> = discovery.peers

    init {
        discovery.start(viewModelScope)
    }

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }
}

@Composable
private fun DeviceList(self: SelfDescription, peers: List<Peer>, modifier: Modifier = Modifier) {
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
            items(peers, key = { it.id }) { peer -> PeerCard(peer) }
        }
    }
}

@Composable
private fun PeerCard(peer: Peer) {
    Card(Modifier.fillMaxWidth()) {
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
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3EC99B)))
        }
    }
}

@Composable
private fun Box(modifier: Modifier) = androidx.compose.foundation.layout.Box(modifier)

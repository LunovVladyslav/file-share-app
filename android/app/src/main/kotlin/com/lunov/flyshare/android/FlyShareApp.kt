package com.lunov.flyshare.android

import android.app.Application
import android.content.Context
import com.lunov.flyshare.core.DiscoveryService
import com.lunov.flyshare.core.History
import com.lunov.flyshare.core.HistoryEntry
import com.lunov.flyshare.core.Identity
import com.lunov.flyshare.core.localInterfaces
import com.lunov.flyshare.core.Outcome
import com.lunov.flyshare.core.Presence
import com.lunov.flyshare.core.SendProgress
import com.lunov.flyshare.core.SendStatus
import com.lunov.flyshare.core.TransferProgress
import com.lunov.flyshare.core.TransferStatus
import com.lunov.flyshare.core.IncomingUi
import com.lunov.flyshare.core.OutgoingUi
import com.lunov.flyshare.core.PeerService
import com.lunov.flyshare.core.SelfDescription
import com.lunov.flyshare.core.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The running protocol, owned by the process rather than by a screen.
 *
 * A transfer has to survive the person switching apps to check something,
 * which a ViewModel cannot promise: it belongs to an Activity, and an Activity
 * belongs to whatever the system decides. So the engine lives here and
 * [TransferService] keeps the process alive while bytes are moving.
 */
class FlyShareEngine(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val storage = FileStorage(app)
    val identity = Identity(storage)
    val trust = TrustStore(storage)
    val folder = DownloadFolder(app)
    val settings = Preferences(app)
    val history = History(storage)

    /** What the phone calls itself, unless the person has said otherwise. */
    private val defaultName = DeviceIdentity.deviceName(app)

    val self = SelfDescription(
        id = identity.deviceId,
        name = settings.deviceName.value ?: defaultName,
        os = "android",
    )

    /**
     * What this device is currently called, as something a screen can watch.
     *
     * [SelfDescription.name] is a plain field — the network reads it whenever
     * it announces, which is what that is for — but a field is not state, so a
     * screen bound to it would never redraw when it changed.
     */
    private val _name = MutableStateFlow(self.name)
    val name: StateFlow<String> = _name.asStateFlow()

    /**
     * Rename this device. The next announcement carries it — three seconds,
     * not a restart — because [SelfDescription] is read afresh each time.
     */
    fun rename(name: String?) {
        settings.setDeviceName(name)
        val chosen = settings.deviceName.value ?: defaultName
        self.name = chosen
        _name.value = chosen
    }

    fun forget(deviceId: String) {
        peers.pairing.forget(deviceId)
        Presence.forget(deviceId)
    }

    val discovery = DiscoveryService(self, AndroidMulticastPermit(app))
    val peers = PeerService(
        self = self,
        identity = identity,
        trust = trust,
        scope = scope,
        downloads = folder::store,
    )

    /**
     * The address other devices reach this one at.
     *
     * This replaced the device id under the app name. The id is a routing
     * label — the specification says so — and it is not what anybody compares:
     * pairing compares six digits, and the device list shows names. It gave a
     * person nothing to do. The address does: it is the first thing to check
     * when two devices cannot see each other, and it is what the desktop has
     * shown about itself all along.
     */
    val address: String get() = localInterfaces().firstOrNull()?.address ?: ""

    private val _busy = MutableStateFlow(false)

    /** True while bytes are actually moving, in either direction. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        discovery.start(scope)
        peers.start()

        scope.launch {
            peers.incoming.state.collect { android.util.Log.i(TAG, "incoming: $it") }
        }
        scope.launch {
            peers.outgoing.state.collect { android.util.Log.i(TAG, "outgoing: $it") }
        }
        scope.launch {
            peers.pairing.state.collect { android.util.Log.i(TAG, "pairing: $it") }
        }
        scope.launch {
            peers.incoming.state.collect { state ->
                _busy.value = isBusy()
                if (state is IncomingUi.Finished) history.record(state.progress.toHistory(folder.treeLabel()))
            }
        }
        scope.launch {
            peers.outgoing.state.collect { state ->
                _busy.value = isBusy()
                if (state is OutgoingUi.Finished) history.record(state.progress.toHistory())
            }
        }
    }

    private fun isBusy(): Boolean =
        peers.incoming.state.value is IncomingUi.Busy || peers.outgoing.state.value is OutgoingUi.Busy

    /**
     * Discovery costs battery — it holds a multicast lock and sends every three
     * seconds — so it only runs while someone is looking at the device list.
     * The listening socket stays up regardless, or the app could not be sent to.
     */
    fun onUiVisible() = discovery.start(scope)

    fun onUiHidden() {
        if (!busy.value) discovery.stop()
    }

    private companion object { const val TAG = "FlyShare" }
}

class FlyShareApp : Application() {

    val engine: FlyShareEngine by lazy { FlyShareEngine(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        private lateinit var instance: FlyShareApp

        fun engineOf(context: Context): FlyShareEngine =
            (context.applicationContext as? FlyShareApp ?: instance).engine
    }
}

/** A finished transfer, reduced to what is worth keeping. */
private fun TransferProgress.toHistory(destination: String?) = HistoryEntry(
    id = transferId,
    outgoing = false,
    peerName = peerName,
    fileCount = fileCount,
    totalSize = totalSize,
    transferred = received,
    outcome = when (status) {
        TransferStatus.Complete -> Outcome.Complete
        TransferStatus.Declined -> Outcome.Declined
        else -> Outcome.Failed
    },
    detail = detail,
    startedAt = startedAt,
    finishedAt = System.currentTimeMillis(),
    destination = savedTo ?: destination,
)

private fun SendProgress.toHistory() = HistoryEntry(
    id = transferId.ifEmpty { "send-" + System.currentTimeMillis() },
    outgoing = true,
    peerName = peerName,
    fileCount = fileCount,
    totalSize = totalSize,
    transferred = sent,
    outcome = when (status) {
        SendStatus.Complete -> Outcome.Complete
        SendStatus.Declined -> Outcome.Declined
        else -> Outcome.Failed
    },
    detail = detail,
    startedAt = startedAt,
    finishedAt = System.currentTimeMillis(),
)

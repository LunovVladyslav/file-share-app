package com.lunov.flyshare.android

import android.app.Application
import android.content.Context
import com.lunov.flyshare.core.DiscoveryService
import com.lunov.flyshare.core.Identity
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

    val self = SelfDescription(
        id = identity.deviceId,
        name = DeviceIdentity.deviceName(app),
        os = "android",
    )

    val discovery = DiscoveryService(self, AndroidMulticastPermit(app))
    val peers = PeerService(
        self = self,
        identity = identity,
        trust = trust,
        scope = scope,
        downloads = folder::store,
    )

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
            peers.incoming.state.collect { _busy.value = isBusy() }
        }
        scope.launch {
            peers.outgoing.state.collect { _busy.value = isBusy() }
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

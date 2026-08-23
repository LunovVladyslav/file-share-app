package com.lunov.flyshare.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** What the pairing screen is showing right now. */
sealed interface PairingUi {
    data object None : PairingUi

    data class Connecting(val peerName: String) : PairingUi

    /** We dialled; the code is on screen and the far side must answer. */
    data class WaitingForPeer(val peerName: String, val code: String) : PairingUi

    /** They dialled; this device is the one that has to decide. */
    data class Confirm(val peerName: String, val code: String) : PairingUi

    data class Done(val peerName: String) : PairingUi

    data class Failed(val peerName: String, val reason: String) : PairingUi
}

/**
 * Drives both halves of pairing and reduces them to one screen state.
 *
 * The two roles look different to a person — one waits, the other decides —
 * but they show the same six digits, and that comparison is the whole security
 * control. Nothing here may shortcut it.
 *
 * The listening socket lives in [PeerService]: pairing is one of the things
 * that arrives on it, not the thing that owns it.
 */
class PairingManager(
    private val self: SelfDescription,
    private val identity: Identity,
    val trust: TrustStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<PairingUi>(PairingUi.None)
    val state: StateFlow<PairingUi> = _state.asStateFlow()

    private val _pairedPeers = MutableStateFlow(trust.all())
    val pairedPeers: StateFlow<List<PairedPeer>> = _pairedPeers.asStateFlow()

    /** Set while an incoming request is waiting on a person. */
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    /** Dial a peer and pair with it. */
    fun pairWith(peer: Peer) {
        if (_state.value is PairingUi.Connecting || _state.value is PairingUi.WaitingForPeer) return
        _state.value = PairingUi.Connecting(peer.name)

        scope.launch(Dispatchers.IO) {
            runCatching {
                Pairing.initiate(peer.address, peer.port, self, identity, trust) { code ->
                    _state.value = PairingUi.WaitingForPeer(peer.name, code)
                }
            }.onSuccess { outcome ->
                _pairedPeers.value = trust.all()
                _state.value = PairingUi.Done(outcome.peer.name)
            }.onFailure { error ->
                _state.value = PairingUi.Failed(peer.name, error.message ?: "pairing failed")
            }
        }
    }

    /** The answer to an incoming request, from the button the person pressed. */
    fun answer(accept: Boolean) {
        pendingDecision?.complete(accept)
        pendingDecision = null
        if (!accept) _state.value = PairingUi.None
    }

    fun dismiss() {
        // Cancelling while a request is open is a refusal, not a no-op.
        pendingDecision?.complete(false)
        pendingDecision = null
        _state.value = PairingUi.None
    }

    fun forget(deviceId: String) {
        if (trust.forget(deviceId)) _pairedPeers.value = trust.all()
    }

    /**
     * Blocks the connection's own thread until someone answers. That wait is
     * the point: it is what stops a device pairing itself while nobody looks.
     */
    fun awaitDecision(device: Pairing.RemoteDevice, code: String): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pendingDecision = decision
        _state.value = PairingUi.Confirm(device.name, code)
        return runBlocking { decision.await() }
    }

    fun rememberPaired(peer: PairedPeer) {
        _pairedPeers.value = trust.all()
        _state.value = PairingUi.Done(peer.name)
    }

    /**
     * A connection on the shared port failed. Only pairing's problem if a
     * pairing was actually on screen — a transfer that goes wrong reports
     * itself, and must not surface as a pairing failure.
     */
    fun fail(reason: String) {
        if (_state.value == PairingUi.None) return
        pendingDecision = null
        _state.value = PairingUi.Failed(_state.value.peerName(), reason)
    }

    private fun PairingUi.peerName(): String = when (this) {
        is PairingUi.Connecting -> peerName
        is PairingUi.WaitingForPeer -> peerName
        is PairingUi.Confirm -> peerName
        is PairingUi.Done -> peerName
        is PairingUi.Failed -> peerName
        PairingUi.None -> "device"
    }
}

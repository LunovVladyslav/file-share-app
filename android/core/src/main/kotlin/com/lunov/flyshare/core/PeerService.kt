package com.lunov.flyshare.core

import kotlinx.coroutines.CoroutineScope

/**
 * Everything that arrives on the transfer port, in one place.
 *
 * One listening socket serves pairing and transfers alike — the first frame
 * says which — so one object owns it and hands each connection to whichever
 * state machine it belongs to.
 */
class PeerService(
    self: SelfDescription,
    identity: Identity,
    trust: TrustStore,
    private val scope: CoroutineScope,
    downloads: () -> DownloadStore,
    port: Int = TRANSFER_PORT,
) {

    val pairing = PairingManager(self, identity, trust, scope)
    val incoming = IncomingTransfers(downloads)
    val outgoing = OutgoingTransfers(self, identity, trust, scope)

    private val server = PeerServer(
        self = self,
        identity = identity,
        trust = trust,
        onPairingRequest = pairing::awaitDecision,
        onPaired = pairing::rememberPaired,
        onFailure = pairing::fail,
        transfers = incoming.receiver,
        port = port,
    )

    fun start() = server.start(scope)

    fun stop() = server.stop()
}

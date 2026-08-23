package com.lunov.flyshare.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the sending screen is showing right now. */
sealed interface OutgoingUi {
    data object None : OutgoingUi

    data class Busy(val progress: SendProgress) : OutgoingUi

    data class Finished(val progress: SendProgress) : OutgoingUi
}

/**
 * Sending, reduced to one screen state.
 *
 * One transfer at a time. Two at once would halve the throughput of both and
 * make the progress on screen impossible to read, and nobody has asked for it.
 */
class OutgoingTransfers(
    private val self: SelfDescription,
    private val identity: Identity,
    private val trust: TrustStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<OutgoingUi>(OutgoingUi.None)
    val state: StateFlow<OutgoingUi> = _state.asStateFlow()

    private var sender: TransferSender? = null

    val busy: Boolean get() = _state.value is OutgoingUi.Busy

    fun sendTo(peer: Peer, files: List<TransferSource>) {
        if (busy || files.isEmpty()) return

        val transfer = TransferSender(self, identity, trust)
        sender = transfer

        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                transfer.send(peer, files) { progress -> _state.value = progress.toUi() }
            }.getOrElse { error ->
                SendProgress(
                    transferId = "",
                    peerName = peer.name,
                    fileCount = files.size,
                    sent = 0,
                    totalSize = files.sumOf { it.size },
                    status = SendStatus.Failed,
                    detail = error.message ?: "the transfer failed",
                )
            }
            _state.value = OutgoingUi.Finished(outcome)
            sender = null
        }
    }

    /**
     * Report a failure that happened before anything was sent — nothing could
     * be read, say. Silence would leave a tap looking like it did nothing.
     */
    fun fail(peerName: String, reason: String, fileCount: Int = 0) {
        if (busy) return
        _state.value = OutgoingUi.Finished(
            SendProgress("", peerName, fileCount, 0, 0, SendStatus.Failed, reason),
        )
    }

    fun cancel() {
        sender?.cancel("cancelled on this device")
    }

    fun dismiss() {
        if (!busy) _state.value = OutgoingUi.None
    }

    private fun SendProgress.toUi(): OutgoingUi = when (status) {
        SendStatus.Complete, SendStatus.Declined, SendStatus.Failed -> OutgoingUi.Finished(this)
        else -> OutgoingUi.Busy(this)
    }
}

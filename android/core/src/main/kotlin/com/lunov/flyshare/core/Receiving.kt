package com.lunov.flyshare.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** What the receiving screen is showing right now. */
sealed interface IncomingUi {
    data object None : IncomingUi

    /** Someone is offering files and this device has to decide. */
    data class Ask(val offer: IncomingOffer) : IncomingUi

    data class Busy(val progress: TransferProgress) : IncomingUi

    data class Finished(val progress: TransferProgress) : IncomingUi
}

/**
 * The receiver, reduced to one screen state.
 *
 * The decision blocks the connection's own thread on purpose: nothing may
 * touch storage until a person has said yes, and the sender is entitled to
 * wait rather than be told anything before then.
 */
class IncomingTransfers(private val downloads: () -> DownloadStore) {

    private val _state = MutableStateFlow<IncomingUi>(IncomingUi.None)
    val state: StateFlow<IncomingUi> = _state.asStateFlow()

    /** Set while an offer is on screen waiting on a person. */
    private var pending: ArrayBlockingQueue<Boolean>? = null

    val receiver = TransferReceiver(
        store = downloads,
        ask = { offer -> awaitDecision(offer) },
        onUpdate = { progress ->
            _state.value = when (progress.status) {
                TransferStatus.Offered -> _state.value // the ask already set this
                TransferStatus.Receiving -> IncomingUi.Busy(progress)
                else -> IncomingUi.Finished(progress)
            }
        },
    )

    /** The answer to an offer, from the button the person pressed. */
    fun answer(accept: Boolean) {
        pending?.offer(accept)
        pending = null
        if (!accept) _state.value = IncomingUi.None
    }

    fun dismiss() {
        // Closing the dialog while an offer is open is a refusal, not a no-op.
        pending?.offer(false)
        pending = null
        _state.value = IncomingUi.None
    }

    /** Where files will land, for the person to see before they agree. */
    fun destination(): String = runCatching { downloads().label }.getOrElse { "this device" }

    private fun awaitDecision(offer: IncomingOffer): Boolean {
        val answer = ArrayBlockingQueue<Boolean>(1)
        pending = answer
        _state.value = IncomingUi.Ask(offer)

        // A phone in a pocket should not hold a sender open forever; five
        // minutes is long enough to walk across a room and back.
        val decision = answer.poll(DECISION_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (decision == null) {
            pending = null
            _state.value = IncomingUi.None
        }
        return decision == true
    }

    private companion object { const val DECISION_TIMEOUT_MINUTES = 5L }
}

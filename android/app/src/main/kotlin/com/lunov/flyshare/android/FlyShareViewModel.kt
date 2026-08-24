package com.lunov.flyshare.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import com.lunov.flyshare.core.HistoryEntry
import com.lunov.flyshare.core.IncomingUi
import com.lunov.flyshare.core.OutgoingUi
import com.lunov.flyshare.core.PairedPeer
import com.lunov.flyshare.core.PairingUi
import com.lunov.flyshare.core.Peer
import com.lunov.flyshare.core.SelfDescription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class FlyShareViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val engine = FlyShareApp.engineOf(app)

    val self: SelfDescription = engine.self

    private val _destination = MutableStateFlow(engine.folder.treeLabel())
    private val _waitingToSend = MutableStateFlow(0)
    private val _notice = MutableStateFlow<String?>(null)
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
    val history: StateFlow<List<HistoryEntry>> = engine.history.entries
    val deviceName: StateFlow<String?> = engine.settings.deviceName

    /** What the device is called right now, default or chosen. */
    val effectiveName: StateFlow<String> = engine.name

    /** Null means the built-in default; the screen names it in its own language. */
    val destination: StateFlow<String?> = _destination.asStateFlow()
    val waitingToSend: StateFlow<Int> = _waitingToSend.asStateFlow()

    /** A one-line message for something that would otherwise fail silently. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        engine.start()
    }

    fun onUiVisible() = engine.onUiVisible()

    fun onUiHidden() = engine.onUiHidden()

    fun dismissNotice() { _notice.value = null }

    // --- sending -------------------------------------------------------------

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

    /** True once the receiver has said it understands a pause — §9.5. */
    val canPause: Boolean get() = engine.peers.outgoing.canPause

    fun pauseSend() = engine.peers.outgoing.pause()

    fun resumeSend() = engine.peers.outgoing.resume()

    // --- settings ------------------------------------------------------------

    fun folderChosen(uri: Uri?) {
        if (uri == null) return
        engine.folder.remember(uri)
        _destination.value = engine.folder.treeLabel()
    }

    fun setTheme(choice: ThemeChoice) = engine.settings.setTheme(choice)

    fun setLanguage(choice: Language) = engine.settings.setLanguage(choice)

    /** Blank clears the override and goes back to what the phone calls itself. */
    fun rename(name: String) = engine.rename(name)

    fun forgetPeer(deviceId: String) = engine.forget(deviceId)

    fun clearHistory() = engine.history.clear()

    // --- answering -----------------------------------------------------------

    fun answerPairing(accept: Boolean) = engine.peers.pairing.answer(accept)

    fun dismissPairing() = engine.peers.pairing.dismiss()

    fun answerOffer(accept: Boolean) = engine.peers.incoming.answer(accept)

    fun declineOffer() = engine.peers.incoming.dismiss()

    fun dismissTransfer() {
        engine.peers.incoming.dismiss()
        engine.peers.outgoing.dismiss()
    }

    // --- opening what arrived ------------------------------------------------

    /**
     * Hand a received file to whatever app opens that kind of thing.
     *
     * Two shapes reach here. A document tree gives a `content://` URI, which
     * can be passed straight on. The built-in fallback writes to app-private
     * storage, and a `file://` path from there has been refused outright since
     * API 24 — so it goes through a FileProvider, which grants that one file
     * and nothing else.
     */
    fun openFile(location: String?) {
        val uri = uriFor(location) ?: run {
            _notice.value = app.getString(R.string.cannot_open)
            return
        }
        val type = runCatching { app.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { app.startActivity(intent) }.onFailure {
            // No app claims this type. Saying so beats a tap that does nothing.
            _notice.value = app.getString(R.string.no_app_for_file)
        }
    }

    fun canOpen(location: String?): Boolean = uriFor(location) != null

    private fun uriFor(location: String?): Uri? {
        if (location.isNullOrBlank()) return null
        if (location.startsWith("content://")) return runCatching { Uri.parse(location) }.getOrNull()
        val file = File(location)
        if (!file.isFile) return null
        return runCatching {
            FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        }.getOrNull()
    }
}

package com.lunov.flyshare.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Holds whatever the platform needs held for multicast to arrive.
 *
 * On Android this wraps `WifiManager.MulticastLock`; without it the OS filters
 * multicast and broadcast datagrams and discovery silently finds nothing — the
 * socket binds, packets go out, and none come back. On a desktop JVM there is
 * nothing to hold, so the default does nothing.
 */
interface MulticastPermit {
    fun acquire() {}
    fun release() {}

    companion object {
        val None = object : MulticastPermit {}
    }
}

/** How this device describes itself in an announcement. */
data class SelfDescription(
    val id: String,
    val name: String,
    val os: String = "android",
    val port: Int = TRANSFER_PORT,
)

/**
 * Announce, listen, and keep a live list of peers — docs/PROTOCOL.md §4.
 *
 * Every packet goes out twice, to the multicast group and to each interface's
 * broadcast address, because consumer routers and host firewalls drop one or
 * the other often enough to matter.
 */
class DiscoveryService(
    private val self: SelfDescription,
    private val permit: MulticastPermit = MulticastPermit.None,
    private val port: Int = DISCOVERY_PORT,
    private val group: String = MULTICAST_GROUP,
) {

    private val peerTable = PeerTable(self.id)
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private var socket: MulticastSocket? = null
    private var jobs = mutableListOf<Job>()

    fun start(scope: CoroutineScope) {
        if (socket != null) return
        permit.acquire()

        // Deliberately not inside an apply block: MulticastSocket has its own
        // `port` member, which would shadow the constructor parameter and bind
        // an unbound socket's port of -1.
        val bound = MulticastSocket(null as java.net.SocketAddress?)
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(port))
        bound.broadcast = true
        runCatching { bound.timeToLive = 2 }
        // Java inverts this flag: disabling loopback mode enables loopback. We
        // want it on, so a second instance on this host is visible during
        // development; our own id is filtered out on receipt anyway.
        runCatching { bound.loopbackMode = false }
        socket = bound
        joinGroupOnEveryInterface(bound)

        jobs += scope.launch(Dispatchers.IO) { receiveLoop(bound) }
        jobs += scope.launch(Dispatchers.IO) { announceLoop(bound) }
        jobs += scope.launch(Dispatchers.IO) { reapLoop() }
    }

    fun stop() {
        val bound = socket ?: return
        runCatching { send(bound, DiscoveryPacket.Bye(self.id)) }
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { bound.close() }
        socket = null
        permit.release()
    }

    private fun joinGroupOnEveryInterface(bound: MulticastSocket) {
        val address = InetSocketAddress(InetAddress.getByName(group), port)
        for (local in localInterfaces()) {
            val nic = runCatching { NetworkInterface.getByName(local.name) }.getOrNull()
                ?: runCatching { NetworkInterface.getByInetAddress(InetAddress.getByName(local.address)) }.getOrNull()
                ?: continue
            runCatching { bound.joinGroup(address, nic) }
        }
    }

    private suspend fun announceLoop(bound: MulticastSocket) {
        // A probe first, so devices already running answer immediately instead
        // of leaving this one staring at an empty list for three seconds.
        runCatching { send(bound, DiscoveryPacket.Probe(self.id)) }
        while (currentCoroutineIsActive()) {
            runCatching { send(bound, announcement()) }
            delay(ANNOUNCE_INTERVAL_MS)
        }
    }

    private suspend fun reapLoop() {
        while (currentCoroutineIsActive()) {
            delay(PEER_TTL_MS / 3)
            if (peerTable.reap()) publish()
        }
    }

    private suspend fun receiveLoop(bound: MulticastSocket) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(8 * 1024)
        while (isActive && !bound.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            val received = runCatching { bound.receive(datagram); true }.getOrDefault(false)
            if (!received) continue

            val packet = DiscoveryCodec.decode(datagram.data, datagram.length) ?: continue
            if (packet.id == self.id) continue
            val source = datagram.address?.hostAddress ?: continue

            when (packet) {
                is DiscoveryPacket.Announce ->
                    if (peerTable.onAnnounce(packet, source, localInterfaces())) publish()
                // Answer at once so a device that just joined sees us without
                // waiting for the next scheduled announcement.
                is DiscoveryPacket.Probe -> runCatching { send(bound, announcement()) }
                is DiscoveryPacket.Bye -> if (peerTable.onBye(packet.id)) publish()
            }
        }
    }

    private fun announcement() = DiscoveryPacket.Announce(
        id = self.id,
        name = self.name,
        os = self.os,
        port = self.port,
        version = PROTOCOL_VERSION,
        addresses = localInterfaces().map { it.address },
    )

    private fun send(bound: MulticastSocket, packet: DiscoveryPacket) {
        val payload = DiscoveryCodec.encode(packet)
        val targets = buildSet {
            add(group)
            localInterfaces().mapNotNullTo(this) { it.broadcast }
            // And straight to everyone already known. Multicast and broadcast
            // leave at the lowest basic rate, unacknowledged, and are the first
            // frames an access point drops under load; a unicast datagram is
            // rate adapted and acknowledged at the link layer, so it survives
            // exactly the conditions — a transfer saturating the air — under
            // which a peer would otherwise flicker out of the list.
            peerTable.snapshot().mapTo(this) { it.address }
        }
        for (target in targets) {
            runCatching {
                bound.send(DatagramPacket(payload, payload.size, InetAddress.getByName(target), port))
            }
        }
    }

    private fun publish() {
        _peers.value = peerTable.snapshot()
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true
}

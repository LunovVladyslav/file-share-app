package com.lunov.flyshare.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Peer discovery, per docs/PROTOCOL.md §4.
 *
 * Nothing here touches the Android SDK — see SPEC.md, "The structural rule".
 * That is not tidiness for its own sake: it means this code runs against the
 * real desktop app from a JVM test, which is how it gets verified before a
 * phone is ever involved.
 */

const val PROTOCOL_VERSION = 2
const val DISCOVERY_PORT = 45888
const val TRANSFER_PORT = 45889
const val MULTICAST_GROUP = "239.255.77.88"
const val ANNOUNCE_INTERVAL_MS = 3_000L
const val PEER_TTL_MS = 12_000L

/** A device seen on the network. */
data class Peer(
    val id: String,
    val name: String,
    val os: String,
    val address: String,
    val port: Int,
    val version: Int,
    val lastSeen: Long,
)

/** The three packet shapes on the discovery port. */
sealed interface DiscoveryPacket {
    val id: String

    data class Announce(
        override val id: String,
        val name: String,
        val os: String,
        val port: Int,
        val version: Int,
        val addresses: List<String>,
    ) : DiscoveryPacket

    data class Probe(override val id: String) : DiscoveryPacket

    data class Bye(override val id: String) : DiscoveryPacket
}

// The wire form. Unknown fields are ignored so a future version adding a field
// does not make older devices disappear from the list.
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private class WireAnnounce(
    val t: String = "announce",
    val id: String,
    val name: String,
    val os: String,
    val port: Int,
    val ver: Int,
    @SerialName("addrs") val addrs: List<String>,
)

@Serializable
private class WireIdOnly(val t: String, val id: String)

object DiscoveryCodec {

    fun encode(packet: DiscoveryPacket): ByteArray = when (packet) {
        is DiscoveryPacket.Announce -> json.encodeToString(
            WireAnnounce(
                id = packet.id,
                name = packet.name,
                os = packet.os,
                port = packet.port,
                ver = packet.version,
                addrs = packet.addresses,
            ),
        )
        is DiscoveryPacket.Probe -> json.encodeToString(WireIdOnly("probe", packet.id))
        is DiscoveryPacket.Bye -> json.encodeToString(WireIdOnly("bye", packet.id))
    }.encodeToByteArray()

    /**
     * Parse a datagram. Returns null for anything unrecognised rather than
     * throwing: this port is open to the whole subnet, and one malformed packet
     * from unrelated software must not take discovery down.
     */
    fun decode(bytes: ByteArray, length: Int = bytes.size): DiscoveryPacket? = try {
        val element = json.parseToJsonElement(bytes.decodeToString(0, length))
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null

        when (obj["t"]?.jsonPrimitive?.contentOrNullSafe()) {
            "announce" -> json.decodeFromJsonElement(WireAnnounce.serializer(), obj).let {
                DiscoveryPacket.Announce(it.id, it.name, it.os, it.port, it.ver, it.addrs)
            }
            "probe" -> DiscoveryPacket.Probe(id)
            "bye" -> DiscoveryPacket.Bye(id)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else null

/**
 * The peer list, with the same lifetime rules as the desktop: a device is
 * present while it keeps announcing, and gone 12 seconds after it stops.
 */
class PeerTable(private val selfId: String, private val clock: () -> Long = System::currentTimeMillis) {

    private val peers = LinkedHashMap<String, Peer>()

    @Synchronized
    fun snapshot(): List<Peer> = peers.values.toList()

    /**
     * Record an announcement. `source` is the address the datagram came from,
     * used only when none of the advertised addresses is reachable.
     *
     * @return true when the visible list changed and the interface should redraw
     */
    @Synchronized
    fun onAnnounce(packet: DiscoveryPacket.Announce, source: String, locals: List<LocalInterface>): Boolean {
        if (packet.id == selfId) return false

        val existing = peers[packet.id]
        val peer = Peer(
            id = packet.id,
            name = packet.name,
            os = packet.os,
            address = pickReachableAddress(packet.addresses, source, locals),
            port = packet.port,
            version = packet.version,
            lastSeen = clock(),
        )
        peers[packet.id] = peer

        return existing == null ||
            existing.name != peer.name ||
            existing.address != peer.address ||
            existing.os != peer.os
    }

    @Synchronized
    fun onBye(id: String): Boolean = id != selfId && peers.remove(id) != null

    /**
     * Drop anything that has gone quiet.
     *
     * A connection that succeeded counts as having seen the device, and counts
     * for more than an announcement that may never have arrived — see
     * [Presence].
     */
    @Synchronized
    fun reap(): Boolean {
        val cutoff = clock() - PEER_TTL_MS
        val before = peers.size
        peers.values.removeAll { maxOf(it.lastSeen, Presence.lastContact(it.id)) < cutoff }
        return peers.size != before
    }
}

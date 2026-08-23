package com.lunov.flyshare.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryCodecTest {

    /** Captured verbatim from the running Node desktop. */
    private val realAnnounce = """
        {"t":"announce","id":"9b52506ab52eeb22","name":"Vladyslav's ThinkPad",
         "os":"windows","port":45889,"ver":2,
         "addrs":["192.168.100.234","172.18.96.1"]}
    """.trimIndent().replace("\n", "")

    @Test
    fun `parses an announcement from the desktop`() {
        val packet = DiscoveryCodec.decode(realAnnounce.encodeToByteArray())
        assertTrue(packet is DiscoveryPacket.Announce)
        assertEquals("9b52506ab52eeb22", packet.id)
        assertEquals("Vladyslav's ThinkPad", packet.name)
        assertEquals("windows", packet.os)
        assertEquals(45889, packet.port)
        assertEquals(2, packet.version)
        assertEquals(listOf("192.168.100.234", "172.18.96.1"), packet.addresses)
    }

    @Test
    fun `round-trips every packet type`() {
        val packets = listOf(
            DiscoveryPacket.Announce("aa", "Phone", "android", 45889, 2, listOf("192.168.1.5")),
            DiscoveryPacket.Probe("aa"),
            DiscoveryPacket.Bye("aa"),
        )
        for (packet in packets) {
            assertEquals(packet, DiscoveryCodec.decode(DiscoveryCodec.encode(packet)))
        }
    }

    @Test
    fun `our announcement is shaped the way the desktop expects`() {
        val encoded = DiscoveryCodec.encode(
            DiscoveryPacket.Announce("aa", "Phone", "android", 45889, 2, listOf("192.168.1.5")),
        ).decodeToString()

        // The desktop reads these keys by name; a rename is a silent break.
        for (key in listOf("\"t\":\"announce\"", "\"id\"", "\"name\"", "\"os\"", "\"port\"", "\"ver\"", "\"addrs\"")) {
            assertTrue(key in encoded, "announcement is missing $key: $encoded")
        }
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val future = """{"t":"announce","id":"aa","name":"N","os":"linux","port":1,
                         "ver":3,"addrs":[],"somethingNew":{"nested":true}}""".replace("\n", "")
        assertNotNull(DiscoveryCodec.decode(future.encodeToByteArray()))
    }

    @Test
    fun `junk on the port is ignored rather than fatal`() {
        // This port is open to the whole subnet; other software will send to it.
        for (junk in listOf("", "{", "not json at all", """{"t":"announce"}""", """{"id":"x"}""")) {
            assertNull(DiscoveryCodec.decode(junk.encodeToByteArray()), "should ignore: $junk")
        }
    }

    @Test
    fun `decode respects the datagram length, not the buffer size`() {
        val buffer = ByteArray(1024)
        val payload = DiscoveryCodec.encode(DiscoveryPacket.Probe("aa"))
        payload.copyInto(buffer)
        // The tail of the buffer is zeroes from a previous, longer packet.
        assertEquals(DiscoveryPacket.Probe("aa"), DiscoveryCodec.decode(buffer, payload.size))
    }
}

class PeerTableTest {

    private var now = 1_000_000L
    private fun table() = PeerTable(selfId = "self", clock = { now })

    private fun announce(id: String, name: String = "Peer", addrs: List<String> = listOf("192.168.1.9")) =
        DiscoveryPacket.Announce(id, name, "windows", 45889, 2, addrs)

    private val locals = listOf(
        LocalInterface("Wi-Fi", "192.168.1.5", 24, "192.168.1.255", physical = true),
    )

    @Test
    fun `adds a peer and reports the change`() {
        val table = table()
        assertTrue(table.onAnnounce(announce("a"), "192.168.1.9", locals))
        assertEquals(1, table.snapshot().size)
        assertEquals("192.168.1.9", table.snapshot().first().address)
    }

    @Test
    fun `repeat announcements do not redraw the list`() {
        val table = table()
        table.onAnnounce(announce("a"), "192.168.1.9", locals)
        assertFalse(table.onAnnounce(announce("a"), "192.168.1.9", locals))
    }

    @Test
    fun `a rename does redraw`() {
        val table = table()
        table.onAnnounce(announce("a", name = "Old"), "192.168.1.9", locals)
        assertTrue(table.onAnnounce(announce("a", name = "New"), "192.168.1.9", locals))
        assertEquals("New", table.snapshot().first().name)
    }

    @Test
    fun `our own announcements are ignored`() {
        val table = table()
        assertFalse(table.onAnnounce(announce("self"), "192.168.1.5", locals))
        assertTrue(table.snapshot().isEmpty())
    }

    @Test
    fun `a peer expires after the silence window`() {
        val table = table()
        table.onAnnounce(announce("a"), "192.168.1.9", locals)

        now += PEER_TTL_MS - 1
        assertFalse(table.reap())
        assertEquals(1, table.snapshot().size)

        now += 2
        assertTrue(table.reap())
        assertTrue(table.snapshot().isEmpty())
    }

    @Test
    fun `bye removes the peer at once`() {
        val table = table()
        table.onAnnounce(announce("a"), "192.168.1.9", locals)
        assertTrue(table.onBye("a"))
        assertTrue(table.snapshot().isEmpty())
        assertFalse(table.onBye("a"))
    }
}

class AddressSelectionTest {

    private val wifi = LocalInterface("Wi-Fi", "192.168.100.234", 24, "192.168.100.255", physical = true)
    private val hyperV = LocalInterface("vEthernet (Default Switch)", "172.18.96.1", 20, "172.18.111.255", physical = false)

    @Test
    fun `prefers the address on our physical interface's subnet`() {
        // Exactly the Windows case: a Hyper-V address nothing else can reach,
        // advertised alongside the real one.
        val chosen = pickReachableAddress(
            candidates = listOf("172.18.96.5", "192.168.100.77"),
            fallback = "172.18.96.5",
            locals = listOf(wifi, hyperV),
        )
        assertEquals("192.168.100.77", chosen)
    }

    @Test
    fun `falls back to the packet source when nothing shares a subnet`() {
        assertEquals(
            "10.9.9.9",
            pickReachableAddress(listOf("10.9.9.9"), "10.9.9.9", listOf(wifi)),
        )
    }

    @Test
    fun `an empty advertisement still yields the source`() {
        assertEquals("10.0.0.2", pickReachableAddress(emptyList(), "10.0.0.2", listOf(wifi)))
    }

    @Test
    fun `subnet maths honours the prefix length`() {
        assertTrue(sameSubnet("192.168.1.5", "192.168.1.200", 24))
        assertFalse(sameSubnet("192.168.1.5", "192.168.2.200", 24))
        assertTrue(sameSubnet("192.168.1.5", "192.168.2.200", 16))
        assertTrue(sameSubnet("10.1.2.3", "10.200.200.200", 8))
        assertFalse(sameSubnet("10.1.2.3", "11.1.2.3", 8))
    }

    @Test
    fun `malformed addresses never match`() {
        assertFalse(sameSubnet("not.an.ip.address", "192.168.1.1", 24))
        assertFalse(sameSubnet("192.168.1.1", "192.168.1", 24))
        assertFalse(sameSubnet("999.1.1.1", "192.168.1.1", 24))
        assertFalse(sameSubnet("192.168.1.1", "192.168.1.1", 0))
    }

    @Test
    fun `this machine reports at least one usable interface`() {
        val locals = localInterfaces()
        assertTrue(locals.isNotEmpty(), "no IPv4 interface found; discovery cannot work here")
        // Physical first — the ordering the address choice depends on.
        val firstVirtual = locals.indexOfFirst { !it.physical }
        val lastPhysical = locals.indexOfLast { it.physical }
        if (firstVirtual >= 0 && lastPhysical >= 0) {
            assertTrue(lastPhysical < firstVirtual, "physical interfaces must sort first: $locals")
        }
    }
}

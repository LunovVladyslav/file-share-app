package com.lunov.flyshare.core

import java.util.concurrent.ConcurrentHashMap

/**
 * When this device last actually spoke to another one.
 *
 * Discovery gives up on a peer after four missed announcements. Those go out
 * over multicast and broadcast, which Wi-Fi sends at a low basic rate, without
 * acknowledgement, and drops first when the air is busy — so during a large
 * transfer the device being transferred *to* can disappear from the list while
 * eighty gigabytes are moving between the two.
 *
 * A TCP connection that completed is far better evidence of presence than a
 * datagram that may never have left the radio. This is where that evidence is
 * kept. It is deliberately a shared object rather than a wire between the two:
 * the transfer code has no reason to hold a reference to discovery, and
 * discovery has no reason to know that transfers exist.
 *
 * The desktop keeps the same record in src/core/presence.js.
 */
object Presence {

    private val contacts = ConcurrentHashMap<String, Long>()

    /** Called whenever a connection with this device succeeds, either way. */
    fun noteContact(deviceId: String?) {
        if (!deviceId.isNullOrBlank()) contacts[deviceId] = System.currentTimeMillis()
    }

    /** Milliseconds since the epoch, or 0 if this device was never reached. */
    fun lastContact(deviceId: String): Long = contacts[deviceId] ?: 0L

    fun forget(deviceId: String) {
        contacts.remove(deviceId)
    }
}

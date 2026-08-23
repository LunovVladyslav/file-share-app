package com.lunov.flyshare.core

import java.net.Inet4Address
import java.net.InterfaceAddress
import java.net.NetworkInterface

/** One usable IPv4 address on this machine. */
data class LocalInterface(
    val name: String,
    val address: String,
    val prefixLength: Int,
    val broadcast: String?,
    val physical: Boolean,
)

// Adapters that exist but rarely carry LAN traffic: Hyper-V and WSL switches,
// VM host-only networks, VPN and mesh tunnels, Apple's peer-to-peer link, and
// Android's own tethering and virtual interfaces.
private val VIRTUAL_ADAPTER = Regex(
    "vethernet|virtualbox|vmware|hyper-?v|loopback|tailscale|zerotier|utun|awdl|llw|" +
        "bridge|docker|veth|tun\\d|tap\\d|rmnet|dummy|ipsec|ap\\d",
    RegexOption.IGNORE_CASE,
)

/**
 * Every non-loopback IPv4 address this device owns, best first.
 *
 * Ordering is load-bearing. It decides which address we advertise as primary
 * and which of a peer's addresses we try — a phone with a VPN up, or a Windows
 * box with Hyper-V, will otherwise hand out an address nothing can reach.
 */
fun localInterfaces(): List<LocalInterface> =
    NetworkInterface.getNetworkInterfaces()
        ?.asSequence()
        ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        ?.flatMap { nic ->
            nic.interfaceAddresses.asSequence()
                .filter { it.address is Inet4Address }
                .map { addr ->
                    LocalInterface(
                        name = nic.displayName ?: nic.name,
                        address = addr.address.hostAddress ?: "",
                        prefixLength = addr.networkPrefixLength.toInt(),
                        broadcast = addr.broadcastOrNull(),
                        physical = !VIRTUAL_ADAPTER.containsMatchIn(nic.displayName ?: nic.name),
                    )
                }
        }
        ?.filter { it.address.isNotEmpty() }
        ?.sortedByDescending { it.physical }
        ?.toList()
        .orEmpty()

private fun InterfaceAddress.broadcastOrNull(): String? =
    runCatching { broadcast?.hostAddress }.getOrNull()

/**
 * Choose the address of a peer we can actually reach: the first advertised one
 * that shares a subnet with one of our interfaces, checked best-interface
 * first. Falls back to where the packet came from.
 */
fun pickReachableAddress(
    candidates: List<String>,
    fallback: String,
    locals: List<LocalInterface> = localInterfaces(),
): String {
    for (local in locals) {
        for (candidate in candidates) {
            if (sameSubnet(candidate, local.address, local.prefixLength)) return candidate
        }
    }
    return fallback
}

internal fun sameSubnet(a: String, b: String, prefixLength: Int): Boolean {
    if (prefixLength !in 1..32) return false
    val left = a.toIpv4Bits() ?: return false
    val right = b.toIpv4Bits() ?: return false
    val mask = (-1L shl (32 - prefixLength)) and 0xFFFFFFFFL
    return (left and mask) == (right and mask)
}

private fun String.toIpv4Bits(): Long? {
    val parts = split('.')
    if (parts.size != 4) return null
    var bits = 0L
    for (part in parts) {
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        bits = (bits shl 8) or octet.toLong()
    }
    return bits
}

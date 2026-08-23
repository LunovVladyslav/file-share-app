package com.lunov.flyshare.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Runs discovery from a plain JVM and prints what it finds.
 *
 * This is how the Android discovery code gets verified against the real desktop
 * before any phone is involved: it is the same engine the app will run, minus
 * the multicast permit, which is the one piece a JVM does not need.
 *
 *   ./gradlew :core:probe -Pname="Kotlin probe" -Pseconds=20
 */
fun main(args: Array<String>) = runBlocking {
    val name = args.getOrNull(0) ?: "Kotlin probe"
    val seconds = args.getOrNull(1)?.toIntOrNull() ?: 20

    val self = SelfDescription(
        // Stable but obviously not a real device, so it is easy to spot and
        // easy to forget from a desktop's paired list afterwards.
        id = "c0defacec0deface",
        name = name,
        os = "android",
    )

    println("interfaces, best first:")
    for (local in localInterfaces()) {
        println("  ${if (local.physical) "physical" else "virtual "}  ${local.address}/${local.prefixLength}".padEnd(34) + local.name)
    }

    val discovery = DiscoveryService(self)
    val scope = CoroutineScope(SupervisorJob())
    discovery.start(scope)
    println("\nannouncing as \"$name\" for ${seconds}s\n")

    var lastSeen = emptyList<Peer>()
    repeat(seconds) {
        delay(1_000)
        val peers = discovery.peers.value
        if (peers != lastSeen) {
            println("peers (${peers.size}):")
            for (peer in peers) {
                println("  ${peer.name}  [${peer.os}]  ${peer.address}:${peer.port}  v${peer.version}  id=${peer.id}")
            }
            lastSeen = peers
        }
    }

    discovery.stop()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    println("\nstopped; sent bye")
}

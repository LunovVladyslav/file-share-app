plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // The platform's XDH only exists from API 33 and the app supports 26, so
    // one implementation covers every level — and it is the same one the spike
    // checked against Node byte for byte.
    api(libs.bouncycastle.prov)

    // TLS 1.3 with an external PSK. Conscrypt cannot do it — its PSK key
    // manager is deprecated precisely because it does not work with 1.3 — and
    // this is the same stack the spike proved against Node in both roles.
    api(libs.bouncycastle.tls)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

/**
 * Runs the discovery engine from a plain JVM, against whatever else is on the
 * network. This is the interop check for the Android code — the same engine,
 * without the multicast permit a desktop does not need.
 */
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Announce on the local network and print the peers that answer"
    mainClass.set("com.lunov.flyshare.core.DiscoveryProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Not "name": Gradle's own Project.name wins over a -P property and the
    // probe would announce itself as "core".
    args = listOf(
        project.findProperty("probeName")?.toString() ?: "Kotlin probe",
        project.findProperty("probeSeconds")?.toString() ?: "20",
    )
}

/**
 * Receives one transfer from the real Node sender — see TransferProbe.
 *
 * Pairing this with `node spike/send-to.mjs` puts the production desktop
 * implementation on one end and this receiver on the other, on a desktop,
 * where a failure can actually be looked at.
 */
tasks.register<JavaExec>("receive") {
    group = "verification"
    description = "Listen for one transfer and print a digest of everything received"
    mainClass.set("com.lunov.flyshare.core.TransferProbe")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
    // Without this the JVM prints received filenames in the console codepage,
    // and a non-Latin name comes out as mojibake — which looks exactly like a
    // transfer bug and is not one.
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
    args = listOf(
        project.findProperty("outDir")?.toString() ?: "build/received",
        project.findProperty("timeoutSeconds")?.toString() ?: "120",
        project.findProperty("receivePort")?.toString() ?: "45889",
    )
}

/**
 * Sends one folder to the real Node receiver — the mirror of `receive`, and
 * the other half of the interoperability check.
 */
tasks.register<JavaExec>("send") {
    group = "verification"
    description = "Send a folder to a FlyShare receiver and print a digest of everything sent"
    mainClass.set("com.lunov.flyshare.core.SendProbe")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
    args = listOf(
        project.findProperty("sendDir")?.toString() ?: "build/to-send",
        project.findProperty("sendHost")?.toString() ?: "127.0.0.1",
        project.findProperty("sendPort")?.toString() ?: "45889",
    )
}

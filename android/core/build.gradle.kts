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

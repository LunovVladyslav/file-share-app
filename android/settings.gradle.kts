pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "flyshare-android"

// The protocol core is a plain JVM module on purpose: it must not reach for the
// Android SDK, so it can be unit-tested — and run against the desktop — without
// an emulator. See SPEC.md "The structural rule".
include(":core")
include(":app")

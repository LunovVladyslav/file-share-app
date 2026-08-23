import java.util.Properties

plugins {
    // AGP 9 brings its own Kotlin support; the standalone kotlin-android
    // plugin is not only unnecessary now, it refuses to apply.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The version lives in the desktop's package.json, and is read rather than
 * repeated. The two halves ship together and speak one protocol; two version
 * numbers that can drift apart is a bug waiting to be reported as "it says
 * 0.1.2 on my laptop".
 */
val appVersion: String = run {
    val json = rootProject.file("../package.json").readText()
    Regex("""["']version["']\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        ?: error("no version in package.json")
}

/** 0.1.2 becomes 102: monotonic, and readable back to the name it came from. */
val appVersionCode: Int = appVersion.split('.').map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

/**
 * Signing details, if this machine has them.
 *
 * The keystore and its password are the developer's, never the repository's —
 * anyone holding them can publish an update that Android will install over
 * this app. keystore.properties is git-ignored, and CI reads the same four
 * values from secrets instead. Without either, a release build is produced
 * unsigned, which is still useful for testing and impossible to mistake for a
 * shippable artifact.
 */
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

val signing = Properties().apply {
    val local = rootProject.file("keystore.properties")
    if (local.exists()) local.inputStream().use { load(it) }
    signingKeys.forEach { key ->
        // Blank, not just absent: CI passes every value as an environment
        // variable whether or not the secret exists, and an empty path would
        // resolve to the project directory — which exists, so signing would
        // switch itself on and then fail on a directory.
        System.getenv("FLYSHARE_" + key.uppercase())
            ?.takeIf { it.isNotBlank() }
            ?.let { setProperty(key, it) }
    }
}

val canSign = signingKeys.all { !signing.getProperty(it).isNullOrBlank() } &&
    file(signing.getProperty("storeFile")).exists()

android {
    // Matches the package the sources are actually in. When it did not, `R`
    // and `BuildConfig` were generated somewhere else and a manifest entry
    // written as ".MainActivity" resolved to a class that does not exist —
    // which builds perfectly and dies on launch. The id installed on the
    // device is applicationId below, and that has not changed.
    namespace = "com.lunov.flyshare.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lunov.flyshare"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Most of the 14 MB was BouncyCastle, most of which this app never
            // calls. See proguard-rules.pro for the two things R8 cannot see
            // on its own — and note that both fail at run time, not build
            // time, so the release APK is checked on a device before it ships.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSign) signingConfigs.getByName("release") else null
        }
    }

    packaging {
        resources {
            // The three BouncyCastle jars each ship the same licence and
            // notice. Take one copy rather than excluding them: they are the
            // terms this app is redistributing that code under.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }

    // A manifest naming a class that is not in the APK builds perfectly well
    // and then dies on launch with ClassNotFoundException. Lint sees it; make
    // that stop the build instead of scrolling past.
    lint {
        warningsAsErrors = false
        abortOnError = true
        fatal += listOf("MissingClass", "Instantiatable")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Writing into a folder the person picked, without asking for broad
    // storage access. The raw DocumentsContract API can do it; this makes
    // "find or create this subdirectory" one call instead of a query.
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
}

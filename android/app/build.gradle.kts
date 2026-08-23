plugins {
    // AGP 9 brings its own Kotlin support; the standalone kotlin-android
    // plugin is not only unnecessary now, it refuses to apply.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lunov.flyshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lunov.flyshare"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

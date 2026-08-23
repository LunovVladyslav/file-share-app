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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
}

// The app itself, shared by Android and iOS: screens, view models, settings,
// caches and the AI features. Each platform supplies a [Platform] and hosts
// the screens; Android-only and iOS-only pieces live in androidMain/iosMain.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "app.northvane.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ui"))
            api(libs.cmp.material.icons.core)
            implementation(libs.cmp.resources)
            api(libs.cmp.lifecycle.viewmodel.compose)
            api(libs.cmp.lifecycle.runtime.compose)
            implementation(libs.cmp.navigation.compose)
            api(libs.datastore.preferences.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.maplibre.android)
            implementation(libs.zxing.core)
        }
    }
}

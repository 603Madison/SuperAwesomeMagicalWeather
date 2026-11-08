// Look and feel shared by the main app, Mini and the iPhone app: weather
// icons, sky colors, the brand color schemes and small drawing helpers.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "app.northvane.ui"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonMain.dependencies {
            // Weather conditions and alert categories appear in this module's API.
            api(project(":core"))
            api(libs.cmp.runtime)
            api(libs.cmp.ui)
            api(libs.cmp.foundation)
            api(libs.cmp.material3)
            implementation(libs.cmp.resources)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "app.northvane.ui.generated.resources"
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// SuperAwesomeMagicalWeather for Wear OS ships with the same version as the main app, from one place.
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val sharedVersionName = Regex("""versionName = "([^"]+)"""").find(appBuild)!!.groupValues[1]
val sharedVersionCode = Regex("""versionCode = (\d+)""").find(appBuild)!!.groupValues[1].toInt()

// Signed with the same release key as the main app (keystore.properties locally,
// RELEASE_KEYSTORE_* variables in CI); unsigned without either.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// Blank counts as unset: CI passes optional secrets that weren't added as empty strings.
fun signingValue(property: String, env: String): String? =
    (keystoreProps.getProperty(property) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val keystorePath = signingValue("storeFile", "RELEASE_KEYSTORE_FILE")

android {
    namespace = "app.northvane.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.northvane.wear"
        // Wear OS 3 and newer.
        minSdk = 30
        targetSdk = 37
        versionCode = sharedVersionCode
        versionName = sharedVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = signingValue("storePassword", "RELEASE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS") ?: "northvane"
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            // Same as the main app: no R8 until a shrunk build has been tried on a device.
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // F-Droid and other independent builders can't read the dependency list AGP
    // otherwise embeds, encrypted with Google's key, in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test -> test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// No AI features on the watch, so it leaves out the Claude SDK that :core brings (about 10 MB).
configurations.configureEach { exclude(group = "com.anthropic") }

dependencies {
    implementation(project(":core"))
    // Lexend and the weather icons.
    implementation(project(":ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.complications.datasource)
    implementation(libs.wear.input)
    implementation(libs.androidx.concurrent.futures.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

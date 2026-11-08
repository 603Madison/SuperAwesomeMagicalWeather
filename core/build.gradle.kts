import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent data layer: API clients, parsing, source fallback,
// radar frame math, outlooks and AI prompts. Shared by the Android apps (JVM)
// and the iPhone app (iOS); unit tested on a plain JVM.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.io.core)
            implementation(libs.atomicfu)
        }
        jvmMain.dependencies {
            api(libs.okhttp)
            implementation(libs.anthropic.java)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit)
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}


tasks.named<Test>("jvmTest") {
    // Opt-in tests against the live weather services: ./gradlew :core:jvmTest -Pnorthvane.live=true
    systemProperty("northvane.live", providers.gradleProperty("northvane.live").getOrElse("false"))
}

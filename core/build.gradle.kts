import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent data layer: API clients, parsing, source fallback,
// radar frame math and network security policy. Kept free of Android APIs so
// it can be unit tested on a plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Opt-in tests against the live weather services: ./gradlew :core:test -Pnorthvane.live=true
    systemProperty("northvane.live", providers.gradleProperty("northvane.live").getOrElse("false"))
}

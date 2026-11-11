import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// SuperAwesomeMagicalWeather for Linux: the shared app in a desktop window,
// packaged as .deb (Ubuntu, Debian, Mint, Pop!_OS) and .rpm (Fedora, RHEL,
// openSUSE) with its own Java runtime. `./gradlew :desktop:run` runs it from
// source; `:desktop:packageDeb` / `:desktop:packageRpm` build the packages.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Same version as the phone app, from one place.
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val sharedVersionName = Regex("""versionName = "([^"]+)"""").find(appBuild)!!.groupValues[1]

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            // Dispatchers.Main on the desktop: the AWT event thread.
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

// The version for the window and User-Agent, written at build time.
val versionSource = layout.buildDirectory.dir("generated/version")
val writeVersion by tasks.registering {
    val version = sharedVersionName
    val out = versionSource
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("app/northvane/desktop/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package app.northvane.desktop\n\ninternal const val APP_VERSION = \"$version\"\n")
    }
}
kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(writeVersion) }

compose.desktop {
    application {
        mainClass = "app.northvane.desktop.MainKt"
        jvmArgs += listOf("-Xmx768m", "-Dawt.useSystemAAFontSettings=on")

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            // The name in the app menu and the launcher's; the package itself is lowercase (below).
            packageName = "SuperAwesomeMagicalWeather"
            packageVersion = sharedVersionName
            description = "Weather forecasts, radar and alerts"
            vendor = "SuperAwesomeMagicalWeather"
            licenseFile.set(rootProject.file("LICENSE"))
            // What the app uses from the JDK (see :desktop:suggestRuntimeModules); the rest is left out.
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "java.desktop")

            linux {
                packageName = "superawesomemagicalweather"
                iconFile.set(project.file("packaging/icon.png"))
                shortcut = true
                menuGroup = "Utility"
                appCategory = "Utility"
                // Debian wants an address here; .invalid is reserved for addresses that go nowhere.
                debMaintainer = "noreply@superawesomemagicalweather.invalid"
                rpmLicenseType = "GPL-3.0-or-later"
                debPackageVersion = sharedVersionName
                rpmPackageVersion = sharedVersionName
            }
        }

        buildTypes.release.proguard {
            // As on Android: no shrinking until a shrunk build has been tried.
            isEnabled.set(false)
        }
    }
}

// See packaging/portable-deb.sh: one .deb for Ubuntu 22.04 and newer.
tasks.matching { it.name == "packageDeb" || it.name == "packageReleaseDeb" }.configureEach {
    val script = project.file("packaging/portable-deb.sh")
    val dir = layout.buildDirectory.dir("compose/binaries")
    doLast {
        dir.get().asFile.walkTopDown().filter { it.extension == "deb" }.forEach { deb ->
            val process = ProcessBuilder(script.path, deb.path).inheritIO().start()
            check(process.waitFor() == 0) { "portable-deb.sh failed for ${deb.name}" }
        }
    }
}

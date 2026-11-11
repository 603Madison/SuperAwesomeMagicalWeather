package app.northvane.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.northvane.weather.desktop.DesktopApp
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    DesktopApp.version = APP_VERSION
    // The systemd timer's run: check alerts and send any reminder that's due, then quit.
    if ("--background-check" in args) {
        DesktopApp.backgroundCheck()
        exitProcess(0)
    }
    if ("--version" in args) {
        println("SuperAwesomeMagicalWeather $APP_VERSION")
        exitProcess(0)
    }
    setWindowClass()
    DesktopApp.onLaunch()
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use {
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }
    application {
        val state = rememberWindowState(size = DpSize(480.dp, 900.dp))
        Window(
            onCloseRequest = {
                DesktopApp.onExit()
                exitApplication()
            },
            title = "SuperAwesomeMagicalWeather",
            icon = icon,
            state = state,
        ) {
            window.minimumSize = java.awt.Dimension(360, 560)
            window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = DesktopApp.onFocus()
            })
            DesktopApp.Content()
        }
    }
}

/**
 * The window's WM_CLASS, which GNOME and KDE match to the installed .desktop
 * file to show the app's icon and name in the dock instead of "java".
 */
private fun setWindowClass() {
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(toolkit, "superawesomemagicalweather-SuperAwesomeMagicalWeather")
    }
}

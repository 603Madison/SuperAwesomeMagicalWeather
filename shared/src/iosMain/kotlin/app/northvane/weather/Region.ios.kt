package app.northvane.weather

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale

internal actual fun regionCode(): String? = NSLocale.currentLocale.countryCode

package app.northvane.weather

internal actual fun regionCode(): String? = java.util.Locale.getDefault().country.takeIf { it.length == 2 }

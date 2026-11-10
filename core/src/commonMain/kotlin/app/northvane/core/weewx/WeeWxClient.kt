package app.northvane.core.weewx

import app.northvane.core.model.CurrentConditions
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.InsecureConfigurationException
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.LanCleartextAllowed
import app.northvane.core.net.LocalServerClients
import app.northvane.core.net.StaleDataException
import app.northvane.core.net.UrlPolicy
import app.northvane.core.net.fetch
import app.northvane.core.net.Credentials
import app.northvane.core.net.Request
import app.northvane.core.time.*

class WeeWxClient(private val clients: LocalServerClients) {

    suspend fun fetch(
        station: WeeWxStation,
        secret: WeeWxSecret?,
        zone: ZoneId,
        now: Instant = kotlin.time.Clock.System.now(),
    ): CurrentConditions {
        val sendsCredentials = station.auth != WeeWxAuth.NONE
        val validated = when (val v = UrlPolicy.validateUserServerUrl(station.url, sendsCredentials)) {
            is UrlPolicy.Validation.Invalid -> throw InsecureConfigurationException(v.reason)
            is UrlPolicy.Validation.Valid -> v
        }

        val builder = Request.Builder().url(validated.url).header("Accept", "application/json, text/plain, */*")
        when (station.auth) {
            WeeWxAuth.NONE -> Unit
            WeeWxAuth.BASIC -> builder.secretHeader(
                "Authorization",
                Credentials.basic(station.username.orEmpty(), secret?.value.orEmpty()),
            )
            WeeWxAuth.BEARER -> builder.secretHeader("Authorization", "Bearer ${secret?.value.orEmpty()}")
            WeeWxAuth.HEADER -> {
                val name = station.headerName?.trim().orEmpty()
                if (!HEADER_NAME.matches(name)) throw InsecureConfigurationException("Invalid custom header name")
                builder.secretHeader(name, secret?.value.orEmpty())
            }
        }
        val client = if (validated.isCleartext) {
            builder.allowLanCleartext()
            clients.cleartext
        } else {
            clients.https
        }

        val response = client.fetch(builder.build(), maxBytes = 512L * 1024)
        when (response.code) {
            in 200..299 -> Unit
            401, 403 -> throw InvalidCredentialsException("Your WeeWX server")
            else -> throw HttpStatusException(response.code, "Your WeeWX server")
        }

        val conditions = WeeWxParsers.parse(
            body = response.body,
            format = station.format,
            stationName = station.name,
            zone = zone,
            lastModified = response.lastModified,
            now = now,
        )
        val observed = conditions.observedAt ?: response.lastModified ?: now
        val age = Duration.between(observed, now)
        if (age > Duration.ofMinutes(station.maxAgeMinutes.toLong())) {
            throw StaleDataException("Local station data is ${age.toMinutes()} minutes old")
        }
        return conditions.copy(observedAt = observed)
    }

    private companion object {
        val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")
    }
}

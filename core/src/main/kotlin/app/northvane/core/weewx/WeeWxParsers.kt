package app.northvane.core.weewx

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.asObj
import app.northvane.core.json.dbl
import app.northvane.core.json.long
import app.northvane.core.json.str
import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DataSource
import app.northvane.core.model.Units
import app.northvane.core.net.ParseException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WeeWxParsers {

    fun detect(body: String): WeeWxFormat {
        val t = body.trimStart()
        return when {
            t.startsWith("{") -> WeeWxFormat.NORTHVANE_JSON
            t.startsWith("12345 ") -> WeeWxFormat.CLIENTRAW
            else -> WeeWxFormat.CUMULUS_REALTIME
        }
    }

    fun parse(
        body: String,
        format: WeeWxFormat,
        stationName: String,
        zone: ZoneId,
        lastModified: Instant?,
        now: Instant,
    ): CurrentConditions {
        val resolved = if (format == WeeWxFormat.AUTO) detect(body) else format
        val parsed = when (resolved) {
            WeeWxFormat.NORTHVANE_JSON -> parseNorthvaneJson(body, stationName)
            WeeWxFormat.CUMULUS_REALTIME -> parseRealtime(body, stationName, zone)
            WeeWxFormat.CLIENTRAW -> parseClientRaw(body, stationName, lastModified ?: now)
            WeeWxFormat.AUTO -> error("unreachable")
        }
        if (parsed.temperatureC == null && parsed.humidityPercent == null && parsed.windSpeedKph == null) {
            throw ParseException("Station data contained no readings")
        }
        return withDerivedFields(parsed)
    }

    /** Fill dew point and feels-like when the station doesn't report them. */
    private fun withDerivedFields(c: CurrentConditions): CurrentConditions {
        val t = c.temperatureC ?: return c
        val dew = c.dewpointC ?: c.humidityPercent?.let { Units.dewpointC(t, it) }
        val feels = c.feelsLikeC ?: Units.feelsLikeC(t, c.humidityPercent, c.windSpeedKph)
        return c.copy(dewpointC = dew, feelsLikeC = feels)
    }

    /** Output of `weewx/northvane.json.tmpl` shipped with this app; values are always metric. */
    fun parseNorthvaneJson(body: String, stationName: String): CurrentConditions {
        val o = runCatching { NorthvaneJson.parseToJsonElement(body).asObj() }.getOrNull()
            ?: throw ParseException("Station JSON could not be parsed")
        if (o.long("northvane_format") == null) {
            throw ParseException("JSON is not in the SuperAwesomeMagicalWeather template format (see weewx/README.md)")
        }
        val heatIndex = o.dbl("heatindex_C")
        val windChill = o.dbl("windchill_C")
        val temperature = o.dbl("outTemp_C")
        return CurrentConditions(
            observedAt = o.long("time")?.let(Instant::ofEpochSecond),
            source = DataSource.WEEWX,
            stationName = o.str("station")?.takeIf { it.isNotBlank() } ?: stationName,
            temperatureC = temperature,
            feelsLikeC = when {
                temperature == null -> null
                heatIndex != null && heatIndex > temperature + 0.5 -> heatIndex
                windChill != null && windChill < temperature - 0.5 -> windChill
                else -> null
            },
            dewpointC = o.dbl("dewpoint_C"),
            humidityPercent = o.dbl("outHumidity"),
            windSpeedKph = o.dbl("windSpeed_kph"),
            windGustKph = o.dbl("windGust_kph"),
            windDirectionDeg = o.dbl("windDir"),
            pressureHpa = o.dbl("barometer_hPa"),
            rainRateMmPerHour = o.dbl("rainRate_mmph"),
            rainTodayMm = o.dbl("dayRain_mm"),
            uvIndex = o.dbl("UV"),
            solarRadiationWm2 = o.dbl("radiation"),
        )
    }

    private fun field(fields: List<String>, index: Int): Double? =
        fields.getOrNull(index)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * Cumulus realtime.txt (as written by the weewx-crt extension): space
     * separated, with units declared in fields 13–16, so no guessing needed.
     */
    fun parseRealtime(body: String, stationName: String, zone: ZoneId): CurrentConditions {
        val f = body.trim().split(Regex("\\s+"))
        if (f.size < 17) throw ParseException("realtime.txt has too few fields (${f.size})")
        val windUnit = f[13].lowercase()
        val tempUnit = f[14].uppercase().trimStart('°')
        val pressUnit = f[15].lowercase()
        val rainUnit = f[16].lowercase()

        fun temp(i: Int) = field(f, i)?.let { if (tempUnit == "F") Units.fToC(it) else it }
        fun wind(i: Int) = field(f, i)?.let {
            when {
                windUnit.startsWith("mph") -> Units.mphToKph(it)
                windUnit.startsWith("m/s") -> Units.msToKph(it)
                windUnit.startsWith("kt") || windUnit.startsWith("knot") -> Units.knotsToKph(it)
                else -> it
            }
        }
        fun rain(i: Int) = field(f, i)?.let { if (rainUnit == "in") Units.inchesToMm(it) else it }
        val pressure = field(f, 10)?.let {
            when (pressUnit) {
                "in", "inhg" -> Units.inHgToHpa(it)
                "kpa" -> it * 10.0
                else -> it
            }
        }

        val observedAt = runCatching {
            val date = LocalDate.parse(f[0].replace('-', '/').replace('.', '/'), DateTimeFormatter.ofPattern("dd/MM/yy"))
            date.atTime(LocalTime.parse(f[1])).atZone(zone).toInstant()
        }.getOrNull()

        return CurrentConditions(
            observedAt = observedAt,
            source = DataSource.WEEWX,
            stationName = stationName,
            temperatureC = temp(2),
            humidityPercent = field(f, 3),
            dewpointC = temp(4),
            windSpeedKph = wind(5),
            windGustKph = wind(40) ?: wind(6),
            windDirectionDeg = field(f, 7),
            rainRateMmPerHour = rain(8),
            rainTodayMm = rain(9),
            pressureHpa = pressure,
            feelsLikeC = temp(54),
            uvIndex = field(f, 43),
            solarRadiationWm2 = field(f, 45),
        )
    }

    /**
     * clientraw.txt (Weather Display format, e.g. from weewx-clientraw). Units
     * are fixed by the format: knots, °C, hPa, mm, rain rate in mm/min.
     * Its timestamp has no zone or year, so the HTTP Last-Modified time is used.
     */
    fun parseClientRaw(body: String, stationName: String, observedAt: Instant): CurrentConditions {
        val f = body.trim().split(Regex("\\s+"))
        if (f.size < 11 || f[0] != "12345") throw ParseException("Not a clientraw.txt file")
        val name = f.getOrNull(32)
            ?.substringBeforeLast('-')
            ?.replace('_', ' ')
            ?.takeIf { it.isNotBlank() }
        return CurrentConditions(
            observedAt = observedAt,
            source = DataSource.WEEWX,
            stationName = name ?: stationName,
            windSpeedKph = field(f, 1)?.let(Units::knotsToKph),
            windGustKph = field(f, 2)?.let(Units::knotsToKph),
            windDirectionDeg = field(f, 3),
            temperatureC = field(f, 4),
            humidityPercent = field(f, 5),
            pressureHpa = field(f, 6),
            rainTodayMm = field(f, 7),
            rainRateMmPerHour = field(f, 10)?.let { it * 60.0 },
            dewpointC = field(f, 72),
        )
    }
}

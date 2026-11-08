package app.northvane.core.owm

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.dbl
import app.northvane.core.json.int
import app.northvane.core.json.long
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DataSource
import app.northvane.core.model.DetailSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherCondition
import app.northvane.core.net.ParseException
import kotlinx.serialization.json.JsonObject
import app.northvane.core.time.*

data class OwmForecast(
    val hourly: List<HourlyForecast>,
    val daily: List<DailyForecast>,
    val zoneOffset: ZoneId,
    val cityName: String?,
)

data class PlaceResult(val name: String, val latitude: Double, val longitude: Double)

object OwmParsers {

    private fun parseObject(json: String): JsonObject =
        runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
            ?: throw ParseException("Unexpected response from OpenWeatherMap")

    /** OWM condition ids: https://openweathermap.org/weather-conditions */
    fun conditionFor(id: Int?): WeatherCondition = when (id) {
        null -> WeatherCondition.UNKNOWN
        in 200..232 -> WeatherCondition.THUNDERSTORM
        in 300..321 -> WeatherCondition.DRIZZLE
        500, 501 -> WeatherCondition.RAIN
        in 502..504 -> WeatherCondition.HEAVY_RAIN
        511 -> WeatherCondition.FREEZING_RAIN
        in 520..531 -> WeatherCondition.SHOWERS
        in 611..613 -> WeatherCondition.SLEET
        615, 616 -> WeatherCondition.RAIN_SNOW
        in 600..622 -> WeatherCondition.SNOW
        701, 741 -> WeatherCondition.FOG
        711 -> WeatherCondition.SMOKE
        721 -> WeatherCondition.HAZE
        731, 751, 761, 762 -> WeatherCondition.DUST
        771 -> WeatherCondition.WINDY
        781 -> WeatherCondition.TORNADO
        800 -> WeatherCondition.CLEAR
        801 -> WeatherCondition.MOSTLY_CLEAR
        802 -> WeatherCondition.PARTLY_CLOUDY
        803 -> WeatherCondition.MOSTLY_CLOUDY
        804 -> WeatherCondition.OVERCAST
        else -> WeatherCondition.UNKNOWN
    }

    private fun weather(o: JsonObject): JsonObject? = o.arr("weather")?.firstOrNull()?.asObj()

    private fun String.capitalized() = replaceFirstChar { it.titlecase() }

    fun parseCurrent(json: String): CurrentConditions {
        val o = parseObject(json)
        val main = o.obj("main") ?: throw ParseException("OpenWeatherMap response has no readings")
        val w = weather(o)
        val wind = o.obj("wind")
        val temp = main.dbl("temp")
        val humidity = main.dbl("humidity")
        return CurrentConditions(
            observedAt = o.long("dt")?.let(Instant::ofEpochSecond),
            source = DataSource.OWM,
            stationName = o.str("name"),
            description = w?.str("description")?.capitalized(),
            condition = conditionFor(w?.int("id")),
            isDaytime = w?.str("icon")?.endsWith("n") != true,
            temperatureC = temp,
            feelsLikeC = main.dbl("feels_like"),
            dewpointC = if (temp != null && humidity != null) Units.dewpointC(temp, humidity) else null,
            humidityPercent = humidity,
            windSpeedKph = wind?.dbl("speed")?.let(Units::msToKph),
            windGustKph = wind?.dbl("gust")?.let(Units::msToKph),
            windDirectionDeg = wind?.dbl("deg"),
            pressureHpa = main.dbl("pressure"),
            visibilityKm = o.dbl("visibility")?.div(1000.0),
            rainRateMmPerHour = o.obj("rain")?.dbl("1h"),
        )
    }

    /** 5-day / 3-hour forecast, aggregated into days in the location's UTC offset. */
    fun parseForecast(json: String): OwmForecast {
        val o = parseObject(json)
        val city = o.obj("city")
        val offset = ZoneOffset.ofTotalSeconds((city?.int("timezone") ?: 0).coerceIn(-18 * 3600, 18 * 3600))
        val hourly = o.arr("list").orEmpty().mapNotNull { element ->
            val item = element.asObj() ?: return@mapNotNull null
            val time = item.long("dt")?.let(Instant::ofEpochSecond) ?: return@mapNotNull null
            val w = weather(item)
            val main = item.obj("main")
            HourlyForecast(
                time = time,
                temperatureC = main?.dbl("temp"),
                condition = conditionFor(w?.int("id")),
                isDaytime = item.obj("sys")?.str("pod") != "n",
                shortForecast = w?.str("description")?.capitalized(),
                precipitationChance = item.dbl("pop")?.let { (it * 100).toInt() },
                windSpeedKph = item.obj("wind")?.dbl("speed")?.let(Units::msToKph),
                windDirection = item.obj("wind")?.dbl("deg")?.let(Units::compass),
                humidityPercent = main?.dbl("humidity"),
                windGustKph = item.obj("wind")?.dbl("gust")?.let(Units::msToKph),
                windDirectionDeg = item.obj("wind")?.dbl("deg"),
                apparentTemperatureC = main?.dbl("feels_like"),
                skyCoverPercent = item.obj("clouds")?.int("all"),
                // 3-hour accumulations; snow is reported as liquid-equivalent mm, so approximate 10:1 depth.
                precipitationMm = listOfNotNull(item.obj("rain")?.dbl("3h"), item.obj("snow")?.dbl("3h")).takeIf { it.isNotEmpty() }?.sum(),
                snowMm = item.obj("snow")?.dbl("3h")?.times(10.0),
            )
        }

        val daily = hourly.groupBy { it.time.atOffset(offset).toLocalDate() }.map { (date, items) ->
            val temps = items.mapNotNull { it.temperatureC }
            // Prefer the early-afternoon condition as representative for the day.
            val representative = items.minByOrNull { kotlin.math.abs(it.time.atOffset(offset).hour - 13) } ?: items.first()
            DailyForecast(
                date = date,
                name = date.dayOfWeek.getDisplayName(TextStyle.FULL),
                highC = temps.maxOrNull(),
                lowC = temps.minOrNull(),
                condition = representative.condition,
                isDaytimeCondition = true,
                shortForecast = representative.shortForecast,
                precipitationChance = items.mapNotNull { it.precipitationChance }.maxOrNull(),
                windMaxKph = items.mapNotNull { it.windSpeedKph }.maxOrNull(),
                gustMaxKph = items.mapNotNull { it.windGustKph }.maxOrNull(),
                windDirectionDeg = items.maxByOrNull { it.windSpeedKph ?: 0.0 }?.windDirectionDeg,
                precipitationMm = items.mapNotNull { it.precipitationMm }.takeIf { it.isNotEmpty() }?.sum() ?: 0.0,
                snowMm = items.mapNotNull { it.snowMm }.takeIf { it.isNotEmpty() }?.sum() ?: 0.0,
                humidityMin = items.mapNotNull { it.humidityPercent }.minOrNull(),
                humidityMax = items.mapNotNull { it.humidityPercent }.maxOrNull(),
                skyCoverPercent = items.mapNotNull { it.skyCoverPercent }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                detailSource = DetailSource.OWM,
            )
        }
        return OwmForecast(hourly, daily, offset, city?.str("name"))
    }

    fun parseGeocode(json: String): List<PlaceResult> {
        val array = runCatching { NorthvaneJson.parseToJsonElement(json) as? kotlinx.serialization.json.JsonArray }
            .getOrNull() ?: throw ParseException("Unexpected geocoding response")
        return array.mapNotNull { element ->
            val o = element.asObj() ?: return@mapNotNull null
            val lat = o.dbl("lat") ?: return@mapNotNull null
            val lon = o.dbl("lon") ?: return@mapNotNull null
            val label = listOfNotNull(o.str("name"), o.str("state"), o.str("country")).joinToString(", ")
            PlaceResult(label, lat, lon)
        }
    }
}

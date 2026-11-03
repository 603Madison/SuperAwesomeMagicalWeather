package app.northvane.core

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.Instant

/** Fixed "now" used across tests: 10:00 CDT on 29 Oct 2026. */
val NOW: Instant = Instant.parse("2026-10-29T15:00:00Z")

/**
 * Short-circuits HTTP calls with canned responses keyed by URL substring, so
 * tests exercise real clients and interceptors without touching the network.
 */
class FakeNetwork : Interceptor {
    data class Canned(val code: Int, val body: String, val headers: Map<String, String> = emptyMap(), val bytes: ByteArray? = null)

    private val routes = mutableListOf<Pair<String, Canned>>()
    // OkHttp calls run in parallel, so recording must be thread-safe.
    val requests: MutableList<okhttp3.Request> = java.util.concurrent.CopyOnWriteArrayList()

    fun on(urlContains: String, code: Int = 200, body: String, headers: Map<String, String> = emptyMap()) {
        routes += urlContains to Canned(code, body, headers)
    }

    /** A binary response, such as a GRIB2 field. */
    fun onBytes(urlContains: String, bytes: ByteArray, code: Int = 200) {
        routes += urlContains to Canned(code, "", bytes = bytes)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val url = request.url.toString()
        // Most specific (longest) matching key wins; for equal keys, the latest registration wins.
        val canned = routes.withIndex()
            .filter { url.contains(it.value.first) }
            .maxWithOrNull(compareBy({ it.value.first.length }, { it.index }))
            ?.value?.second
            ?: Canned(404, """{"status":404,"title":"Not Found"}""")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(canned.code)
            .message("Canned")
            .apply { canned.headers.forEach { (k, v) -> header(k, v) } }
            .body(canned.bytes?.toResponseBody("application/octet-stream".toMediaType()) ?: canned.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

object Nws {
    const val BASE = "https://api.weather.gov"

    val point = """
    {
      "id": "$BASE/points/39.7456,-97.0892",
      "type": "Feature",
      "properties": {
        "@id": "$BASE/points/39.7456,-97.0892",
        "cwa": "TOP",
        "gridId": "TOP",
        "gridX": 32,
        "gridY": 81,
        "forecast": "$BASE/gridpoints/TOP/32,81/forecast",
        "forecastHourly": "$BASE/gridpoints/TOP/32,81/forecast/hourly",
        "forecastGridData": "$BASE/gridpoints/TOP/32,81",
        "observationStations": "$BASE/gridpoints/TOP/32,81/stations",
        "relativeLocation": {
          "type": "Feature",
          "geometry": { "type": "Point", "coordinates": [-97.086661, 39.679376] },
          "properties": {
            "city": "Linn",
            "state": "KS",
            "distance": { "unitCode": "wmoUnit:m", "value": 7366.98 },
            "bearing": { "unitCode": "wmoUnit:degree_(angle)", "value": 358 }
          }
        },
        "timeZone": "America/Chicago",
        "radarStation": "KTWX"
      }
    }
    """.trimIndent()

    val stations = """
    {
      "type": "FeatureCollection",
      "features": [
        { "id": "$BASE/stations/KMYZ", "type": "Feature",
          "properties": { "stationIdentifier": "KMYZ", "name": "Marysville Municipal Airport" } },
        { "id": "$BASE/stations/KCNK", "type": "Feature",
          "properties": { "stationIdentifier": "KCNK", "name": "Concordia, Blosser Municipal Airport" } }
      ]
    }
    """.trimIndent()

    fun observation(timestamp: String = "2026-10-29T14:53:00+00:00", tempC: String = "12.2") = """
    {
      "properties": {
        "stationName": "Marysville Municipal Airport",
        "timestamp": "$timestamp",
        "textDescription": "Partly Cloudy",
        "icon": "$BASE/icons/land/day/sct?size=medium",
        "temperature": { "unitCode": "wmoUnit:degC", "value": $tempC, "qualityControl": "V" },
        "dewpoint": { "unitCode": "wmoUnit:degC", "value": 3.9, "qualityControl": "V" },
        "windDirection": { "unitCode": "wmoUnit:degree_(angle)", "value": 200, "qualityControl": "V" },
        "windSpeed": { "unitCode": "wmoUnit:km_h-1", "value": 18.36, "qualityControl": "V" },
        "windGust": { "unitCode": "wmoUnit:km_h-1", "value": null, "qualityControl": "Z" },
        "barometricPressure": { "unitCode": "wmoUnit:Pa", "value": 101660, "qualityControl": "V" },
        "seaLevelPressure": { "unitCode": "wmoUnit:Pa", "value": null, "qualityControl": "Z" },
        "visibility": { "unitCode": "wmoUnit:m", "value": 16090, "qualityControl": "C" },
        "relativeHumidity": { "unitCode": "wmoUnit:percent", "value": 56.9, "qualityControl": "V" },
        "windChill": { "unitCode": "wmoUnit:degC", "value": 10.1, "qualityControl": "V" },
        "heatIndex": { "unitCode": "wmoUnit:degC", "value": null, "qualityControl": "V" }
      }
    }
    """.trimIndent()

    val hourly = """
    {
      "properties": {
        "units": "us",
        "periods": [
          { "number": 1, "name": "", "startTime": "2026-10-29T10:00:00-05:00", "endTime": "2026-10-29T11:00:00-05:00",
            "isDaytime": true, "temperature": 54, "temperatureUnit": "F", "temperatureTrend": "",
            "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": 3 },
            "dewpoint": { "unitCode": "wmoUnit:degC", "value": 3.888 },
            "relativeHumidity": { "unitCode": "wmoUnit:percent", "value": 56 },
            "windSpeed": "12 mph", "windDirection": "SSW",
            "icon": "$BASE/icons/land/day/sct?size=small",
            "shortForecast": "Mostly Sunny", "detailedForecast": "" },
          { "number": 2, "name": "", "startTime": "2026-10-29T11:00:00-05:00", "endTime": "2026-10-29T12:00:00-05:00",
            "isDaytime": true, "temperature": 57, "temperatureUnit": "F",
            "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": 20 },
            "windSpeed": "14 mph", "windDirection": "S",
            "icon": "$BASE/icons/land/day/rain_showers,20?size=small",
            "shortForecast": "Slight Chance Rain Showers" },
          { "number": 3, "name": "", "startTime": "2026-10-29T12:00:00-05:00", "endTime": "2026-10-29T13:00:00-05:00",
            "isDaytime": true, "temperature": 59, "temperatureUnit": "F",
            "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": 40 },
            "windSpeed": "15 mph", "windDirection": "S",
            "icon": "$BASE/icons/land/day/tsra_hi,40?size=small",
            "shortForecast": "Chance Showers And Thunderstorms" }
        ]
      }
    }
    """.trimIndent()

    val daily = """
    {
      "properties": {
        "periods": [
          { "number": 1, "name": "Today", "startTime": "2026-10-29T10:00:00-05:00", "isDaytime": true,
            "temperature": 61, "temperatureUnit": "F",
            "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": 40 },
            "windSpeed": "10 to 15 mph", "windDirection": "S",
            "icon": "$BASE/icons/land/day/sct/tsra_hi,40?size=medium",
            "shortForecast": "Mostly Sunny then Chance Showers And Thunderstorms",
            "detailedForecast": "A chance of showers and thunderstorms after 1pm." },
          { "number": 2, "name": "Tonight", "startTime": "2026-10-29T18:00:00-05:00", "isDaytime": false,
            "temperature": 44, "temperatureUnit": "F",
            "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": 60 },
            "windSpeed": "5 to 10 mph", "windDirection": "NW",
            "icon": "$BASE/icons/land/night/tsra,60/rain,30?size=medium",
            "shortForecast": "Showers And Thunderstorms Likely", "detailedForecast": "Showers likely." },
          { "number": 3, "name": "Friday", "startTime": "2026-10-30T06:00:00-05:00", "isDaytime": true,
            "temperature": 52, "temperatureUnit": "F",
            "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": null },
            "windSpeed": "10 mph", "windDirection": "NW",
            "icon": "$BASE/icons/land/day/few?size=medium",
            "shortForecast": "Sunny", "detailedForecast": "Sunny, with a high near 52." },
          { "number": 4, "name": "Friday Night", "startTime": "2026-10-30T18:00:00-05:00", "isDaytime": false,
            "temperature": 33, "temperatureUnit": "F",
            "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": null },
            "windSpeed": "5 mph", "windDirection": "N",
            "icon": "$BASE/icons/land/night/skc?size=medium",
            "shortForecast": "Clear", "detailedForecast": "Clear, with a low around 33." }
        ]
      }
    }
    """.trimIndent()

    val dailyStartingOvernight = """
    {
      "properties": {
        "periods": [
          { "number": 1, "name": "Overnight", "startTime": "2026-10-29T01:00:00-05:00", "isDaytime": false,
            "temperature": 40, "temperatureUnit": "F", "windSpeed": "5 mph", "windDirection": "S",
            "icon": "$BASE/icons/land/night/few?size=medium", "shortForecast": "Mostly Clear" },
          { "number": 2, "name": "Today", "startTime": "2026-10-29T06:00:00-05:00", "isDaytime": true,
            "temperature": 61, "temperatureUnit": "F", "windSpeed": "10 mph", "windDirection": "S",
            "icon": "$BASE/icons/land/day/skc?size=medium", "shortForecast": "Sunny" },
          { "number": 3, "name": "Tonight", "startTime": "2026-10-29T18:00:00-05:00", "isDaytime": false,
            "temperature": 44, "temperatureUnit": "F", "windSpeed": "5 mph", "windDirection": "S",
            "icon": "$BASE/icons/land/night/skc?size=medium", "shortForecast": "Clear" }
        ]
      }
    }
    """.trimIndent()

    /** Synthetic gridpoint data for TOP/32,81 around NOW: a windy, rainy afternoon. */
    val grid = """
    {
      "properties": {
        "temperature": { "uom": "wmoUnit:degC", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT12H", "value": 15 } ] },
        "relativeHumidity": { "uom": "wmoUnit:percent", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT12H", "value": 80 } ] },
        "skyCover": { "uom": "wmoUnit:percent", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT12H", "value": 90 } ] },
        "windDirection": { "uom": "wmoUnit:degree_(angle)", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT12H", "value": 200 } ] },
        "windSpeed": { "uom": "wmoUnit:km_h-1", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT12H", "value": 40 } ] },
        "windGust": { "uom": "wmoUnit:km_h-1", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT12H", "value": 96 } ] },
        "quantitativePrecipitation": { "uom": "wmoUnit:mm", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT6H", "value": 12 } ] },
        "snowfallAmount": { "uom": "wmoUnit:mm", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT6H", "value": 0 } ] },
        "iceAccumulation": { "uom": "wmoUnit:mm", "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT6H", "value": 0 } ] },
        "probabilityOfThunder": { "values": [ { "validTime": "2026-10-29T12:00:00+00:00/PT12H", "value": 10 } ] }
      }
    }
    """.trimIndent()

    val alerts = """
    {
      "type": "FeatureCollection",
      "features": [
        { "id": "$BASE/alerts/urn:oid:2.49.0.1.840.0.bbb", "type": "Feature",
          "properties": {
            "id": "urn:oid:2.49.0.1.840.0.bbb", "areaDesc": "Marshall, KS",
            "sent": "2026-10-29T08:00:00-05:00", "effective": "2026-10-29T08:00:00-05:00",
            "onset": "2026-10-29T12:00:00-05:00", "expires": "2026-10-29T20:00:00-05:00",
            "ends": "2026-10-29T21:00:00-05:00", "status": "Actual", "messageType": "Alert",
            "category": "Met", "severity": "Moderate", "certainty": "Likely", "urgency": "Expected",
            "event": "Wind Advisory", "senderName": "NWS Topeka KS",
            "headline": "Wind Advisory issued October 29 at 8:00AM CDT", "description": "South winds 25 to 35 mph.",
            "instruction": "Use extra caution when driving.", "references": [] } },
        { "id": "$BASE/alerts/urn:oid:2.49.0.1.840.0.aaa.2", "type": "Feature",
          "properties": {
            "id": "urn:oid:2.49.0.1.840.0.aaa.2", "areaDesc": "Marshall, KS",
            "sent": "2026-10-29T09:40:00-05:00", "effective": "2026-10-29T09:40:00-05:00",
            "onset": "2026-10-29T09:20:00-05:00", "expires": "2026-10-29T10:15:00-05:00",
            "ends": "2026-10-29T10:15:00-05:00", "status": "Actual", "messageType": "Update",
            "category": "Met", "severity": "Severe", "certainty": "Observed", "urgency": "Immediate",
            "event": "Severe Thunderstorm Warning", "senderName": "NWS Topeka KS",
            "headline": "Severe Thunderstorm Warning issued October 29 at 9:40AM CDT",
            "description": "At 938 AM CDT, a severe thunderstorm was located near Marysville.",
            "instruction": "For your protection move to an interior room on the lowest floor of a building.",
            "references": [
              { "@id": "$BASE/alerts/urn:oid:2.49.0.1.840.0.aaa.1", "identifier": "urn:oid:2.49.0.1.840.0.aaa.1",
                "sender": "w-nws.webmaster@noaa.gov", "sent": "2026-10-29T09:20:00-05:00" },
              { "@id": "$BASE/alerts/urn:oid:2.49.0.1.840.0.aaa.0", "identifier": "urn:oid:2.49.0.1.840.0.aaa.0",
                "sender": "w-nws.webmaster@noaa.gov", "sent": "2026-10-29T09:05:00-05:00" }
            ] } },
        { "id": "$BASE/alerts/urn:oid:test", "type": "Feature",
          "properties": { "id": "urn:oid:test", "status": "Test", "messageType": "Alert",
            "severity": "Unknown", "event": "Test Message" } },
        { "id": "$BASE/alerts/urn:oid:2.49.0.1.840.0.ddd", "type": "Feature",
          "properties": {
            "id": "urn:oid:2.49.0.1.840.0.ddd", "status": "Actual", "messageType": "Alert",
            "severity": "Severe", "certainty": "Possible", "urgency": "Future",
            "event": "Tornado Watch", "onset": "2026-10-29T11:00:00-05:00",
            "ends": "2026-10-29T19:00:00-05:00", "references": [] } }
      ]
    }
    """.trimIndent()
}

object Owm {
    val current = """
    {"coord":{"lon":-0.1257,"lat":51.5085},
     "weather":[{"id":803,"main":"Clouds","description":"broken clouds","icon":"04d"}],
     "main":{"temp":14.2,"feels_like":13.6,"temp_min":13.1,"temp_max":15.3,"pressure":1012,"humidity":78},
     "visibility":10000,"wind":{"speed":5.0,"deg":240,"gust":10.0},"clouds":{"all":75},
     "dt":${NOW.epochSecond - 600},"sys":{"country":"GB"},"timezone":0,"id":2643743,"name":"London","cod":200}
    """.trimIndent()

    val forecast = """
    {"cod":"200","cnt":4,"list":[
      {"dt":${Instant.parse("2026-10-29T12:00:00Z").epochSecond},"main":{"temp":15.0,"humidity":70},
       "weather":[{"id":500,"description":"light rain","icon":"10d"}],"wind":{"speed":4.0,"deg":180},"pop":0.65,"sys":{"pod":"d"}},
      {"dt":${Instant.parse("2026-10-29T21:00:00Z").epochSecond},"main":{"temp":9.0,"humidity":85},
       "weather":[{"id":800,"description":"clear sky","icon":"01n"}],"wind":{"speed":2.0,"deg":200},"pop":0.1,"sys":{"pod":"n"}},
      {"dt":${Instant.parse("2026-10-30T12:00:00Z").epochSecond},"main":{"temp":13.0,"humidity":60},
       "weather":[{"id":802,"description":"scattered clouds","icon":"03d"}],"wind":{"speed":3.0,"deg":270},"pop":0,"sys":{"pod":"d"}},
      {"dt":${Instant.parse("2026-10-30T03:00:00Z").epochSecond},"main":{"temp":7.5,"humidity":90},
       "weather":[{"id":741,"description":"fog","icon":"50n"}],"wind":{"speed":1.0,"deg":90},"pop":0,"sys":{"pod":"n"}}
    ],"city":{"name":"London","country":"GB","timezone":0}}
    """.trimIndent()
}

object WeeWx {
    val northvaneJson = """
    {"northvane_format": 1, "station": "Backyard", "time": ${NOW.epochSecond - 120},
     "outTemp_C": 10.5, "dewpoint_C": 4.2, "outHumidity": 65, "windSpeed_kph": 8.0, "windGust_kph": 15.0,
     "windDir": 225, "barometer_hPa": 1015.2, "rainRate_mmph": 0.0, "dayRain_mm": 1.2, "UV": 2.0,
     "radiation": 350, "heatindex_C": 10.5, "windchill_C": 9.0}
    """.trimIndent()

    /** Cumulus realtime.txt in imperial units (mph, °F, inHg, in), 58 fields. */
    fun realtime(date: String = "29/10/26", time: String = "09:58:00"): String {
        val f = MutableList(58) { "0" }
        f[0] = date; f[1] = time
        f[2] = "52.3"; f[3] = "65"; f[4] = "41.0"; f[5] = "5.0"; f[6] = "6.0"; f[7] = "225"
        f[8] = "0.00"; f[9] = "0.05"; f[10] = "30.01"; f[11] = "SW"; f[12] = "2"
        f[13] = "mph"; f[14] = "F"; f[15] = "in"; f[16] = "in"
        f[40] = "12.0"; f[43] = "2.5"; f[45] = "300"; f[54] = "50.5"
        return f.joinToString(" ")
    }

    fun clientraw(): String {
        val f = MutableList(80) { "0" }
        f[0] = "12345"; f[1] = "4.3"; f[2] = "8.6"; f[3] = "225"; f[4] = "10.5"; f[5] = "65"
        f[6] = "1015.2"; f[7] = "1.2"; f[10] = "0.01"; f[32] = "Backyard_Station-10:55:00"; f[72] = "4.2"
        return f.joinToString(" ")
    }
}

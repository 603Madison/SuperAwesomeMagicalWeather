package app.northvane.core.outlook

import app.northvane.core.model.AirQuality
import app.northvane.core.model.Allergen
import app.northvane.core.model.AllergyDay
import app.northvane.core.model.AllergyDriver
import app.northvane.core.model.AllergyLevel
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.MudLevel
import app.northvane.core.model.PollenDay
import app.northvane.core.model.PollenForecast
import app.northvane.core.model.Sensitivity
import app.northvane.core.model.Tip
import app.northvane.core.model.Tip.Kind
import app.northvane.core.model.Units
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import app.northvane.core.time.*

/**
 * A personal allergy score: how bad a day is likely to be for *this* person,
 * from the allergens they chose and how strongly they react.
 *
 * - **Pollen** comes from the Google Pollen API forecast: the specific plant
 *   (e.g. birch) when Google reports it for the area, otherwise the plant's
 *   pollen type (tree/grass/weed) at a discount.
 * - **Outdoor mold** isn't measured by any free service, so it's estimated
 *   from season, humidity, recent rain and warmth (leaf litter makes fall the
 *   peak in New England; snow cover and frozen ground shut it down).
 * - **Air pollution** uses the AQI forecast.
 *
 * Weather adjusts pollen exposure: steady rain washes it out, dry wind spreads
 * it, and thunderstorms during high grass/tree pollen can trigger asthma.
 */
object AllergyEstimator {

    fun forecast(
        profile: Map<Allergen, Sensitivity>,
        days: List<DailyForecast>,
        pollen: PollenForecast?,
        air: AirQuality?,
        today: LocalDate,
    ): List<AllergyDay> {
        if (profile.isEmpty()) return emptyList()
        val pollenByDate = pollen?.days.orEmpty().associateBy { it.date }
        val aqiByDate = air?.days.orEmpty().associateBy { it.date }
        return days.filter { !it.date.isBefore(today) }.take(7).map { day ->
            val pollenDay = pollenByDate[day.date]
            val aqi = aqiByDate[day.date]?.aqi ?: if (day.date == today) air?.current?.aqi else null
            score(profile, day, pollenDay, aqi)
        }
    }

    fun score(profile: Map<Allergen, Sensitivity>, day: DailyForecast, pollen: PollenDay?, aqi: Int?): AllergyDay {
        val drivers = profile.mapNotNull { (allergen, _) -> exposure(allergen, day, pollen, aqi) }
        val weighted = drivers.map { it to it.index * profile.getValue(it.allergen).weight }.sortedByDescending { it.second }
        val wantsPollen = profile.keys.any { it.typeCode != null }
        val pollenDriven = weighted.firstOrNull()?.first?.allergen?.typeCode != null

        var raw = (weighted.firstOrNull()?.second ?: 0.0) * 20 + min(15.0, 3.0 * weighted.drop(1).sumOf { it.second })
        val tips = mutableListOf<Tip>()
        val p = day.daytime
        val chance = p?.precipChanceMax ?: day.precipitationChance ?: 0
        val rainMm = p?.precipMm ?: day.precipitationMm?.let { it / 2 } ?: 0.0
        val gustMph = (p?.gustMaxKph ?: day.gustMaxKph)?.let(Units::kphToMph) ?: 0.0
        val thunder = p?.thunderChanceMax ?: day.thunderChance ?: 0

        if (pollenDriven && chance >= 70 && rainMm >= 4) {
            raw *= 0.7
            tips += Tip(Kind.ALLERGY_RAIN_HELPS)
        } else if (pollenDriven && gustMph >= 25 && chance < 30) {
            raw *= 1.12
            tips += Tip(Kind.ALLERGY_WIND, p?.gustMaxKph ?: day.gustMaxKph)
        }
        val highTreeOrGrass = drivers.any { it.allergen.group.name in setOf("TREES", "GRASSES") && it.index >= 3 }
        if (thunder >= 30 && highTreeOrGrass) {
            raw += 6
            tips += Tip(Kind.ALLERGY_THUNDERSTORM, thunder.toDouble())
        }
        if ((aqi ?: 0) > 100) {
            raw += 8
            tips += Tip(Kind.ALLERGY_AIR, aqi?.toDouble())
        }
        val score = raw.coerceIn(0.0, 100.0).roundToInt()
        val level = when {
            score >= 75 -> AllergyLevel.VERY_HIGH
            score >= 50 -> AllergyLevel.HIGH
            score >= 25 -> AllergyLevel.MODERATE
            else -> AllergyLevel.LOW
        }
        if (score >= 50) tips += Tip(Kind.ALLERGY_MEDICATE)
        if (score >= 40 && pollenDriven) {
            tips += Tip(Kind.ALLERGY_WINDOWS)
            tips += Tip(Kind.ALLERGY_SHOWER)
            tips += Tip(Kind.ALLERGY_TIMING)
        }
        if (weighted.firstOrNull()?.first?.allergen == Allergen.MOLD && score >= 35) tips += Tip(Kind.ALLERGY_MASK_YARDWORK)
        if (score < 25) tips.add(0, Tip(Kind.ALLERGY_QUIET))
        return AllergyDay(
            date = day.date,
            score = score,
            level = level,
            drivers = weighted.map { it.first }.filter { it.index > 0.05 },
            tips = tips.distinctBy { it.kind },
            pollenKnown = pollen != null || !wantsPollen,
        )
    }

    private fun exposure(allergen: Allergen, day: DailyForecast, pollen: PollenDay?, aqi: Int?): AllergyDriver? = when (allergen) {
        Allergen.MOLD -> AllergyDriver(allergen, moldIndex(day), estimated = true)
        Allergen.AIR_POLLUTION -> aqi?.let { AllergyDriver(allergen, (it / 40.0).coerceIn(0.0, 5.0)) }
        else -> {
            if (pollen == null) {
                null
            } else {
                val plant = (pollen.plants + pollen.types).firstOrNull { it.code in allergen.codes && it.index != null }
                if (plant != null) {
                    AllergyDriver(allergen, plant.index!!.toDouble())
                } else {
                    val type = pollen.types.firstOrNull { it.code == allergen.typeCode }
                    val index = type?.index?.toDouble() ?: 0.0
                    AllergyDriver(allergen, if (type?.inSeason == true) index * 0.8 else 0.0, estimated = true)
                }
            }
        }
    }

    /**
     * Outdoor mold spores, 0–5. Spores thrive in warm, damp weather and rotting
     * leaves (late summer and fall), and fall away under snow or frozen ground.
     */
    fun moldIndex(day: DailyForecast): Double {
        if (day.mud?.level == MudLevel.SNOW_COVERED || day.mud?.level == MudLevel.FROZEN) return 0.3
        val highF = day.highC?.let(Units::cToF) ?: 55.0
        if (highF < 36) return 0.3
        var index = when (day.date.monthValue) {
            7, 8, 9, 10 -> 2.6
            11 -> 1.8 // leaf litter, until it freezes
            5, 6 -> 1.8
            4 -> 1.2
            else -> 0.8
        }
        val humidity = listOfNotNull(day.humidityMin, day.humidityMax).takeIf { it.isNotEmpty() }?.average() ?: day.daytime?.humidityMean
        if ((humidity ?: 60.0) >= 75) index += 0.8
        if ((humidity ?: 60.0) <= 40) index -= 0.6
        val wetGround = day.mud?.level == MudLevel.MUDDY || day.mud?.level == MudLevel.VERY_MUDDY || (day.precipitationChance ?: 0) >= 60
        if (wetGround && highF >= 50) index += 0.8
        // Spores are released in bursts on breezy, dry days after rain.
        if (wetGround && (day.gustMaxKph?.let(Units::kphToMph) ?: 0.0) >= 20 && (day.precipitationChance ?: 0) < 40) index += 0.4
        if (highF in 65.0..85.0) index += 0.4
        return max(0.0, min(5.0, index))
    }
}

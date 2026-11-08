package app.northvane.core.outlook

import app.northvane.core.model.Activity
import app.northvane.core.model.ActivityScore
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.MudLevel
import app.northvane.core.model.MudOutlook
import app.northvane.core.model.PeriodStats
import app.northvane.core.model.Tip
import app.northvane.core.model.Tip.Kind
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.util.Solar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import app.northvane.core.time.*

/**
 * How pleasant a forecast day is for outdoor activities, 0–100, with the
 * reasons and practical recommendations. Each activity starts at 100 and
 * loses points for what matters to it: heat and storms for hiking, overnight
 * cold and wind for camping, mud for off-roading and biking, wind for
 * paddling, clouds and moonlight for stargazing. Seasonal notes (black flies,
 * ticks, hunting season) follow New England norms.
 */
object ActivityScorer {

    data class Context(
        val latitude: Double,
        val longitude: Double,
        val zone: ZoneId,
        val alerts: List<WeatherAlert>,
        /** Ice balance per day from [WaterIce]. */
        val waterIce: Map<LocalDate, Double> = emptyMap(),
        /** A nearby station actually reported snow on the ground (not just the model). */
        val snowConfirmed: Boolean = false,
        /** Measured water temperature nearby (river gauge or tide station), when the water card is on. */
        val waterTempC: Double? = null,
        /** The nearest river gauge is rising fast. */
        val riverRising: Boolean = false,
    )

    /** Seasonal conditions that decide which activities make sense on a day. */
    data class Season(
        val snowDepthIn: Double,
        val snowSeason: Boolean,
        val iceInches: Double,
        val summer: Boolean,
        val hunting: Boolean,
        val wintry: Boolean,
    )

    fun season(day: DailyForecast, mud: MudOutlook?, ctx: Context): Season {
        val depthIn = (mud?.snowDepthMm ?: 0.0) / 25.4
        val month = day.date.monthValue
        val md = MonthDay.from(day.date)
        val snowSeason = ctx.snowConfirmed && depthIn >= 1.0 && (month >= 11 || month <= 4)
        val highF = day.highC?.let(Units::cToF) ?: 50.0
        return Season(
            snowDepthIn = depthIn,
            snowSeason = snowSeason,
            iceInches = WaterIce.thicknessInches(ctx.waterIce[day.date] ?: 0.0),
            summer = md >= MonthDay.of(6, 1) && md <= MonthDay.of(9, 10),
            // Northern New England deer/bear/moose (fall) and wild turkey (spring) seasons, roughly.
            hunting = (md >= MonthDay.of(9, 1) && md <= MonthDay.of(12, 15)) || (md >= MonthDay.of(4, 25) && md <= MonthDay.of(5, 31)),
            wintry = snowSeason || ((month == 12 || month <= 3) && highF <= 36),
        )
    }

    /** Whether an activity makes sense at all on this day (snow sports need confirmed snowpack, etc.). */
    fun available(activity: Activity, s: Season, date: LocalDate): Boolean = when (activity) {
        Activity.PADDLING -> !s.snowSeason
        Activity.OFF_ROADING -> !s.snowSeason
        Activity.SWIMMING -> s.summer
        Activity.HUNTING -> s.hunting
        Activity.SKIING -> s.snowSeason
        Activity.XC_SKIING, Activity.SNOWSHOEING -> s.snowSeason && s.snowDepthIn >= 4
        Activity.SNOWMOBILING -> s.snowSeason && s.snowDepthIn >= 8
        Activity.ICE_FISHING -> (date.monthValue == 12 || date.monthValue <= 3) && s.iceInches >= 5
        else -> true
    }

    fun score(day: DailyForecast, mud: MudOutlook?, ctx: Context, activities: Collection<Activity> = Activity.entries): List<ActivityScore> {
        val daytime = day.daytime ?: Periods.fromDaily(day, overnight = false)
        val overnight = day.overnight ?: Periods.fromDaily(day, overnight = true)
        val s = season(day, mud, ctx)
        return activities.filter { available(it, s, day.date) }.map { activity ->
            val period = if (activity.overnight) overnight else daytime
            val window = if (activity.overnight) Periods.overnight(day.date, ctx.zone) else Periods.daytime(day.date, ctx.zone)
            val card = Scorecard()
            var title: String? = null
            when (activity) {
                Activity.HIKING -> {
                    hiking(card, period, mud, day.date, ctx, s)
                    if (s.wintry) title = "Winter hiking"
                }
                Activity.CAMPING -> {
                    camping(card, period, mud, day.date, ctx)
                    if (s.wintry) {
                        title = "Winter camping"
                        card.advise(Tip(Kind.WINTER_GEAR))
                    }
                }
                Activity.OFF_ROADING -> offRoading(card, period, mud, day.date, ctx)
                Activity.MOUNTAIN_BIKING -> {
                    biking(card, period, mud, day.date, ctx, fat = s.snowSeason)
                    if (s.snowSeason) title = "Fat biking"
                }
                Activity.PADDLING -> paddling(card, period, day.date, ctx)
                Activity.SWIMMING -> swimming(card, period, day.date)
                Activity.HUNTING -> hunting(card, period, day, s, ctx)
                Activity.SKIING -> skiing(card, period, day, s)
                Activity.XC_SKIING -> xcSkiing(card, period, day, s)
                Activity.SNOWSHOEING -> snowshoeing(card, period, day, s)
                Activity.SNOWMOBILING -> snowmobiling(card, period, day, s)
                Activity.ICE_FISHING -> iceFishing(card, period, s)
                Activity.STARGAZING -> stargazing(card, period, window)
            }
            val warned = ctx.alerts.any { a ->
                a.category == AlertCategory.WARNING && overlaps(a, window)
            }
            card.finish(activity, warned).copy(title = title)
        }
    }

    private class Scorecard {
        var penalty = 0.0
        val problems = mutableListOf<Pair<Tip, Double>>() // tip, weight for ordering
        val advice = linkedSetOf<Tip>()

        fun lose(points: Double, tip: Tip? = null) {
            if (points <= 0) return
            penalty += points
            if (tip != null) problems += tip to points
        }

        fun advise(tip: Tip) {
            advice += tip
        }

        fun finish(activity: Activity, warned: Boolean): ActivityScore {
            var score = (100 - penalty).coerceIn(0.0, 100.0).roundToInt()
            val tips = mutableListOf<Tip>()
            if (warned) {
                score = min(score, 10)
                tips += Tip(Kind.WARNING)
            }
            tips += problems.filter { it.second >= 4 }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.kind }
            tips += advice.filter { a -> tips.none { it.kind == a.kind } }
            if (score >= 85 && problems.none { it.second >= 4 }) tips.add(0, Tip(Kind.IDEAL))
            return ActivityScore(activity, score, tips.take(6))
        }
    }

    // --- Activities ---

    private fun hiking(c: Scorecard, p: PeriodStats, mud: MudOutlook?, date: LocalDate, ctx: Context, s: Season) {
        val hot = f(p.feelsMaxC ?: p.tempMaxC)
        val cold = f(p.feelsMinC ?: p.tempMinC)
        heat(c, hot, comfortableTo = 78.0, perDegree = 2.5)
        chill(c, cold, comfortableFrom = 35.0, perDegree = 1.2, p)
        muggy(c, p)
        wet(c, p, chanceWeight = 0.35, amountWeight = 30.0)
        thunder(c, p, weight = 0.6, earlyStart = true)
        val gust = mph(p.gustMaxKph)
        if (gust != null) {
            c.lose((gust - 25) * 1.2, Tip(Kind.WINDY, p.gustMaxKph))
            if (gust >= 20) c.advise(Tip(Kind.SUMMIT_WIND, p.gustMaxKph))
        }
        winterTrail(c, p, mud, cold)
        if (s.wintry) {
            c.advise(Tip(Kind.WINTER_GEAR))
            if (s.snowDepthIn >= 4) c.advise(Tip(Kind.SNOWSHOES)) else c.advise(Tip(Kind.MICROSPIKES))
        }
        when (mud?.level) {
            MudLevel.MUDDY -> c.lose(8.0, Tip(Kind.MUDDY))
            MudLevel.VERY_MUDDY -> c.lose(15.0, Tip(Kind.MUDDY))
            else -> {}
        }
        if (mud?.mudSeason == true) c.advise(Tip(Kind.MUD_SEASON))
        if ((p.skyCoverMean ?: 0) >= 85 && (p.precipChanceMax ?: 0) < 40) c.lose(5.0, Tip(Kind.CLOUDY))
        if ((p.skyCoverMean ?: 100) < 40 && date.monthValue in 5..8) c.advise(Tip(Kind.SUNSCREEN))
        daylight(c, date, ctx)
        seasonal(c, date, hot, ctx.latitude, bugs = true)
    }

    private fun camping(c: Scorecard, p: PeriodStats, mud: MudOutlook?, date: LocalDate, ctx: Context) {
        val lowF = f(p.feelsMinC ?: p.tempMinC)
        val lowC = p.tempMinC ?: p.feelsMinC
        if (lowF != null) {
            if (lowF < 45) c.lose((45 - lowF) * 1.3, Tip(if (lowF < 15) Kind.VERY_COLD else Kind.COLD, p.feelsMinC ?: lowC))
            if (lowF > 65) c.lose((lowF - 65) * 3.0, Tip(Kind.HOT_NIGHT, lowC))
            c.advise(
                when {
                    lowF < 15 -> Tip(Kind.SLEEPING_BAG_0F, lowC)
                    lowF < 32 -> Tip(Kind.SLEEPING_BAG_20F, lowC)
                    lowF < 45 -> Tip(Kind.SLEEPING_BAG_30F, lowC)
                    lowF <= 65 -> Tip(Kind.SLEEPING_BAG_SUMMER, lowC)
                    else -> Tip(Kind.HOT_NIGHT, lowC)
                },
            )
            val airLowF = f(p.tempMinC) ?: lowF
            if (airLowF in 15.0..34.0) c.advise(Tip(Kind.FROST, p.tempMinC))
        }
        val chance = p.precipChanceMax ?: 0
        wet(c, p, chanceWeight = 0.4, amountWeight = 35.0)
        if (chance >= 30) c.advise(Tip(Kind.RAINFLY, chance.toDouble()))
        thunder(c, p, weight = 0.8, earlyStart = false)
        val gust = mph(p.gustMaxKph)
        if (gust != null) {
            c.lose((gust - 20) * 1.3, Tip(Kind.WINDY, p.gustMaxKph))
            if (gust >= 20) c.advise(Tip(Kind.GUYLINES, p.gustMaxKph))
        }
        snowAndIce(c, p)
        if ((p.humidityMean ?: 0.0) >= 85 && (mph(p.windMeanKph) ?: 0.0) < 5 && chance < 30) c.advise(Tip(Kind.CONDENSATION))
        if (mud?.level == MudLevel.VERY_MUDDY || mud?.level == MudLevel.MUDDY) c.lose(5.0, Tip(Kind.MUDDY))
        val month = date.monthValue
        if (lowF != null && lowF > 55 && month in 6..8 && (p.humidityMean ?: 0.0) >= 70) c.advise(Tip(Kind.MOSQUITOES))
        seasonal(c, date, lowF, ctx.latitude, bugs = true)
    }

    private fun offRoading(c: Scorecard, p: PeriodStats, mud: MudOutlook?, date: LocalDate, ctx: Context) {
        when (mud?.level) {
            MudLevel.DAMP -> c.lose(5.0)
            MudLevel.MUDDY -> {
                c.lose(25.0, Tip(Kind.MUDDY))
                c.advise(Tip(Kind.AVOID_RUTS)); c.advise(Tip(Kind.RECOVERY_GEAR))
            }
            MudLevel.VERY_MUDDY -> {
                c.lose(45.0, Tip(Kind.MUDDY))
                c.advise(Tip(Kind.AVOID_RUTS)); c.advise(Tip(Kind.RECOVERY_GEAR))
            }
            MudLevel.SNOW_COVERED -> c.lose(20.0, Tip(Kind.SNOW, p.snowMm))
            else -> {}
        }
        if (mud?.mudSeason == true) c.lose(15.0, Tip(Kind.TRAIL_CLOSURES))
        wet(c, p, chanceWeight = 0.3, amountWeight = 30.0)
        thunder(c, p, weight = 0.6, earlyStart = false)
        val hot = f(p.feelsMaxC ?: p.tempMaxC)
        heat(c, hot, comfortableTo = 88.0, perDegree = 2.0)
        chill(c, f(p.feelsMinC ?: p.tempMinC), comfortableFrom = 20.0, perDegree = 1.0, p)
        mph(p.gustMaxKph)?.let { c.lose((it - 40) * 1.5, Tip(Kind.WINDY, p.gustMaxKph)) }
        snowAndIce(c, p)
        if (mud?.level == MudLevel.DRY && (p.precipChanceMax ?: 0) < 20 && (hot ?: 0.0) >= 75) c.advise(Tip(Kind.DUSTY))
        seasonal(c, date, hot, ctx.latitude, bugs = false)
    }

    private fun biking(c: Scorecard, p: PeriodStats, mud: MudOutlook?, date: LocalDate, ctx: Context, fat: Boolean = false) {
        if (fat) {
            // Fat bikes ride packed or groomed snow; deep fresh snow and thaw slush make it a slog.
            val freshIn = (p.snowMm ?: 0.0) / 25.4
            if (freshIn >= 3) c.lose(25.0, Tip(Kind.BREAK_TRAIL, p.snowMm))
            val warm = f(p.tempMaxC) ?: 20.0
            if (warm > 34) c.lose((warm - 34) * 2.5, Tip(Kind.SPRING_SNOW))
            chill(c, f(p.feelsMinC ?: p.tempMinC), comfortableFrom = 10.0, perDegree = 1.3, p)
            wet(c, p, chanceWeight = 0.3, amountWeight = 30.0)
            mph(p.gustMaxKph)?.let { c.lose((it - 30) * 1.0, Tip(Kind.WINDY, p.gustMaxKph)) }
            c.advise(Tip(Kind.TRAIL_REPORT))
            c.advise(Tip(Kind.HAND_WARMERS))
            daylight(c, date, ctx)
            return
        }
        when (mud?.level) {
            MudLevel.DAMP -> c.lose(5.0)
            MudLevel.MUDDY -> { c.lose(30.0, Tip(Kind.MUDDY)); c.advise(Tip(Kind.LET_TRAILS_DRY)) }
            MudLevel.VERY_MUDDY -> { c.lose(50.0, Tip(Kind.MUDDY)); c.advise(Tip(Kind.LET_TRAILS_DRY)) }
            MudLevel.SNOW_COVERED -> c.lose(30.0, Tip(Kind.SNOW, p.snowMm))
            else -> {}
        }
        if (mud?.mudSeason == true) c.lose(20.0, Tip(Kind.TRAIL_CLOSURES))
        val hot = f(p.feelsMaxC ?: p.tempMaxC)
        heat(c, hot, comfortableTo = 80.0, perDegree = 2.5)
        chill(c, f(p.feelsMinC ?: p.tempMinC), comfortableFrom = 40.0, perDegree = 1.3, p)
        muggy(c, p)
        wet(c, p, chanceWeight = 0.4, amountWeight = 30.0)
        thunder(c, p, weight = 0.6, earlyStart = true)
        mph(p.gustMaxKph)?.let { c.lose((it - 30) * 1.0, Tip(Kind.WINDY, p.gustMaxKph)) }
        snowAndIce(c, p)
        daylight(c, date, ctx)
        seasonal(c, date, hot, ctx.latitude, bugs = false)
    }

    private fun paddling(c: Scorecard, p: PeriodStats, date: LocalDate, ctx: Context) {
        // A sustained freeze ices over lakes and fills rivers with ice floes: no paddling at all.
        val ice = ctx.waterIce[date] ?: 0.0
        if (ice >= WaterIce.FROZEN) {
            c.lose(100.0, Tip(Kind.ICE_ON_WATER))
            return
        }
        if (ice >= WaterIce.SHORE_ICE) c.lose(25.0, Tip(Kind.SHORE_ICE))
        val wind = mph(p.windMeanKph ?: p.windMaxKph?.let { it * 0.75 })
        val gust = mph(p.gustMaxKph)
        val rough = (wind ?: 0.0) >= 12 || (gust ?: 0.0) >= 22
        if (wind != null) c.lose((wind - 8) * 3.5, if (rough) Tip(Kind.ROUGH_WATER, p.windMeanKph ?: p.windMaxKph) else null)
        if (gust != null) c.lose((gust - 18) * 1.5, Tip(Kind.WINDY, p.gustMaxKph))
        if (rough) c.advise(Tip(Kind.OFFSHORE_WIND))
        thunder(c, p, weight = 1.0, earlyStart = false)
        wet(c, p, chanceWeight = 0.3, amountWeight = 25.0)
        if ((p.precipChanceMax ?: 0) >= 30) c.advise(Tip(Kind.DRY_BAG))
        val hot = f(p.feelsMaxC ?: p.tempMaxC)
        heat(c, hot, comfortableTo = 88.0, perDegree = 2.0)
        val cool = f(p.feelsMinC ?: p.tempMinC)
        if (cool != null) c.lose((55 - cool) * 1.2, Tip(Kind.COLD, p.feelsMinC ?: p.tempMinC))
        val water = f(ctx.waterTempC)
        if (water != null) {
            // Measured nearby: under 60 °F, a capsize brings cold shock whatever the air temperature.
            if (water < 60) c.lose((60 - water) * 0.6, Tip(Kind.COLD_WATER, ctx.waterTempC))
        } else if (date.monthValue !in 7..9 || (f(p.tempMaxC) ?: 70.0) < 60) {
            // New England lakes and the Gulf of Maine stay cold well into summer.
            c.lose(5.0, Tip(Kind.COLD_WATER))
        }
        if (ctx.riverRising) c.lose(15.0, Tip(Kind.RIVER_RISING))
        snowAndIce(c, p)
        if ((p.skyCoverMean ?: 100) < 40 && date.monthValue in 5..9) c.advise(Tip(Kind.SUNSCREEN))
    }

    private fun stargazing(c: Scorecard, p: PeriodStats, window: Pair<Instant, Instant>) {
        val sky = p.skyCoverMean ?: ((p.precipChanceMax ?: 30) + 20).coerceAtMost(100)
        c.lose(sky * 0.8, if (sky >= 30) Tip(Kind.CLOUDY, sky.toDouble()) else null)
        c.lose((p.precipChanceMax ?: 0) * 0.3)
        val moon = moonIllumination(window.first.plus(Duration.between(window.first, window.second).dividedBy(2)))
        c.lose(moon * 25, if (moon > 0.6) Tip(Kind.BRIGHT_MOON, moon * 100) else null)
        if (moon < 0.25 && sky < 30) c.advise(Tip(Kind.DARK_SKIES, moon * 100))
        if (moon in 0.25..0.6) c.advise(Tip(Kind.MOONLIT, moon * 100))
        if ((p.humidityMean ?: 0.0) >= 90) c.lose(5.0, Tip(Kind.CONDENSATION))
        val low = f(p.feelsMinC ?: p.tempMinC)
        if (low != null) {
            c.lose((25 - low) * 0.8, Tip(Kind.COLD, p.feelsMinC ?: p.tempMinC))
            if (low < 45) c.advise(Tip(Kind.LAYERS, p.feelsMinC ?: p.tempMinC))
        }
    }

    private fun snowDayCommon(c: Scorecard, p: PeriodStats, day: DailyForecast) {
        val rainy = (p.precipChanceMax ?: 0) >= 40 && (f(p.tempMaxC) ?: 30.0) > 34 && (p.snowMm ?: 0.0) < (p.precipMm ?: 0.0)
        if (rainy) c.lose(35.0, Tip(Kind.RAIN_ON_SNOW))
        if ((p.iceMm ?: day.iceMm ?: 0.0) > 0.2) c.lose(30.0, Tip(Kind.ICY_SURFACES))
        thunder(c, p, weight = 0.8, earlyStart = false)
    }

    private fun skiing(c: Scorecard, p: PeriodStats, day: DailyForecast, s: Season) {
        val cold = f(p.feelsMinC ?: p.tempMinC)
        if (cold != null && cold < 5) {
            c.lose((5 - cold) * 1.5, Tip(if (cold < -15) Kind.VERY_COLD else Kind.COLD, p.feelsMinC ?: p.tempMinC))
            c.advise(Tip(Kind.HAND_WARMERS))
        }
        val warm = f(p.tempMaxC)
        if (warm != null && warm > 40) c.lose((warm - 40) * 1.5, Tip(Kind.SPRING_SNOW))
        snowDayCommon(c, p, day)
        mph(p.gustMaxKph)?.let { c.lose((it - 30) * 1.5, if (it >= 35) Tip(Kind.LIFT_HOLDS, p.gustMaxKph) else null) }
        val freshIn = ((day.snowMm ?: 0.0) / 25.4)
        if (freshIn >= 3) {
            c.penalty -= 10 // powder day
            c.advise(Tip(Kind.POWDER_DAY, day.snowMm))
        }
        if (s.snowDepthIn < 6) c.advise(Tip(Kind.THIN_COVER, s.snowDepthIn * 25.4))
        c.advise(Tip(Kind.CHECK_SNOW_REPORT))
    }

    private fun xcSkiing(c: Scorecard, p: PeriodStats, day: DailyForecast, s: Season) {
        chill(c, f(p.feelsMinC ?: p.tempMinC), comfortableFrom = 10.0, perDegree = 1.2, p)
        val warm = f(p.tempMaxC)
        if (warm != null && warm > 36) c.lose((warm - 36) * 2.0, Tip(Kind.SPRING_SNOW))
        snowDayCommon(c, p, day)
        mph(p.gustMaxKph)?.let { c.lose((it - 30) * 1.0, Tip(Kind.WINDY, p.gustMaxKph)) }
        c.advise(Tip(Kind.KICK_WAX, p.tempMaxC ?: day.highC))
        if ((day.snowMm ?: 0.0) >= 100) c.advise(Tip(Kind.BREAK_TRAIL, day.snowMm))
        if (s.snowDepthIn < 8) c.advise(Tip(Kind.THIN_COVER, s.snowDepthIn * 25.4))
    }

    private fun snowshoeing(c: Scorecard, p: PeriodStats, day: DailyForecast, s: Season) {
        chill(c, f(p.feelsMinC ?: p.tempMinC), comfortableFrom = 10.0, perDegree = 1.2, p)
        snowDayCommon(c, p, day)
        mph(p.gustMaxKph)?.let { c.lose((it - 30) * 1.0, Tip(Kind.WINDY, p.gustMaxKph)) }
        if ((day.snowMm ?: 0.0) >= 75) c.advise(Tip(Kind.BREAK_TRAIL, day.snowMm))
        c.advise(Tip(Kind.WINTER_GEAR))
        if (s.snowDepthIn >= 4) c.advise(Tip(Kind.SNOWSHOES))
    }

    private fun snowmobiling(c: Scorecard, p: PeriodStats, day: DailyForecast, s: Season) {
        val cold = f(p.feelsMinC ?: p.tempMinC)
        if (cold != null && cold < 0) c.lose((0 - cold) * 1.2, Tip(if (cold < -20) Kind.VERY_COLD else Kind.COLD, p.feelsMinC ?: p.tempMinC))
        val warm = f(p.tempMaxC)
        if (warm != null && warm > 36) c.lose((warm - 36) * 3.0, Tip(Kind.SPRING_SNOW))
        snowDayCommon(c, p, day)
        if (s.snowDepthIn < 12) c.lose(10.0, Tip(Kind.THIN_COVER, s.snowDepthIn * 25.4))
        c.advise(Tip(Kind.TRAIL_REPORT))
        c.advise(Tip(Kind.ICE_CHECK, s.iceInches))
    }

    private fun iceFishing(c: Scorecard, p: PeriodStats, s: Season) {
        chill(c, f(p.feelsMinC ?: p.tempMinC), comfortableFrom = 15.0, perDegree = 1.3, p)
        mph(p.gustMaxKph)?.let { c.lose((it - 20) * 1.5, Tip(Kind.WINDY, p.gustMaxKph)) }
        val warm = f(p.tempMaxC)
        if (warm != null && warm > 38) c.lose((warm - 38) * 3.0, Tip(Kind.ICE_WEAKENING))
        if ((p.precipChanceMax ?: 0) >= 50 && (warm ?: 30.0) > 33) c.lose(30.0, Tip(Kind.ICE_WEAKENING))
        wet(c, p, chanceWeight = 0.2, amountWeight = 20.0)
        c.advise(Tip(Kind.ICE_CHECK, s.iceInches))
        c.advise(Tip(Kind.HAND_WARMERS))
    }

    private fun swimming(c: Scorecard, p: PeriodStats, date: LocalDate) {
        val hot = f(p.feelsMaxC ?: p.tempMaxC) ?: 70.0
        if (hot < 78) c.lose((78 - hot) * 3.0, Tip(Kind.COLD, Units.fToC(hot)))
        if (hot > 95) c.lose((hot - 95) * 2.0, Tip(Kind.HOT, Units.fToC(hot)))
        thunder(c, p, weight = 1.2, earlyStart = false)
        if ((p.thunderChanceMax ?: 0) >= 10) c.advise(Tip(Kind.LIGHTNING_SWIM))
        wet(c, p, chanceWeight = 0.4, amountWeight = 30.0)
        c.lose(((p.skyCoverMean ?: 30) - 50) * 0.3, Tip(Kind.CLOUDY))
        mph(p.gustMaxKph)?.let { c.lose((it - 22) * 1.2, Tip(Kind.ROUGH_WATER, p.gustMaxKph)) }
        c.advise(Tip(Kind.COLD_WATER_SWIM))
        if ((p.skyCoverMean ?: 100) < 50) c.advise(Tip(Kind.SUNSCREEN))
    }

    private fun hunting(c: Scorecard, p: PeriodStats, day: DailyForecast, s: Season, ctx: Context) {
        val cold = f(p.feelsMinC ?: p.tempMinC)
        if (cold != null && cold < 15) c.lose((15 - cold) * 1.0, Tip(Kind.COLD, p.feelsMinC ?: p.tempMinC))
        val warm = f(p.tempMaxC)
        if (warm != null && warm > 65) {
            c.lose((warm - 65) * 1.5, Tip(Kind.HOT, p.tempMaxC))
            c.advise(Tip(Kind.MEAT_CARE))
        }
        val wind = mph(p.windMeanKph ?: p.windMaxKph)
        if (wind != null && wind > 12) c.lose((wind - 12) * 2.0, Tip(Kind.SCENT_WIND, p.windMeanKph ?: p.windMaxKph))
        if (wind != null && wind < 6) c.advise(Tip(Kind.QUIET_WIND))
        val chance = p.precipChanceMax ?: 0
        if ((p.snowMm ?: 0.0) >= 10 || (s.snowDepthIn in 0.5..6.0 && (day.snowMm ?: 0.0) > 0)) {
            c.advise(Tip(Kind.TRACKING_SNOW, day.snowMm))
        } else {
            c.lose(chance * 0.3 + min(25.0, (p.precipMm ?: 0.0) / 25.4 * 25), if (chance >= 50) Tip(Kind.RAIN, chance.toDouble()) else null)
        }
        thunder(c, p, weight = 0.8, earlyStart = false)
        c.advise(Tip(Kind.BLAZE_ORANGE))
        Solar.sunTimes(ctx.latitude, ctx.longitude, day.date, ctx.zone).first?.let { rise ->
            c.advise(Tip(Kind.LEGAL_HOURS, rise.atZone(ctx.zone).toLocalTime().toSecondOfDay().toDouble()))
        }
    }

    // --- Shared rules ---

    private fun heat(c: Scorecard, hotF: Double?, comfortableTo: Double, perDegree: Double) {
        if (hotF == null || hotF <= comfortableTo) return
        c.lose((hotF - comfortableTo) * perDegree, Tip(Kind.HOT, Units.fToC(hotF)))
        if (hotF >= 80) c.advise(Tip(Kind.HYDRATE))
    }

    private fun chill(c: Scorecard, coldF: Double?, comfortableFrom: Double, perDegree: Double, p: PeriodStats) {
        if (coldF == null) return
        if (coldF < comfortableFrom) {
            val extra = if (coldF < 10) (10 - coldF) * 1.5 else 0.0
            c.lose((comfortableFrom - coldF) * perDegree + extra, Tip(if (coldF < 10) Kind.VERY_COLD else Kind.COLD, Units.fToC(coldF)))
        }
        if (coldF < 45) c.advise(Tip(Kind.LAYERS, p.feelsMinC ?: p.tempMinC))
    }

    private fun muggy(c: Scorecard, p: PeriodStats) {
        val dew = f(p.dewpointMaxC) ?: return
        if (dew > 62) c.lose((dew - 62) * 2.0, Tip(Kind.HUMID, p.dewpointMaxC))
    }

    private fun wet(c: Scorecard, p: PeriodStats, chanceWeight: Double, amountWeight: Double) {
        val chance = p.precipChanceMax ?: 0
        val rainIn = (p.precipMm ?: 0.0) / 25.4
        val points = chance * chanceWeight + min(30.0, rainIn * amountWeight)
        val snowy = (p.snowMm ?: 0.0) >= 5 && (p.snowMm ?: 0.0) >= (p.precipMm ?: 0.0)
        c.lose(points, if (chance >= 30 && !snowy) Tip(Kind.RAIN, chance.toDouble()) else null)
    }

    private fun thunder(c: Scorecard, p: PeriodStats, weight: Double, earlyStart: Boolean) {
        val t = p.thunderChanceMax ?: return
        if (t < 10) return
        c.lose(t * weight, Tip(Kind.THUNDER, t.toDouble()))
        if (earlyStart && t >= 20) c.advise(Tip(Kind.EARLY_START))
    }

    private fun snowAndIce(c: Scorecard, p: PeriodStats) {
        val snowIn = (p.snowMm ?: 0.0) / 25.4
        if (snowIn >= 0.5) c.lose(10 + snowIn * 4, Tip(Kind.SNOW, p.snowMm))
        if ((p.iceMm ?: 0.0) > 0.2) c.lose(35.0, Tip(Kind.ICE, p.iceMm))
    }

    private fun winterTrail(c: Scorecard, p: PeriodStats, mud: MudOutlook?, coldF: Double?) {
        snowAndIce(c, p)
        val snowCovered = mud?.level == MudLevel.SNOW_COVERED
        if (snowCovered) {
            c.lose(5.0)
            c.advise(Tip(Kind.SNOWSHOES))
        }
        val icy = (p.iceMm ?: 0.0) > 0 || snowCovered || mud?.level == MudLevel.FROZEN
        if (icy && (coldF ?: 40.0) < 40) c.advise(Tip(Kind.MICROSPIKES))
    }

    private fun daylight(c: Scorecard, date: LocalDate, ctx: Context) {
        val (rise, set) = Solar.sunTimes(ctx.latitude, ctx.longitude, date, ctx.zone)
        if (rise == null || set == null) return
        val hours = Duration.between(rise, set).toMinutes() / 60.0
        if (hours < 10.5) c.advise(Tip(Kind.SHORT_DAYLIGHT, hours))
    }

    /** New England seasonal hazards: hunting season, black flies, ticks. */
    private fun seasonal(c: Scorecard, date: LocalDate, warmestF: Double?, latitude: Double, bugs: Boolean) {
        val md = MonthDay.from(date)
        if (md >= MonthDay.of(10, 20) && md <= MonthDay.of(12, 15)) c.advise(Tip(Kind.BLAZE_ORANGE))
        if (!bugs) return
        val warm = (warmestF ?: 0.0) >= 50
        if (latitude >= 41.5 && warm && md >= MonthDay.of(5, 10) && md <= MonthDay.of(6, 25)) c.advise(Tip(Kind.BLACK_FLIES))
        if ((warmestF ?: 0.0) >= 45 && date.monthValue in 4..11) c.advise(Tip(Kind.TICKS))
    }

    private fun overlaps(alert: WeatherAlert, window: Pair<Instant, Instant>): Boolean {
        val start = alert.onset ?: alert.effective ?: alert.sent ?: return true
        val end = alert.ends ?: alert.expires ?: return start.isBefore(window.second)
        return end.isAfter(window.first) && start.isBefore(window.second)
    }

    private fun f(c: Double?): Double? = c?.let(Units::cToF)
    private fun mph(kph: Double?): Double? = kph?.let(Units::kphToMph)

    /** Fraction of the moon's disc lit, 0 (new) to 1 (full). Accurate to a few percent. */
    fun moonIllumination(at: Instant): Double {
        val referenceNewMoon = Instant.parse("2000-01-06T18:14:00Z")
        val days = Duration.between(referenceNewMoon, at).toMinutes() / 1440.0
        val phase = ((days / 29.530588853) % 1.0 + 1.0) % 1.0
        return (1 - cos(2 * PI * phase)) / 2
    }
}

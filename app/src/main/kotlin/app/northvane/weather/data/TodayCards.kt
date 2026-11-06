package app.northvane.weather.data

/**
 * The movable cards on the Today screen, in their default order. The top card
 * (temperature and headline), alerts and the at-a-glance row stay on top.
 */
enum class TodayCard(val label: String, val hideable: Boolean) {
    STORM("Storm totals", true),
    SMOKE("Wildfire smoke", true),
    HOURLY("Hourly", false),
    DAILY("7-day forecast", false),
    AURORA("Aurora", true),
    AIR("Air quality", true),
    UV("UV index", true),
    POLLEN("Pollen", true),
    FORECASTERS("Forecasters' notes", true),
    ASK("Ask about the forecast", true),
    ;

    companion object {
        val DEFAULT: List<TodayCard> = entries.toList()

        /** A saved order, with cards added in later versions slotted in at their default place. */
        fun parse(saved: String?): List<TodayCard> {
            val known = saved?.split(',')?.mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }?.distinct().orEmpty()
            if (known.isEmpty()) return DEFAULT
            val result = known.toMutableList()
            DEFAULT.forEachIndexed { i, card ->
                if (card !in result) {
                    val before = DEFAULT.take(i).lastOrNull { it in result }
                    result.add(before?.let { result.indexOf(it) + 1 } ?: 0, card)
                }
            }
            return result
        }

        fun format(order: List<TodayCard>): String = order.joinToString(",") { it.name }

        /**
         * Moves [card] one place up ([by] = -1) or down (+1) among the [visible]
         * cards; hidden cards keep their places.
         */
        fun move(order: List<TodayCard>, card: TodayCard, by: Int, visible: Collection<TodayCard>): List<TodayCard> {
            val list = order.toMutableList()
            val from = list.indexOf(card)
            if (from < 0) return order
            var to = from + by
            while (to in list.indices && list[to] !in visible) to += by
            if (to !in list.indices) return order
            list[from] = list[to].also { list[to] = list[from] }
            return list
        }
    }
}

/** Whether the feature behind [card] is switched on (hourly and daily always are). */
fun AppSettings.isOn(card: TodayCard): Boolean = when (card) {
    TodayCard.STORM -> stormEnabled
    TodayCard.SMOKE -> smokeEnabled
    TodayCard.HOURLY, TodayCard.DAILY -> true
    TodayCard.AURORA -> auroraEnabled
    TodayCard.AIR -> airQualityEnabled
    TodayCard.UV -> uvEnabled
    TodayCard.POLLEN -> pollenEnabled
    TodayCard.FORECASTERS -> aiEnabled && aiDiscussion
    TodayCard.ASK -> aiEnabled && aiAsk
}

/** What a new user can say they care about on the welcome screen; each switches on its cards. */
enum class Interest(val label: String) {
    OUTDOORS("Hiking & outdoors"),
    WATER("Rivers & tides"),
    GARDEN("Garden"),
    HEATING("Home heating"),
    DRYING("Line-drying laundry"),
    ALLERGIES("Pollen & allergies"),
    AURORA("Northern lights"),
    ;

    fun isOn(s: AppSettings): Boolean = when (this) {
        OUTDOORS -> s.activitiesEnabled
        WATER -> s.waterEnabled
        GARDEN -> s.gardenEnabled
        HEATING -> s.heatingEnabled
        DRYING -> s.dryingEnabled
        ALLERGIES -> s.pollenEnabled && s.allergyEnabled
        AURORA -> s.auroraEnabled
    }
}

suspend fun SettingsRepository.setInterest(interest: Interest, on: Boolean) {
    when (interest) {
        Interest.OUTDOORS -> setActivitiesEnabled(on)
        Interest.WATER -> setWater(on)
        Interest.GARDEN -> setGarden(on)
        Interest.HEATING -> setHeating(on)
        Interest.DRYING -> setDrying(on)
        Interest.ALLERGIES -> { setPollen(on); setAllergy(on) }
        Interest.AURORA -> setAurora(on)
    }
}

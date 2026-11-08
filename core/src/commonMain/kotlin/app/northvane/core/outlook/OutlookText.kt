package app.northvane.core.outlook

import app.northvane.core.model.FoliageReport
import app.northvane.core.model.MudReason
import app.northvane.core.model.Tip
import app.northvane.core.model.Tip.Kind
import app.northvane.core.model.UnitSystem
import app.northvane.core.util.UnitFormatter
import kotlin.math.roundToInt
import app.northvane.core.time.*
import app.northvane.core.util.fmt

/** User-facing wording for outlook tips and reasons, in the user's units. */
object OutlookText {

    fun tip(t: Tip, f: UnitFormatter): String {
        val v = t.value
        val temp = { f.temperatureWithUnit(v) }
        val pct = { "${v?.roundToInt() ?: "--"}%" }
        val imperial = f.system == UnitSystem.IMPERIAL
        return when (t.kind) {
            Kind.IDEAL -> "Close to ideal conditions"
            Kind.WARNING -> "A weather warning is in effect: postpone if you can"
            Kind.HOT -> "Hot: feels like ${temp()}"
            Kind.HUMID -> "Muggy: dew point ${temp()}"
            Kind.COLD -> "Cold: feels like ${temp()}"
            Kind.VERY_COLD -> "Bitter cold: feels like ${temp()}. Frostbite risk on exposed skin"
            Kind.RAIN -> "${pct()} chance of rain"
            Kind.SNOW -> if (v != null && v > 0) "Snow: about ${f.snow(v)}" else "Snow on the ground"
            Kind.ICE -> "Freezing rain: icy surfaces"
            Kind.THUNDER -> "${pct()} chance of thunderstorms"
            Kind.WINDY -> "Gusts to ${f.windSpeed(v)}"
            Kind.CLOUDY -> if (v != null) "Cloud cover around ${pct()}" else "Mostly cloudy: limited views"
            Kind.MUDDY -> "Trails will be muddy"
            Kind.MUD_SEASON -> "Mud season: skip high-elevation trails until they dry, and walk through mud, not around it"
            Kind.ROUGH_WATER -> "Choppy water: wind around ${f.windSpeed(v)}"
            Kind.COLD_WATER -> v?.let { "Water about ${f.temperature(it)}: wear your PFD and dress for immersion, not the air temperature" }
                ?: "Cold water: wear your PFD and dress for immersion, not the air temperature"
            Kind.RIVER_RISING -> "The nearest river gauge is rising fast: expect stronger current and debris"
            Kind.BRIGHT_MOON -> "Bright moon (${pct()} lit) washes out faint stars"
            Kind.SLEEPING_BAG_0F -> "Low near ${temp()}: bring a 0°F (-18°C) bag, an insulated pad (R5+) and keep your water filter and batteries in your bag"
            Kind.SLEEPING_BAG_20F -> "Low near ${temp()}: bring a 20°F (-7°C) bag and an insulated pad (R4+). Don't let your water filter freeze"
            Kind.SLEEPING_BAG_30F -> "Low near ${temp()}: a 30°F (-1°C) bag and a warm layer for the evening"
            Kind.SLEEPING_BAG_SUMMER -> "Low near ${temp()}: a light bag or quilt will do"
            Kind.HOT_NIGHT -> "Warm night (low ${temp()}): pick a breezy site and open the tent vents"
            Kind.RAINFLY -> "Pitch the rainfly, pick high ground, and pack a tarp for camp"
            Kind.GUYLINES -> "Stake out guylines and pick a site sheltered from the wind"
            Kind.CONDENSATION -> "Damp, calm air: expect heavy dew and tent condensation, so ventilate"
            Kind.FROST -> "Frost likely by morning"
            Kind.SUMMIT_WIND -> "Expect much stronger wind and colder air above treeline"
            Kind.EARLY_START -> "Start early and be off exposed ridges and summits by early afternoon"
            Kind.MICROSPIKES -> "Icy spots likely: carry microspikes"
            Kind.SNOWSHOES -> "Snow on the trails: bring snowshoes or traction"
            Kind.LAYERS -> "Dress in layers: hat, gloves and an insulating jacket"
            Kind.HYDRATE -> "Carry extra water and take breaks in the shade"
            Kind.SUNSCREEN -> "Strong sun: sunscreen and a hat"
            Kind.RECOVERY_GEAR -> "Bring recovery gear (strap, shovel, traction boards) and ride with a buddy"
            Kind.AVOID_RUTS -> "Soft trails rut easily: stay off them to avoid damage and closures"
            Kind.TRAIL_CLOSURES -> "Many ATV and bike trails close during mud season. Check before you go"
            Kind.LET_TRAILS_DRY -> "Give trails a day or two to dry after rain: riding wet trails damages them"
            Kind.DUSTY -> "Dry and dusty: keep your distance from the vehicle ahead"
            Kind.BLAZE_ORANGE -> "Hunting season: wear blaze orange"
            Kind.BLACK_FLIES -> "Black fly season: bring a head net and repellent"
            Kind.TICKS -> "Ticks are active: treat clothing, and check yourself afterwards"
            Kind.MOSQUITOES -> "Warm, humid night: mosquitoes, so bring a bug net"
            Kind.SHORT_DAYLIGHT -> "Only ${"%.1f".fmt(v ?: 0.0)} h of daylight: start early and carry a headlamp"
            Kind.DARK_SKIES -> "Dark, moonless skies: great for the Milky Way"
            Kind.MOONLIT -> "Moon ${pct()} lit: fine for bright objects and planets"
            Kind.OFFSHORE_WIND -> "Stay near shore and watch for offshore wind on bigger lakes and the coast"
            Kind.DRY_BAG -> "Pack a dry bag and rain shell"
            Kind.ICE_ON_WATER -> "Too cold for too long: lakes may be iced over and rivers can carry dangerous ice. Stay off the water"
            Kind.SHORE_ICE -> "Skim ice likely in coves and along shores"
            Kind.ALLERGY_WINDOWS -> "Keep windows closed and run AC or a HEPA filter"
            Kind.ALLERGY_SHOWER -> "Shower and change clothes after time outside"
            Kind.ALLERGY_MEDICATE -> "Take allergy medicine before symptoms start"
            Kind.ALLERGY_TIMING -> "Pollen peaks late morning to afternoon: head out early or after rain"
            Kind.ALLERGY_RAIN_HELPS -> "Steady rain will wash pollen out of the air"
            Kind.ALLERGY_WIND -> "Dry, gusty wind (to ${f.windSpeed(v)}) will spread pollen"
            Kind.ALLERGY_MASK_YARDWORK -> "Damp leaf litter: wear a mask for raking and yard work"
            Kind.ALLERGY_THUNDERSTORM -> "Thunderstorms with high pollen can trigger asthma: keep your inhaler handy"
            Kind.ALLERGY_AIR -> "Air quality is unhealthy for sensitive groups (AQI ${v?.roundToInt()}): limit hard outdoor exertion"
            Kind.ALLERGY_QUIET -> "A quiet day for your allergies"
            Kind.WINTER_GEAR -> "Winter kit: insulated layers, spare gloves and hat, headlamp, a thermos, and tell someone your plan"
            Kind.POWDER_DAY -> "Powder day: about ${f.snow(v)} of new snow"
            Kind.ICY_SURFACES -> "Freezing rain or refreeze: firm, icy surfaces. Sharp edges and traction help"
            Kind.LIFT_HOLDS -> "Gusts to ${f.windSpeed(v)}: upper lifts may go on wind hold"
            Kind.THIN_COVER -> "Natural snow is thin (about ${f.snow(v)} here): expect limited terrain except where there's snowmaking"
            Kind.CHECK_SNOW_REPORT -> "Check the mountain's snow report for base depth and open trails"
            Kind.SPRING_SNOW -> "Warm temperatures: soft, heavy snow by afternoon"
            Kind.RAIN_ON_SNOW -> "Rain on the snowpack: slushy now, icy when it refreezes"
            Kind.KICK_WAX -> kickWax(v, f)
            Kind.BREAK_TRAIL -> "Fresh snow (about ${f.snow(v)}): expect to break trail, which is slow going"
            Kind.TRAIL_REPORT -> "Check local club and trail-association reports for grooming and closures"
            Kind.ICE_CHECK -> "Estimated lake ice about ${v?.roundToInt() ?: "?"}\". Always check thickness yourself: 4\" of clear ice to walk, 5–7\" for a snowmobile"
            Kind.ICE_WEAKENING -> "Thaw or rain weakens ice, especially near inlets, pressure ridges and shorelines"
            Kind.HAND_WARMERS -> "Pack hand and toe warmers and cover exposed skin"
            Kind.COLD_WATER_SWIM -> "Northern New England water stays cold (60s °F on the coast): ease in and watch for cold shock"
            Kind.LIGHTNING_SWIM -> "Get out of the water at the first thunder and wait 30 minutes after the last"
            Kind.TRACKING_SNOW -> "Fresh tracking snow"
            Kind.LEGAL_HOURS -> "Sunrise ${v?.let { app.northvane.core.time.LocalTime.ofSecondOfDay(it.toLong()).withSecond(0) } ?: "--"}: check your state's legal hours and season dates"
            Kind.MEAT_CARE -> "Warm day: field dress quickly and cool game fast"
            Kind.QUIET_WIND -> "Calm wind: sounds and scent carry, so move slowly and mind your wind"
            Kind.SCENT_WIND -> "Wind around ${f.windSpeed(v)}: game stays bedded in cover; hunt the lee side"
        }.let { if (imperial) it else metricize(it) }
    }

    private fun kickWax(tempC: Double?, f: UnitFormatter): String {
        val t = tempC ?: return "Pick kick wax for the snow temperature"
        val wax = when {
            t <= -10 -> "green (very cold)"
            t <= -4 -> "blue (cold, dry snow)"
            t <= 0 -> "purple/violet (around freezing)"
            t <= 2 -> "red (wet, new snow)"
            else -> "klister (wet or icy old snow), or skins"
        }
        return "Kick wax: $wax for a high near ${f.temperatureWithUnit(t)}"
    }

    /** Drop the Fahrenheit-first gear ratings for metric users. */
    private fun metricize(s: String) = s
        .replace("0°F (-18°C)", "-18°C")
        .replace("20°F (-7°C)", "-7°C")
        .replace("30°F (-1°C)", "-1°C")

    fun mudReason(r: MudReason, f: UnitFormatter): String = when (r.kind) {
        MudReason.Kind.RECENT_RAIN -> "${f.precipitation(r.value)} of rain in the last few days"
        MudReason.Kind.FORECAST_RAIN -> "${f.precipitation(r.value)} of rain expected"
        MudReason.Kind.THAW -> "Spring thaw: the ground is thawing from the top while still frozen below, so water can't drain"
        MudReason.Kind.SNOWMELT -> "Melting snow"
        MudReason.Kind.FROZEN -> "Ground is frozen"
        MudReason.Kind.DRYING -> "Dry weather is firming trails up"
        MudReason.Kind.SNOW_COVER -> "Snow on the ground (about ${f.snow(r.value)})"
        MudReason.Kind.NO_HISTORY -> "Estimated without recent rainfall data"
    }

    fun foliageNote(n: FoliageReport.Note): String = when (n) {
        FoliageReport.Note.COOL_NIGHTS -> "Cool nights this fall are bringing color early"
        FoliageReport.Note.WARM_FALL -> "A warm fall is delaying color"
        FoliageReport.Note.HARD_FREEZE -> "A hard freeze hurried leaves along"
        FoliageReport.Note.DRY -> "Dry weather may mute colors and drop leaves early"
        FoliageReport.Note.HIGH_ELEVATION -> "Higher elevations color and drop earlier than the valleys"
        FoliageReport.Note.NO_HISTORY -> "Based on typical timing only (recent temperatures unavailable)"
    }

    /** Pollen index (Universal Pollen Index 0–5) as words. */
    fun pollenCategory(index: Int?): String = when (index) {
        null -> "Out of season"
        0 -> "None"
        1 -> "Very low"
        2 -> "Low"
        3 -> "Moderate"
        4 -> "High"
        else -> "Very high"
    }

    /**
     * What to do before a likely power outage, shaped by what's driving it
     * and how the household heats (from the heating settings, if any).
     */
    fun outagePrep(risk: app.northvane.core.model.OutageRisk, heating: List<HeatType>): List<String> {
        val kinds = risk.factors.map { it.kind }.toSet()
        val winter = app.northvane.core.model.OutageFactor.Kind.ICE in kinds || app.northvane.core.model.OutageFactor.Kind.WET_SNOW in kinds ||
            app.northvane.core.model.OutageFactor.Kind.SNOW in kinds || app.northvane.core.model.OutageFactor.Kind.FROZEN_GROUND in kinds
        return buildList {
            add("Charge phones, power banks and laptops; find flashlights and fresh batteries (skip candles)")
            add("Fill the car's gas tank and get some cash, since pumps and card readers need power")
            add("Keep fridge and freezer closed during an outage: food stays safe about 4 hours in the fridge, 24–48 in a full freezer")
            add("Store drinking water (a gallon per person per day); if you're on a well, fill tubs for flushing")
            add("Refill prescriptions; plan for any medical devices that need power")
            if (heating.isNotEmpty()) {
                val powered = heating.filter { it.needsPower }.map { it.label.substringBefore(" (").lowercase() }
                val independent = heating.filterNot { it.needsPower }.map { it.label.substringBefore(" (").lowercase() }
                if (powered.isNotEmpty()) add("Your ${powered.joinToString(" and ")} won't run without power" + if (independent.isNotEmpty()) "; have the ${independent.joinToString(" and ")} ready with fuel inside" else "; plan a warm place to go")
            } else if (winter) {
                add("Most furnaces and boilers need electricity: plan a safe backup heat source or a warm place to go")
            }
            if (winter) add("Protect pipes: if heat is lost, open cabinet doors and let faucets drip")
            add("Generators only outdoors, at least 20 feet from doors and windows, and test your CO alarms")
            add("Stay at least 30 feet from downed lines and report them to your utility (CMP, Versant, Eversource, NHEC, Unitil or Liberty in ME/NH)")
        }
    }

    /** Steps to keep pipes from freezing, most useful first; more as the risk rises. */
    fun pipePrep(risk: app.northvane.core.model.PipeRisk, heating: List<HeatType>, f: UnitFormatter): List<String> {
        val kinds = risk.factors.map { it.kind }.toSet()
        val high = risk.level >= app.northvane.core.model.OutageLevel.HIGH
        return buildList {
            add("Disconnect garden hoses and shut off and drain outdoor faucets")
            add("Open cabinet doors under sinks on outside walls so warm air reaches the pipes")
            if (high) add("Let faucets on outside walls drip slowly overnight, hot and cold")
            add("Keep the heat at ${f.temperatureWithUnit(12.8)} or warmer day and night, even if you're away")
            if (high) add("Check unheated spaces: crawl spaces, garages, basements, and well or pump lines. Plug in heat tape where you have it")
            if (app.northvane.core.model.PipeFactor.Kind.WIND in kinds) add("Close garage doors and foundation vents; wind drives cold into walls and crawl spaces")
            add("Know where your main water shutoff is")
            if (app.northvane.core.model.PipeFactor.Kind.OUTAGE in kinds) {
                val backup = heating.filterNot { it.needsPower }.map { it.label.substringBefore(" (").lowercase() }
                if (backup.isNotEmpty()) add("If the power goes out, keep the ${backup.joinToString(" and ")} going to protect the pipes")
                add("If the power is out for long and the house drops below ${f.temperatureWithUnit(4.4)}, shut off the main and drain the lines")
            }
            if (risk.level == app.northvane.core.model.OutageLevel.VERY_HIGH) {
                add("If a pipe freezes, open the faucet and warm the pipe with a hair dryer or space heater, never an open flame")
            }
        }
    }

    /** Tips for a frosty or icy morning. */
    fun iceTips(ice: app.northvane.core.model.MorningIce): List<String> = buildList {
        if (ice.blackIce != null) {
            add("Bridges, overpasses, shaded curves and the end of driveways ice over first")
            add("Drive slowly, leave extra room, and brake and steer gently")
            add("Put salt or sand on steps and walkways before you head out")
        }
        if (ice.frost != null) {
            add("Leave 10 minutes to clear the windshield, or cover it overnight")
            if (ice.blackIce == null) add("Frost can make bridges and shaded spots slick early in the morning")
        }
    }

    fun heatingTask(t: HeatingTask, f: UnitFormatter): String {
        val fuel = t.source?.fuel ?: "fuel"
        return when (t.kind) {
            HeatingTask.Kind.SERVICE_SYSTEM -> "Book your annual heating service before the fall rush"
            HeatingTask.Kind.CHIMNEY_SWEEP -> "Have the chimney or flue inspected and swept before the first fire"
            HeatingTask.Kind.CO_DETECTORS -> "Test smoke and carbon monoxide alarms and replace batteries"
            HeatingTask.Kind.FILL_TANK_PREBUY -> "Fill the $fuel tank or lock in a pre-buy price now. Late summer is usually cheaper than mid-winter"
            HeatingTask.Kind.CHECK_TANK_COLD_SNAP -> "Cold snap ahead (low near ${f.temperatureWithUnit(t.value)}): check your $fuel level and order if under half a tank, before deliveries back up"
            HeatingTask.Kind.BUY_SEASONED_WOOD -> "Buy seasoned firewood now if you need it (under 20% moisture). Green wood bought in fall won't burn well this winter"
            HeatingTask.Kind.SPLIT_STACK_NEXT_YEAR -> "Split and stack next winter's wood now: off the ground, top covered, sides open. It needs 6–12 months to season"
            HeatingTask.Kind.MOVE_WOOD_IN -> "Bring a few days of firewood under cover near the stove before the cold"
            HeatingTask.Kind.BUY_PELLETS -> "Buy pellets for the season while supply is good and prices are lower"
            HeatingTask.Kind.HEAT_PUMP_FILTERS -> "Clean the heat pump filters and clear plants and debris from the outdoor unit"
            HeatingTask.Kind.CLEAR_OUTDOOR_UNIT -> "Snow coming: keep the heat pump's outdoor unit clear of snow and ice"
            HeatingTask.Kind.BELOW_HEAT_PUMP_RANGE -> "Low near ${f.temperatureWithUnit(t.value)}: below the range you set for the heat pump, so switch to your backup"
            HeatingTask.Kind.PROTECT_PIPES -> "Near or below 0°F (${f.temperatureWithUnit(t.value)}): protect pipes on outside walls by opening cabinets and letting faucets drip"
            HeatingTask.Kind.END_OF_SEASON -> "End of heating season: clean the stove, schedule service, and plan next winter's fuel"
            HeatingTask.Kind.MONITOR_FUEL -> "Mid-winter check: read your $fuel gauge and keep at least a quarter tank"
            HeatingTask.Kind.NO_SOURCES -> "Add how you heat in Settings › Heating to get advice for your system"
        }
    }
}

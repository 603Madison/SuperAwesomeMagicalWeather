package app.northvane.core.garden

import kotlinx.serialization.Serializable
import app.northvane.core.time.*

@Serializable
enum class FrostTolerance(val label: String, /** Lowest night temperature (°F) safe for young plants. */ val safeLowF: Double) {
    HARDY("Frost-hardy", 26.0),
    HALF_HARDY("Tolerates light frost", 31.0),
    TENDER("Frost-tender", 38.0),
}

@Serializable
enum class CropGroup(val label: String) { VEGETABLE("Vegetables"), HERB("Herbs"), FRUIT("Fruit"), FLOWER("Flowers") }

/**
 * Common garden crops with timing relative to the average last spring frost
 * (weeks; negative = before), following typical Northeast extension-service
 * guidance (UNH / UMaine Cooperative Extension calendars).
 *
 * - [startIndoors]: weeks before last frost to sow seeds indoors (null = not started indoors).
 * - [transplant] / [directSow]: earliest week to set out transplants or sow outdoors.
 * - [minSoilF]: minimum soil temperature for planting or germination.
 * - [daysToHarvest]: from transplanting (or sowing, for direct-sown crops).
 */
@Serializable
enum class Crop(
    val label: String,
    val group: CropGroup,
    val tolerance: FrostTolerance,
    val startIndoors: IntRange?,
    val transplant: Int?,
    val directSow: Int?,
    val minSoilF: Int,
    val daysToHarvest: IntRange,
    /** How many weeks the harvest typically lasts once it starts. */
    val harvestWeeks: Int,
    /** Planted in fall for harvest next summer (garlic). */
    val fallPlanted: Boolean = false,
    /** Flavor improves after light frost (kale, Brussels sprouts, parsnips). */
    val frostSweetened: Boolean = false,
) {
    TOMATO("Tomatoes", CropGroup.VEGETABLE, FrostTolerance.TENDER, 6..8, 1, null, 60, 60..85, 8),
    PEPPER("Peppers", CropGroup.VEGETABLE, FrostTolerance.TENDER, 8..10, 2, null, 65, 65..90, 8),
    EGGPLANT("Eggplant", CropGroup.VEGETABLE, FrostTolerance.TENDER, 8..10, 2, null, 65, 70..85, 6),
    CUCUMBER("Cucumbers", CropGroup.VEGETABLE, FrostTolerance.TENDER, 3..4, 2, 1, 65, 50..65, 6),
    ZUCCHINI("Zucchini / summer squash", CropGroup.VEGETABLE, FrostTolerance.TENDER, 3..4, 1, 1, 65, 45..60, 8),
    WINTER_SQUASH("Winter squash / pumpkins", CropGroup.VEGETABLE, FrostTolerance.TENDER, 3..4, 2, 1, 65, 85..110, 3),
    MELON("Melons", CropGroup.FRUIT, FrostTolerance.TENDER, 3..4, 2, null, 70, 75..95, 3),
    BEANS("Beans (bush/pole)", CropGroup.VEGETABLE, FrostTolerance.TENDER, null, null, 1, 60, 50..65, 5),
    CORN("Sweet corn", CropGroup.VEGETABLE, FrostTolerance.TENDER, null, null, 1, 60, 70..90, 2),
    PEAS("Peas", CropGroup.VEGETABLE, FrostTolerance.HARDY, null, null, -6, 40, 55..70, 4),
    LETTUCE("Lettuce / salad greens", CropGroup.VEGETABLE, FrostTolerance.HALF_HARDY, 4..6, -3, -4, 40, 40..60, 5),
    SPINACH("Spinach", CropGroup.VEGETABLE, FrostTolerance.HARDY, null, null, -6, 40, 40..50, 4),
    KALE("Kale", CropGroup.VEGETABLE, FrostTolerance.HARDY, 4..6, -3, -4, 45, 55..70, 16, frostSweetened = true),
    BROCCOLI("Broccoli", CropGroup.VEGETABLE, FrostTolerance.HALF_HARDY, 6..8, -2, null, 45, 55..75, 4),
    CABBAGE("Cabbage", CropGroup.VEGETABLE, FrostTolerance.HARDY, 6..8, -3, null, 45, 65..95, 4),
    BRUSSELS("Brussels sprouts", CropGroup.VEGETABLE, FrostTolerance.HARDY, 6..8, -2, null, 45, 90..110, 8, frostSweetened = true),
    CARROTS("Carrots", CropGroup.VEGETABLE, FrostTolerance.HALF_HARDY, null, null, -3, 45, 65..80, 8),
    BEETS("Beets", CropGroup.VEGETABLE, FrostTolerance.HALF_HARDY, null, null, -3, 50, 50..65, 6),
    RADISH("Radishes", CropGroup.VEGETABLE, FrostTolerance.HARDY, null, null, -5, 40, 25..35, 2),
    ONION("Onions", CropGroup.VEGETABLE, FrostTolerance.HARDY, 10..12, -4, null, 40, 90..120, 3),
    POTATO("Potatoes", CropGroup.VEGETABLE, FrostTolerance.HALF_HARDY, null, null, -3, 45, 70..110, 4),
    CHARD("Swiss chard", CropGroup.VEGETABLE, FrostTolerance.HALF_HARDY, null, null, -2, 50, 50..60, 14),
    GARLIC("Garlic", CropGroup.VEGETABLE, FrostTolerance.HARDY, null, null, null, 40, 240..280, 2, fallPlanted = true),
    STRAWBERRY("Strawberries", CropGroup.FRUIT, FrostTolerance.HALF_HARDY, null, -3, null, 45, 330..400, 4),
    BASIL("Basil", CropGroup.HERB, FrostTolerance.TENDER, 6..8, 2, null, 60, 50..65, 10),
    PARSLEY("Parsley", CropGroup.HERB, FrostTolerance.HARDY, 8..10, -2, null, 45, 70..90, 14),
    CILANTRO("Cilantro / dill", CropGroup.HERB, FrostTolerance.HALF_HARDY, null, null, -1, 50, 45..60, 4),
    SUNFLOWER("Sunflowers", CropGroup.FLOWER, FrostTolerance.TENDER, null, null, 1, 55, 70..95, 4),
    ZINNIA("Zinnias / marigolds", CropGroup.FLOWER, FrostTolerance.TENDER, 4..6, 1, 1, 60, 60..75, 10),
    ;

    val startsIndoors: Boolean get() = startIndoors != null
}

/** How the user intends to get a crop going. */
@Serializable
enum class PlantingMethod(val label: String) {
    AUTO("Usual way"),
    SEED_INDOORS("Start seeds indoors"),
    BUY_TRANSPLANTS("Buy transplants"),
    DIRECT_SOW("Sow outdoors"),
}

/** One line in the user's garden plan. */
@Serializable
data class GardenCrop(
    val crop: Crop,
    val method: PlantingMethod = PlantingMethod.AUTO,
    /** Set when the user marks it planted outdoors; harvest timing then counts from here. */
    @Serializable(app.northvane.core.json.LocalDateSerializer::class) val plantedOn: app.northvane.core.time.LocalDate? = null,
) {
    /** Effective method: AUTO picks indoor starting for crops that are usually started indoors. */
    val resolvedMethod: PlantingMethod
        get() = when {
            method != PlantingMethod.AUTO -> method
            crop.fallPlanted -> PlantingMethod.DIRECT_SOW
            crop.directSow != null && (crop.startIndoors == null || crop.directSow >= (crop.transplant ?: Int.MAX_VALUE) || crop in DIRECT_FIRST) ->
                PlantingMethod.DIRECT_SOW
            crop.startIndoors != null -> PlantingMethod.SEED_INDOORS
            else -> PlantingMethod.BUY_TRANSPLANTS
        }
}

/** Usually sown in place even though they can be started indoors. */
private val DIRECT_FIRST = setOf(Crop.CUCUMBER, Crop.ZUCCHINI, Crop.WINTER_SQUASH, Crop.LETTUCE, Crop.SUNFLOWER)

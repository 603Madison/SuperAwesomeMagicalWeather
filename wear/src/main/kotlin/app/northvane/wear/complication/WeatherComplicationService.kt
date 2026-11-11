package app.northvane.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.northvane.wear.MainActivity
import app.northvane.wear.R
import app.northvane.wear.glance.ComplicationTexts
import app.northvane.wear.tile.WeatherTileService

/** The tile place's temperature (SHORT_TEXT) and a line of weather (LONG_TEXT) for watch faces, from the app's cache. */
class WeatherComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, ComplicationTexts("52°", null, "52° Partly cloudy", "Dry for the next hour", "52°, Partly cloudy"), tap = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val glance = WeatherTileService.currentGlance(this) ?: return NoDataComplicationData()
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return build(request.complicationType, ComplicationTexts.of(glance), tap)
    }

    private fun build(type: ComplicationType, t: ComplicationTexts, tap: PendingIntent?): ComplicationData? {
        fun plain(s: String) = PlainComplicationText.Builder(s).build()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_complication)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(t.shortText), plain(t.description))
                .setMonochromaticImage(icon)
                .apply { t.shortTitle?.let { setTitle(plain(it)) } }
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(t.longText), plain(t.description))
                .setTitle(plain(t.longTitle))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> null
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Small custom glyphs not in the core Material icon set. */
object NorthvaneIcons {
    private fun icon(name: String, path: String, stroked: Boolean): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (stroked) {
                addPath(
                    pathData = PathParser().parsePathString(path).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            }
        }.build()

    val Radar: ImageVector by lazy {
        icon(
            "radar",
            "M2.5,12 a9.5,9.5 0 1,0 19,0 a9.5,9.5 0 1,0 -19,0 " +
                "M7,12 a5,5 0 1,0 10,0 a5,5 0 1,0 -10,0 " +
                "M12,12 L18.7,5.3 M11,12 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0",
            stroked = true,
        )
    }

    /** A microphone, for asking by voice. */
    val Mic: ImageVector by lazy {
        icon("mic", "M12,3 a3,3 0 0,1 3,3 v5 a3,3 0 0,1 -6,0 v-5 a3,3 0 0,1 3,-3 z M6,11 a6,6 0 0,0 12,0 M12,17 v4 M9,21 h6", stroked = true)
    }

    /** Two bars: grab here to drag a row. */
    val DragHandle: ImageVector by lazy { icon("drag", "M5,9.5 h14 M5,14.5 h14", stroked = true) }

    /** A flower, for allergies. */
    val Pollen: ImageVector by lazy {
        icon(
            "pollen",
            "M12,9.5 a2.5,2.5 0 1,0 0.01,0 M12,9.5 a3,3 0 1,1 0,-5 a3,3 0 1,1 0,5 " +
                "M14.4,11.2 a3,3 0 1,1 3.6,-3.4 M9.6,11.2 a3,3 0 1,0 -3.6,-3.4 M12,14.5 v6 M12,18 c-2,-1 -3.5,-1 -4.5,0",
            stroked = true,
        )
    }

    val Pause: ImageVector by lazy { icon("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z", stroked = false) }

    val Satellite: ImageVector by lazy {
        val stroke = SolidColor(Color.Black)
        fun ImageVector.Builder.line(d: String) = addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = stroke,
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        ImageVector.Builder("satellite", 24.dp, 24.dp, 24f, 24f).apply {
            // Drawn level, then tilted 45° so it reads as a satellite in orbit.
            addGroup(rotate = -45f, pivotX = 12f, pivotY = 11f)
            addPath(pathData = PathParser().parsePathString("M10,9h4v4h-4z").toNodes(), fill = stroke)
            line("M2.5,8.5h5v5h-5z M5,8.5v5 M7.5,11h2.5")
            line("M16.5,8.5h5v5h-5z M19,8.5v5 M14,11h2.5")
            clearGroup()
            line("M4.5,16.5a4,4 0 0,0 4,4 M4.5,13.5a7,7 0 0,0 7,7")
        }.build()
    }
}

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LabeledSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun <T> ChoiceDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // The read-only field swallows taps; this transparent layer opens the menu instead.
        Box(
            Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun LegendSwatch(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .padding(1.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawCircle(color) }
    }
}

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Marks the window secure while this composable is shown, so screens where
 * credentials are entered don't appear in screenshots, screen recordings or the
 * recent-apps thumbnail.
 */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

package app.northvane.weather.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What the ⋮ menu on a Today card can do; null entries are left out. */
class CardMenu(
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
    val onHide: (() -> Unit)? = null,
    val onSettings: (() -> Unit)? = null,
)

/** Set by the Today screen around each card it places. */
val LocalCardMenu = compositionLocalOf<CardMenu?> { null }

/**
 * The app's one card style: the title inside the card, an optional ⋮ menu
 * (from [LocalCardMenu]), then the content. [inset] pads the content; cards
 * with edge-to-edge rows (forecast lists) pass false and pad their own rows.
 */
@Composable
fun WeatherCard(
    title: String?,
    modifier: Modifier = Modifier,
    inset: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val menu = LocalCardMenu.current
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(20.dp)
    val body: @Composable ColumnScope.() -> Unit = {
        if (title != null || menu != null || trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = if (menu != null || trailing != null) 4.dp else 16.dp, top = 6.dp),
            ) {
                Text(
                    title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() },
                )
                trailing?.invoke()
                menu?.let { CardMenuButton(it, title) }
            }
        }
        Column(if (inset) Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp) else Modifier.padding(bottom = 8.dp)) { content() }
    }
    if (onClick != null) {
        Card(onClick = onClick, shape = shape, colors = colors, modifier = modifier.fillMaxWidth()) { body() }
    } else {
        Card(shape = shape, colors = colors, modifier = modifier.fillMaxWidth()) { body() }
    }
}

@Composable
private fun CardMenuButton(menu: CardMenu, title: String?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${title ?: "this card"}", modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.onMoveUp?.let { DropdownMenuItem(text = { Text("Move up") }, onClick = { open = false; it() }) }
            menu.onMoveDown?.let { DropdownMenuItem(text = { Text("Move down") }, onClick = { open = false; it() }) }
            menu.onHide?.let { DropdownMenuItem(text = { Text("Hide this card") }, onClick = { open = false; it() }) }
            menu.onSettings?.let { DropdownMenuItem(text = { Text("Card settings") }, onClick = { open = false; it() }) }
        }
    }
}

package app.northvane.mini.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// --- Theme: compact type and strong contrast for 2.4–2.8" screens ---

val Accent = Color(0xFF3B82F6)
val Warn = Color(0xFFFF6B5E)
val Caution = Color(0xFFF2B53A)

private val MiniType = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, lineHeight = 44.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp),
)

@Composable
fun MiniTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(
            primary = Accent, background = Color(0xFF0E1420), surface = Color(0xFF0E1420),
            surfaceVariant = Color(0xFF1B2433), onSurface = Color(0xFFF1F4F9), onSurfaceVariant = Color(0xFFAAB4C3),
            primaryContainer = Color(0xFF1E3A6B), onPrimaryContainer = Color.White, error = Warn,
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1D5FD1), background = Color.White, surface = Color.White,
            surfaceVariant = Color(0xFFE9EDF3), onSurface = Color(0xFF0E1420), onSurfaceVariant = Color(0xFF465166),
            primaryContainer = Color(0xFFD3E2FB), onPrimaryContainer = Color(0xFF0E1420), error = Color(0xFFC62828),
        )
    }
    MaterialTheme(colorScheme = colors, typography = MiniType, content = content)
}

// --- Soft keys and hardware keys ---

/** A soft-key action: [label] shows in the bottom bar, [run] happens on the key or a tap on the label. */
class SoftKey(val label: String, val run: () -> Unit)

/** What the current screen does with the soft keys and other keys. */
class ScreenKeys(
    val left: SoftKey? = null,
    val center: String? = null,
    val right: String = "Back",
    /** Number keys, * and #, and anything else the screen wants; true when handled. */
    val onKey: (Int) -> Boolean = { false },
)

/** The activity routes soft keys and number keys here; the visible screen registers its keys. */
class KeyHub {
    var keys by mutableStateOf(ScreenKeys())
    var back: () -> Unit = {}
}

val LocalKeyHub = compositionLocalOf { KeyHub() }

/** Registers this screen's soft keys while it's shown. */
@Composable
fun ScreenKeysEffect(keys: ScreenKeys) {
    val hub = LocalKeyHub.current
    SideEffect { hub.keys = keys }
}

/** The number on a key, or null. */
fun digitOf(keyCode: Int): Int? = if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) keyCode - KeyEvent.KEYCODE_0 else null

// --- Focus memory: come back to the row you left ---

class FocusMemory {
    private val rows = mutableMapOf<String, Int>()
    fun get(screen: String) = rows[screen] ?: 0
    fun set(screen: String, index: Int) { rows[screen] = index }
}

val LocalFocusMemory = compositionLocalOf { FocusMemory() }

/**
 * A row you can reach with the d-pad and select with OK/Enter or a tap.
 * Always focusable (also on touch phones), with a clear highlight because
 * flip-phone screens are small and often seen in sunlight.
 */
@Composable
fun MiniRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    // Tracked directly: focus interactions aren't reported in touch mode, but the highlight must always show.
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics(mergeDescendants = true) {}
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent { e ->
                val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (select && e.type == KeyEventType.KeyUp && onClick != null) {
                    onClick()
                    true
                } else {
                    select && onClick != null
                }
            }
            .focusable()
            .pointerInput(onClick) { detectTapGestures { onClick?.invoke() } }
            .background(if (focused) colors.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) colors.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A scrolling list of [MiniRow]s that focuses the row last used on this
 * screen, so Back returns you where you were. [header] scrolls with the list
 * and comes back into view when the first row is selected.
 */
@Composable
fun MiniList(
    screen: String,
    count: Int,
    header: (@Composable () -> Unit)? = null,
    row: @Composable (index: Int, focus: FocusRequester, onFocused: () -> Unit) -> Unit,
) {
    val memory = LocalFocusMemory.current
    val start = memory.get(screen).coerceIn(0, (count - 1).coerceAtLeast(0))
    val offset = if (header != null) 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (start == 0) 0 else start + offset)
    val scope = rememberCoroutineScope()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        if (header != null) item(key = "header") { header() }
        rowItems(count) { index ->
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (index == start) runCatching { focus.requestFocus() } }
            row(index, focus) {
                memory.set(screen, index)
                if (index == 0 && header != null) scope.launch { listState.animateScrollToItem(0) }
            }
        }
    }
}

private fun LazyListScope.rowItems(count: Int, content: @Composable (Int) -> Unit) {
    items(count) { content(it) }
}

/** Long text that scrolls with ▲/▼ (and by touch). */
@Composable
fun ScrollText(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var height by remember { mutableIntStateOf(300) }
    Column(
        modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = height * 0.7f
                when (e.key) {
                    Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(step) }; true }
                    Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-step) }; true }
                    else -> false
                }
            }
            .focusable()
            .onSizeChanged { height = it.height }
            .verticalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        content = content,
    )
}

/** Screen title with the place name, in one compact line. */
@Composable
fun TopBar(title: String, subtitle: String?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = subtitle == null),
        )
        if (subtitle != null) {
            Text(
                "  ·  $subtitle",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The labels for the left soft key, OK and the right soft key, as on any flip phone. Tappable too. */
@Composable
fun SoftKeyBar(keys: ScreenKeys, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(colors.surfaceVariant).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftLabel(keys.left?.label.orEmpty(), TextAlign.Start, Modifier.weight(1f)) { keys.left?.run?.invoke() }
        SoftLabel(keys.center.orEmpty(), TextAlign.Center, Modifier.weight(1f), bold = true) {}
        SoftLabel(keys.right, TextAlign.End, Modifier.weight(1f)) { onBack() }
    }
}

@Composable
private fun SoftLabel(text: String, align: TextAlign, modifier: Modifier, bold: Boolean = false, onTap: () -> Unit) {
    Box(modifier.fillMaxSize().pointerInput(text) { detectTapGestures { onTap() } }, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = align,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Small grey text for hints and sources. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp))

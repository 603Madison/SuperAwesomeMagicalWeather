@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.alerts

import app.northvane.ui.alertColor
import app.northvane.weather.ui.theme.LocalIsDark
import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.WeatherAlert
import app.northvane.weather.notify.AlertNotifier
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.TimeFormat
import java.time.ZoneId

@Composable
fun AlertsScreen(state: MainUiState, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val snapshot = state.snapshot
    val zone = remember(snapshot?.timeZoneId) {
        snapshot?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }
    val time = remember(zone) { TimeFormat(zone, DateFormat.is24HourFormat(context)) }
    val notificationsAllowed = remember(state.settings) { AlertNotifier.canNotify(context) }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Alerts")
                        state.selected?.let {
                            Text(it.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    NotificationStatus(
                        enabled = state.settings.alertsEnabled && notificationsAllowed,
                        categories = state.settings.alertCategories,
                        onOpenSettings = onOpenSettings,
                    )
                }
                when {
                    snapshot == null -> item { InfoCard("Loading alerts…") }
                    !snapshot.alertsAvailable -> item {
                        InfoCard("Weather alerts come from the National Weather Service and are available for the US and its territories.")
                    }
                    snapshot.alerts.isEmpty() -> item { NoAlerts(snapshot.placeName ?: state.selected?.name) }
                    else -> items(snapshot.alerts, key = { it.id }) { alert -> AlertCard(alert, time) }
                }
            }
        }
    }
}

@Composable
private fun NotificationStatus(enabled: Boolean, categories: Set<AlertCategory>, onOpenSettings: () -> Unit) {
    val summary = if (enabled) {
        val names = AlertCategory.entries.filter { it in categories }.joinToString(", ") {
            when (it) {
                AlertCategory.WARNING -> "warnings"
                AlertCategory.WATCH -> "watches"
                AlertCategory.ADVISORY -> "advisories"
                AlertCategory.STATEMENT -> "statements"
                AlertCategory.OTHER -> "other"
            }
        }
        "Notifying you of $names"
    } else {
        "Alert notifications are off"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.Notifications,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpenSettings) { Text("Change") }
    }
}

@Composable
private fun AlertCard(alert: WeatherAlert, time: TimeFormat) {
    var expanded by rememberSaveable(alert.id) { mutableStateOf(false) }
    val color = alertColor(alert.category)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(color),
            )
            Column(Modifier.padding(16.dp)) {
                Text(alert.event, style = MaterialTheme.typography.titleMedium, color = color)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(severityLabel(alert.severity), color)
                    alert.urgency?.takeIf { it != "Unknown" }?.let { Chip(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                    alert.certainty?.takeIf { it != "Unknown" }?.let { Chip(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(8.dp))
                val window = buildList {
                    alert.onset?.let { add("From ${time.smart(it)}") }
                    (alert.ends ?: alert.expires)?.let { add("until ${time.smart(it)}") }
                }.joinToString(" ")
                if (window.isNotEmpty()) {
                    Text(window.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
                }
                alert.areaDesc?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (expanded) {
                    alert.headline?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    alert.description?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it.trim(), style = MaterialTheme.typography.bodyMedium)
                    }
                    alert.instruction?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("What to do", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(it.trim(), style = MaterialTheme.typography.bodyMedium)
                    }
                    alert.senderName?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("Issued by $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text("Tap for details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun severityLabel(severity: AlertSeverity) = when (severity) {
    AlertSeverity.EXTREME -> "Extreme"
    AlertSeverity.SEVERE -> "Severe"
    AlertSeverity.MODERATE -> "Moderate"
    AlertSeverity.MINOR -> "Minor"
    AlertSeverity.UNKNOWN -> "Severity unknown"
}

@Composable
private fun Chip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun NoAlerts(place: String?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E9D5B))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("No active alerts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The National Weather Service has no watches, warnings or advisories" + (place?.let { " for $it" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

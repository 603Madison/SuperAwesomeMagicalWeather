@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.settings

import app.northvane.weather.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.northvane.core.model.GeoPoint
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import app.northvane.core.outlook.OutlookText
import app.northvane.core.pollen.PollenClient
import app.northvane.core.air.AirNowClient
import app.northvane.core.pollen.PollenPricing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import kotlin.math.roundToInt
import app.northvane.core.net.UrlPolicy
import app.northvane.core.net.toUserMessage
import app.northvane.core.owm.OwmClient
import app.northvane.core.repo.attempt
import app.northvane.core.util.UnitFormatter
import app.northvane.core.weewx.WeeWxAuth
import app.northvane.core.weewx.WeeWxFormat
import app.northvane.core.weewx.WeeWxSecret
import app.northvane.core.weewx.WeeWxStation
import app.northvane.weather.AppContainer
import app.northvane.weather.SecretStore
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.ui.components.ChoiceDropdown
import app.northvane.weather.ui.components.LabeledSwitch
import app.northvane.weather.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.northvane.core.util.randomUuid
import app.northvane.core.time.*
import app.northvane.core.util.fmt

@Composable
fun DataSourcesScreen(
    state: MainUiState,
    container: AppContainer,
    onBack: () -> Unit,
    onSourcesChanged: () -> Unit,
) {
    SecureWindow()
    val scope = rememberCoroutineScope()
    val stations by container.locations.stations.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<Pair<WeeWxStation, Boolean>?>(null) } // station, isNew
    var deleting by remember { mutableStateOf<WeeWxStation?>(null) }
    var permissionCheck by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionCheck++ }
    var lanStationsBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(stations, permissionCheck) {
        lanStationsBlocked = stations.any { it.enabled && container.platform.localNetworkBlocked(it.url) }
    }
    val localNetworkPermission = rememberPermissionLauncher { granted ->
        permissionCheck++
        if (granted) onSourcesChanged()
    }

    Scaffold(
        containerColor = Color.Transparent,
        // Text on the theme backdrop needs the theme's color, not the black default for transparent containers.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Data sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            NwsSection(state.settings.nwsContact) { contact ->
                scope.launch { container.settings.setNwsContact(contact) }
            }

            SectionCard("WeeWX local station") {
                Text(
                    "Current conditions from your own station replace NWS observations for every saved location within " +
                        "the radius you choose around the station. Sky condition and anything your station doesn't measure " +
                        "still come from NWS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stations.forEach { station ->
                    StationRow(
                        station = station,
                        covered = state.locations.filter { station.covers(it) }.map { it.displayName },
                        units = UnitFormatter(state.settings.units),
                        onEdit = { editing = station to false },
                        onDelete = { deleting = station },
                    )
                }
                if (lanStationsBlocked) {
                    Text(container.platform.localNetworkDeniedMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { localNetworkPermission.launch(Permission.LOCAL_NETWORK) }) {
                        Text("Allow local network access")
                    }
                }
                FilledTonalButton(
                    onClick = {
                        editing = WeeWxStation(
                            id = randomUuid(),
                            name = "My station",
                            url = "",
                            latitude = state.selected?.latitude,
                            longitude = state.selected?.longitude,
                        ) to true
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add WeeWX station")
                }
                Text(
                    "Supported: the SuperAwesomeMagicalWeather JSON template (recommended, see weewx/README.md in the project), " +
                        "realtime.txt from weewx-crt, or clientraw.txt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PollenSection(
                state = state,
                container = container,
                onEnabledChange = { enabled ->
                    scope.launch {
                        container.settings.setPollen(enabled)
                        onSourcesChanged()
                    }
                },
                onKeyChanged = onSourcesChanged,
            )

            ApiKeySection(
                title = "Air quality: EPA AirNow (optional)",
                description = "Official AQI from EPA monitoring stations and state/local forecasts. Free: register at " +
                    "docs.airnowapi.org and paste the key here. Without a key, the app uses Open-Meteo's air quality " +
                    "model (free, no key, less precise near the ground).",
                costLines = listOf(
                    "Free government API (EPA). Limit: 500 requests per hour; the app uses at most 2 per place every 30 minutes.",
                    "AirNow only accepts the key inside the request URL, so those requests are never cached.",
                ),
                secretName = SecretStore.AIRNOW_API_KEY,
                validate = { if (AirNowClient.looksLikeApiKey(it)) null else "AirNow keys look like 8-4-4-4-12 hex characters." },
                container = container,
                onChanged = onSourcesChanged,
            )

            OwmSection(
                enabled = state.settings.owmEnabled,
                container = container,
                onEnabledChange = { enabled ->
                    scope.launch {
                        container.settings.setOwmEnabled(enabled)
                        onSourcesChanged()
                    }
                },
                onKeyChanged = onSourcesChanged,
            )
        }
    }

    editing?.let { (station, isNew) ->
        StationEditor(
            initial = station,
            isNew = isNew,
            locations = state.locations,
            container = container,
            units = UnitFormatter(state.settings.units),
            onDismiss = { editing = null },
            onSaved = {
                editing = null
                onSourcesChanged()
            },
        )
    }

    deleting?.let { station ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${station.name}?") },
            text = { Text("The station and its saved credentials will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.locations.removeStation(station.id)
                        withContext(Dispatchers.Default) { container.secrets.remove(SecretStore.weewxSecret(station.id)) }
                        onSourcesChanged()
                    }
                    deleting = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NwsSection(savedContact: String?, onSave: (String?) -> Unit) {
    var contact by remember(savedContact) { mutableStateOf(savedContact.orEmpty()) }
    SectionCard("National Weather Service") {
        Text(
            "Always on. Free, no account or key needed. Provides observations, forecasts and alerts for the US. " +
                "In Canada, Environment and Climate Change Canada provides them instead (also free and keyless).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = contact,
            onValueChange = { contact = it.take(100) },
            singleLine = true,
            label = { Text("Contact email (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "NWS asks apps to identify themselves. If you add an email, it's included in requests to weather.gov only, so they can reach you if a request causes problems.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (contact.trim() != savedContact.orEmpty()) {
            TextButton(onClick = { onSave(contact.trim().ifEmpty { null }) }) { Text("Save contact") }
        }
    }
}

@Composable
private fun StationRow(station: WeeWxStation, covered: List<String>, units: UnitFormatter, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(station.name + if (!station.enabled) " (off)" else "", style = MaterialTheme.typography.bodyLarge)
            val host = app.northvane.core.net.HttpUrl.parse(station.url)?.host ?: station.url
            val where = when {
                !station.hasPosition && station.linkedLocationId == null -> "location not set"
                covered.isEmpty() -> "no saved places within ${radiusLabel(station.radiusKm, units)}"
                else -> "used for " + covered.joinToString(", ")
            }
            Text(
                "$host · $where",
                style = MaterialTheme.typography.bodySmall,
                color = if (covered.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${station.name}") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${station.name}") }
    }
}

@Composable
private fun OwmSection(
    enabled: Boolean,
    container: AppContainer,
    onEnabledChange: (Boolean) -> Unit,
    onKeyChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { hasKey = withContext(Dispatchers.Default) { container.secrets.has(SecretStore.OWM_API_KEY) } }

    SectionCard("OpenWeatherMap (fallback)") {
        Text(
            "Used only when NWS and WeeWX can't provide data — for example outside the US or during an outage. " +
                "Requires your own free API key from openweathermap.org.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledSwitch(title = "Use as fallback", checked = enabled, onCheckedChange = onEnabledChange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                if (hasKey) "API key saved (encrypted on this device)" else "No API key saved",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedTextField(
            value = newKey,
            onValueChange = {
                newKey = it.trim().take(64)
                error = null
            },
            singleLine = true,
            label = { Text(if (hasKey) "Replace API key" else "API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                enabled = newKey.isNotEmpty(),
                onClick = {
                    if (!OwmClient.looksLikeApiKey(newKey)) {
                        error = "That doesn't look like an OpenWeatherMap key (32 letters and digits)."
                    } else {
                        val key = newKey
                        scope.launch {
                            withContext(Dispatchers.Default) { container.secrets.put(SecretStore.OWM_API_KEY, key) }
                            newKey = ""
                            hasKey = true
                            onKeyChanged()
                        }
                    }
                },
            ) { Text("Save key") }
            if (hasKey) {
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.Default) { container.secrets.remove(SecretStore.OWM_API_KEY) }
                        hasKey = false
                        onKeyChanged()
                    }
                }) { Text("Remove key") }
            }
        }
    }
}

@Composable
private fun StationEditor(
    initial: WeeWxStation,
    isNew: Boolean,
    locations: List<SavedLocation>,
    container: AppContainer,
    units: UnitFormatter,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var format by remember { mutableStateOf(initial.format) }
    var auth by remember { mutableStateOf(initial.auth) }
    var username by remember { mutableStateOf(initial.username.orEmpty()) }
    var headerName by remember { mutableStateOf(initial.headerName.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    // Older stations were paired with one saved place; start from that place's position.
    val legacy = locations.firstOrNull { it.id == initial.linkedLocationId }
    var latText by remember { mutableStateOf((initial.latitude ?: legacy?.latitude)?.let(GeoPoint::format).orEmpty()) }
    var lonText by remember { mutableStateOf((initial.longitude ?: legacy?.longitude)?.let(GeoPoint::format).orEmpty()) }
    var radiusKm by remember { mutableDoubleStateOf(initial.radiusKm) }
    var locating by remember { mutableStateOf(false) }
    var maxAge by remember { mutableStateOf(initial.maxAgeMinutes.toString()) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var hasSavedSecret by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val granted = rememberPermissionCheck()
    var afterPermission by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val localNetworkPermission = rememberPermissionLauncher { granted ->
        afterPermission?.invoke(granted)
        afterPermission = null
    }

    /** Runs [action] once local network access is settled (asking only if the URL is on the LAN). */
    fun withLocalNetworkAccess(url: String, action: (granted: Boolean) -> Unit) {
        scope.launch {
            if (container.platform.localNetworkBlocked(url)) {
                afterPermission = action
                localNetworkPermission.launch(Permission.LOCAL_NETWORK)
            } else {
                action(true)
            }
        }
    }

    LaunchedEffect(initial.id) {
        hasSavedSecret = withContext(Dispatchers.Default) { container.secrets.has(SecretStore.weewxSecret(initial.id)) }
    }

    fun build(): WeeWxStation = initial.copy(
        name = name.trim().take(60).ifEmpty { "WeeWX station" },
        url = url.trim(),
        format = format,
        auth = auth,
        username = username.trim().takeIf { auth == WeeWxAuth.BASIC && it.isNotEmpty() },
        headerName = headerName.trim().takeIf { auth == WeeWxAuth.HEADER && it.isNotEmpty() },
        latitude = latText.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 },
        longitude = lonText.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 },
        radiusKm = radiusKm,
        linkedLocationId = null,
        maxAgeMinutes = maxAge.toIntOrNull()?.coerceIn(5, 24 * 60) ?: 30,
        enabled = enabled,
    )

    fun validate(station: WeeWxStation): String? {
        (UrlPolicy.validateUserServerUrl(station.url, station.auth != WeeWxAuth.NONE) as? UrlPolicy.Validation.Invalid)
            ?.let { return it.reason }
        if (!station.hasPosition) return "Set the station's location (latitude and longitude)"
        if (station.auth == WeeWxAuth.BASIC && station.username.isNullOrEmpty()) return "Enter a username"
        if (station.auth == WeeWxAuth.HEADER && station.headerName.isNullOrEmpty()) return "Enter a header name"
        if (station.auth != WeeWxAuth.NONE && secret.isEmpty() && !hasSavedSecret) return "Enter the ${secretLabel(station.auth).lowercase()}"
        return null
    }

    suspend fun effectiveSecret(station: WeeWxStation): WeeWxSecret? = when {
        station.auth == WeeWxAuth.NONE -> null
        secret.isNotEmpty() -> WeeWxSecret(secret)
        else -> WeeWxSecret(withContext(Dispatchers.Default) { container.secrets.get(SecretStore.weewxSecret(station.id)) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add WeeWX station" else "Edit station") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it.take(2048)
                        problem = null
                    },
                    singleLine = true,
                    label = { Text("Data URL") },
                    placeholder = { Text("https://example.com/weewx/northvane.json") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    supportingText = { Text("http:// is allowed only for local-network addresses, and never with a login.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceDropdown("Format", WeeWxFormat.entries, format, { it.label }, { format = it })
                ChoiceDropdown("Login", WeeWxAuth.entries, auth, { it.label }, {
                    auth = it
                    problem = null
                })
                if (auth == WeeWxAuth.BASIC) {
                    OutlinedTextField(value = username, onValueChange = { username = it.take(100) }, singleLine = true, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                }
                if (auth == WeeWxAuth.HEADER) {
                    OutlinedTextField(
                        value = headerName,
                        onValueChange = { headerName = it.take(64) },
                        singleLine = true,
                        label = { Text("Header name") },
                        placeholder = { Text("X-Api-Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (auth != WeeWxAuth.NONE) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it.take(512) },
                        singleLine = true,
                        label = { Text(secretLabel(auth)) },
                        placeholder = { if (hasSavedSecret) Text("Saved — leave blank to keep") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Station location", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(12) },
                        singleLine = true,
                        label = { Text("Latitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(12) },
                        singleLine = true,
                        label = { Text("Longitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !locating,
                        onClick = {
                            locating = true
                            scope.launch {
                                val here = if (granted(Permission.LOCATION)) container.deviceLocation.current() else null
                                if (here != null) {
                                    latText = GeoPoint.format(here.latitude)
                                    lonText = GeoPoint.format(here.longitude)
                                } else {
                                    problem = "Couldn't get your location. Allow location access, or copy a saved place."
                                }
                                locating = false
                            }
                        },
                    ) { Text(if (locating) "Locating…" else "I'm at the station") }
                    if (locations.isNotEmpty()) {
                        var pick by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { pick = true }) { Text("Copy a place") }
                            DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                                locations.forEach { place ->
                                    DropdownMenuItem(
                                        text = { Text(place.displayName) },
                                        onClick = {
                                            latText = GeoPoint.format(place.latitude)
                                            lonText = GeoPoint.format(place.longitude)
                                            pick = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                ChoiceDropdown(
                    label = "Use for places within",
                    options = WeeWxStation.RADIUS_CHOICES_KM,
                    selected = radiusKm,
                    optionLabel = { radiusLabel(it, units) },
                    onSelect = { radiusKm = it },
                )
                val preview = build()
                val covered = if (preview.hasPosition) locations.filter { preview.covers(it) } else emptyList()
                Text(
                    when {
                        !preview.hasPosition -> "Set where the station is to choose which places it serves."
                        covered.isEmpty() -> "No saved places are within this radius yet. Places you add nearby will use it automatically."
                        else -> "Replaces current conditions for: " + covered.joinToString(", ") { it.displayName }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = maxAge,
                    onValueChange = { maxAge = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    label = { Text("Ignore data older than (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledSwitch(title = "Enabled", checked = enabled, onCheckedChange = { enabled = it })
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        val station = build()
                        validate(station)?.let {
                            problem = it
                            return@OutlinedButton
                        }
                        testing = true
                        testResult = null
                        withLocalNetworkAccess(station.url) { granted ->
                            if (!granted) {
                                testResult = "✗ ${container.platform.localNetworkDeniedMessage}"
                                testing = false
                                return@withLocalNetworkAccess
                            }
                            scope.launch {
                                val result = attempt { container.weewx.fetch(station, effectiveSecret(station), ZoneId.systemDefault()) }
                                testResult = result.fold(
                                    onSuccess = { c ->
                                        "✓ ${units.temperatureWithUnit(c.temperatureC)}, " +
                                            "${units.percent(c.humidityPercent)} humidity · " +
                                            (c.observedAt?.let { TimeFormat.ago(it) } ?: "time unknown")
                                    },
                                    onFailure = { "✗ ${it.toUserMessage()}" },
                                )
                                testing = false
                            }
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test connection") }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val station = build()
                val issue = validate(station)
                if (issue != null) {
                    problem = issue
                } else {
                    val newSecret = secret
                    // Saving works either way; without permission the app explains why the station isn't used.
                    withLocalNetworkAccess(station.url) { _ -> scope.launch {
                        container.locations.upsertStation(station)
                        withContext(Dispatchers.Default) {
                            val key = SecretStore.weewxSecret(station.id)
                            when {
                                station.auth == WeeWxAuth.NONE -> container.secrets.remove(key)
                                newSecret.isNotEmpty() -> container.secrets.put(key, newSecret)
                            }
                        }
                        onSaved()
                    } }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun radiusLabel(km: Double, units: UnitFormatter): String =
    if (units.system == UnitSystem.IMPERIAL) "${Units.kmToMiles(km).roundToInt().coerceAtLeast(1)} mi" else "${km.roundToInt()} km"

/**
 * Google Pollen API key, with what it costs. No NOAA/NWS product covers
 * pollen, so this is the one source that needs the user's own billing account.
 */
@Composable
private fun PollenSection(
    state: MainUiState,
    container: AppContainer,
    onEnabledChange: (Boolean) -> Unit,
    onKeyChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasKey = withContext(Dispatchers.Default) { container.secrets.has(SecretStore.POLLEN_API_KEY) } }
    val places = state.locations.size.coerceAtLeast(1)
    val maxRequests = PollenPricing.maxMonthlyRequests(places)
    val maxCost = PollenPricing.maxMonthlyCostUsd(places)
    val sha1 = remember { container.platform.signingSha1() }

    SectionCard("Pollen (Google Pollen API)") {
        Text(
            "NOAA and the NWS don't forecast pollen, so the app uses Google's Pollen API: 5-day tree, grass and " +
                "weed levels on a 1 km grid, plus the main plants (birch, oak, ragweed…). It needs your own API key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What it costs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                val lines = listOf(
                    "Free for the first %,d requests each month.".fmt(PollenPricing.FREE_REQUESTS_PER_MONTH),
                    "After that, US$%.0f per 1,000 requests (1¢ each).".fmt(PollenPricing.USD_PER_1000),
                    "the app asks at most once every ${PollenPricing.MIN_REFRESH_HOURS} hours per saved place. With your " +
                        "$places place${if (places == 1) "" else "s"}, that's at most %,d requests a month, ".fmt(maxRequests) +
                        if (maxCost == 0.0) "so you'd pay $0." else "or up to about US$%.2f a month.".fmt(maxCost),
                    "Google needs a billing account (a card on file) to issue any Maps Platform key, even if you never pass the free tier.",
                    "Prices from Google's Maps Platform price list, checked ${PollenPricing.PRICE_CHECKED}. Google can change them.",
                )
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
        }
        Text(
            "To get a key: in Google Cloud Console create a project, enable the \"Pollen API\", then create an API key under " +
                "APIs & Services › Credentials. To be safe, restrict the key to the Pollen API and to this app, and set a " +
                "daily quota (for example 200 requests) so it can never run up a bill.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sha1 != null) {
            SelectionContainer {
                Text(
                    "Android app restriction: package ${container.platform.packageName}, SHA-1 $sha1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LabeledSwitch(title = "Show pollen forecast", checked = state.settings.pollenEnabled, onCheckedChange = onEnabledChange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                if (hasKey) "API key saved (encrypted on this device)" else "No API key saved",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedTextField(
            value = newKey,
            onValueChange = {
                newKey = it.trim().take(64)
                error = null
            },
            singleLine = true,
            label = { Text(if (hasKey) "Replace API key" else "API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                enabled = newKey.isNotEmpty(),
                onClick = {
                    if (!PollenClient.looksLikeApiKey(newKey)) {
                        error = "That doesn't look like a Google API key (it usually starts with \"AIza\")."
                    } else {
                        val key = newKey
                        scope.launch {
                            withContext(Dispatchers.Default) { container.secrets.put(SecretStore.POLLEN_API_KEY, key) }
                            container.settings.setPollen(true)
                            newKey = ""
                            hasKey = true
                            onKeyChanged()
                        }
                    }
                },
            ) { Text("Save key") }
            if (hasKey) {
                OutlinedButton(
                    enabled = !testing && state.selected != null,
                    onClick = {
                        val place = state.selected ?: return@OutlinedButton
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = attempt {
                                val key = withContext(Dispatchers.Default) { container.secrets.get(SecretStore.POLLEN_API_KEY) }
                                    ?: throw IllegalStateException("No key saved")
                                container.pollen.forecast(place.point, key, kotlin.time.Clock.System.now(), container.platform.pollenRestriction())
                            }
                            testResult = result.fold(
                                onSuccess = { f ->
                                    val worst = f.days.firstOrNull()?.worst
                                    "✓ Key works. Today: " + (worst?.let { "${OutlookText.pollenCategory(it.index).lowercase()} ${it.name.lowercase()} pollen" } ?: "no pollen in season")
                                },
                                onFailure = { "✗ ${it.toUserMessage()}" },
                            )
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test") }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.Default) { container.secrets.remove(SecretStore.POLLEN_API_KEY) }
                        hasKey = false
                        testResult = null
                        onKeyChanged()
                    }
                }) { Text("Remove") }
            }
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** A write-only API key field with an explanation of what the service costs. */
@Composable
private fun ApiKeySection(
    title: String,
    description: String,
    costLines: List<String>,
    secretName: String,
    validate: (String) -> String?,
    container: AppContainer,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(secretName) { hasKey = withContext(Dispatchers.Default) { container.secrets.has(secretName) } }
    SectionCard(title) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Cost", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                costLines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(if (hasKey) "Key saved (encrypted on this device)" else "No key saved", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = newKey,
            onValueChange = {
                newKey = it.trim().take(80)
                error = null
            },
            singleLine = true,
            label = { Text(if (hasKey) "Replace key" else "API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = newKey.isNotEmpty(), onClick = {
                val problem = validate(newKey)
                if (problem != null) {
                    error = problem
                } else {
                    val key = newKey
                    scope.launch {
                        withContext(Dispatchers.Default) { container.secrets.put(secretName, key) }
                        newKey = ""
                        hasKey = true
                        onChanged()
                    }
                }
            }) { Text("Save key") }
            if (hasKey) {
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.Default) { container.secrets.remove(secretName) }
                        hasKey = false
                        onChanged()
                    }
                }) { Text("Remove key") }
            }
        }
    }
}

private fun secretLabel(auth: WeeWxAuth) = when (auth) {
    WeeWxAuth.BASIC -> "Password"
    WeeWxAuth.BEARER -> "Token"
    WeeWxAuth.HEADER -> "Header value"
    WeeWxAuth.NONE -> "Secret"
}

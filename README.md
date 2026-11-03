# Northvane

[![CI](https://github.com/603Madison/Northvane-Weather/actions/workflows/ci.yml/badge.svg)](https://github.com/603Madison/Northvane-Weather/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/603Madison/Northvane-Weather?label=download)](https://github.com/603Madison/Northvane-Weather/releases/latest)

A clean, private weather app for Android.

Northvane shows current conditions, hourly and 10-day forecasts, animated radar
with a future cast, and National Weather Service alerts. It uses free NOAA/NWS
data, can take current conditions from **your own WeeWX station**, and keeps
working over **satellite links such as T-Mobile T-Satellite** in **Backwoods mode**. No API key is
built into the app, and there are no accounts, ads or analytics.

| | |
|---|---|
| **Forecast numbers** | Hourly and daily wind, gusts, rain/snow/ice amounts, humidity, cloud cover, thunder chance (NWS gridpoint data) |
| **Power outage outlook** | Optional (off by default) daily estimate tuned for New England (gusts + leaf-on season, ice, wet snow, saturated ground, alerts) |
| **Current conditions** | Your WeeWX station → nearest NWS observation → OpenWeatherMap (optional) → NWS hourly estimate |
| **Hourly / daily forecast** | NWS (api.weather.gov) → OpenWeatherMap (optional, e.g. outside the US) |
| **Alerts & notifications** | NWS active alerts for every saved location, checked in the background |
| **Radar** | NOAA MRMS (official) or NEXRAD composite, plus a future cast from the newest NOAA HRRR model run |
| **Backwoods mode** | Opts in to Android 16 constrained satellite networks and limits downloads to what you allow |
| **Look & feel** | Material 3, light/dark/system theme, optional wallpaper colors, °F/°C |

<p>
<img src="docs/screenshots/today_ai_summary.png" width="200" alt="AI summary on Today">
<img src="docs/screenshots/today_canada.png" width="200" alt="Fredericton, NB from Environment Canada">
<img src="docs/screenshots/outlook_tab.png" width="200" alt="Outlook tab">
<img src="docs/screenshots/settings_satellite.png" width="200" alt="Backwoods mode settings">
</p>
<p>
<img src="docs/screenshots/today_light.png" width="200" alt="Today, Portland ME">
<img src="docs/screenshots/warning_red.png" width="200" alt="Red Today screen during a warning">
<img src="docs/screenshots/outage_outlook_dark.png" width="200" alt="Power outage outlook">
<img src="docs/screenshots/today_dark_weewx.png" width="200" alt="Dark theme with a local WeeWX station">
</p>
<p>
<img src="docs/screenshots/hourly_card.png" width="300" alt="Consolidated hourly card">
</p>
<p>
<img src="docs/screenshots/pollen_key_costs.png" width="250" alt="Pollen API key and costs">
<img src="docs/screenshots/locations_nicknames.png" width="200" alt="Location nicknames and notifications">
</p>
<p>
<img src="docs/screenshots/themes.png" width="600" alt="Six themes, light and dark">
</p>
<p>
<img src="docs/screenshots/air_uv_allergy.png" width="250" alt="Air quality, UV and allergy score">
<img src="docs/screenshots/outage_prepare.png" width="250" alt="Outage preparation steps">
<img src="docs/screenshots/traffic_list.png" width="250" alt="Traffic events near Portland, ME">
<img src="docs/screenshots/themes_screen.png" width="250" alt="Theme picker">
</p>
<p>
<img src="docs/screenshots/alerts_dark.png" width="200" alt="NWS alerts">
<img src="docs/screenshots/today_satellite_metric.png" width="200" alt="Backwoods mode, metric">
</p>

*Screenshots are rendered from real NWS and NOAA ACIS responses for Portland, ME by `ScreenshotTest` (the outage example adds a synthetic nor'easter day; the warning, pollen and nickname examples use sample data).*

## Install

**[Download the latest APK](https://github.com/603Madison/Northvane-Weather/releases/latest)**
(older versions are under [all releases](https://github.com/603Madison/Northvane-Weather/releases)),
check it against `SHA256SUMS.txt`, and open it on your phone (you'll be asked to
allow installs from your browser or file manager). Northvane runs on Android 8.0+.
The satellite features need Android 16+ and a carrier that supports satellite data.

### Northvane Mini (flip phones)

Each release also has **Northvane-Mini**, a separate small app for Android flip
phones (Kyocera DuraXV, Sonim XP3plus, TCL Flip, CAT S22 Flip and similar). It
installs alongside Northvane and is built for 2.4–2.8" screens and keypads:

- **Everything works from the d-pad and keys**, with or without a
  touchscreen: ▲▼ to move, OK to select, the right soft key (or Back) to go
  back, and the left soft key for the action shown at bottom left (Refresh,
  Add, Search…). Phones that send MENU for the left soft key work too.
- **Number shortcuts** from the home screen: 1 Hourly, 2 7-day, 3 Alerts,
  4 Radar, 5 Places, 6 Settings, 0 Keys & help.
- Current conditions, 48 hours, 7 days with full NWS text, alerts with
  background **notifications**, several places (search by town or ZIP with the
  keypad, or use GPS), °F/°C and dark/light themes.
- **Radar** as a single map picture per frame (IEM's NEXRAD maps with counties,
  highways and towns), no map engine: ◀▶ steps through the past hour, ▲▼ or
  `*` `#` zoom, 2 4 6 8 pan, OK plays.
- Light on data (about 25 KB per forecast refresh, ~60 KB per radar frame) and
  on memory, for Android Go phones. Same forecast engine and sources as
  Northvane; no accounts, ads or tracking.

<img src="docs/screenshots/mini_home.png" width="240" alt="Northvane Mini home screen at 480×640">

## Status

Version 1.0.0. The data layer is tested against captured and live NWS, NOAA and
IEM responses, and every screen is rendered in tests. Still to be exercised on
real hardware: the radar map, notifications, connecting to a WeeWX station on a
LAN, and behavior on an actual satellite connection. Bug reports from those
setups are especially welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Features

### Weather data
- **US and Canada, for free.** The National Weather Service covers the US;
  **Environment and Climate Change Canada** (MSC GeoMet city pages, keyless)
  covers Canada, including public alerts and alert notifications.
- **NWS first, for free.** Grid metadata for each location is cached for two
  weeks, so a refresh is a handful of small, gzip-compressed requests.
- **WeeWX override.** Give a station its position and a radius (1–25 mi);
  its readings (temperature, humidity, wind, pressure, rain, UV, solar) replace
  the NWS observation for every saved place within that radius. Sky condition and visibility are filled from NWS. Stale station
  data falls back to NWS automatically. See [`weewx/README.md`](weewx/README.md).
- **OpenWeatherMap only as a last resort**, off by default, using *your* key
  entered in the app. It's used only when NWS/WeeWX can't supply a section,
  such as for locations outside the US.
- Offline-friendly: the last result per location is cached on the device.

### Forecast details
- Real numbers, not just the forecast wording. The NWS **gridpoint forecast**
  (~10 KB compressed) adds gusts, wind direction, rain/snow/ice amounts, sky
  cover, humidity, feels-like temperature and thunder probability to every
  hour and day.
- **Hourly** view: temperature and precipitation chance at a glance. Tap the
  card for every number for the next 36 hours: feels-like, precipitation
  amount, wind direction and speed, gusts, humidity, dew point and cloud
  cover. It remembers whether you left it open.
- **Daily** view: tap a day for its wind and gusts, totals, humidity range,
  cloud cover, thunder chance and the outage outlook. If detailed data is turned
  off for satellite, amounts are read from the NWS forecast text instead.
- **Sun** card: the sun's path across the sky with its current position, time
  to sunset or sunrise, day length and the change since yesterday.

### AI summary (optional)
Connect your own **Ollama**, **Open WebUI**, **LibreChat** (Agents API) or any
**OpenAI-compatible** server (LM Studio, vLLM, llama.cpp, LiteLLM, OpenAI…)
in Settings › AI summary. It writes a 2–4 sentence briefing that sits under
current conditions on the Today screen. Only the place name and the forecast
numbers are sent. Local servers can use plain HTTP on your home network; an API
key is never sent over HTTP. Reasoning tags (`<think>…</think>`) are stripped,
and a summary is written at most once per forecast download.

### Outlook tab
Planning cards live on their own tab so Today stays focused on the weather:
outdoor activity scores, garden, trail mud, clothes drying, heating season and
fall foliage. Each appears only when turned on and in season.

### Outdoor outlook (optional)
Daily 0–100 scores, with reasons and practical recommendations, for **hiking**,
**camping** (overnight), **off-roading**, **mountain biking**, **paddling** and
**stargazing** (overnight; uses cloud cover and the moon's phase). Seasonal
activities appear only when they make sense:
- **Winter, with confirmed snow on the ground** (nearest reported snow depth):
  downhill skiing (powder days, lift wind holds, rain-on-snow), cross-country
  skiing (kick-wax suggestions), snowshoeing, snowmobiling.
- **Ice fishing**, once lakes have likely frozen solid (estimated ice
  thickness, with "check it yourself" guidance).
- **Swimming** in summer, and **hunting** in fall and spring turkey season.
- In winter, hiking, camping and biking become **winter hiking**, **winter
  camping** and **fat biking**, with winter gear tips. Paddling and
  off-roading hide while there's snowpack. Each
activity weighs what matters to it: heat and humidity, cold and wind chill,
rain and thunderstorms, gusts (and stronger summit winds), snow and ice, trail
mud and active warnings. Tips include sleeping-bag ratings for the overnight
low, rainfly and guyline advice, microspikes or snowshoes, early starts before
afternoon storms, and New England seasonal notes (black flies, ticks, hunting
season blaze orange, short days).

### Garden (optional, off by default)
Pick what you're growing and how you'll start it, and Northvane builds a
schedule from your **local frost dates**. These are computed once from 30 years of NOAA
daily lows for your spot (median and 1-in-10 dates):
- **Planting card** (only in planting season): start seeds indoors, harden
  off, plant out or sow. When a window opens, it checks the forecast for the
  first day whose coming nights stay above that crop's frost limit, and holds
  off for cold soil (estimated from recent temperatures). Mark crops planted
  as you go.
- **Harvest card** (only in harvest season): ready dates from days-to-maturity,
  "pick before Thursday's frost" for tender crops, "cover plants tonight",
  and "frost will sweeten your kale". Garlic gets a fall planting reminder.
- Optional extras: frost & freeze notifications (NWS Frost Advisories and
  Freeze Warnings even if you've turned advisories off), watering advice
  (past week's rain vs. about an inch a week), soil readiness, and growing
  degree days for farmers.

### Clothes drying
The best window for line-drying in the next two days (warm dry air, a
breeze, sun, no rain) with an estimated drying time, plus a 7-day ranking.
It never recommends drying below freezing. Tap **My laundry is out** and the
app checks the hourly forecast every 20 minutes in the background, notifying
you before rain, thunder or evening dew (for up to 12 hours).

### Heating season (optional, off by default)
Tell the app how you heat and when you use each source, for example heat pump
above 15°F, wood stove below 30°F, oil below 0°F. It shows which system to
run each day this week and the heating demand (degree days). It also lists
chores timed to your climate:
- Fuel: pre-buy oil or propane in late summer, check the tank before a cold snap.
- Wood: buy seasoned firewood, and split and stack next winter's wood in spring.
- Pellets, plus service and chimney visits.
- Pipe protection and clearing snow from heat-pump units.

### Allergy score (optional)
Tell the app what you're allergic to, for example birch (severe), grass (mild)
and outdoor mold, and it gives each day a personal 0–100 score:
- **Pollen** for the exact plant from the Google Pollen API forecast when it's
  reported for your area, otherwise its pollen type (tree, grass, weed) at a
  discount (marked as estimated). Pollen needs your Google key.
- **Outdoor mold** (no free source measures it) is estimated from season,
  humidity, recent rain, warmth and wet leaf litter, and drops under snow or
  frozen ground.
- **Air pollution** from the AQI forecast.
- Weather adjusts it: steady rain washes pollen out, dry gusty wind spreads it,
  and thunderstorms during high grass/tree pollen bring an asthma warning.
Tips scale with the score: take medicine early, keep windows closed, shower
after being outside, wear a mask for yard work. The allergy forecast appears
inside the pollen card when pollen data is available.

### Air quality and UV index (optional)
- **AQI** from **EPA AirNow** (government monitors and state/local forecasts)
  if you add the free key from docs.airnowapi.org; otherwise from Open-Meteo's
  air quality model (CAMS), which needs no key. Shows the pollutant, a 4-day
  outlook, Air Quality Action Days and the forecaster's discussion.
- **UV index** from **EPA's** hourly UV forecast by ZIP code (keyless; the ZIP
  is looked up on the device), with today's peak, an hourly bar chart and
  protection advice. Open-Meteo fills in outside the US.

### Trail mud (optional)
A daily mud level (dry, damp, muddy, very muddy, frozen, snow-covered) from a
soil water balance run over the last 2–5 months of **observed** NOAA data
(ACIS gridded station analysis: daily highs, lows and precipitation; free and
keyless) and on through the forecast. It models rain, snowmelt, drying, and the
**spring thaw**: frost depth is tracked from freezing and thawing degree-days,
so mud season shows up while the ground thaws from the top over still-frozen
subsoil (validated against Concord, NH, winter–spring 2026).

### Fall foliage (optional)
There's no free machine-readable foliage feed, so Northvane estimates the color
stage and peak from latitude, elevation and this fall's observed night-time
temperatures (cool nights hurry color, a warm fall delays it, hard freezes and
drought drop leaves early), and flags windy or very wet days that will strip
leaves. Links to the official Maine and New Hampshire reports.

### Pollen (optional, your own key)
NOAA/NWS don't forecast pollen. Northvane can use the **Google Pollen API**
(5-day tree, grass and weed levels and main plants). It needs your own key,
and Settings › Data sources explains the cost. Google gives 5,000 free requests a
month, then charges US$10 per 1,000 (prices checked October 2026). Northvane asks at most
every 6 hours per place (about 124 a month), so typical use is free. The key is
sent in a header and never in the URL. Coordinates are rounded to about 1 km, and
the request carries the app's package and certificate so you can restrict the key.

### Power outage outlook (optional)
A rough, transparent daily score (Low / Elevated / High / Very high) built for
New England's tree-lined overhead lines. Each day lists the factors behind it:

- **Gusts**, weighted up while trees are in leaf, when the same wind brings
  down far more limbs. Leaf cover follows the season for the location:
  leaf-out in May (later up north and at elevation), full canopy through peak
  color, then dropping, so bare winter trees don't count.
- **Ground state**: frozen ground in winter anchors roots (less uprooting),
  while thawing, waterlogged ground in mud season lets moderate wind topple trees.
- **Ice** from freezing rain, roughly following the Sperry-Piltz ice index
  (0.25" scattered outages, 0.5"+ widespread), worse with wind.
- **Heavy wet snow** near freezing or with a low snow-to-liquid ratio, and
  especially early or late-season snow on leafed trees (October 2011, October 2020).
- **Saturated ground** (1"+ of rain) combined with wind, **thunderstorms**, heat,
  and active **NWS alerts** (High Wind, Ice Storm, Winter Storm, and others).

When the risk is High or Very high, tap the panel for preparation steps. They
are tailored to what's driving the risk and to whether your heat sources need
electricity.

Quiet days with nothing that raises the risk show no panel at all. This is not
a utility forecast. Turn it on or off in Settings › Today screen.

### Frozen pipe risk (optional, off by default)
Shown in a day's details on nights at or below 20°F, the point where pipes in
outside walls and unheated spaces start to freeze. The level rises with how
cold it gets (10°F, 0°F), how many hours stay below 20°F, a day that never
thaws, wind, and a high power outage risk the same day (no power usually means
no heat). Tap the panel for steps: hoses and outdoor faucets, open sink
cabinets, dripping faucets, thermostat settings, heat tape, and what to do if
the power is out for long. A water-drop mark on the day's row flags High and
Very high risk.

### Black ice and frost (optional, off by default)
For each morning, from the hourly forecast where it reaches and the day
summaries beyond it:

- **Frost** on windshields after clear, calm nights near or below freezing in damp air.
- **Black ice** from freezing rain, from rain or drizzle followed by a drop
  below freezing, or from daytime snowmelt that refreezes overnight.

An ice-crystal mark flags mornings with black ice; tap the panel for driving
and walkway tips. Turn both on in Settings › Today screen.

### Aurora forecast (on by default)
NOAA's Space Weather Prediction Center publishes the planetary K-index for the
past three days and the next three, free and the same for everyone (no
location is sent). Northvane works out your geomagnetic latitude and how far
south the auroral oval reaches at each Kp, then checks when it's dark and how
cloudy the hourly forecast is. The card appears on the Today screen only when
the aurora may be visible from where you are: through a phone camera, low on
the northern horizon, or overhead. During a strong storm (Kp 7 and up) it also
warns that GPS and satellite texting can be less reliable. In Backwoods mode
the forecast (~7 KB) is off unless you allow it.

**Aurora notifications** (on with the forecast) check NOAA's forecast every
three hours for places that have notifications on. You get a heads-up up to a
day ahead and another if it may be visible right now, at most once per night
unless the outlook improves. Overcast nights are skipped. Choose the threshold
in Settings › Today screen: visible to a phone camera, to the eye low on the
horizon (the default), or overhead.

### Maple sap runs (optional, off by default)
On the Outlook tab from mid-January through April. Each day is rated from the
night before's low and the day's high: sap runs best after a night in the 20s
°F and a sunny day in the 40s, and stops when nights stay above freezing. A
run of warm nights late in the season is flagged, since buds end it.

### Burn days (optional, off by default)
On the Outlook tab: a week of Good / Use caution / Don't burn days for brush
and yard waste, from wind and gusts, the day's lowest humidity, how long since
meaningful rain (NOAA observed data plus the forecast), snow cover, and NWS
Red Flag Warnings and Fire Weather Watches. Rules vary by town and state: many
need a burn permit.

### Alerts
- Background checks every 15/30/60 minutes (WorkManager) for the saved
  locations you choose (Locations › ⋮ › Alert notifications, or Settings).
- When an NWS **warning** is in effect, the Today screen turns red. Watches,
  advisories and statements keep the normal look.
- **Send test notification** in Settings shows exactly how a warning will look and sound.
- Separate notification channels for **Warnings** (high priority), **Watches**,
  **Advisories** and **Statements**, each opt-in.
- Updates to an alert replace its earlier notification instead of stacking.
  Notifications expire when the alert ends, and tapping one opens the Alerts tab.

### Radar
- Past hour from **NOAA MRMS** via the NWS time-enabled image service (default),
  or the **IEM NEXRAD composite** as an alternative. Frames follow the actual
  scan times in NOAA's image catalog, and each frame draws the newest scan for
  every region (CONUS, Alaska, Hawaii, Puerto Rico, Guam).
- **Future cast**: NOAA's HRRR model (3 km, updated hourly), read straight
  from NOAA's open data on AWS rather than pre-rendered tiles. Each 15-minute
  frame comes from the newest run that has already published it (runs appear
  about an hour after they start and fill in over the next hour), so the first
  hour is usually from a run under two hours old. Northvane downloads only the
  simulated **composite** reflectivity (the strongest echo in each column,
  which is what a radar mosaic shows far from the radars; it matched NOAA's
  radar over Maine noticeably better than reflectivity 1 km above ground),
  about 490 KB per frame by byte range, decodes the GRIB2 data on the phone,
  projects it onto sub-kilometer map pixels and colors it with the same scale
  as NOAA's radar, so past and forecast frames look alike. Each run is then
  **calibrated to the newest radar** by quantile mapping: at the radar's time,
  the model's reflectivity at each percentile is matched to the radar's, and
  that correction is applied to the run's later frames (capped at ±10 dBZ).
  Model values below 0 dBZ (cloud, virga, the soft fringe between grid
  points) are never turned into rain, which would paint a pale haze. This
  fixes a model that draws too little or too much rain on a given day without
  moving anything; where the rain is still comes from the model. On a
  day of light rain in Maine it brought the model's rain area in line with the
  radar and raised the critical success index for light rain by 0.02–0.07 over
  the next 90 minutes. Frames start right after the newest radar scan, and the
  caption names the runs used. If NOAA's data can't be reached, the Iowa
  Environmental Mesonet's HRRR tiles are used instead.
- **Satellite imagery** toggle on the map: GOES-East/West visible by day and
  infrared at night (or pick visible, infrared or water vapor) from NOAA
  nowCOAST, drawn under the radar.
- A pin marks the selected location.
- Keyless OpenStreetMap basemap (OpenFreeMap) via MapLibre, with light and
  dark styles.

### Traffic (optional tab)
- **Work zones** from state DOT feeds in the USDOT **Work Zone Data Exchange
  (WZDx)** standard. The app finds keyless feeds for your state in USDOT's
  public feed registry; New Hampshire, Vermont and Maine publish one together.
- **Crashes, closures and incidents** from **New England 511** (NHDOT,
  MaineDOT, VTrans) with a free developer key.
- **Live speeds** (optional): no government source publishes road speeds, so
  the speed layer uses TomTom traffic tiles with your own key. TomTom's free
  tier is 50,000 tiles a day, and one look at the map uses roughly 20–60, so
  it's free in practice (details in Settings › Data sources).
- Map with events and your location, plus a list sorted by distance (active
  now, then starting this week). Radius 10, 25 or 50 miles.

### Themes
Seven static themes (Northvane, Harbor, Evergreen, Maple, Lupine, Aurora,
Driftwood), each with its own colors, background and hero card. There are also
two **weather themes**: **Live sky** follows the current conditions (sunny
blue, storm violet, rainy slate, snowy white, night navy) and **Temperature**
shifts from icy blue to summer amber. **Custom** lets you pick an accent color
and a gradient, or your own photo with adjustable dimming. The photo is
re-encoded into app storage with its location metadata removed.

### Backwoods mode (T-Satellite and similar)
Backwoods mode is Northvane's light-packing mode. It turns on automatically
on a satellite connection, or on mobile data while Android's **Data Saver** is
on (even if Northvane is set to "Unrestricted data"), or always or never, as
you choose. It only downloads what you allow.

- The manifest declares
  `android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED`, so on Android 16+ the
  app is allowed onto constrained satellite networks and is listed among
  satellite-enabled apps in system Settings.
- `ConnectivityMonitor` follows Google's guidance: it requests a network
  *without* `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED` and treats it as
  constrained if that capability is missing or the transport is
  `TRANSPORT_SATELLITE`.
- **You choose what Backwoods mode downloads** (Settings › Backwoods mode),
  with typical sizes shown for each item: alerts, background alert checks,
  current conditions, WeeWX station (internet-reachable), daily forecast,
  forecast details, hourly forecast, recent weather history, pollen, radar,
  radar future cast, satellite imagery and the OpenWeatherMap fallback. Limits apply on satellite automatically, always, or
  never.
- Anything turned off keeps its last downloaded data, but cards whose data is
  more than 12 hours old (or missing) are hidden while limits are on. Whole
  tabs are hidden too, for example Traffic when traffic is off for satellite
  and nothing recent is saved. Turn on **Show stale data** to see them anyway.
- Options are grouped (Forecast & alerts, Extras, Maps) with one switch per
  group and an expandable list of individual items and their sizes. Timeouts are generous
  for high-latency links.

## Building

Requirements: a current Android Studio (with AGP 9.4 support), JDK 17+ and the
Android SDK platform 37 (Android 17). The app targets API 37 and runs on Android 8.0+.

```bash
./gradlew :core:test                        # parsers (incl. captured live NWS responses), fallback logic, security policy
./gradlew :core:test -Pnorthvane.live=true    # also hit the real NWS/NOAA/IEM services end to end
./gradlew :app:testDebugUnitTest            # renders screens to app/build/outputs/screenshots (Robolectric)
./gradlew :app:lintDebug                    # Android lint
./gradlew :app:assembleDebug                # installable debug APK in app/build/outputs/apk/debug/
```

Release builds are minified with R8. To sign one, create a git-ignored
`keystore.properties` next to `settings.gradle.kts`:

```properties
storeFile=/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

then run `./gradlew :app:assembleRelease`.

The Gradle wrapper uses Gradle 9.6.0 and verifies the distribution's SHA-256
checksum before running it.

## Project layout

```
core/   Pure Kotlin/JVM: API clients (NWS, WeeWX, OpenWeatherMap), parsers,
        source fallback/merge (WeatherRepository), alert tracking, radar
        frame math, unit formatting, URL/cleartext security policy. Unit tested.
app/    Android: Compose UI, MapLibre radar, WorkManager alert checks,
        Keystore-encrypted secret store, DataStore settings, satellite
        connectivity monitor.
weewx/  WeeWX skin + setup guide for the local-station feed.
docs/   Security and privacy notes.
```

## Configuration in the app

- **Settings › Data sources**
  - *NWS contact email* (optional). It goes in the User-Agent sent to
    weather.gov only, as NWS requests.
  - *WeeWX stations*: URL, format, optional login (Basic, bearer token or
    custom header), station position and radius, and max data age. Includes a
    **Test connection** button. On Android 17+, a station on your home
    network needs the **Nearby devices** permission. The app asks for it
    when you test or save such a station, and only then.
  - *Google Pollen API*: your key, with the cost explained, a **Test** button
    and the package/SHA-1 for restricting the key.
  - *OpenWeatherMap*: enable/disable and enter your API key. The key is stored
    encrypted and never shown again.
- **Settings › Severe weather alerts**: categories, check frequency, channels.
- **Settings › Backwoods mode**: when it turns on, show stale data, and what it may download (grouped).
- **Settings › Today screen**: hourly details, air quality, UV, power outage
  outlook, frozen pipe risk, black ice and frost, aurora forecast, pollen and
  allergies, AI summary. Each is a separate switch.
- **Settings › Outlook**: outdoor activities (and which ones), trail mud, fall
  foliage, clothes drying, garden, burn days, maple sap runs and heating.
- **Settings › Radar**: past-radar source, future cast on/off and range,
  satellite imagery and type.
- **Locations**: rename a place (nickname), choose whether it sends alert
  notifications, remove it.
- **Settings › Appearance**: theme (static, weather or custom), System / Light / Dark, wallpaper colors, units.
- **Settings › Today screen › My allergies**: your allergens and sensitivity.
- **Settings › Traffic**: tab on/off, radius, live speed layer.
- **Settings › Outlook › My garden / My heating**: crops, methods, planted
  dates and garden extras; heat sources and temperature ranges.

## Data sources and attribution

- Weather, alerts and radar: NOAA / National Weather Service (public domain).
- Canada: Environment and Climate Change Canada (Open Government Licence – Canada).
- Future cast: NOAA HRRR model via the NOAA Open Data Dissemination program
  on AWS (public domain); fallback HRRR tiles and the alternative NEXRAD composite: Iowa
  Environmental Mesonet, Iowa State University.
- Recent observed weather: NOAA Regional Climate Centers, ACIS (public domain).
- Aurora: NOAA Space Weather Prediction Center planetary K-index (public domain).
- UV index and AirNow air quality: US EPA. Air quality model: Open-Meteo (CC BY 4.0, CAMS).
- Work zones: state DOT WZDx feeds via the USDOT ITS DataHub registry. Incidents: New England 511.
- Optional traffic speeds: TomTom (your own key).
- Satellite imagery: NOAA nowCOAST (GOES-East/West).
- Basemap: OpenFreeMap; © OpenMapTiles; © OpenStreetMap contributors.
- Optional: Google Pollen API, OpenWeatherMap (your own accounts and keys).
- Activity, mud, foliage, outage, frozen pipe, ice, aurora, sap and burn outlooks are Northvane's own estimates.

See [`docs/SECURITY.md`](docs/SECURITY.md) for the security and privacy design, [`SECURITY.md`](SECURITY.md) for reporting vulnerabilities, [`CHANGELOG.md`](CHANGELOG.md) for release notes and [`docs/RELEASING.md`](docs/RELEASING.md) for how releases are made.

## License

Northvane is free software: you can redistribute it and/or modify it under
the terms of the **GNU General Public License** as published by the Free
Software Foundation, either **version 3** of the License, or (at your option)
any later version. It is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE. See [`LICENSE`](LICENSE) for the full text.

Copyright © 2026 Northvane contributors. SPDX-License-Identifier: `GPL-3.0-or-later`.

Weather data and map tiles remain under their providers' own terms (see
*Data sources and attribution* above).

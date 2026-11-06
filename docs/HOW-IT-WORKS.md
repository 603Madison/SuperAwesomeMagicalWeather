# How it works

The details behind SuperAwesomeMagicalWeather's data and estimates. For what the
app does, see the [README](../README.md).

- [Where the weather comes from](#where-the-weather-comes-from)
- [Radar and the future cast](#radar-and-the-future-cast)
- [Alerts](#alerts)
- [Estimates](#estimates)
- [Optional services](#optional-services)
- [Wildfire smoke](#wildfire-smoke)
- [Rivers and tides](#rivers-and-tides)
- [Notifications and widget](#notifications-and-widget)
- [Backwoods mode](#backwoods-mode)
- [Screenshots](#screenshots)

## Where the weather comes from

Each part of the forecast has a chain of sources. The app uses the first one
that has fresh data.

| Data | Sources, in order |
|---|---|
| Current conditions | Your WeeWX station → nearest NWS observation → OpenWeatherMap (if on) → NWS hourly forecast |
| Hourly and daily forecast | NWS (api.weather.gov) → Environment Canada → OpenWeatherMap (if on) |
| Forecast details | NWS gridpoint data |
| Alerts | NWS active alerts; Environment Canada public alerts in Canada |

- **NWS.** Each place's grid information is cached for two weeks, so a refresh
  is a handful of small, compressed requests. The **gridpoint forecast**
  (about 10 KB compressed) adds the numbers behind the forecast wording: gusts,
  wind direction, rain, snow and ice amounts, sky cover, humidity, feels-like
  temperature and thunder chance, for every hour and day.
- **Environment and Climate Change Canada** covers Canada with its keyless
  MSC GeoMet city pages, including public alerts.
- **WeeWX.** Give a station its position and a radius (1–25 mi). Its readings
  (temperature, humidity, wind, pressure, rain, UV, solar) replace the NWS
  observation for every saved place within that radius; sky condition and
  visibility still come from NWS. If the station's data goes stale, the app
  falls back to NWS. See the [WeeWX guide](../weewx/README.md).
- **OpenWeatherMap** is off by default and uses your own key. It's used only
  when the sources above can't supply a section, such as outside North America.
- The last result for each place is kept on the phone, so the app works offline.

## Radar and the future cast

### Past radar

The past hour comes from **NOAA MRMS** through the NWS time-enabled image
service, or from the **IEM NEXRAD composite** if you prefer. Frames follow the
actual scan times in NOAA's image catalog, and each frame shows the newest scan
for every region (CONUS, Alaska, Hawaii, Puerto Rico, Guam). The basemap is
OpenFreeMap (OpenStreetMap) through MapLibre, in light and dark styles.
Optional satellite imagery from NOAA nowCOAST (GOES-East/West) is drawn under
the radar: visible by day and infrared at night, or a fixed visible, infrared
or water vapor layer.

### Future cast

The future cast comes from NOAA's **HRRR** model (3 km grid, run every hour),
read straight from NOAA's open data on AWS instead of pre-drawn tiles.

1. **Newest data for each frame.** Each 15-minute frame comes from the newest
   model run that has already published it. Runs appear about an hour after
   they start and fill in over the next hour, so the first hour is usually from
   a run under two hours old.
2. **Only what's needed.** The app downloads just the simulated **composite
   reflectivity**, about 490 KB per frame, using byte ranges from the run's
   index file. Composite reflectivity is the strongest echo in each column,
   which is what a radar mosaic shows far from the radars. Over Maine it matched
   NOAA's radar noticeably better than reflectivity 1 km above the ground.
3. **Decoded on the phone.** The app's own GRIB2 decoder (checked value for
   value against ECMWF's eccodes) unpacks the data. It is projected onto
   sub-kilometer map pixels and colored with NOAA's radar scale, so past and
   forecast frames look alike.
4. **Calibrated to the latest radar.** At the time of the newest radar scan,
   the model's reflectivity at each percentile is matched to the radar's
   (quantile mapping), and that correction is applied to the run's later frames.
   The correction is capped at ±10 dBZ, and model values below 0 dBZ (cloud,
   virga, the soft fringe between grid points) are never turned into rain,
   which would paint a pale haze. This fixes a model that draws too much or too
   little rain on a given day without moving anything: where the rain falls
   still comes from the model. On a day of light rain in Maine it brought the
   model's rain area in line with the radar and raised the critical success
   index for light rain by 0.02–0.07 over the next 90 minutes.

5. **Handed over from the radar.** Even a fresh run often has rain in the wrong
   place at the moment of the last scan, so going straight from radar to model
   made rain jump or vanish. The app measures how the rain is moving by
   comparing the newest scan with one about 20 minutes earlier, carries the
   newest scan along that motion, and fades it out over two hours while the
   model fades in (mixed as rain rates, so rain the model lacks fades rather
   than ghosting). The first frames follow what is actually falling; from two
   hours on, the frames are the model's alone.

Frames start right after the newest radar scan, and the caption names the runs
used. If NOAA's data can't be reached, the Iowa Environmental Mesonet's HRRR
tiles are used instead.

## Alerts

- Background checks run every 15, 30 or 60 minutes (WorkManager) for the places
  you choose, under Locations › ⋮ › Alert notifications or in Settings.
- **Warnings**, **watches**, **advisories** and **statements** each have their
  own notification channel and switch; warnings are high priority.
- An update to an alert replaces its earlier notification instead of stacking.
  Notifications expire when the alert ends, and tapping one opens the Alerts tab.
- While a **warning** is in effect, the Today screen turns red. Watches,
  advisories and statements keep the normal look.

## Estimates

These are the app's own estimates from the forecast and recent observations,
not official forecasts.

### Storm totals

From the NWS gridpoint forecast's hourly rain, snow and ice amounts: wet
hours with dry gaps of up to six hours count as one storm, and the first one
with at least 2" of snow, 0.1" of ice or 1" of rain gets a card. It shows the
totals (rain counted only in hours without snow or ice), start and end, and
the six hours with the most precipitation. An active NWS storm alert (Winter
Storm Warning, Blizzard, Ice Storm, Flood…) names it; otherwise it's a
snowstorm, ice storm (a quarter inch of ice or more), winter storm (mixed) or
soaking rain. Beyond the hourly forecast's reach, daily totals stand in.

### Power outage risk

A daily level (Low, Elevated, High, Very high) built for New England's
tree-lined overhead lines. Each day lists the factors behind it:

- **Gusts**, weighted up while trees are in leaf, when the same wind brings
  down far more limbs. Leaf cover follows the season for the place: leaf-out
  in May (later up north and at elevation), full canopy through peak color,
  then dropping, so bare winter trees don't count.
- **Ground state**: frozen ground in winter anchors roots, while thawing,
  waterlogged ground in mud season lets moderate wind topple trees.
- **Ice** from freezing rain, roughly following the Sperry-Piltz ice index
  (0.25" brings scattered outages, 0.5" and up widespread ones), worse with wind.
- **Heavy wet snow** near freezing or with a low snow-to-liquid ratio,
  especially early or late-season snow on leafed trees (as in October 2011
  and October 2020).
- **Saturated ground** (1" or more of rain) combined with wind,
  **thunderstorms**, heat, and active **NWS alerts** such as High Wind, Ice
  Storm and Winter Storm.

At High or Very high, tap the panel for preparation steps tailored to what's
driving the risk and to whether your heat sources need electricity. Days with
nothing raising the risk show no panel at all.

### Frozen pipes

Shown on nights at or below 20°F, the point where pipes in outside walls and
unheated spaces start to freeze. The level rises with how cold it gets (10°F,
0°F), how many hours stay below 20°F, a day that never thaws, wind, and a high
outage risk the same day (no power usually means no heat). The steps cover
hoses and outdoor faucets, open sink cabinets, dripping faucets, thermostat
settings, heat tape, and what to do during a long outage.

### Black ice and frost

For each morning, from the hourly forecast where it reaches and the daily
forecast beyond it:

- **Frost** on windshields after clear, calm nights near or below freezing in damp air.
- **Black ice** from freezing rain, from rain or drizzle followed by a drop
  below freezing, or from daytime snowmelt that refreezes overnight.

### Aurora

NOAA's Space Weather Prediction Center publishes the planetary K-index (Kp)
for the past three days and the next three. It's the same for everyone, so no
location is sent. The app works out your geomagnetic latitude and how far south
the auroral oval reaches at each Kp, then checks when it's dark and how cloudy
the hourly forecast is. The card appears only when the aurora may be visible:
to a phone camera, low on the northern horizon, or overhead. In a strong storm
(Kp 7 and up) it also warns that GPS and satellite texting can be less reliable.

**Notifications** check the forecast every three hours for places with
notifications on. You get a heads-up up to a day ahead and another when it may
be visible now, at most once a night unless the outlook improves. Overcast
nights are skipped. The threshold (camera, low on the horizon, or overhead) is
in Settings › Today screen.

### Outdoor activities

Daily 0–100 scores, with reasons and recommendations, for hiking, camping
(overnight), off-roading, mountain biking, paddling and stargazing (overnight;
uses cloud cover and the moon's phase). Seasonal activities appear when they
make sense:

- **Winter, with snow on the ground** (nearest reported snow depth): downhill
  skiing (powder days, lift wind holds, rain on snow), cross-country skiing
  (kick-wax suggestions), snowshoeing and snowmobiling. Hiking, camping and
  biking become winter hiking, winter camping and fat biking; paddling and
  off-roading hide.
- **Ice fishing**, once lakes have likely frozen solid (estimated ice
  thickness, with a reminder to check it yourself).
- **Swimming** in summer, and **hunting** in fall and spring turkey season.

Each activity weighs what matters to it: heat and humidity, cold and wind
chill, rain and thunderstorms, gusts (and stronger summit winds), snow and ice,
trail mud and active warnings. Tips include sleeping-bag ratings for the
overnight low, microspikes or snowshoes, early starts before afternoon storms,
and New England notes such as black flies, ticks and blaze orange in hunting
season.

### Trail mud

A daily level (dry, damp, muddy, very muddy, frozen, snow-covered) from a soil
water balance run over the last 2–5 months of **observed** NOAA data (ACIS
gridded daily highs, lows and precipitation) and on through the forecast. It
models rain, snowmelt and drying, and the **spring thaw**: frost depth is
tracked from freezing and thawing degree-days, so mud season shows up while the
ground thaws from the top over still-frozen subsoil. Checked against Concord,
NH, in winter and spring 2026.

### Fall foliage

There's no free machine-readable foliage feed, so the color stage and peak are
estimated from latitude, elevation and this fall's observed night temperatures:
cool nights hurry color, a warm fall delays it, and hard freezes and drought
drop leaves early. Windy or very wet days that will strip leaves are flagged,
with links to the official Maine and New Hampshire reports.

### Garden

Pick your crops and how you'll start them. The schedule comes from your
**local frost dates**, computed once from 30 years of NOAA daily lows for your
spot (the median and the 1-in-10 dates).

- **Planting** (in planting season): start seeds indoors, harden off, plant
  out or sow. When a window opens, it looks for the first day whose coming
  nights stay above that crop's frost limit, and holds off for cold soil
  (estimated from recent temperatures).
- **Harvest** (in harvest season): ready dates from days to maturity, "pick
  before Thursday's frost" for tender crops, "cover plants tonight", and "frost
  will sweeten your kale". Garlic gets a fall planting reminder.
- **Extras**: frost and freeze notifications (NWS Frost Advisories and Freeze
  Warnings, even with advisories off), watering advice (last week's rain against
  about an inch a week), soil readiness, and growing degree days.

### Clothes drying

The best line-drying window in the next two days (warm dry air, a breeze, sun,
no rain) with an estimated drying time, plus a 7-day ranking. It never
recommends drying below freezing. **My laundry is out** checks the hourly
forecast every 20 minutes in the background for up to 12 hours, and notifies
you before rain, thunder or evening dew.

### Heating

Tell the app how you heat and when you use each source, for example a heat pump
above 15°F, the wood stove below 30°F and oil below 0°F. It shows which system
to run each day and the heating demand in degree days, plus chores timed to your
climate: pre-buying oil or propane, checking the tank before a cold snap,
buying and stacking firewood, pellets, service and chimney visits, protecting
pipes and clearing snow off heat-pump units.

### Allergy score

Tell the app what you're allergic to (for example birch, severe; grass, mild;
outdoor mold) for a personal daily 0–100 score:

- **Pollen** for that plant from the Google Pollen API where it's reported,
  otherwise its pollen type (tree, grass, weed) at a discount, marked as
  estimated. Pollen needs your Google key.
- **Outdoor mold**, which no free source measures, is estimated from season,
  humidity, recent rain, warmth and wet leaf litter, and drops under snow or
  frozen ground.
- **Air pollution** from the AQI forecast.
- **Weather**: steady rain washes pollen out, dry gusty wind spreads it, and
  thunderstorms during high grass or tree pollen bring an asthma warning.

### Maple sap

From mid-January through April, each day is rated from the night before's low
and the day's high. Sap runs best after a night in the 20s °F and a sunny day
in the 40s, and stops when nights stay above freezing. A stretch of warm nights
late in the season is flagged, since budding ends it.

### Burn days

A week of Good, Use caution and Don't burn days for brush and yard waste, from
wind and gusts, the day's lowest humidity, how long since meaningful rain (NOAA
observations plus the forecast), snow cover, and NWS Red Flag Warnings and Fire
Weather Watches. Rules vary by town and state, and many need a burn permit.

## Optional services

- **Air quality**: EPA **AirNow** (government monitors and state and local
  forecasts) with the free key from docs.airnowapi.org, otherwise Open-Meteo's
  CAMS model, which needs no key. Shows the main pollutant, a 4-day outlook, Air
  Quality Action Days and the forecaster's discussion.
- **UV index**: EPA's keyless hourly UV forecast by ZIP code (the ZIP is looked
  up on the phone). Open-Meteo fills in outside the US.
- **Pollen**: NOAA and the NWS don't forecast pollen, so the app can use the
  **Google Pollen API** with your own key (5-day tree, grass and weed levels and
  the main plants). Google gives 5,000 free requests a month, then charges
  US$10 per 1,000 (prices checked October 2026). The app asks at most every 6
  hours per place (about 124 requests a month), so typical use is free. The key
  is sent in a header, never in the URL. Coordinates are rounded to about 1 km,
  and requests carry the app's package name and certificate so you can restrict
  the key to this app.
- **AI**: used only where the app's own rules can't do the job. A summary
  that restated the forecast numbers (1.4–1.8) was removed: the screen already
  shows them. What's left:
  - *Forecasters' notes*: each NWS office's Area Forecast Discussion (the
    forecasters' reasoning: confidence, model disagreement, what could change)
    rewritten in plain words for your place. The app checks for a new
    discussion (about 1 KB) when the forecast updates, at most every 30
    minutes, and asks the AI only when the office has issued a new one, so a
    place costs 2–4 AI requests a day. The aviation section is dropped before
    sending. US places only.
  - *Explain* on each alert: the alert rewritten for your place, keeping its
    times and numbers, and saying when it covers only part of the area (the
    coast, higher elevations). Only when tapped; cached until the alert changes.
  - *Ask about the forecast*: your question answered from the next 48 hours
    and 7 days of your forecast (about 4 KB of compact lines). Not stored.

  Providers: **Claude** (Anthropic's API, called through Anthropic's official
  Java SDK; the default model is Claude Opus 5.5 and any model on your account
  can be picked; low effort for discussions and alerts, medium for questions;
  refusals fall back server-side on models that support it), or your own
  **Ollama, Open WebUI, LibreChat** (Agents API) or **OpenAI-compatible**
  server (LM Studio, vLLM, llama.cpp, LiteLLM, OpenAI and others). Reasoning
  tags (`<think>…</think>`) and markdown are removed from replies.
- **Traffic**: work zones come from state DOT feeds in the USDOT **Work Zone
  Data Exchange (WZDx)** standard, found in USDOT's public feed registry (New
  Hampshire, Vermont and Maine publish one together). Crashes, closures and
  incidents come from **New England 511** (NHDOT, MaineDOT, VTrans) with a free
  developer key. No government source publishes road speeds, so the live speed
  layer uses TomTom traffic tiles with your own key: the free tier is 50,000
  tiles a day, and one look at the map uses about 20–60.
- **NWS contact email** (optional) goes only into the User-Agent sent to
  weather.gov, as NWS asks.

## Wildfire smoke

NOAA's HRRR model carries a smoke tracer: near-surface smoke (MASSDEN, 8 m
above ground). The app finds the newest run that goes out 48 hours (00, 06, 12
or 18 UTC), downloads only that field for 3, 9, 15 and 24 hours ahead (about
480 KB each, by byte range, as for the future cast), decodes it on the phone
and reads the value at the place, between the four nearest grid points. The
level uses the EPA's PM2.5 scale, since smoke is mostly fine particles; the
card appears only from a light haze (5 µg/m³) up. Updated at most every 6
hours. The decoder was checked against ECMWF's eccodes on a real smoke field.

## Rivers and tides

- **Rivers:** the USGS Water Data API (the OGC service replacing the old
  WaterServices). The nearest stream gauge within about 30 km that reported
  flow or height in the last two days, with water temperature if it measures
  it, and its height change over the last six hours. Rising half a foot in six
  hours counts as rising fast.
- **Tides:** NOAA Tides & Currents. The nearest tide-prediction station within
  40 km, its next highs and lows, and water temperature at stations with
  sensors.

A measured water temperature replaces the paddling score's seasonal guess
(below 60 °F lowers it more the colder it is), and a fast-rising river lowers
it and adds a caution. About 15–30 KB per update, at most hourly.

## Notifications and widget

- **Morning briefing:** a one-off job booked for the chosen time and rebooked
  each day. It refreshes the forecast for the place on the Today screen
  (within Backwoods limits, otherwise the last forecast if under 12 hours old)
  and writes a few sentences: conditions with the high and low, when rain or
  snow is likely, strong gusts, and active alerts. Mini uses the same text,
  and its Read aloud key speaks it with Android's text-to-speech.
- **Rain starting soon:** every 15 minutes, for places with notifications on,
  Open-Meteo's 15-minute precipitation (from HRRR in the US, about 1 KB). If
  it's dry now and at least 0.1 mm per 15 minutes is due within the hour, one
  notification per event (a new one only three hours after the last). Silent
  from 10 PM to 6 AM.
- **Widget:** drawn from the last downloaded forecast whenever the app saves
  one, and refreshed in the background about hourly while a widget is placed.

## Backwoods mode

Backwoods mode limits downloads to what you allow. It turns on automatically
on a satellite connection, or on mobile data while Android's **Data Saver** is
on (even if the app has "Unrestricted data"). You can also set it to always or never.

- The manifest declares `android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED`,
  so on Android 16+ the app is allowed onto constrained satellite networks and
  is listed among satellite-enabled apps in system Settings.
- `ConnectivityMonitor` follows Google's guidance: it requests a network
  *without* `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED`, and treats it as
  constrained if that capability is missing or the transport is
  `TRANSPORT_SATELLITE`.
- In Settings › Backwoods mode you choose what may still download, with typical
  sizes: alerts, background alert checks, current conditions, an
  internet-reachable WeeWX station, the daily and hourly forecasts, forecast
  details, recent weather history, pollen, radar, the future cast, satellite
  imagery, the aurora forecast (about 7 KB) and the OpenWeatherMap fallback.
  Options are grouped (Forecast & alerts, Extras, Maps), with one switch per
  group and an expandable list of items.
- Anything turned off keeps its last data, but cards whose data is more than 12
  hours old (or missing) are hidden while limits are on, and so are whole tabs
  with nothing recent (Traffic, for example). **Show stale data** shows them
  anyway.
- Timeouts are generous for high-latency links.

## Screenshots

The screenshots in the README are rendered by the app's `ScreenshotTest` from
real NWS and NOAA ACIS responses for Portland, ME. The warning and AI
examples use sample data. Run `./gradlew :app:testDebugUnitTest` to
render them into `app/build/outputs/screenshots`.

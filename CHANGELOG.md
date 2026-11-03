# Changelog

All notable changes to Northvane. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [1.4.4] - 2026-11-03

### Changed
- In an opened day of the 10-day forecast, the power outage outlook now comes after the day and night forecast text.

## [1.4.3] - 2026-11-02

### Changed
- Releases are now signed with Northvane's permanent release key, so future versions install as updates. This build is the regular `app.northvane.weather` app; earlier GitHub downloads were debug builds that install as a separate "debug" app, which you can uninstall once you've set this one up.
- Release builds are no longer shrunk with R8 until a shrunk build has been tested on a device.

## [1.4.2] - 2026-11-02

### Changed
- Backwoods mode now also turns on automatically when Android's **Data Saver** is on and you're on mobile data, even if Northvane is set to "Unrestricted data". It stays off on unmetered Wi-Fi, where Data Saver doesn't apply, and "Never" still means never.

### Fixed
- A repository test that could fail at random because the fake network recorded parallel requests unsafely.

## [1.4.1] - 2026-10-29

### Changed
- Satellite data saver is now called **Backwoods mode**.
- The power outage outlook is off by default (turn it on in Settings › Today screen).
- Northvane is now open source under the **GNU GPL v3.0 or later**.

## [1.4.0] - 2026-10-29

### Added
- **Canada**: forecasts, current conditions, hourly forecasts and public alerts from Environment and Climate Change Canada (MSC GeoMet, free and keyless) for places outside NWS coverage, including background alert notifications.
- **AI summary** on the Today screen, under current conditions: a short plain-language briefing written by your own **Ollama**, **Open WebUI**, **LibreChat** (Agents API) or **OpenAI-compatible** server from the forecast. Model picker, test button, key stored encrypted, plain HTTP allowed only on the local network and never with a key; at most one summary per forecast download (45 minutes minimum).
- **Outlook tab**: activity scores, trail mud, garden, clothes drying, heating season and fall foliage moved off the Today screen into their own tab.
- **Show stale data** (Settings › Satellite & data saver): with satellite limits on, cards and whole tabs (Radar, Traffic, Alerts, Outlook cards) whose data is over 12 hours old, or missing, are hidden unless you turn this on.
- **Location reordering** with up/down arrows in Locations.
- **Heating aggressive mode**: switch systems day by day and at night; otherwise one recommendation from the two-week average temperature.
- **Heating types**: wood stove/fireplace (no power), wood boiler/furnace, pellet stove and pellet boiler/furnace (need power).
- A few hidden Easter eggs.

### Changed
- **Settings** reorganized into pages (Appearance, Today screen, Outlook, Alerts, Radar & traffic, Satellite & data saver, Data sources, AI summary, About) with dropdowns; satellite options are grouped (Forecast & alerts, Extras, Maps) with one switch per group and an expandable list.
- **Garden**: one card listing each plant with its current planting step and harvest outlook, instead of separate planting and harvest cards.
- **Fall foliage** shows only while leaves are changing.
- **Pollen** card appears only when a Google Pollen API key is set.
- **Trail mud** and **clothes drying** are now off by default.
- Activity scores and trail mud no longer appear in the daily forecast details (they're on the Outlook tab); the outage panel stays.

### Fixed
- Environment Canada sometimes reports a wind chill on mild days; it's now only used at 10°C or colder.

## [1.3.0] - 2026-10-29

### Added
- **Garden** (off by default): choose what you're growing (30 vegetables, herbs, fruit and flowers) and how (start seeds indoors, buy transplants, sow outdoors). A **planting** card shows when to start seeds indoors, harden off, and plant out or sow, timed to your local frost dates (from 30 years of NOAA daily lows) and confirmed against the forecast: no frost below each crop's limit in the coming nights, and warm enough soil. A **harvest** card shows when crops should be ready, what to pick or cover before frost, and which crops get sweeter after a frost. Garlic gets a fall planting reminder. Each card appears only in season. Optional extras: frost & freeze notifications (even with advisories off), watering advice, soil readiness and estimated soil temperature, and growing degree days.
- **Clothes drying**: the best line-drying window for the next two days with an estimated drying time, a 7-day ranking, and never below freezing. Tap "My laundry is out" to get a notification before rain or thunder (and evening dew).
- **Heating season** (off by default): list your heat sources and the outdoor temperatures you use each in. The card shows which system to run each day this week, heating degree days, and seasonal chores timed to your climate: fuel pre-buys, seasoned wood, next year's wood stacking, pellets, service and chimney sweeps, cold-snap tank checks, pipes and heat-pump care.
- **Winter activities**, shown only with confirmed snow on the ground: downhill skiing (powder days, wind holds, icy surfaces), cross-country skiing (with kick-wax suggestions), snowshoeing, snowmobiling, and ice fishing once lakes have likely frozen solid (with an estimated ice thickness and safety guidance).
- **Swimming** in summer and **hunting** during fall and spring turkey seasons.
- **Outage preparation**: tap a High or Very high outage risk for steps to get ready, including which of your heat sources need power.

### Changed
- Hiking, camping and mountain biking become **winter hiking**, **winter camping** and **fat biking** in winter, with winter gear advice; paddling and off-roading hide while there's snowpack.
- The hourly card shows just temperature and precipitation by default; tap it for everything else. It remembers your choice.
- The power outage panel now comes after the activity scores in each day's details.
- The personal allergy forecast moved into the pollen card and appears only when pollen data is available.

## [1.2.0] - 2026-10-29

### Added
- **Personal allergy score**: pick your allergens (birch, oak, maple, grass, ragweed, outdoor mold, air pollution and more) and how strongly you react; get a daily 0–100 score with the main culprits and tips. Pollen comes from the Google Pollen API (plant-level where reported), mold is estimated from season, humidity, rain and leaf litter, and rain, dry wind, thunderstorms and poor air quality adjust the score.
- **Air quality (AQI)** card: EPA AirNow monitors and forecasts with your free key, otherwise Open-Meteo's CAMS model (no key).
- **UV index** card: EPA's hourly UV forecast for your ZIP code (no key), with today's peak and sun-protection advice.
- **Themes**: 7 static themes (Northvane, Harbor, Evergreen, Maple, Lupine, Aurora, Driftwood), 2 weather themes that change with conditions (Live sky, Temperature), and a Custom theme with your accent color and a gradient or your own photo as the background.
- **Traffic tab** (can be turned off): work zones from state DOT WZDx feeds found through USDOT's registry (no key; NH/VT/ME publish one), crashes and closures from New England 511 (free key), and an optional live speed layer from TomTom (your key; free tier explained in Settings).

### Changed
- Paddling scores 0 when it has been below freezing long enough for lakes to ice over or rivers to carry ice, and stays there until spring ice-out; shore ice lowers the score earlier.

## [1.1.0] - 2026-10-29

### Added
- **Outdoor outlook**: daily 0–100 scores with reasons and recommendations for hiking, camping (overnight), off-roading, mountain biking, paddling and stargazing (cloud cover and moon phase). Choose which activities to show.
- **Trail mud** level from a water-balance model over observed NOAA (ACIS) rain and temperatures, including spring thaw / mud season, snowmelt and frozen ground.
- **Fall foliage** estimate: color stage, estimated peak, this year's shift from observed temperatures, and leaf-stripping wind/rain days.
- **Pollen** forecast from the Google Pollen API with your own key, with the cost explained in Settings (5,000 free requests/month; at most one request per place every 6 hours).
- **Satellite imagery** on the radar map (GOES visible/infrared/water vapor from NOAA nowCOAST) and a pin for the selected location.
- **Location nicknames** and **per-location alert notifications**.
- The Today screen turns **red while an NWS warning is in effect** (watches, advisories and statements unchanged).
- Satellite settings for recent weather history, pollen and satellite imagery.

### Changed
- The hourly forecast is one tall card with every value (feels-like, precipitation chance and amount, wind, gusts, humidity, dew point, clouds) instead of switchable chips.
- WeeWX stations now have a position and radius and serve every saved place within it, instead of being paired with one location (existing pairings keep working until edited).
- The power outage outlook is season-aware: leaf cover follows estimated leaf-out and fall foliage for the location, frozen ground lowers uprooting risk and thawing ground raises it.

## [1.0.0] - 2026-10-29

First release.

### Weather
- Current conditions, 48-hour hourly forecast, 7-day daily forecast from the National Weather Service, with no API key.
- Real numbers in forecasts: gusts, wind direction, rain/snow/ice amounts, humidity, cloud cover, feels-like and thunder chance (NWS gridpoint data).
- Optional power outage outlook per day, tuned for New England (gusts with leaf-on season, ice, wet snow, saturated ground, thunderstorms, NWS alerts).
- Sun card with the sun's path, time to sunrise/sunset, day length and change since yesterday.
- Animated radar: NOAA MRMS (or NEXRAD via the Iowa Environmental Mesonet) plus HRRR model future cast.

### Sources
- WeeWX station support (Northvane JSON template, `realtime.txt`, `clientraw.txt`) that overrides current conditions for a linked location.
- Optional OpenWeatherMap fallback using your own API key, stored encrypted.

### Alerts
- Background NWS watch/warning/advisory notifications per saved location, with separate channels and a test notification button.

### Satellite
- Opts in to Android 16+ constrained satellite networks (T-Satellite and similar).
- Per-feature control over what is allowed over satellite, with typical data sizes.

### Privacy and security
- HTTPS-only for all built-in services; plain HTTP only for local-network WeeWX servers, never with credentials.
- Keystore-encrypted secrets, no backups, no analytics or trackers.
- Precise or approximate location, foreground only.

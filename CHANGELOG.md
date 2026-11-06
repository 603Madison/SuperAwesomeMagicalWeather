# Changelog

All notable changes to SuperAwesomeMagicalWeather (called Northvane before 1.7.0). The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [2.0.1] - 2026-11-06

### Changed
- **AI replies are short and scannable.** Forecasters' notes are one headline with a High/Medium/Low confidence chip; the supporting points are behind **More**. Alert explanations are one line plus at most two things to do, and answers are one or two sentences. The "written by AI" note is a small "✦ AI · 3 min ago" (the full note is read out by TalkBack).
- The AI cards use the same card style as the rest of Today, with the ⋮ menu to move or hide them.

### Fixed
- **If it's in the app, the AI can use it.** Questions now also get trail mud, recent rain and snow on the ground, fall foliage, UV and air quality right now, sunrise and sunset, the National Weather Service's own forecast wording, each activity's best hours and what decides its score, the app's headline and 15-minute rain, frost dates, and what you've set up: your allergy score, garden (planting, harvest, frost nights, watering), heating, clothes drying, maple sap and burn days. The model is told never to say the app doesn't track something it does.

## [2.0.0] - 2026-11-06

A polish release: Today shows less, louder when it matters, and the traffic feature is gone.

### Added
- **Headline** under the temperature on what matters next: "Rain in about 20 min", "Snow from 3 PM, about 4 in", strong gusts, frost after a mild day, a big change in tomorrow's high. Written by the app, no AI. The 15-minute rain forecast it uses is about 1 KB.
- **At a glance:** air quality, UV, pollen and sunset sit as small chips under the top card; tap one for its full card. Each turns into a full card by itself when it matters (AQI over 50, UV 6 or more, high pollen).
- **Arrange Today your way:** every card has a ⋮ menu to move it, hide it (with undo) or open its settings, and Settings › Today screen lists the cards to drag into order.
- **Best time to go** on the Outlook tab: the best few hours today and tomorrow for each activity ("Best 1–4 PM · dry, 59°"), plus what decides the score. Pick your activities right on the tab.
- **Widgets in three sizes:** small (temperature), medium (adds the headline) and large (adds the next five hours), in your wallpaper's colors with a dynamic theme.
- **Welcome screen:** pick units and what you care about (outdoors, rivers & tides, garden, heating, laundry, pollen, northern lights), and the matching cards turn on.
- Saved places can be dragged into order (long-press).

### Changed
- One card style everywhere, with the title inside the card.
- Today's details (humidity, dew point, pressure, visibility…) moved into the top card behind **Details**; the sun card opens from the sunset chip.
- The hourly card hides its precipitation row when no hour has a real chance.
- Outlook is grouped into Outdoors, Home and Seasonal, and starts with four activities (hiking, mountain biking, paddling, stargazing); add more with "Choose activities".
- Trail mud is no longer a separate card: it counts in the hiking, biking and off-roading scores.
- Settings are split into "What you see" and "Data & privacy"; Backwoods mode's explanation is two lines.
- Saved places show their name, not coordinates.
- Plainer words in a few places: heating demand instead of degree days, "the app" instead of the full app name mid-sentence, the weather station's own name in the top card.
- The pollen card's "choose your allergies" prompt can be dismissed.

### Removed
- **Traffic** (the tab, its settings and its New England 511 and TomTom keys). Saved traffic keys are deleted on first start.
- The "hourly details open by default" setting (the card remembers how you left it).

## [1.9.2] - 2026-11-06

### Fixed
- **Ask about the forecast knows what the app knows.** Questions used to get only the forecast, so "when are we getting northern lights?" was answered with "the forecast doesn't cover that" right above the aurora card. Questions now also get the app's own outlooks: northern lights (Kp, best time, clouds), storm totals, wildfire smoke, air quality and pollen forecasts, the nearest river and tides, power outage and frozen pipe risk, frost and black ice, and activity scores.

## [1.9.1] - 2026-11-06

### Fixed
- **Forecasters' notes, alert explanations and questions now use your town.** For the "Current location" place, the AI was told the place was literally "Current location", so it described the whole forecast area and said it didn't know where you are. It now gets the National Weather Service's name for the spot (for example "Gray, ME"), never a nickname.
- **Shorter forecasters' notes:** at most four sentences plus a one-line confidence, keeping only what applies to your part of the area (coast, foothills or mountains). Alert explanations and answers are shorter too.
- Notes and alert explanations are now kept per place, so two places covered by the same weather office no longer share one write-up.

## [1.9.0] - 2026-11-06

AI where it's useful, and Claude as a provider.

### Added
- **Claude** as an AI provider: add an Anthropic API key in Settings › AI and pick any model on your account (Claude Opus 5.5 is suggested; Haiku costs a small fraction as much). Uses Anthropic's official SDK.
- **Forecasters' notes:** the National Weather Service forecasters' own discussion for your area, in plain words: what they expect, how confident they are and what could change. Rewritten only when they issue a new one (2–4 times a day), with a link to the original.
- **Explain** on each alert: the alert rewritten for your place, keeping its times and numbers. Only when you tap it.
- **Ask about the forecast:** a question box on Today ("Can I paint the deck Saturday?"), answered from your 48-hour and 7-day forecast.
- Each AI use has its own switch in Settings › AI, and all of them stay off until AI is set up.

### Removed
- The AI summary at the top of Today. It only restated numbers already on the screen.

### Changed
- **On by default:** power outage outlook, frozen pipe risk, black ice & frost, storm totals, wildfire smoke and rivers & tides (aurora forecast and fall foliage already were). Anyone who hasn't touched these switches gets them on; a switch you've turned off stays off. Each can still be turned off in Settings, and wildfire smoke stays off in Backwoods mode unless allowed there.

## [1.8.0] - 2026-11-06

Six new features, each with its own switch and off by default (the widget is on when you place it).

### Added
- **Home-screen widget:** temperature, conditions, high and low and the next rain for the place on your Today screen, on its sky colors (red during a warning). Updates with the app and about hourly in the background.
- **Rain starting soon:** a heads-up up to an hour before rain or snow starts, from Open-Meteo's 15-minute forecast (about 1 KB every 15 minutes), for places with notifications on. One per event, never 10 PM–6 AM. Settings › Alerts & notifications.
- **Morning briefing** in both apps: today's forecast in a few sentences at the time you pick. Mini can also **read it aloud** (key 7) with the phone's own voice.
- **Wildfire smoke** card from NOAA's HRRR smoke model, shown only when there's smoke in the forecast, with what to do (about 2 MB at most every 6 hours). Settings › Today screen.
- **Rivers & tides** card on the Outlook tab: the nearest USGS river gauge (flow, height, water temperature, rising or falling) and NOAA tide times. A measured water temperature replaces the paddling score's seasonal guess, and a fast-rising river lowers it. Settings › Outlook.
- **Storm totals** card when a big storm is coming: snow, ice and rain totals, start and end, and the heaviest hours, from the NWS forecast. Settings › Today screen.
- Each download has its own item in Backwoods mode.

### Improved
- **Future cast handover:** the model often has rain in the wrong place where the forecast begins, so rain jumped or vanished after the last radar frame. The latest radar is now carried along its measured motion and handed over to the model over two hours.

## [1.7.3] - 2026-11-06

Fixes from a full review of the code.

### Fixed
- **Canadian forecasts:** between midnight and Environment Canada's morning update, every day was dated a week ahead. Dates now count from when the forecast was issued.
- **Garden crash:** places whose last spring frost fell on Feb 29 in a leap year crashed the app when the garden or heating card was on.
- **Current conditions:** a station that stopped reporting days ago was shown as current, and stopped OpenWeatherMap (with your key) from being used. Readings over 6 hours old now give way to the forecast estimate, and the "updated" time is when it was measured.
- **Missed alerts:** while notifications were blocked, alerts were still recorded as sent, so they never appeared after you allowed them. Both apps now keep them until they can be shown. Mini also asks for permission on Android 13+.
- **Notification taps:** after rotating the phone, the app jumped back to the place and tab of an old notification. Aurora notifications now open the place they're about.
- **Traffic:** switching places while traffic was loading could show the previous place's events.
- **Removed places:** a place removed while it was refreshing could come back.
- **Saved keys:** a passing Android Keystore error could delete your saved API keys and passwords.
- **Air quality times** were an hour off in summer (AirNow reports standard time) and around daylight-saving changes (Open-Meteo).
- **Work zones:** one bad coordinate in a state's feed hid all of its work zones.
- **Mini:** a refresh for the previous place could show under the new one, "Use my location" could add the same place twice, the radar loop cancelled its own downloads on slow connections, and old cached hours showed as current.

### Improved
- **Radar:** future-cast frames are drawn off the main thread (no more pause opening Radar), and decoding the model data uses about 15 MB less memory per frame.
- **Mini on low-memory phones:** radar pictures use half the memory and at most 8 MB in all, and are freed when Android needs memory. Location asks every location source at once, so phones without Google services get a fix from GPS.
- **Less data:** no more 150-day history requests for places outside the contiguous US, and Canadian places' background alert checks go straight to Environment Canada.
- **Mini settings** are written atomically, survive a full disk without crashing, and a damaged file is kept as `settings.json.bad` instead of being overwritten.

### Security
- The F-Droid repository job installs only hash-pinned packages, gets only the secrets it needs, and can't publish to Pages itself.
- Release builds can't read the workflow's GitHub token, every APK is checked against the fixed release certificate, and a published release can only be rebuilt from its own tag.
- Work-zone feeds from the public USDOT registry can't point the app at an IP address or a local network name.

## [1.7.2] - 2026-11-06

### Changed
- **Mini looks like the main app.** It uses the main app's blue theme in light and dark, and the same soft background behind rounded cards. Current conditions sit on the main app's sky-colored card, with its weather icon, and the card turns red during a warning. Hourly and 7-day rows have weather icons, and 7-day has the same temperature range bars. Menu shortcuts are blue key badges, and alerts are marked in the main app's alert colors. Keypad navigation is unchanged.
- The weather icons, sky colors and brand colors now live in a module shared by both apps (`ui/`), so they stay in step.

### Added
- **Own F-Droid repository** on GitHub Pages, updated after every release with the same signed APKs as the GitHub releases. Add it in F-Droid with the address and fingerprint in the README (`docs/FDROID.md`).

## [1.7.1] - 2026-11-06

### Fixed
- **Backwoods mode said "Android Data Saver is on" when it wasn't.** Android reports Data Saver and a switched-off "Background data" for the app the same way, and the app took both as Data Saver. Now only a certain Data Saver is called that. For the other case the banner says what Android actually reports, with an **App data settings** shortcut and an **Ignore this** button; the same choice is in Settings › Backwoods mode.
- The list of paused items no longer writes "AI" as "ai".

### Added
- **Ready for F-Droid**: store listings in the fastlane layout, build recipes in `fdroid/`, and no Google-encrypted dependency block in the APKs. See `docs/FDROID.md`.

## [1.7.0] - 2026-11-03

### Changed
- **Northvane is now SuperAwesomeMagicalWeather**, and Northvane Mini is SuperAwesomeMagicalWeather Mini: app names, screens, notifications, release downloads and docs. It installs as an update over Northvane (same package and signing key), and your places and settings stay. On Mini's home screen the place name is now the title, since the full app name doesn't fit next to it on a flip phone.
- **A shorter, clearer README**, organized by screen with feature tables and fresh screenshots. The technical details moved to `docs/HOW-IT-WORKS.md`.

### Security
- **LAN servers can't redirect you onto the internet in plain text.** The connection used for a WeeWX station or AI server on `http://` can now only reach private-network addresses, including after a redirect, and never goes through a proxy.
- **Damaged downloads can't crash the app.** The future cast's GRIB2 decoder checks every size and bit width before allocating memory, and radar pictures are checked for size before decoding (in both apps).
- **Release workflow hardening.** Inputs are checked and passed only through environment variables (no command injection), GitHub Actions are pinned to commit hashes with Dependabot updates, and the signing key is restored only after the tests.

## [1.6.2] - 2026-11-03

### Fixed
- **Future cast haze.** Calibration was lifting the model's weakest values (below 0 dBZ: cloud, virga and the soft fringe between grid points) into pale "rain", which spread a grey-lavender haze and flattened the forecast. Those values now never become rain, and the correction is capped at ±10 dBZ. The forecast keeps the radar's rain area and colors without the haze.

## [1.6.1] - 2026-11-03

### Changed
- **Future cast calibrated to the radar.** Each model run is matched to the newest radar picture by quantile mapping (same amount of rain, same mix of light and heavy) and that correction carries through its forecast, capped at ±15 dBZ. Nothing is moved: where rain falls is still the model's forecast. The radar caption says when frames are calibrated.

## [1.6.0] - 2026-11-03

### Added
- **Northvane Mini**, a separate app for Android flip phones (Northvane-Mini APK in each release). Every action works from the d-pad, OK, soft keys and number keys, with or without a touchscreen; compact screens for current conditions, hourly, 7-day, alerts (with notifications), places (search by town or ZIP, or GPS), settings and a lightweight radar (IEM NEXRAD map pictures, ◀▶ time, ▲▼ zoom, 2468 pan).

### Fixed
- **Future cast**: uses the model's composite reflectivity, which matches the radar far better than reflectivity 1 km above ground (it was dropping rain the radar sees aloft far from the radars), takes each frame from the newest run that has published it instead of waiting for one run to cover every frame, and draws at twice the resolution so frames no longer look blocky.

## [1.5.3] - 2026-11-03

### Changed
- **Future cast from real model data.** The extrapolated "nowcast" from 1.5.2 is gone. Forecast frames now come from NOAA's HRRR model read directly from NOAA's open data: the newest run (about an hour old instead of two to three), the model's simulated radar 1 km above ground at 3 km resolution, decoded on the phone and drawn in the same colors as the past radar. Frames start right after the newest radar scan and show which run they're from. Iowa Environmental Mesonet tiles remain as a fallback.

## [1.5.2] - 2026-11-03

### Changed
- **Better future cast.** The first 90 minutes now come from a radar nowcast: Northvane measures how the rain moved over the last half hour and carries the latest radar forward, instead of jumping to the HRRR model, which often misses light rain entirely. Model frames follow beyond 90 minutes and are labeled **Model**; nowcast frames are labeled **Nowcast**.

## [1.5.1] - 2026-11-03

### Added
- **Aurora notifications**: a heads-up up to a day before the northern lights may be visible from your saved places, and another when they may be visible right now. At most once per night unless the outlook improves; overcast nights are skipped. Choose camera, horizon (default) or overhead in Settings › Today screen. Checks NOAA every 3 hours; Backwoods mode can allow or block it with the aurora forecast.

### Changed
- The power outage outlook only appears in a day's details when something raises the risk (wind, ice, snow, thunderstorms, soaked ground, heat or an NWS alert), not on quiet days.

## [1.5.0] - 2026-11-03

### Added
- **Aurora forecast** (on by default): a Today card that appears only when the northern lights may be visible from your location, from NOAA's Space Weather Prediction Center K-index forecast, darkness and cloud cover. It warns when a strong geomagnetic storm may affect GPS and satellite texting.
- **Frozen pipe risk** (off by default): in each day's details on nights near 20°F or colder, with steps to protect your pipes, including what to do if the power goes out.
- **Black ice and frost** (off by default): each morning's chance of frosty windshields and icy roads, from freezing rain, wet roads freezing, and snowmelt refreezing.
- **Maple sap runs** (off by default): an Outlook card rating freeze-thaw days for tapping, mid-January through April.
- **Burn days** (off by default): an Outlook card with good, caution and no-burn days from wind, humidity, dry spells, snow cover and NWS fire weather alerts.
- Backwoods mode can allow or block the aurora forecast (blocked by default).

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

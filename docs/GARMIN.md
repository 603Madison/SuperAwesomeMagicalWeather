# SuperAwesomeMagicalWeather on a Garmin watch

A small Connect IQ watch app with a glance, in [`garmin/`](../garmin). It
gets its forecast from Open-Meteo and its alerts from the National Weather
Service, through the Garmin Connect app on your phone. No account and no key
are needed.

## What it shows

**Glance** (the list you scroll from the watch face):

```
52° Mostly cloudy
Rain ~3:40
```

The second line is, in this order: a warning in effect ("Winter Storm
Warning"), rain or snow in the next hour ("Rain ~3:40", "Snow until ~4:15"),
or the headline ("Gusts to 40 mph around 3 PM", "Frost tonight, low 30°",
"Dry for the next 12 hours").

**The app** (press Start on the glance, or open it from the apps list). Up
and down, or swipe on a touch screen, move between pages:

1. **Now**: temperature, condition, today's high and low, the headline, the
   next hour of rain, the alerts in effect, and when it last updated.
2. **One page per NWS alert**: the event and when it ends. Press Start (or
   tap) to read the NWS headline; up and down scroll it.
3. **Next hours**: temperature and chance of rain for the next 8 hours.
4. **5 days**: condition, high and low, chance of rain.

Start on any other page updates now. **Menu** opens the settings (hold Up
on five-button watches; on Venu and vivoactive, whatever the watch uses for
an app's menu, usually holding a button):

- **Location**: the watch's GPS (its last known position) or a saved place.
- **Set place**: type a town ("Portland, ME", "Portland, Maine") or
  latitude,longitude ("43.66,-70.26").
- **Units**: as the watch is set, °F and mph, or °C and km/h.
- **Background refresh**: every 30 minutes (default), every hour, or off.
  This keeps the glance current without opening the app.
- **Warning pop-ups**: when the background refresh finds a new NWS warning,
  the watch asks whether to open the app. Once per warning.

The headline uses the same rules as the phone app's, cut down to one short
sentence: rain or snow first, then gusts of about 35 mph or more, frost after
a mild day, a big change in tomorrow's high, and otherwise "Dry for the next
12 hours".

## Building it and putting it on your watch

Garmin only gives out the files that describe each watch model after you sign
in with a Garmin account, so the app is built on your own computer (Windows,
Mac or Linux). You do this once, and again after each change.

1. Install [Visual Studio Code](https://code.visualstudio.com) and, in it, the
   **Monkey C** extension by Garmin (Extensions, search "Monkey C").
2. Install the **Connect IQ SDK Manager** from
   <https://developer.garmin.com/connect-iq/sdk/>. Open it, sign in with your
   Garmin account, download the latest SDK (9.3.0 or newer) and make it the
   current one. Then on the **Devices** tab download your watch.
3. In VS Code, press Ctrl+Shift+P (Cmd+Shift+P on a Mac) and run
   **Monkey C: Generate a Developer Key**. Save it somewhere outside this
   folder, or inside `garmin/` (git ignores `*.der` there). Use the same key
   for every build. Never commit it.
4. **File › Open Folder…** and pick the `garmin` folder of this repository.
5. Ctrl+Shift+P, **Monkey C: Build for Device**, pick your watch and an
   output folder. You get a `.prg` file, for example `garmin.prg`.
6. Connect the watch to the computer with its USB cable. Copy the `.prg` into
   the watch's **GARMIN/APPS** folder. On a Mac, use
   [OpenMTP](https://openmtp.ganeshrvel.com) if the watch doesn't show up in
   Finder. Unplug the watch.
7. The app is now in the watch's apps list. To add the glance: from the watch
   face, scroll to the glances, then **Edit** (or hold Up) › **Add** ›
   SuperAwesomeMagicalWeather.

The first time it opens, the app asks the watch for a GPS position if it has
none yet; go outside if that takes long. The phone needs Bluetooth on and the
Garmin Connect app running.

To update, build again and copy the new `.prg` over the old one.

### Settings in the Garmin Connect app

Garmin Connect only shows an app's settings when the app came from the
Connect IQ store. A copied `.prg` is set up with the watch menu above. If you
want phone settings and automatic updates, upload the app as a private beta
in the Connect IQ store (Garmin's developer site, "Beta Apps"); only your
account can see it.

### Try it in the simulator first

Ctrl+Shift+P, **Monkey C: Run**, picks a device and opens the simulator.
**Simulation › Trigger Background Event** runs the background refresh, and
**Settings › Glance** shows the glance (the exact menu names vary a little by
SDK version).

### From the command line

With the SDK's `bin` folder on your PATH:

```bash
cd garmin
monkeyc -f monkey.jungle -d fenix847mm -y /path/to/developer_key.der -o bin/Weather.prg
monkeyc -f monkey.jungle -d fenix847mm -y /path/to/developer_key.der -o bin/Tests.prg -t
connectiq &                  # the simulator
monkeydo bin/Tests.prg fenix847mm -t
```

The device names are in `garmin/manifest.xml` (fenix 8 47 mm is
`fenix847mm`, Forerunner 265 is `fr265`, Venu 3 is `venu3`, Instinct 2 is
`instinct2`). The second line builds the unit tests in `source/Tests.mc`
(dates, alert parsing, the rain line and the headline); `monkeydo … -t` runs
them in the simulator. In VS Code, the test tube icon does the same.

A developer key can also be made with OpenSSL:

```bash
openssl genrsa -out developer_key.pem 4096
openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem -out developer_key.der -nocrypt
```

## Watches

The manifest lists recent watches: Forerunner 165, 255, 265, 570, 955, 965
and 970; fenix 7, 7 Pro, 8, 8 Pro and E; epix (Gen 2) and epix Pro; Enduro 3;
MARQ (Gen 2) and (Gen 3); Venu 2, 3, 4 and X1; vivoactive 5 and 6; Instinct
2, 3, E and Crossover. Everything is drawn to fit round, Instinct's
semi-octagon (its little round window shows the temperature or the page
number) and the Venu X1's rectangle, and black-and-white Instinct screens get
white text instead of colors.

On Instinct 2, 2S, 2X and Instinct Crossover, Garmin only lets widgets show a
glance, so open the app from the apps list there.

## How it works

- **Forecast**: one request to `api.open-meteo.com` for exactly what the
  screens show (current conditions, 12 hours, 5 days and the next hour in 15
  minute steps), about 1.5 KB. Watches have little memory for web responses;
  larger answers fail with error -402.
- **Alerts**: `api.weather.gov/alerts/active?point=…`, US only. Elsewhere the
  app shows no alerts. Every alert is a few KB, so a long list can be too big
  for the watch; then the app says "Alerts unavailable" and keeps working. If
  the phone won't pass the NWS response on as JSON, the app asks for it as
  plain text and reads the few fields it needs.
- **Place names** are looked up once with Open-Meteo's geocoder and saved.
- **Background refresh**: a Connect IQ temporal event every 30 minutes saves
  fresh data for the glance. It uses the last position the app saw (the
  background can't use GPS) or the saved place.
- Times are shown in the watch's time zone.

### Why GitHub Actions doesn't build the `.prg`

`monkeyc` refuses to build without the device files, and Garmin serves those
only to a signed-in Garmin account. Keeping a Garmin password, or a zip of the
device files, in the repository's secrets isn't a good idea, so the
**Garmin** workflow only checks what it can: it downloads the same pinned SDK
(checking its SHA-256), validates the resource files against the SDK's schema
and makes sure every watch in the manifest is one the SDK knows. Then it notes
that the `.prg` has to be built on a computer as above.

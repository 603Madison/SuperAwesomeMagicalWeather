# Security and privacy design

This summarizes the measures in the app and where to find them in the code.

## Permissions (least privilege)

| Permission | Why | Notes |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Fetch weather; detect satellite/constrained networks | |
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | "Use my location" | Requested together only when the user taps it, so the system dialog lets them choose precise or approximate. Foreground only; never background location. |
| `POST_NOTIFICATIONS` | Alert notifications (Android 13+) | Requested once, after a location exists |
| `ACCESS_LOCAL_NETWORK` ("Nearby devices") | Reach a WeeWX server on the user's LAN (enforced on Android 17+) | Requested only when a station URL resolves to a local-network address. Without it, the station is skipped with an explanation instead of a hung connection. |

`ACCESS_WIFI_STATE`, which the MapLibre SDK would otherwise merge in, is
removed with `tools:node="remove"`. Positions are rounded to 4 decimal places
(about 11 m) before leaving the location provider, and are sent only to the
weather services described below.

## Network

- **Built-in services are HTTPS only**, pinned to system CAs in
  `res/xml/network_security_config.xml` (weather.gov, noaa.gov, iastate.edu,
  openfreemap.org, openweathermap.org). Built-in endpoint URLs are constants.
  URLs returned by weather.gov are followed only if they stay on the API host
  (`NwsClient.requireNwsUrl`).
- **User-configured servers (WeeWX)** are validated by `UrlPolicy`:
  - only `http`/`https`, no credentials embedded in the URL;
  - plain `http` only for private-network addresses (RFC 1918, loopback,
    link-local, CGNAT/Tailscale, IPv6 ULA);
  - hostnames used over `http` must *resolve* to private addresses
    (`PrivateNetworkDns`), which defeats DNS rebinding;
  - credentials (Basic auth, tokens, custom headers) are **never** sent over
    `http`.
- `CleartextGuardInterceptor` is a final in-app check on every request. An
  `http://` request must be explicitly tagged as a local-server request and
  must not carry an `Authorization` header.
- The app targets API 37, so **Certificate Transparency** is enforced and
  **Encrypted Client Hello** is used where servers support it.
- Redirects between http and https are never followed (`followSslRedirects(false)`).
- Response bodies are size-capped (512 KB from user servers, 4 MB otherwise).
- The platform config permits cleartext only in the `base-config`, which
  applies to user-configured hosts. That is the only way Android allows
  `http://192.168.x.x`, and the app-level checks above narrow it further.
- User-installed CAs are trusted only for user-configured hosts, so a
  self-hosted WeeWX server with a private CA can use HTTPS. The built-in
  weather services never trust user CAs.
- Lint flags these two `base-config` allowances (`InsecureBaseConfiguration`,
  `AcceptsUserCertificates`). They are suppressed with `tools:ignore` in
  `network_security_config.xml`, with the reasoning above recorded inline.

## Secrets

- The OpenWeatherMap, Google Pollen, EPA AirNow, New England 511 and TomTom keys and WeeWX passwords/tokens are encrypted with
  AES-256-GCM under a non-exportable **Android Keystore** key
  (`SecretStore`). The entry name is bound as associated data.
- Secrets are write-only in the UI: screens show whether a value is saved,
  never the value itself. Credential screens set `FLAG_SECURE`, which blocks
  screenshots, screen recording and the recent-apps thumbnail.
- The OpenWeatherMap key travels as a query parameter (OWM's API requires it).
  Those requests are sent with `Cache-Control: no-store`, so the URL is never
  written to the HTTP disk cache.
- The Google Pollen API key is sent only in the `X-Goog-Api-Key` header, never
  in the URL, with `Cache-Control: no-store`. Requests also carry the app's
  package name and signing-certificate SHA-1 (`X-Android-Package` /
  `X-Android-Cert`, both public), so users can restrict their key to this
  app in Google Cloud Console. Settings recommends restricting the key to the
  Pollen API and setting a daily quota so a leaked key can't run up a bill.
- No secrets, URLs or coordinates are logged, and R8 strips `Log.v/d/i` from
  release builds.

## Data at rest and backups

- `android:allowBackup="false"`, and `data_extraction_rules.xml` excludes all
  domains from cloud backup and device-to-device transfer.
- Cached weather and alert state live in `noBackupFilesDir`.
- Cache file names derived from location IDs are sanitized against path traversal.

## Components and intents

- The release build exports only the launcher activity plus three library
  components, each guarded by a permission only the OS or adb shell holds:
  WorkManager's `SystemJobService` (`BIND_JOB_SERVICE`), and WorkManager's
  `DiagnosticsReceiver` and the profile installer's `ProfileInstallReceiver`
  (`DUMP`). Debug builds additionally contain Compose's test and preview
  activities.
- Notification `PendingIntent`s are `FLAG_IMMUTABLE` and target an explicit
  component. Extras received by `MainActivity` are validated (location IDs
  must be short alphanumeric strings).

## Third parties and privacy

- No analytics, crash reporting, ads or tracking SDKs.
- Over satellite links, each data type can be blocked individually, which also
  limits what leaves the device on that network.
- Requests go only to: api.weather.gov (NWS), mapservices.weather.noaa.gov
  (NOAA radar), noaa-hrrr-bdp-pds.s3.amazonaws.com (NOAA HRRR future cast;
  no location is sent, the same file serves everyone), mesonet.agron.iastate.edu
  (fallback HRRR tiles / alternative radar), services.swpc.noaa.gov (aurora
  forecast; no location sent), nowcoast.noaa.gov (satellite imagery, only when the layer is on),
  data.rcc-acis.org (NOAA ACIS recent observed weather, at most every 6 hours
  per place), tiles.openfreemap.org (basemap), the user's WeeWX server,
  pollen.googleapis.com (only with the user's key; coordinates rounded to
  about 1 km) and api.openweathermap.org (only if enabled with the user's key).
- Air quality and UV: data.epa.gov (UV by ZIP code; the ZIP is derived on the
  device), air-quality-api.open-meteo.com (rounded coordinates), and
  www.airnowapi.org only with the user's key (the key must go in the URL, so
  those requests are `no-store`).
- Traffic (only while the Traffic tab is used): datahub.transportation.gov
  (USDOT WZDx registry), the keyless state DOT WZDx feeds it lists (HTTPS
  only), newengland511.org with the user's key (`no-store`), and TomTom tiles
  with the user's key (the key is part of the tile URL by TomTom's design).
  Traffic requests don't include your coordinates except TomTom tile
  addresses, which reveal the map area being viewed.
- Garden and heating frost dates: one download of 30 years of daily lows
  from data.rcc-acis.org per place (~40 KB), cached on the device for a year,
  and only when those features are on.
- While "laundry is out", a WorkManager job requests only the hourly forecast
  from weather.gov every 20 minutes, for at most 12 hours.
- The custom theme photo is copied into `noBackupFilesDir` after decoding and
  re-encoding, which strips EXIF metadata such as GPS position. The app uses
  the system photo picker and holds no media permission.
- Canada: api.weather.gc.ca (Environment Canada city pages; nearest page looked
  up with an id-only query).
- AI summary (only if configured): the user's own server. The request holds
  the place name and forecast numbers. The URL follows the same rules as WeeWX:
  http only for private/LAN hosts (with LAN-only DNS) and never with an API key.
  The key is stored encrypted, sent as a Bearer header, and requests are
  `no-store`. The settings page sets `FLAG_SECURE`.
- All of these hosts are HTTPS-only with system CAs in the network security config.
- Place search uses the platform `Geocoder` (on most devices this is backed
  by the system location provider) and OpenWeatherMap geocoding only if the
  user enabled OWM.
- The optional NWS contact email is sent only to weather.gov, inside the
  User-Agent header, as NWS requests.

## Supply chain and build

- Dependencies resolve only from Google Maven and Maven Central, and modules
  cannot add repositories (`FAIL_ON_PROJECT_REPOS`). Google Maven is limited
  to Android/Google/AndroidX groups.
- Versions are pinned in `gradle/libs.versions.toml`.
- Release builds are minified and resource-shrunk. Signing keys are never
  committed (`keystore.properties` and `*.jks` are git-ignored).

## Northvane Mini

The flip-phone app keeps the same rules with a smaller surface: HTTPS only
(cleartext is off for every host, since Mini has no local-server features), no
API keys, no accounts, and places and settings stored only on the device
(excluded from cloud backup and device transfer). It talks only to
api.weather.gov and Environment Canada (forecasts and alerts),
mesonet.agron.iastate.edu (radar map pictures, which include the map area
around the place being viewed) and geocoding-api.open-meteo.com (place search;
only the typed town or ZIP code is sent). Location is used only when the user
picks "Use my location", and only while the app is open.

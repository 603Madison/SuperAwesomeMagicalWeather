# Connecting SuperAwesomeMagicalWeather to your WeeWX station

SuperAwesomeMagicalWeather can show **your own station's current conditions** in place of the
nearest NWS observation for one of your saved locations. The NWS still provides
the sky condition (sunny, cloudy, …), the forecast and alerts, and fills in
anything your station doesn't measure, such as visibility.

You need a URL the app can fetch. Pick **one** of the options below.

## Option A — SuperAwesomeMagicalWeather JSON template (recommended)

A tiny skin that writes `northvane.json` with explicit metric units on every
report cycle (usually every 5 minutes).

1. Copy the `skins/Northvane` folder from this directory into your WeeWX skins
   directory:
   - WeeWX 5 (pip install): `~/weewx-data/skins/`
   - WeeWX 5/4 (package install): `/etc/weewx/skins/`
2. Add a report to `weewx.conf`, inside `[StdReport]`:

   ```ini
   [[NorthvaneReport]]
       skin = Northvane
       enable = true
       # Optional: write into its own folder
       # HTML_ROOT = public_html/northvane
   ```
3. Restart WeeWX and wait for one archive interval. You should now have
   `northvane.json` next to your other generated pages, e.g.
   `https://weather.example.com/northvane.json` or
   `http://192.168.1.20/weewx/northvane.json`.
4. In the app: **Settings › Data sources › Add WeeWX station**, paste the URL,
   choose the location it should be used for, then tap **Test connection**.

## Option B — `realtime.txt` (weewx-crt extension)

If you already run [weewx-crt](https://github.com/matthewwall/weewx-crt), point
the app at the `realtime.txt` it writes. Its units are declared inside the
file, so any WeeWX unit system works. It updates on every LOOP packet, so it's
fresher than option A.

## Option C — `clientraw.txt`

The Weather Display format written by the weewx-clientraw extension. Supported
for temperature, humidity, dew point, wind, pressure and rain.

## Network and security rules

- **HTTPS works everywhere.** Use it whenever the station is reachable from
  the internet, and always if the server needs a login.
- **Plain `http://` only works on your local network** (192.168.x.x, 10.x.x.x,
  172.16–31.x.x, Tailscale 100.64–127.x.x, IPv6 ULA, or a hostname that
  resolves to one of those). Public addresses over http are refused.
- **Logins are only ever sent over HTTPS.** Basic auth, bearer tokens and
  custom headers (for example a reverse proxy's API key) are supported. They
  are stored encrypted with a hardware-backed Android Keystore key.
- **Android 17+:** reaching a server on your home network needs the
  **Nearby devices** permission. SuperAwesomeMagicalWeather asks for it when you test or save such
  a station. If it's denied, the station is skipped and NWS is used instead.
- Self-signed certificates: install your CA certificate on the phone
  (Settings › Security › Encryption & credentials). The app trusts
  user-installed CAs **only** for your own servers, never for the built-in
  weather services.
- Data older than the "Ignore data older than" setting (default 30 minutes) is
  treated as missing, and the app falls back to NWS.

# SuperAwesomeMagicalWeather for Linux

The same app as on Android and iPhone (Today, Outlook, Radar, Alerts, the AI
features and every setting) in a desktop window. Each release has two
packages for 64-bit PCs (x86_64), each with its own Java runtime, so nothing
else needs installing:

| Your system | File | Install |
|---|---|---|
| Ubuntu 22.04 or newer, Debian 12+, Linux Mint, Pop!_OS, elementary OS | `superawesomemagicalweather_X.Y.Z_amd64.deb` | `sudo apt install ./superawesomemagicalweather_X.Y.Z_amd64.deb` |
| Fedora 39 or newer, RHEL/Rocky/Alma 9+, openSUSE | `superawesomemagicalweather-X.Y.Z-1.x86_64.rpm` | `sudo dnf install ./superawesomemagicalweather-X.Y.Z-1.x86_64.rpm` |

Then open **SuperAwesomeMagicalWeather** from your app menu. Installing a newer
package the same way updates it; your places and settings stay.

To check a download first: `sha256sum -c SHA256SUMS.txt --ignore-missing`.

To remove it: `sudo apt remove superawesomemagicalweather` or
`sudo dnf remove superawesomemagicalweather`.

## What's different on a computer

- **No "use my location".** Computers don't have a location service the app
  can rely on, so add places by searching (town, ZIP code or "lat, lon").
- **Alerts and reminders when the app is closed.** While it's open the app
  checks alerts itself. When it's closed, a systemd timer for your user runs a
  quick check every 15 minutes (`superawesomemagicalweather-check.timer`); it
  turns itself off when alerts, the morning briefing and Tonight are all off,
  and does nothing once the app is uninstalled. To look at it:
  `systemctl --user list-timers superawesomemagicalweather-check.timer`.
- **Notifications** use your desktop's own (via `notify-send`); warnings stay
  on screen until dismissed.
- **API keys** (Pollen, AI, OpenWeatherMap, WeeWX passwords) go in your login
  keyring (GNOME Keyring or KWallet) through `secret-tool`. Without it they're
  kept in a file only your user can read. On Ubuntu, `secret-tool` comes from
  `libsecret-tools`, which the package recommends; Fedora has it already.
- **The radar map** is the U.S. Geological Survey's National Map (public
  domain), drawn by the app: drag to move, scroll or double-click to zoom.
  Outside the US it shows less detail.
- No widget, voice questions or "Send to Mini" camera scan on a computer.

## Where things are kept

| What | Where |
|---|---|
| Settings and places | `~/.config/superawesomemagicalweather/` |
| Cached forecasts, the forecast log, background photo | `~/.local/share/superawesomemagicalweather/` |
| Downloaded map tiles and responses | `~/.cache/superawesomemagicalweather/` |

All three are readable by your user only.

## Building it yourself

```bash
./gradlew :desktop:run                          # run from source
sudo apt install rpm fakeroot                   # once, to build the .rpm on Ubuntu
./gradlew :desktop:packageDeb :desktop:packageRpm
```

The packages land in `desktop/build/compose/binaries/main/`.

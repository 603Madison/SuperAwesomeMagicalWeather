# SuperAwesomeMagicalWeather on iPhone

The iPhone app is the same app as on Android: the same screens, forecasts,
outlooks, alerts, AI features and settings, from the same shared code
(`core`, `ui` and `shared`). Only the parts that talk to the phone are
written for iOS: Apple Maps under the radar, the Keychain for API keys,
iOS notifications and background refresh, the photo picker, Core Location
and a WidgetKit widget.

## Installing

Each release on GitHub has `SuperAwesomeMagicalWeather-vX.Y.Z.ipa` next to
the Android APKs. The file is unsigned: Apple only runs apps signed for your
own device, so a sideloading tool signs it with your Apple ID as it installs.

### Sideloadly (Windows or Mac, free Apple ID)

1. Install [Sideloadly](https://sideloadly.io) and iTunes (Windows) or nothing
   extra (Mac). Connect the iPhone with a cable and tap **Trust**.
2. Turn on **Developer Mode** on the iPhone: Settings › Privacy & Security ›
   Developer Mode, then restart.
3. Drag the `.ipa` into Sideloadly, enter your Apple ID and click **Start**.
4. On the iPhone: Settings › General › VPN & Device Management › your Apple
   ID › **Trust**.

### AltStore

Add the `.ipa` from the Files app in AltStore's My Apps tab (the **+**
button). AltStore re-signs it in the background while your computer's
AltServer is on the same Wi-Fi.

### What a free Apple ID means

- **7 days.** Apps signed with a free Apple ID stop opening after 7 days.
  Re-install the same `.ipa` with Sideloadly (your settings and places are
  kept), or let AltStore refresh it automatically. A paid Apple Developer
  account ($99/year) makes it a year.
- **Updates** install over the old version the same way, and keep everything.
- **The widget** reads from an App Group that the signing tool has to set up
  for your Apple ID. If the widget only says "Open SuperAwesomeMagicalWeather
  to set up the widget" after you've opened the app, the tool didn't; the app
  itself works either way.

## Differences from Android

| | Android | iPhone |
|---|---|---|
| Radar base map | OpenFreeMap (MapLibre) | Apple Maps |
| Alert checks | Every 15–60 min (WorkManager) | When iOS allows background refresh, usually within the hour; and every time you open the app |
| Morning briefing, Tonight | Written at the time you pick | Written from the latest forecast and delivered at the time you pick |
| Rain starting soon, laundry watch, northern-lights alerts | Background notifications | Not yet: iOS doesn't let apps wake up often enough |
| Voice questions | Mic button | The keyboard's dictation key |
| Wallpaper colors | Android 12+ | Not on iOS |
| Home-screen widget | Small, medium, large | Small, medium, and Lock Screen |

## Building it yourself

On a Mac with Xcode 26 and JDK 21:

```sh
brew install xcodegen
cd iosApp && xcodegen generate && open SuperAwesomeMagicalWeather.xcodeproj
```

Pick your team under Signing & Capabilities for both targets and run. The
"Kotlin" build phase builds the shared code with Gradle first; the first build
takes a few minutes.

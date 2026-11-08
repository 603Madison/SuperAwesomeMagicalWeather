# Contributing

Thanks for helping. Issues and pull requests are welcome.

## Setup

You need JDK 17+ (21 works), Android Studio with AGP 9.4 support and the Android 17 (API 37) SDK platform.

```bash
./gradlew :core:jvmTest                      # data layer: parsers, fallbacks, security policy
./gradlew :core:jvmTest -Pnorthvane.live=true # also hits the real NWS/NOAA/IEM services
./gradlew :app:testDebugUnitTest          # renders screens to app/build/outputs/screenshots
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

## Layout

- `core/` is plain Kotlin/JVM: API clients, parsers, source fallback, outage estimator, security policy. Keep it free of Android APIs so it stays testable.
- `app/` is Compose UI, MapLibre radar, WorkManager alerts and settings.
- `weewx/` is the WeeWX skin and setup guide.

## Guidelines

- **Tests:** parsers should be covered by captured real responses (see `core/src/test/resources/live`). Don't commit responses containing personal locations; trim them to what's needed.
- **Network:** built-in services are HTTPS-only. User-configured hosts must go through `UrlPolicy` and `CleartextGuardInterceptor`. Never send credentials over HTTP.
- **Secrets:** nothing sensitive in code, logs or fixtures. API keys belong in `SecretStore`.
- **Backwoods mode:** new network features need a `SatelliteFeature` entry and an honest size estimate.
- **Outage outlook:** changes to thresholds in `OutageEstimator` should come with a test and the reasoning (storm, ice index, etc.).
- Run lint and the tests before opening a PR.

## License

SuperAwesomeMagicalWeather is licensed under the GNU General Public License v3.0 or later
(`GPL-3.0-or-later`; see [`LICENSE`](LICENSE)). By contributing, you agree that
your contributions are licensed under the same terms.

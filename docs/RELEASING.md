# Releasing

Releases are built and signed by GitHub Actions. Nobody needs to push tags: the
**Release** workflow creates the tag on the commit it builds.

## One-time: signing key

Every release is signed with one permanent key, so each version installs as an
update over the last. The key lives in two repository secrets (Settings ›
Secrets and variables › Actions › **Repository secrets**):

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password (also the key password) |

Optional: `RELEASE_KEY_ALIAS` (default `northvane`) and `RELEASE_KEY_PASSWORD`
(defaults to the keystore password).

To make a new key yourself, run `./scripts/generate-keystore.sh`. Back up the
`.jks` file and its password somewhere safe, such as a password manager. If the
key is lost, no future release can install over existing copies of the app.

Without the secrets, the workflow stops with an error rather than publishing an
unsigned or debug-signed APK.

## Each release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Move the Unreleased notes in `CHANGELOG.md` under a new `## [x.y.z] - date`
   heading. The release uses that section as its notes.
3. Write the F-Droid changelog for the new `versionCode` in
   `fastlane/metadata/android/en-US/changelogs/`,
   `mini/fastlane/metadata/android/en-US/changelogs/` and
   `wear/fastlane/metadata/android/en-US/changelogs/` (up to 500 characters;
   the release workflow checks for all three).
4. Commit and push to `main`.
5. Start **Actions › Release › Run workflow** on `main` with the version (for example
   `v1.4.3`), or `gh workflow run release.yml -f version=v1.4.3`. Pushing a
   `v*` tag also works, but then the F-Droid repository isn't updated (GitHub
   Pages only deploys from `main`). The last job of the run updates the F-Droid
   repository ([docs/FDROID.md](FDROID.md)).

The workflow checks that the version matches `versionName`, runs the tests and
lint, builds the signed APKs (`SuperAwesomeMagicalWeather-vX.Y.Z.apk`, the
`-Mini-` flip-phone app and, from 2.3.0, the `-Wear-` watch app), writes
`SHA256SUMS.txt` and publishes the release. All three share the version from
`app/build.gradle.kts` and the one signing key.
Only the highest version is marked Latest, which is where the README's download
link points. Versions with a hyphen (for example `v1.5.0-beta.1`) are marked as
pre-releases. The release is published only once everything is built, the APKs
and the iPhone `.ipa`, because this repository's releases are immutable: once
published, files can't be added or replaced. Running the workflow again for a
version that's already released stops with an error; release a new version
instead.

Releases before 1.4.3 were debug-signed (package `app.northvane.weather.debug`)
and can't be re-signed, because their code doesn't read the signing secrets.

## Verifying a download

```bash
sha256sum -c SHA256SUMS.txt
apksigner verify --print-certs SuperAwesomeMagicalWeather-v1.4.3.apk
```

The signing certificate fingerprint should be the same on every release.

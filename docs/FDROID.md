# F-Droid repository

The project publishes its own F-Droid repository, so both apps can be installed
and kept up to date from the F-Droid app (or another F-Droid client such as
Droid-ify or Neo Store). It serves the same developer-signed APKs as the GitHub
releases, so a copy installed from either one updates from the other.

| | |
|---|---|
| Repository | `https://603madison.github.io/SuperAwesomeMagicalWeather/repo` |
| Fingerprint | `959FA8D3634E3CF7409BA1D5BD1C438FD68B9FC169C47E429E16800FD076DBBF` |
| One-tap link | [Add to F-Droid](https://fdroid.link/#https://603madison.github.io/SuperAwesomeMagicalWeather/repo?fingerprint=959FA8D3634E3CF7409BA1D5BD1C438FD68B9FC169C47E429E16800FD076DBBF) |

## Adding it on your phone

Open the one-tap link above on the phone, or:

1. In F-Droid, open **Settings › Repositories** and tap **+**.
2. Enter the repository address and the fingerprint above (or scan the QR code
   on the [repository page](https://603madison.github.io/SuperAwesomeMagicalWeather/repo/)).
3. Search for SuperAwesomeMagicalWeather. Both apps are in the repository.

The fingerprint is the SHA-256 of the key that signs the APKs. F-Droid checks
that the repository is signed with it, so nobody else can publish to it.

## How it's built

`.github/workflows/fdroid-repo.yml` runs after every release (as the last job
of the Release workflow) and can be started by hand from the Actions tab
(**F-Droid repository › Run workflow**). It:

1. downloads the APKs from the three newest GitHub releases and checks that
   each is signed with the release key;
2. adds the store listings from `fastlane/`, `mini/fastlane/` and `wear/fastlane/` (descriptions,
   icon, screenshots, changelogs) and the settings in `fdroid/`;
3. builds and signs the index with [fdroidserver](https://gitlab.com/fdroid/fdroidserver),
   using the release key from the repository secrets;
4. publishes it to GitHub Pages.

### One-time setup

GitHub Pages has to be turned on once: **Settings › Pages › Build and
deployment › Source: GitHub Actions**. Then run **Actions › F-Droid repository
› Run workflow** on `main` to publish the current release. Until Pages is on,
the F-Droid job at the end of each release fails (the release itself is still
published).

Pages deployments are allowed from `main`, so start releases from `main` as
usual; a run started by pushing a tag can't deploy the repository.

### Every release

Each release needs an F-Droid changelog for its `versionCode` in
`fastlane/metadata/android/en-US/changelogs/`,
`mini/fastlane/metadata/android/en-US/changelogs/` and
`wear/fastlane/metadata/android/en-US/changelogs/` (up to 500 characters). The
release workflow stops if one is missing.

### Updating fdroidserver

The workflow installs fdroidserver and its dependencies from
`fdroid/requirements.txt`, where every package is pinned with its hash. To
update, change the version in `fdroid/requirements.in` and regenerate the file
with Python 3.13 (the version the workflow uses):

```bash
pip install pip-tools
pip-compile --generate-hashes --allow-unsafe --strip-extras \
  --output-file fdroid/requirements.txt fdroid/requirements.in
```

## Building from source

The apps also meet F-Droid's rules for building from source: everything is free
software from Google Maven and Maven Central (no Google Play Services, Firebase
or trackers), the APKs don't embed AGP's dependency list encrypted with Google's
key, and `assembleRelease` makes an unsigned APK when there's no signing key.

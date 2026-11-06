# F-Droid

The repository is set up so [F-Droid](https://f-droid.org) can build both apps
from source:

- Everything is free software, built only from Google Maven and Maven Central.
  No Google Play Services, Firebase, trackers or prebuilt binaries.
- Release builds don't embed AGP's dependency list, which is encrypted with
  Google's key (`dependenciesInfo` in both `build.gradle.kts` files).
- Without a signing key, `assembleRelease` makes an unsigned APK, which F-Droid
  then signs.
- Store listings are in the fastlane layout: `fastlane/` for the main app and
  `mini/fastlane/` for Mini (title, descriptions, icon, screenshots and a
  changelog per version code).
- Build recipes ready for fdroiddata are in [`fdroid/`](../fdroid).

## Submitting

F-Droid's catalog lives in the [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
repository on GitLab. You need a GitLab account.

1. Fork fdroiddata on GitLab.
2. Copy `fdroid/app.northvane.weather.yml` and `fdroid/app.northvane.mini.yml`
   into `metadata/` in your fork, on a new branch.
3. Optional: check them with `fdroid lint` and `fdroid build -v -l app.northvane.weather`
   (see F-Droid's [quick start guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)).
4. Open a merge request. F-Droid's reviewers may adjust the recipes.

After that, F-Droid picks up new versions by itself from new `v*` tags.

## Signing

F-Droid signs the apps with its own key. Android won't update an app across
different keys, so a copy installed from GitHub can't be updated from F-Droid
(or the other way round) without uninstalling first, which loses places and
settings.

F-Droid can instead ship the GitHub-signed APKs if its build of the same tag
matches them byte for byte ("reproducible builds"). That means adding
`Binaries:` and `AllowedAPKSigningKeys: 959fa8d3634e3cf7409ba1d5bd1c438fd68b9fc169c47e429e16800fd076dbbf`
to the recipes once a release has been confirmed to build reproducibly.

## Every release

The release needs a changelog for its version code in both
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and
`mini/fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (up to 500
characters each). The release workflow stops if one is missing.

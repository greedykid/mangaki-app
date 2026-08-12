# Releasing

APKs are built and published by `.github/workflows/release.yml`. Tag a commit
and the workflow does the rest:

```sh
git tag v9.4.1-mangaki.1
git push --tags
```

The Actions tab can also run it by hand, which builds and uploads an APK as a
workflow artifact without creating a release.

## One-time setup: the signing key

Android will not install an unsigned APK, and an app can only ever be updated
by a build signed with the **same** key. Lose this keystore and every existing
install becomes a dead end — the only way back is for users to uninstall and
lose their library. Back it up somewhere you will still have in five years.

Create it once, on your own machine:

```sh
keytool -genkeypair -v \
  -keystore mangaki-release.jks \
  -alias mangaki \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository secrets under **Settings → Secrets and variables →
Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 mangaki-release.jks` |
| `KEYSTORE_PASSWORD` | the store password you chose |
| `KEY_ALIAS` | `mangaki` |
| `KEY_PASSWORD` | the key password (often the same as the store password) |

The workflow fails loudly if `KEYSTORE_BASE64` is missing rather than
publishing an APK nobody can install.

## Building signed locally

Put the same values in `local.properties`, which is git-ignored:

```properties
keystore.file=/absolute/path/to/mangaki-release.jks
keystore.password=...
keystore.alias=mangaki
keystore.keyPassword=...
```

Then `./gradlew assembleRelease`. Without them the build still succeeds and
produces an unsigned APK, so a fresh clone is never broken by their absence.

## Version numbers

`versionCode` and `versionName` live in `app/build.gradle` and are **not** read
from the tag. Bump `versionCode` before every release — Android refuses to
install an APK whose code is not higher than the installed one, and the failure
message does not explain why.

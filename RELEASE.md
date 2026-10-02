# Livrospeak Private Alpha Release

## Prerequisites

- JDK 17 (Android Studio's bundled JBR is supported).
- Android SDK platform/build tools for this project.
- A private release keystore outside the repository.
- A connected Light Phone III with USB debugging for installation tests.

The release identity is `com.tyshi00.livrospeak`. Preserve the same signing key for
every future upgrade using this application ID.

## Signing setup

Store the release keystore outside this repository, for example:

```text
~/.android/keys/livrospeak-release.jks
```

Copy `keystore.properties.example` to the ignored `keystore.properties` file,
then replace the placeholder password values locally. Restrict the file to the
current user:

```sh
cp keystore.properties.example keystore.properties
chmod 600 keystore.properties
```

Never commit `keystore.properties`, a keystore, or signing passwords. Release
tasks fail closed when the properties file is absent.

## Build

From the repository root:

```sh
./gradlew clean assembleRelease
```

The signed APK is produced at:

```text
app/build/outputs/apk/release/app-release.apk
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Verify

Inspect package/version metadata and signing information:

```sh
apkanalyzer manifest application-id app/build/outputs/apk/release/app-release.apk
apkanalyzer manifest version-code app/build/outputs/apk/release/app-release.apk
apkanalyzer manifest version-name app/build/outputs/apk/release/app-release.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Create a SHA-256 checksum:

```sh
shasum -a 256 app/build/outputs/apk/release/app-release.apk
```

## Install

An upgrade installation preserves Livrospeak's app-private Libby session, progress,
RSS settings, and RSS downloads:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

A clean installation removes the existing package and all its app-private data.
Only run this after explicitly accepting that Libby login, progress, RSS
settings, and RSS downloads will be erased:

```sh
adb uninstall com.tyshi00.livrospeak
adb install app/build/outputs/apk/release/app-release.apk
```

## Tester installation

1. Enable developer/USB debugging access on the Light Phone III.
2. Connect the phone to a trusted computer.
3. Install the supplied APK with `adb install -r <apk-file>`.
4. Open Livrospeak from the phone's tools/application list.
5. Keep the APK private; this is an early test build.

## Smoke-test checklist

- Open Settings and confirm Version shows the expected release.
- Connect Libby with Copy To Another Device and confirm Books populate.
- Open a Libby loan, play, seek ±15 seconds, scrub, and change speed.
- Turn the screen off for at least 90 seconds and confirm Libby continues.
- Pause Libby and confirm the foreground playback notification disappears.
- Put MP3 and M4B files directly in `Light Phone III/Audiobooks`, scan, and play.
- Add an HTTP/HTTPS RSS feed, stream an item, and explicitly download it.
- Restart Livrospeak and confirm progress and Now Playing restore without autoplay.
- Test with networking disabled and confirm cached metadata remains readable.

## Known limitations

- Local audiobooks support one MP3 or M4B file per book; folders and chapters
  are not supported.
- RSS supports one playable MP3/M4B enclosure per item, without feed
  authentication, chapters, scheduled refresh, or automatic downloads.
- Libby requires an existing Libby device and an eight-digit Copy To Another
  Device setup code.
- Libby depends on Libby's private web-player behavior and may require updates
  when that client changes.
- No cover art, ebook reading, playback notifications controls, or cloud sync.

## Rollback

Reinstall a previously retained APK signed with the same release key. Android
normally blocks a lower version code; for an explicitly approved test-device
rollback use:

```sh
adb install -r -d <previous-signed-livrospeak.apk>
```

If downgrade installation is rejected, a clean reinstall is the fallback, but
it erases Livrospeak's app-private data. Never uninstall without tester approval.

## Publishing boundary

Do not create a tag or GitHub Release until explicitly approved. Once approved,
the corresponding command would be:

```sh
gh release create v0.1.0-alpha3 app/build/outputs/apk/release/app-release.apk --prerelease --title "Livrospeak 0.1.0-alpha3" --notes-file RELEASE_NOTES_0.1.0-alpha3.md
```

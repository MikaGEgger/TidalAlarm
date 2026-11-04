# Lavender

An Android alarm clock that wakes you with your music from TIDAL.

Lavender rings like a normal alarm app (repeat days, snooze, gradual volume, vibration, upcoming-alarm notification with "Dismiss now", skip next, one-off dates). If an alarm has a TIDAL playlist or track, Lavender starts it in the TIDAL app when the alarm goes off. If TIDAL doesn't start within about 25 seconds, or stops playing, Lavender's own backup sound rings instead.

Lavender isn't made by or affiliated with TIDAL. It uses the TIDAL app (with your subscription) and the official TIDAL login.

## Install a build

Every push to `main` builds an APK on GitHub Actions and publishes it as the **latest-build** release. On your phone, open that release (logged in to GitHub) and download `lavender.apk`. Updates install over the previous version and keep your alarms.

## Develop on any computer

1. Clone the repo and open the folder in Android Studio (or run `./gradlew assembleDebug`).
2. Builds from every computer and from GitHub use the same development key (`app/dev.keystore`), so they install over each other.
3. Unit tests: `./gradlew testDebugUnitTest`.

## How it works

| Part | Where |
| --- | --- |
| When an alarm rings next (pure logic, unit-tested) | `alarm/NextTrigger.kt` |
| Registering alarms with Android (`setAlarmClock`) | `alarm/AlarmScheduler.kt` |
| Reboot, time-zone change, app update | `alarm/Receivers.kt` |
| Ringing: TIDAL start, backup sound, snooze, dismiss | `alarm/AlarmService.kt` |
| Ringing screen over the lock screen | `ui/ringing/RingingActivity.kt` |
| Starting TIDAL with "play" links | `tidal/TidalLauncher.kt` |
| TIDAL login (OAuth PKCE) and library | `tidal/TidalAuth.kt`, `tidal/TidalApi.kt` |
| Themes (Lavender + 7 flag themes) | `ui/theme/Theme.kt` |

### Why TIDAL is started this way

Third-party apps can only play 30-second previews through TIDAL's own developer platform. So Lavender hands playback to the TIDAL app instead. Tested on a real phone (see the `test-app` branch): links with `?play=true` make TIDAL start the playlist by itself, also when the phone is locked. Android only lets a visible screen open another app, so the ringing screen opens TIDAL, and a fresh full-screen notification brings Snooze/Dismiss back on top afterwards.

Alarms and settings live in device-protected storage, so alarms also ring after a restart before the phone is unlocked (with the backup sound, since TIDAL can't run then).

## Configuration

`gradle.properties` holds the public TIDAL Client ID (`tidalClientId`). The login redirect is `tidalarm://auth`. Never commit the Client Secret; Lavender doesn't use it.

## Play Store

See `docs/play-store.md` for the release checklist and `docs/privacy-policy.md` for the privacy policy.

# Alarm Test (temporary test app)

This branch holds a throwaway test app, not the real alarm app. It answers one question before the real app is built: **can an alarm app get the TIDAL app to start playing by itself, also when the phone is locked?**

## Install

1. On your phone, open the **test-build** release of this repo (you need to be logged in to GitHub in the browser) and download `alarm-test.apk`.
2. Open it and allow your browser to install apps when Android asks.
3. Open **Alarm Test** and allow notifications.

## Run the tests

Before you start: be logged in to the TIDAL app and turn the **media volume** up.

1. **Pick something to play.** In TIDAL, open a playlist, tap **Share** and choose **Alarm Test**. Then type the playlist's name in the second field.
2. **Test with the phone unlocked.** Tap tests A to F one at a time. After each, note whether music started, then tap **Pause TIDAL**.
3. **Test with the phone locked.** Pick the test that worked best, tap **Schedule test alarm in 1 minute**, lock the phone and wait. Watch whether music starts and whether the alarm screen comes back on top. Then dismiss.
4. **Web player.** Tap **Open web player**, log in, start the playlist yourself and listen for over a minute. Try once with and once without desktop mode.
5. Tap **Copy log** and paste it into the chat with Claude, together with what you heard.

## Building

Every push builds the APK on GitHub Actions and publishes it as the `test-build` release. To build locally: open the folder in Android Studio, or run `./gradlew assembleDebug`.

# Alarm Test (temporary test app)

This branch holds a throwaway test app, not the real alarm app. It answers one question before the real app is built: **can an alarm app get the TIDAL app to play a chosen playlist or track by itself, also when the phone is locked?**

## Round 1 results (ALONG MP01, Android 14, TIDAL 2.215.0)

- Opening a link opens the right playlist in TIDAL, but doesn't start it.
- A general "Play" key reaches whichever app played last (e.g. Audible), not necessarily TIDAL.
- Play-from-search intent: ignored. TIDAL's media browser service: refuses other apps.
- Alarm screen over the lock screen and returning on top after TIDAL opens: works.
- TIDAL web player inside the app: doesn't load. Dropped.

## Round 2 results

- Play key sent to TIDAL only: nothing happens.
- Notification access: blocked by Android for apps installed outside the Play Store ("restricted setting").
- **Links with a play hint start TIDAL:** `https://tidal.com/browse/playlist/<id>?play=true`, `tidal://playlist/<id>?play=true` and `tidal://playlist/<id>/play` worked; `?autoplay=true` didn't.

## Round 3 tests

- **J**: the play links, tried one after another until music plays, the way the real alarm would do it.
- Questions: does TIDAL play the chosen playlist/track (not just resume its last song)? Does it work for tracks? Does it work with the phone locked, and keep playing for a minute?

## Install

On your phone, open the **test-build** release of this repo (logged in to GitHub) and download `alarm-test.apk`. It installs over the previous round.

## Run the tests

1. Before each test: play something in Audible briefly and pause it. Then play a TIDAL song that's **not** in your test playlist and pause it.
2. Run J with a playlist link. Did your playlist start, or the old song?
3. Share a **track** from TIDAL to the app, and run J again.
4. Locked test with J: lock the phone, let it ring for a full minute before dismissing.
5. Tap **Copy log** and paste it into the chat with Claude, with what you heard.

## Building

Every push builds the APK on GitHub Actions and publishes it as the `test-build` release. Locally: open the folder in Android Studio, or run `./gradlew assembleDebug`.

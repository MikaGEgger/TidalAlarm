# Alarm Test (temporary test app)

This branch holds a throwaway test app, not the real alarm app. It answers one question before the real app is built: **can an alarm app get the TIDAL app to play a chosen playlist or track by itself, also when the phone is locked?**

## Round 1 results (ALONG MP01, Android 14, TIDAL 2.215.0)

- Opening a link opens the right playlist in TIDAL, but doesn't start it.
- A general "Play" key reaches whichever app played last (e.g. Audible), not necessarily TIDAL.
- Play-from-search intent: ignored. TIDAL's media browser service: refuses other apps.
- Alarm screen over the lock screen and returning on top after TIDAL opens: works.
- TIDAL web player inside the app: doesn't load. Dropped.

## Round 2 tests

- **G**: open the link, then send Play to TIDAL only. Fixes the Audible problem, probably resumes TIDAL's last queue.
- **H**: with notification access, control TIDAL's media session directly and try several ways to play the chosen link. The log also shows which app is playing and which song.
- **I**: open the link with a few guessed "play" hints.

## Install

1. On your phone, open the **test-build** release of this repo (logged in to GitHub) and download `alarm-test.apk`. It installs over round 1.
2. Open **Alarm Test**, tap **Allow notification access** and turn it on. If the switch is greyed out: Settings → Apps → Alarm Test → ⋮ → **Allow restricted settings**, then try again.

## Run the tests

1. Play something in Audible briefly and pause it, before each test.
2. Before test H, also play a song in TIDAL that is **not** in your test playlist, then pause.
3. Run G, H and I. H takes about 45 seconds; let it finish.
4. Pick the test that played your playlist, run the locked alarm test with it.
5. Tap **Copy log** and paste it into the chat with Claude, with what you heard.

## Building

Every push builds the APK on GitHub Actions and publishes it as the `test-build` release. Locally: open the folder in Android Studio, or run `./gradlew assembleDebug`.

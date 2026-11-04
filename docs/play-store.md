# Play Store checklist

What's needed before Lavender can be published, and what each Play Console form should say.

## 1. Must change in the code

- [x] **Target Android 16 (API 36).** Required for new apps and updates since 31 Aug 2026. Done (AGP 8.9.1, Gradle 8.11.1).
- [ ] **Release build with an upload key.** Create a private upload key once, keep it outside the repo, and add it as GitHub secrets (keystore as base64, passwords). The committed `dev.keystore` is for test builds only.
- [ ] **App bundle** (`bundleRelease`) instead of an APK, and turn on code shrinking (R8) with a test round.

## 2. Play Console declarations

| Form | Answer |
| --- | --- |
| Exact alarms (`USE_EXACT_ALARM`) | Alarm clock is the app's core function. |
| Full-screen intent (`USE_FULL_SCREEN_INTENT`) | Alarm clock: shows the ringing alarm over the lock screen. |
| Foreground service: `systemExempted` | Keeps a ringing alarm (sound, vibration, TIDAL hand-off) alive in the background. Needs a short screen recording of an alarm ringing. |
| Foreground service: `mediaPlayback` | Fallback type on Android 10–13, plays the alarm sound. |
| Package visibility | Only `com.aspiro.tidal` is queried, to check that TIDAL is installed and open the chosen music in it. |
| Data safety | No data collected or shared. The TIDAL login token is stored only on the phone, encrypted. Data in transit uses HTTPS. |
| Ads | No ads. |
| Target audience | 18+ is simplest (avoids the Families policy). |

## 3. Store listing

- **Name:** "Lavender: Music Alarm Clock" (no "TIDAL" in the name or icon).
- **Short description:** "Wake up to your TIDAL playlists and tracks."
- **Full description** must say that it needs the TIDAL app and a TIDAL subscription, and include: "Lavender is not affiliated with or endorsed by TIDAL." Don't use TIDAL's logo.
- **Privacy policy URL:** `docs/privacy-policy.md` needs a public address. The repo is private, so publish it elsewhere (e.g. a public GitHub Gist, GitHub Pages from a public repo, or a Google Doc shared as "anyone with the link").
- Screenshots: alarm list, editor with a TIDAL playlist, ringing screen, theme picker.

## 4. TIDAL

- TIDAL's developer terms apply to the app's use of the TIDAL login and API. Check them again before publishing, especially rules on naming and branding.
- In the TIDAL dashboard, the app needs the scopes `user.read`, `collection.read`, `playlists.read` and the redirect `tidalarm://auth`.
- Starting playback with `?play=true` links is undocumented TIDAL app behaviour. If a TIDAL update breaks it, alarms still ring with the backup sound.

# Jarvis Release 6 Hotfix 2 — Dust/Audio Sync Verification Report

## Release identity

- Android application ID: `com.jarvisai.app`
- Android versionCode: `146`
- Android versionName: `1.8.10`

## Source changes

- Assistant captions use the real AudioTrack playback position and active flag. Transcript-only updates do not claim that audible speech has started.
- New/restarted transcripts invalidate the old caption group and re-anchor their word timing when the next playback epoch begins.
- Caption expiry, group replacement, and explicit caption cleanup start a finite 720 ms outgoing-particle dissolution. The ongoing incoming caption-flight pass still owns canvas clearing, so the outgoing dust does not erase freshly arriving text particles.
- Direct insults addressed to Jarvis are normalized for Turkish diacritics and dotless `ı`; the matched handler calls `stopActiveConversation()`, which stops and releases the active recorder and disconnects the Live WebSocket.

## Checks

- `node --check` for JavaScript assets and the extracted inline `orb.html` controller.
- `node scripts/verify-thinking-orbs.js`: all nine animation presets render, native-to-WebView audio-clock bridge checks pass, text streaming is incremental, old response clock is not inherited, and insult-to-session-stop source checks pass.
- `node scripts/verify-caption-dust.js`: 20 varied combinations pass finite-coordinate, visible-motion, finite-lifetime cleanup, reduced-motion and empty-caption checks.
- `python3 scripts/verify-jarvis-project.py`: all static Android project checks pass, including parsing all Android XML resources.
- ZIP archive integrity is checked separately after packaging.

## Validation limitation

Gradle could not download its required `gradle-8.9-bin.zip` because network access to `services.gradle.org` was unavailable (`UnknownHostException`). No Android SDK or connected device was present. Therefore this report does not claim a successful APK build or physical-microphone verification; those require building/installing this source on an Android environment.

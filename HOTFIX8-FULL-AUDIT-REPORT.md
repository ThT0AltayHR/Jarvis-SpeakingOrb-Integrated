# Jarvis Release 6 — Hotfix 8 Full Source Audit

Version: `1.8.18` (`versionCode 154`)

## Source changes

- Corrected Gemini Live setup/event field casing and enabled both input and output audio transcription in the setup payload.
- Kept `generationComplete` distinct from `turnComplete`; only the turn-finalization policy can finish a response. Model-turn text remains a fallback when output transcription is absent, to avoid duplicate caption streams.
- Enforced an allow-list of supported Live model IDs and migrated the default from the legacy `gemini-3.1-flash-live-preview` to the stable `gemini-3.8-live` model. Removed the pseudo-agent model and unsupported 2.5 preview choice from the UI.
- Removed the nonfunctional custom gateway input, which was being saved but never passed to the WebSocket constructor. Old stored values are cleared when connection settings are saved.
- Renamed the API key check to report what it actually checks (Google's model-list HTTP endpoint); it no longer claims a Live WebSocket handshake was verified.
- Kept the visual-orb task/state bridge, playback completion gate, transcript accumulation and final dust animation checks in the regression suite.
- Updated the architecture notes and added `scripts/verify-live-model-migration.js`.

## Validation completed in this environment

- All project `scripts/verify-*.js` checks passed.
- `scripts/verify-jarvis-project.py` passed, including parsing all 133 Android XML files.
- JavaScript syntax passed for 13 standalone JS files and 24 inline HTML scripts (module scripts checked in module mode).
- The pure Kotlin `GeminiTurnCompletionPolicy` and `PlaybackCompletionPolicy` helpers compiled with `kotlinc` and passed direct checks.
- ZIP archive integrity was checked after packaging.

## Important limits

The Android Gradle unit-test/build command could not start because Gradle 8.9 could not be downloaded (`UnknownHostException: services.gradle.org`). No Android SDK/device/emulator was available in this environment. As a result, this report does **not** claim an Android APK build or a physical-device microphone/speaker test. Static checks and isolated policy tests cannot guarantee every runtime/device-specific behavior.

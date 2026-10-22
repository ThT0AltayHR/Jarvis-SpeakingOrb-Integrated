# Jarvis Release 6 — Hotfix 9: Audio, Interrupt & State Repair

**App version:** `1.8.20` (`versionCode 156`)

## Source fixes in this build

- **Interrupted Live frames:** when `serverContent.interrupted` is true, the current assistant playback is flushed on speakerphone and headset routes, and the model-turn payload in that same frame is dropped so stale PCM cannot be queued after the flush. Fallback assistant transcript state is reset at the same boundary.
- **Speech completion:** the audio player does not finish just because the network queue is temporarily empty. It requires the provider's completed-turn signal, an empty PCM queue, hardware playhead drain, and a short drain window.
- **Voice barge-in:** AEC is considered trustworthy only when Android reports the effect enabled *and* the route is actually in communication mode (or a headset route is active). Speakerphone paths without a trusted AEC reference now use a stricter output-relative threshold and sustained voice frames to reduce Jarvis hearing its own loudspeaker as a user interruption.
- **Orb stability:** the Orb follows confirmed audio playback and actual user-interrupt state; duplicate status reports remain idempotent. Stale PCM after a locally confirmed interruption cannot cancel watchdogs for the replacement user turn.
- **Live captions and dust:** transcript deltas retain provider boundaries without invented spaces. Prior words/captions remain visible throughout the answer; cleanup uses the final caption dust-out path after playback rather than removing the text on every new word.
- **API error reporting:** close/error responses use a shared classifier. Generic “API key” wording is no longer automatically reported as an invalid key; enum-style values such as `PERMISSION_DENIED`, `INVALID_ARGUMENT`, and `RESOURCE_EXHAUSTED` are normalized and classified. A server-side error retires the half-open socket; quota errors use a cooldown and transient close errors use bounded exponential reconnect backoff.
- **Model/API setup:** the project uses the stable Gemini 3.8 Live model and its canonical Live WebSocket setup/transcription fields. The related setup and model regression checks remain enabled.

## Validation completed

- All `scripts/verify-*.js` regression scripts passed after the final source changes.
- `scripts/verify-jarvis-project.py` passed; all 133 Android XML files parsed and project static checks passed.
- `speaking-orb.js` passed `node --check`.
- Seven production Kotlin policy/helper files compiled with `kotlinc`; a direct runtime harness passed **30,038 assertions** covering error classification, interruption/turn-completion rules, playback completion, transcript delta joining, state priority, and headset/speakerphone voice-detection cases.
- ZIP integrity is checked after packaging.

## Not verified in this environment

A full Android Gradle build and physical-device audio/microphone test could not be completed. The Gradle wrapper needs `https://services.gradle.org/distributions/gradle-8.9-bin.zip`, but DNS resolution failed with `UnknownHostException`; no Android SDK is installed here. Therefore this package is source-tested, **not** a device-certified APK, and zero runtime errors cannot honestly be guaranteed.

## Protocol references used during review

- [Gemini Live WebSocket API reference](https://ai.google.dev/api/live) — explains `interrupted`, independent transcription events, and the distinct `generationComplete` / `turnComplete` signals.
- [Gemini 3.8 Live model reference](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live) — stable model and Live API configuration.

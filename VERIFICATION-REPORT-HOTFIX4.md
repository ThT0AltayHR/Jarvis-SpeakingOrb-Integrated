# Jarvis Hotfix 4 — verification report

## Source changes

1. `AudioPlayer` no longer equates a temporary empty PCM queue with completed speech. Playback transitions to finished only after the server turn-complete signal, hardware playback drain, and the short drain guard.
2. The turn-complete flag is reset when new PCM is enqueued, not when the playback worker eventually dequeues the first chunk. This prevents a completion event received during queue wait from being erased.
3. Normal end-of-playback preserves the final live transcript briefly so the WebView can display and dissolve the complete caption. A new incoming assistant answer cancels the pending clear.
4. Added `PlaybackCompletionPolicy` and a JVM unit test.

## Checks run

- `node scripts/verify-audio-continuity.js`: PASS — 30,003 truth-table checks over queue-idle counts 0–10,000 plus source invariants.
- `node scripts/verify-caption-dust.js`: PASS — 100 incremental multi-sentence timelines, full-text retention, final dust-out, active-playback holding and particle cleanup cases.
- `node scripts/verify-thinking-orbs.js`: PASS — all nine animation presets and bridge smoke checks.
- `python3 scripts/verify-jarvis-project.py`: PASS — all static Android project checks, including XML parsing.
- `node --check app/src/main/assets/speaking-orb.js`: PASS.
- Compiled and executed `PlaybackCompletionPolicy.kt` with a Kotlin smoke harness: PASS.

## Not verified in this environment

- Full Android/Gradle unit-test task and APK compilation: blocked because `services.gradle.org` cannot be resolved and no Android SDK is configured.
- Physical-phone audio output, live caption synchronization, and microphone behavior: no device runtime was available. Those require a build and a real-device playback check.

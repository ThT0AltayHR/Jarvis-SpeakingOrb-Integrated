# Jarvis Release 6 — Hotfix 12 Verification Report

**Version:** 1.8.25 (`versionCode 161`)

## New defects addressed

1. **Queued amplitude was reported as currently audible amplitude.** `AudioPlayer` previously sent RMS from the newest packet immediately after `AudioTrack.write()`, even when that packet could still be buffered behind earlier audio. This value feeds both Orb motion and the voice barge-in detector. The player now records amplitude segments against written frame ranges and publishes the amplitude selected by the actual `AudioTrack.playbackHeadPosition`.
2. **Stale Orb state could win while PCM was queued but `SPEAKING` had not yet been published.** `ConversationStatePolicy` previously protected only transitions when the current state was already `SPEAKING`. It now rejects non-`SPEAKING` state transitions whenever playback is active/queued, except during the explicitly confirmed user-interrupt path.
3. **AudioTrack reconstruction could skip the accepted prefix of the current PCM chunk.** Since releasing/recreating `AudioTrack` discards that sink's pending buffer, recovery now resets the write offset and resubmits the current chunk from its start instead of continuing after data that existed only in the discarded sink.
4. Added regression coverage to exercise the actual production `AudioPlayer.kt` and `ConversationStatePolicy.kt` with Android API test doubles.

## Checks completed

- 14 JavaScript regression scripts passed.
- `verify-jarvis-project.py` passed; all 133 Android XML files parsed and the source/layout checks passed.
- `verify-audio-player-kotlin.sh` compiled the production `AudioPlayer.kt` with `kotlinc` and passed seven harness scenarios: 16 KB PCM across partial writes/backpressure; hardware-playhead drain; prompt flush and stale-write cancellation; stalled sink error; callback/flush race; amplitude follows the hardware playhead instead of future queued audio; and current-chunk replay after sink reconstruction.
- `verify-conversation-state-policy.sh` compiled the production policy and passed the state transition matrix and interrupt exceptions.
- New Hotfix 12 source guards passed.
- ZIP was opened after packaging and passed `unzip -t`.

## Important uncompleted verification

The full Android/Gradle compile could not run. `./gradlew :app:compileDebugKotlin --no-daemon` failed while trying to download Gradle 8.9 with `java.net.UnknownHostException: services.gradle.org`; `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset and no Android SDK was found. Therefore this report does **not** claim a successful APK build or physical-device verification. Microphone/AEC behaviour and the visual timing on an actual phone remain unverified.

## Files most directly changed

- `app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt`
- `app/src/main/java/com/jarvis/assistant/data/model/ConversationStatePolicy.kt`
- `scripts/audio-player-harness/`
- `scripts/state-policy-harness/`
- `scripts/verify-hotfix12-playback-clock-state.js`
- `scripts/verify-conversation-state-policy.sh`

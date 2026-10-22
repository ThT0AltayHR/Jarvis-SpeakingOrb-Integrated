# Jarvis Release 6 — Hotfix 10 Source Audit

**Version:** 1.8.21 (versionCode 157)  
**Scope:** compile blockers, Gemini Live readiness/interruption ordering, audio-route/AEC trust, transcript concurrency, Orb state rendering, settings truthfulness.

## Findings fixed

1. **Kotlin compile blocker:** removed a duplicated non-repeatable `@Volatile` annotation from `JarvisConversationService.kt`.
2. **False Live readiness:** the WebSocket handler no longer treats arbitrary `serverContent`/`toolCall` payloads as a substitute for the `setupComplete` handshake. Non-error content received before that handshake is ignored instead of marking the connection `LIVE` prematurely.
3. **Interrupted-frame ordering:** `interrupted` is now handled before assistant output transcription. Input transcription remains available to preserve the user's barge-in speech, while trailing assistant transcription and model audio from the interrupted frame are discarded.
4. **Voice playback routing:** speakerphone output now attempts an explicit built-in-speaker communication route on Android 12+, and uses the communication audio usage. AEC is trusted only when the route was actually selected; an enabled effect by itself is not treated as proof of a working route.
5. **Barge-in responsiveness:** AEC-backed speakerphone detection is more responsive while the no-AEC fallback remains stricter and requires sustained frames. RMS-based detection cannot perfectly distinguish speech from echo on every phone; real-device tuning remains necessary.
6. **Concurrent transcript updates:** assistant/user transcript reads and clears now use synchronized helpers; multi-chunk user transcript append is atomic. Recorder/worker handoff fields that cross threads are volatile.
7. **Orb state stability:** state read/policy/write is serialized because WebSocket, AudioTrack, recorder and UI callbacks can arrive on different threads. Muting the microphone no longer forces the Orb to `idle` while the assistant is actually speaking or thinking.
8. **Task animation mapping:** `SEARCHING` maps explicitly to the visible search animation rather than falling back to generic `working`.
9. **Settings truthfulness:** removed the false claim that the app includes an embedded release API key. The build uses a preselected model but requires the user's own Gemini API key.
10. **Version:** bumped to `1.8.21` / `157`.

## Validation completed

- All 12 `scripts/verify-*.js` regression scripts passed, including new checks for the duplicate annotation, handshake gating, interruption order, transcript synchronization, route-success tracking and state stability.
- `scripts/verify-jarvis-project.py` passed; all 133 Android XML files parsed.
- A standalone Kotlin harness compiled the production helpers `PlaybackCompletionPolicy`, `VoiceBargeInDetector`, `ConversationState`, `ConversationStatePolicy`, and `StreamingTextAccumulator`, then exercised audio completion, state priority, barge-in frame thresholds and split-word accumulation. All assertions passed.
- The ZIP archive is checked after packaging.

## Validation not possible in this environment

The Android Gradle build could not start because the wrapper could not resolve `services.gradle.org` (`UnknownHostException`). No Android SDK or connected Android device/emulator is available here. Therefore no APK build, actual microphone/AEC routing test, physical playback test, or on-device visual animation test is claimed. A clean source/static test run is not proof that every runtime device issue is gone.

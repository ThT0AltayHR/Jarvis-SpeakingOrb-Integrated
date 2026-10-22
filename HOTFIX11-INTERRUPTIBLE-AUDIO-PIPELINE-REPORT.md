# Jarvis Release 6 — Hotfix 11 report

**Version:** 1.8.24 (`versionCode 160`)

## Changes made

### Audio playback and interruption
- Replaced long blocking `AudioTrack.write()` operations with `WRITE_NON_BLOCKING` partial writes. Partial writes are retried and valid zero-write backpressure does not immediately drop the rest of a PCM chunk.
- Bounded a permanently stalled output sink at 2.5 seconds and surface an error instead of leaving the UI in `SPEAKING` indefinitely.
- Added playback generations so a user interruption/flush invalidates PCM already queued or held by the worker.
- Serialized late amplitude/progress callbacks against flush/release; stale callbacks cannot restore the prior response's orb amplitude or caption clock after interruption.
- Ignore a write error from an obsolete generation so it cannot clear the new turn or display a false error after a user interruption.
- Preserve short final audio chunks and count progress after partial writes.
- Keep the completion gate dependent on provider turn completion, an empty app queue, the hardware playback head draining, and a short drain window.

### User interruption and live state
- Flush the actual `AudioTrack` at the interruption boundary, before tool cancellation and cleanup can race with a stale `IDLE` callback.
- Keep stale provider output suppressed while speaker audio remains queued/playing, even if the model has already reported its turn complete.
- Preserve the user's onset audio when the interruption and microphone callback race.

### Gemini Live tool behavior
- Explicitly mark all 21 native phone/search/UI actions `behavior: BLOCKING` so each action's result is awaited before the model proceeds. Gemini 3.8 Live uses non-blocking tool execution by default; blocking mode is available for sequential workflows. This matters for actions that must inspect the resulting screen before issuing the next action.
- Retain setup-complete gating, canonical protocol field names, enabled input/output transcription, and separate `generationComplete` vs `turnComplete` handling.

## Tests completed

- 13 JavaScript regression scripts passed, covering audio completion, caption accumulation/dust-out, Live API protocol, error classification, model migration, setup/transcription, orb-state stability, text-only captions, and all nine Thinking Orb animation states.
- The production `AudioPlayer.kt` was compiled and run with Android `AudioTrack` test doubles across five harness scenarios:
  1. a 16 KB PCM chunk was drained through 64 partial writes and transient zero-write backpressure without dropping bytes;
  2. an empty software queue did not finish the turn before the simulated hardware playhead drained;
  3. a flush during a delayed native write stopped stale PCM from being written after the flush returned;
  4. a permanently stalled sink reported an error and left `SPEAKING` within the bounded deadline;
  5. delayed amplitude/progress callbacks could not restore non-zero state after a flush returned.
- 40,006 playback-completion truth-table checks passed.
- 100 caption timelines and 20 dust/cleanup cases passed.
- 48 audio bridge edge/throttle cases and 16 barge-in state combinations passed.
- All 21 Gemini 3.8 Live tool declarations were checked for explicit blocking behavior.
- All 133 Android XML files parsed, and the repository's static project checks passed.
- A Kotlin parser scan on `JarvisConversationService.kt` found no syntax/parser diagnostics. Standalone compilation still reports unresolved application/Android dependencies because the complete project classpath was unavailable.
- ZIP archive integrity was checked after packaging.

## Limitations — not device-verified

A full Android Gradle build and physical-device playback test could not be completed in this environment. `./gradlew :app:compileDebugKotlin --no-daemon` failed while downloading Gradle 8.9 with `java.net.UnknownHostException: services.gradle.org`, and neither `ANDROID_HOME` nor `ANDROID_SDK_ROOT` is configured. Thus this report does **not** claim that an APK was built or that every device-level issue is resolved. Hardware acoustic echo cancellation and microphone barge-in behavior still need testing on the target phone.

## Official references consulted

- Google Gemini 3.8 Live migration/model guidance: https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live
- Google Live API tool behavior and blocking/non-blocking function execution: https://ai.google.dev/gemini-api/docs/live-api/tools
- Android `AudioTrack.write()` non-blocking and short-write semantics: https://developer.android.com/reference/android/media/AudioTrack

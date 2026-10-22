# Jarvis Release 6 — Orb Dust + Model-Voice Welcome

- Fixed text-to-particle glyph rasterization on Android WebView versions that omit Canvas `fontBoundingBoxAscent/Descent`.
- Enlarged the primary Speaking Orb and its caption; removed the separate top Thinking Orb canvas from the Home screen to avoid competing/covering animation layers. Chat can continue to use its own thinking-orb surface.
- Replaced the first-launch Android `TextToSpeech` greeting with a greeting request sent through the configured Gemini Live model voice. The `hasSpokenWelcome` flag is set only when that model audio begins playback.
- Application ID remains `com.jarvisai.app`.

Android APK compilation was not run in this environment.

## Verification executed

- `node --check` for `speaking-orb.js`, `thinking-orb-engine.js`, and the animation verification script.
- `node scripts/verify-thinking-orbs.js` — all nine geometric presets rendered non-zero canvas operations; controller and dust-cleanup bridge checks passed.
- `python scripts/verify-jarvis-project.py` — all static project checks passed, including Android XML parsing, theme defaults, history/chat affordances, app ID, dust fallback, and model-voice welcome wiring.

The Android APK itself was not built, per requested workflow.


## Hotfix 1 — live speech dust timeline

- Fixed a playback-clock race where assistant transcript words could be scheduled against the previous response's AudioTrack position before the next audio stream started.
- New transcript lines wait for the next playback epoch when audio is inactive; their estimated word timeline starts at zero rather than inheriting a stale playhead.
- When playback begins or the AudioTrack playhead resets, pending word timestamps are re-anchored to the new hardware playback clock, and caption dust resumes immediately.
- Added a regression test that simulates a stale 6.4-second previous playback position and verifies re-anchoring to zero.
- Package ID remains `com.jarvisai.app`; this earlier hotfix used versionCode 145, versionName 1.8.9.

## Hotfix 2 — caption dust exit + insult session shutdown

- Kept the live caption timeline tied to actual AudioTrack playback; transcript text arriving before playback no longer forces the orb into the speaking state.
- Added a timed outgoing-particle dissolution when a caption expires, is replaced by a new word group, or is cleared at the end of a response. Existing incoming caption particles remain on the same canvas pass.
- Reset the DOM caption-group key when a transcript is restarted, preventing spans from an older response being reused.
- Normalized Turkish dotless `ı` before matching direct insults aimed at Jarvis; the existing session shutdown path stops/releases `AudioRecord` and disconnects the Live WebSocket.
- Added deterministic checks for 20 different caption-dust cases, including movement, finite coordinates, cleanup, and reduced-motion settings.
- Bumped Android package version to versionCode 146 / versionName 1.8.10.
- Android APK compilation and a physical-device microphone check were not possible in this environment: the Gradle distribution download is blocked by unavailable network access and no Android SDK is installed. These items must be confirmed in an Android build/device run.


## Hotfix 3 — cumulative spoken sentences + final dust-out

- Fixed the root cause of premature dust: the live-caption DOM key previously included the growing word count, so every incoming token could dismiss the words that had just appeared. A single assistant turn now keeps one stable caption line and appends words in audio-time order.
- Previously spoken words and sentences remain visible while later sentences assemble from particles. The entire completed answer is captured into dust exactly once after the final word and hold interval; text is no longer faded to invisibility before the dust animation starts.
- Added a monotonic post-playback caption clock, allowing final text to remain visible briefly and then dissolve even after AudioTrack stops advancing. Explicit clear requests during an active/waiting spoken response are deferred so they cannot erase the text mid-sentence.
- Added regression coverage for 20 varied streaming timelines with 3–25 words each, sentence punctuation, active-audio holding, end-of-playback timing, complete-text particle capture, and repeated-render cleanup.
- Bumped Android package version to versionCode 147 / versionName 1.8.11.
- JavaScript and project static tests pass. Android APK compilation and physical-device validation could not be run here: no Android SDK is configured, and the Gradle wrapper distribution download is blocked by unavailable network access.


## Hotfix 4 — prevent mid-sentence audio cutoff and preserve dust captions

- Removed the unconditional 800 ms queue-idle completion fallback. A network gap no longer changes SPEAKING to IDLE or finalizes the assistant turn while more PCM may still arrive. Playback completes only after the protocol's turn-complete signal and after the AudioTrack playhead renders all queued frames.
- Reset the turn-complete flag on PCM enqueue, not on first dequeue. This preserves a completion signal that arrives while the first audio chunk is waiting in the playback queue.
- Kept the completed assistant transcript in the live caption bridge for a short tail interval so StateFlow clearing cannot overtake the final visible text and prevent its dust-out. New user/assistant turns cancel the pending clear.
- Added a pure playback-completion policy used by the audio worker and targeted JVM regression tests.
- Bumped Android package version to versionCode 148 / versionName 1.8.12.
- Static/JavaScript checks can run in this environment. An Android build and physical-device playback/microphone test still require a configured Android SDK and device.
- Expanded deterministic stress coverage to 100 incremental multi-sentence caption timelines and more than 30,000 audio completion-gate truth-table checks.


## Hotfix 5 — output continuity, caption end-edge, and prose typography

- The Home-to-WebView audio bridge now sends playback start/stop edges immediately even if the 30fps animation throttle would otherwise drop them; this prevents the WebView remaining stuck in `externalAudioActive=true` and never starting final dust-out.
- Gemini `interrupted` events now mark the current PCM segment terminal so queued audio can drain and the local conversation state cannot remain stuck in SPEAKING waiting for a missing `turnComplete`.
- During THINKING, the app no longer sends fresh ambient microphone frames back into the active Gemini Live response. On speakerphone, the user can interrupt through the on-screen mic/orb; headset voice barge-in during playback remains available.
- Added a Claude-inspired editorial typography treatment to assistant prose (serif, calmer line spacing, heading hierarchy, bullets, quote blocks, inline code and links) while preserving monospace syntax-highlighted code fences.
- Package version bumped to versionCode 149 / versionName 1.8.13.


## Hotfix 6 — transcript chunk fidelity, reload recovery, and real chat replay

- Fixed partial-word caption updates in the actual `orb.html` `appendText` function. If output arrives as `"Merha"` followed by `"ba dünya"`, the existing token is extended to `"Merhaba"` and only `"dünya"` becomes a new token. Punctuation stays attached to the word and the full line remains stable until the final dust-out.
- Removed the service's guessed-space insertion between Gemini transcript deltas. The new `StreamingTextAccumulator` preserves exact provider chunk boundaries and only removes a duplicate literal space at a boundary; it does not split partial words or collapse line breaks.
- The Home WebView audio bridge now invalidates its old state on page start and immediately re-seeds from the current playback `StateFlow`s after load, so a reload during playback does not lose the final `playbackActive=false` edge.
- Replaced the chat replay button's toast-only placeholder with actual Android `TextToSpeech` playback in Turkish, including Markdown/code cleanup, status feedback, a package-visibility query for TTS engines, and lifecycle stop/shutdown. The Jarvis/Code chips are now explicitly static status labels rather than nonfunctional clickable controls.
- Assistant prose formatting uses an editorial serif type treatment with Markdown heading/list/quote hierarchy, inline emphasis, clickable links, and monospace code fences.
- Package version bumped to versionCode 150 / versionName 1.8.14.
- JavaScript caption tests execute the actual `appendText` implementation from `orb.html`; the transcript accumulator was independently compiled with `kotlinc` and exercised over 2,000 randomly partitioned transcripts. Android Gradle build and real-device voice verification remain blocked in this environment because Gradle 8.9 cannot be downloaded (`UnknownHostException: services.gradle.org`) and no Android SDK is configured.


## Hotfix 7 — Voice continuity and stable Orb state (1.8.16)

- Orb SPEAKING begins only when the actual AudioTrack playback callback fires, not when a network packet arrives.
- Async IDLE/LISTENING/THINKING events are prevented from downgrading SPEAKING while speaker output is still active, except during explicit user barge-in.
- Orb primary-state updates are idempotent; repeated text/audio notifications no longer reset the same animation state.
- The microphone remains locally monitored during playback. A sustained voice can interrupt through a headset, with hardware AEC, or through a more conservative sustained-voice fallback on devices without active AEC. RMS-only speakerphone detection still cannot perfectly distinguish every user voice from acoustic echo on every device.
- Confirmed voice barge-in flushes assistant output, preserves the new user utterance state, bypasses the post-playback echo guard, and immediately sends the triggering PCM frame when owner-voice policy allows it.
- Devices without active AEC use a sustained near-end voice fallback tuned to remain responsive at high output levels while rejecting lower-level leakage; this remains an acoustic heuristic, not a perfect voice/echo separator.
- A short post-playback echo guard and elevated tail threshold reduce false LISTENING/THINKING transitions caused by room reverberation.

- Audio completion now requires the provider's turn-complete/interrupted terminal event, an empty PCM queue, and confirmation that AudioTrack's playhead rendered all written samples. Queue-idle time alone cannot truncate the reply. The terminal decision is synchronized with PCM enqueue to prevent a stale queue-empty read from briefly switching the Orb out of SPEAKING.
- The Orb's task-state, thinking-animation, and primary-state setters are idempotent: repeated duplicate status events no longer restart their animation timers.
- Added speakerphone barge-in fallback requiring sustained voice relative to output amplitude on devices without hardware AEC; the current utterance's onset buffer is preserved while the assistant is flushed.
- Verification scripts now perform 40,006 playback policy checks and cover 100 streaming caption timelines, text chunk boundaries, nine Orb animation modes, and all 133 Android XML files. Pure Kotlin helper tests compile independently; Android Gradle/APK and physical-device tests remain blocked here because Gradle cannot be downloaded and the Android SDK is not configured.

- Package version bumped to versionCode 152 / versionName 1.8.16.


## Hotfix 9 — Interrupt & State Repair (1.8.20 / 156)

- Server interruption now immediately flushes speaker and headset output.
- Interrupted frames cannot requeue stale modelTurn audio/text.
- Interrupted fallback transcription state is cleared to protect the next answer.
- Delayed stale PCM no longer cancels the replacement-turn watchdog.
- Orb state returns to actual listening/idle status after flush.


## Hotfix 10 — Audio State, Handshake & Transcript Repair (1.8.21 / 157)

- Removed a duplicate `@Volatile` annotation that could stop Kotlin compilation.
- Require the Live API `setupComplete` handshake before marking the socket ready.
- Process interruption edges before assistant transcript/audio callbacks so trailing output is dropped.
- Route speech through the communication audio path where the OS confirms the device selection; trust AEC only for a confirmed route.
- Synchronize transcript snapshots/clears and serialize concurrent conversation-state transitions.
- Keep the Orb's actual SPEAKING/THINKING state visible while the microphone is muted; map SEARCHING to its real animation.
- Corrected API settings copy: the build does not ship an embedded key.
- Validation: 12 JavaScript regression scripts, 133 XML parse checks, and a standalone Kotlin helper harness passed. Gradle/APK and physical-device validation remain unavailable because the Gradle distribution host cannot be resolved and no Android SDK/device is present.

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
- Package ID remains `com.jarvisai.app`; versionCode 145, versionName 1.8.9.

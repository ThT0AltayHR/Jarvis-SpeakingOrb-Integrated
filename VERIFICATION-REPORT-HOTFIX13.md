# Jarvis Release 6 — Hotfix 13 (Caption/Dust + Faster Replies)

**Version:** 1.8.26 (`versionCode 162`)

## 1. Root cause: captions never appeared (no text, no dust)
`orb.html` ran `orb.level = ...` inside `setExternalAudio()`. `SpeakingOrb.level` is a **getter-only** accessor and the
script runs in `'use strict'`, so every audio sample threw a `TypeError` *before* `externalAudioActive` was set.
Result: the sphere still "spoke" (Kotlin drives its state), but captions were hidden forever because the caption
renderer believed no audio was playing, so there was nothing to turn into dust.
Fix: audio level is stored in `orb.externalAudioLevel` only; the read-only `level` is never assigned.

## 2. Dust timing
External (AudioTrack) captions now turn to dust ~0.9 s after the voice really stops (was ~1.8 s after the last
estimated word). Hard cap 2.2 s, measured from the later of voice-end / line creation.
Transcript words that trail the audio *after* the dust-out are shown and dusted as their own short line instead of
being silently dropped.

## 3. Slow replies ("thinking too long")
* `gemini-3.8-live` does **not** accept `thinkingConfig` (Google docs: omit it) — nothing to lower there; not touched.
* While `THINKING`, the mic stream used to stop completely ~450 ms after the last word, so Gemini's server-side
  end-of-speech detector could be left waiting for trailing silence. Now **quiet** frames (below the local VAD
  threshold) keep streaming for `TRAILING_SILENCE_FORWARD_MS = 1500` after the last voiced frame. Loud/voiced frames are
  still dropped, so room noise cannot interrupt the answer. Set the constant to `0L` to restore the old behaviour.
* System prompt: added "plain conversation = no deliberation" line (no tools / planning for chit-chat).
* If **Owner voice** is enabled, mic audio is held until the speaker check passes — that alone adds noticeable delay.

## Verification
* New `scripts/verify-orb-runtime-captions.js` runs the *real* `speaking-orb.js` + `orb.html` script in a DOM stub
  (`scripts/orb-runtime-sim/`): words appear, no mid-sentence dust, dust after voice end, late words, next answer.
* All 15 JS verification scripts and `verify-jarvis-project.py` pass (version guards moved to 1.8.26 / 162; the
  caption-hold and THINKING-gate source guards were updated to the new, intentional behaviour).

## NOT verified
No Gradle/APK build, no device. The Kotlin edit (`JarvisConversationService.kt`) is un-compiled, and the latency
change is a reasoned fix, not a measured one. Real-phone timing of captions/dust and reply speed must be checked.

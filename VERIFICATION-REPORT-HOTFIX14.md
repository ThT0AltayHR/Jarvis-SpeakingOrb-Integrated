# Jarvis Release 6 — Hotfix 14 (Fast replies, restored from the 1.8.7 build)

Version 1.8.27 (versionCode 163). Compared against the old, fast 1.8.7 (Part 7) source.

## Findings (old fast build vs. current)
1. **Model.** Old default: `gemini-3.1-flash-live-preview` (its own UI label: "Ultra-Low Latency Live ~700ms").
   Hotfix 8 force-migrated everyone to `gemini-3.8-live` and removed 3.1 from the picker.
2. **Mic stream while "thinking".** Old build streamed mic audio to Gemini continuously (only muted while the
   speaker plays). Hotfix 8-12 stopped streaming ~450 ms after the last word, so Gemini's server-side
   end-of-speech detector could be left waiting for silence.
3. Tool declarations: current build sends `"behavior":"BLOCKING"` on all 21 tools, old build did not.

## Changes
* Default model back to `gemini-3.1-flash-live-preview`; both 3.1 and 3.8 selectable in the API screen.
  One-time preference migration moves existing installs (saved = 3.8) to the fast default; afterwards the user's
  choice is respected.
* For 3.1 the setup payload omits per-tool `behavior` (matches the old build).
* Mic keeps streaming while THINKING (owner-voice gate and speaker-active mute unchanged).
  The Hotfix 13 "quiet frames only" window was removed in favour of the old, proven behaviour.
* Updated guard scripts (model allow-list, THINKING mic gate, versions). All 15 JS checks + project check pass.

## NOT verified
No build and no device run. Which of the two causes (model vs. mic stream) accounts for most of the delay is
unmeasured; both are restored to the old behaviour. If 3.1 is ever retired by Google, pick 3.8 in the API screen.

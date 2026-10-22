# Jarvis Hotfix 8 — Live Transcript Repair

## Source-level changes

- Corrected Gemini Live setup field names to the documented JSON API names (`systemInstruction`, `generationConfig`, `responseModalities`, `speechConfig`, `voiceConfig`, `prebuiltVoiceConfig`, `voiceName`, `thinkingConfig`, `thinkingBudget`).
- Added top-level `inputAudioTranscription: {}` and `outputAudioTranscription: {}` so input and generated speech transcripts are requested from Gemini Live.
- Fixed the home Orb's task-state bridge: task status now changes the visible main Orb between supported state visuals, but `SPEAKING`/`LISTENING` always take priority. Repeated state updates remain idempotent.
- Added a text-only caption lifecycle fallback: if a completed model turn has transcript text but never starts AudioTrack playback, the line gets a synthetic caption clock instead of waiting for audio forever. Actual audio playback always retains the real AudioTrack timeline.
- Added guarded finalization of text-only turns so they do not remain in the accumulation buffer indefinitely. New transcript/audio cancels the pending cleanup window.
- Version: 1.8.17 (versionCode 153).

## Validation status

The project source must be retested after packaging. Source-level checks do not replace Android build or device tests. This environment has no configured Android SDK and the Gradle wrapper requires an unavailable network download, so no APK/device validation is claimed.

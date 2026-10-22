# Hotfix 12 — Playback Clock & Orb State Repair

This release addresses three source-level audio/state defects: output amplitude is synchronized to the physical AudioTrack playhead; stale conversation-state events cannot steal the Orb while output PCM is queued/playing; and the current PCM chunk is replayed from its beginning when AudioTrack has to be rebuilt after a native write error.

See `VERIFICATION-REPORT-HOTFIX12.md` for tests and the Android build/device-test limitation.

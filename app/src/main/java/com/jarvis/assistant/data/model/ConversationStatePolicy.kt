package com.jarvis.assistant.data.model

/** Guards the visual state against stale async events while PCM is queued or actively rendered. */
internal object ConversationStatePolicy {
    fun shouldApply(
        current: ConversationState,
        requested: ConversationState,
        playbackActive: Boolean,
        userInterrupting: Boolean
    ): Boolean {
        if (current == requested) return false

        // PCM may already be queued while the AudioTrack callback that publishes SPEAKING is still
        // pending. In that window, stale IDLE/LISTENING/THINKING events must not steal the Orb.
        // The explicit user-interrupt path is the only exception: it flushes playback first.
        if (playbackActive && !userInterrupting && requested != ConversationState.SPEAKING) {
            return false
        }
        return true
    }
}

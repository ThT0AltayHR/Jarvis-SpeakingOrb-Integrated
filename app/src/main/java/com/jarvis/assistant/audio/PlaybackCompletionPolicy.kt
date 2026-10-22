package com.jarvis.assistant.audio

/**
 * End-of-playback gate shared by the AudioPlayer worker and its regression tests.
 * Queue-idle duration alone must never interrupt a response mid-sentence.
 */
internal object PlaybackCompletionPolicy {
    fun shouldFinish(
        turnComplete: Boolean,
        queueEmpty: Boolean,
        allSamplesPlayed: Boolean,
        idlePolls: Int
    ): Boolean = turnComplete && queueEmpty && allSamplesPlayed && idlePolls >= 8
}

package com.jarvis.assistant.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Invalidates queued/in-flight playback immediately when the user interrupts a response.
 * A writer must check its captured generation before each non-blocking PCM write.
 */
internal class PlaybackGenerationGate {
    private val generation = AtomicLong(0L)

    fun current(): Long = generation.get()

    fun advance(): Long = generation.incrementAndGet()

    fun isCurrent(candidate: Long): Boolean = generation.get() == candidate
}

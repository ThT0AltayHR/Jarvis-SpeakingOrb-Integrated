package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit,
    private val onPlaybackProgress: (elapsedMs: Long) -> Unit = {},
    private val onPlaybackError: (message: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioPlayer"
        // Non-blocking writes normally make progress as AudioTrack drains. If the sink stays
        // stalled this long, fail visibly instead of leaving the conversation stuck in SPEAKING.
        private const val MAX_WRITE_STALL_MS = 2500L
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private data class QueuedPcm(val generation: Long, val bytes: ByteArray)
    private data class PlaybackSegment(
        val generation: Long,
        val startFrame: Long,
        var endFrame: Long,
        val amplitude: Float
    )

    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    // Serializes PLAYING/IDLE callbacks with flush/release so a late worker callback cannot
    // resurrect SPEAKING after an interrupt has already published LISTENING.
    private val playbackStateLock = Any()
    private val audioQueue = LinkedBlockingQueue<QueuedPcm>()
    // Incremented synchronously at interruption. A chunk already held by the worker becomes
    // stale immediately, even if it has not yet made it through the PCM queue.
    private val playbackGeneration = PlaybackGenerationGate()
    // Serializes enqueue with the terminal playback decision to prevent a racing PCM packet
    // from being discarded by a stale completion read.
    private val completionLock = Any()
    // Amplitude segments follow the hardware playhead. This avoids using the RMS of a PCM packet
    // that was just queued but may not be audible yet (important for AEC/barge-in and Orb motion).
    private val playbackSegments = ArrayDeque<PlaybackSegment>()
    private var lastPublishedAmplitude = -1f
    private val isRunning = AtomicBoolean(false)
    private val isPlayingState = AtomicBoolean(false)
    @Volatile private var playbackHeadStart = 0L
    @Volatile private var framesWrittenThisTurn = 0L
    // Preserve a trailing half-sample when AudioTrack reports an odd partial byte count.
    private var pendingWrittenByte = 0
    private var playbackThread: Thread? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize <= 0) {
                Log.e(TAG, "Invalid AudioTrack minimum buffer size: $minBufferSize")
                audioTrack = null
                return
            }
            // Keep enough headroom for network jitter, but never hold this lock through a blocking
            // write. The actual PCM writes below use WRITE_NON_BLOCKING so interruption stays fast.
            val bufferSize = maxOf(minBufferSize * 4, 48_000)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack builder returned an uninitialized track")
                try { track.release() } catch (_: Exception) {}
                audioTrack = null
            } else {
                audioTrack = track
            }
        } catch (e: Exception) {
            audioTrack = null
            Log.e(TAG, "AudioTrack initialization failed; it will be retried on the next PCM write: ${e.message}", e)
        }
    }

    private val isTurnComplete = AtomicBoolean(false)

    fun notifyTurnComplete() {
        synchronized(completionLock) {
            isTurnComplete.set(true)
        }
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        playbackThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            var silenceCounter = 0
            framesWrittenThisTurn = 0L
            pendingWrittenByte = 0

            while (isRunning.get()) {
                var iterationGeneration = playbackGeneration.current()
                try {
                    if (!isPlayingState.get()) {
                        val firstPacket = audioQueue.poll(30, TimeUnit.MILLISECONDS)
                        if (firstPacket != null && firstPacket.bytes.isNotEmpty()) {
                            iterationGeneration = firstPacket.generation
                            if (firstPacket.generation != playbackGeneration.current()) continue
                            val firstChunk = firstPacket.bytes
                            // Completion is reset when a new PCM chunk is enqueued, not here.
                            // Linearize this edge against flush(); otherwise a flush could set
                            // IDLE and then lose the race to a stale PLAYING callback.
                            val acceptedStart = synchronized(playbackStateLock) {
                                if (!playbackGeneration.isCurrent(firstPacket.generation)) {
                                    false
                                } else {
                                    framesWrittenThisTurn = 0L
                                    pendingWrittenByte = 0
                                    playbackSegments.clear()
                                    lastPublishedAmplitude = -1f
                                    if (isPlayingState.compareAndSet(false, true)) {
                                        onPlaybackProgress(0L)
                                        onPlaybackStateChanged(true)
                                    }
                                    true
                                }
                            }
                            if (!acceptedStart) continue
                            silenceCounter = 0

                            // The playhead, not the WebSocket arrival time, is the caption clock.
                            synchronized(trackLock) {
                                var track = audioTrack
                                if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                                    initAudioTrack()
                                    track = audioTrack
                                }
                                track?.let { active ->
                                    playbackHeadStart = active.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                                    if (active.state == AudioTrack.STATE_INITIALIZED && active.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                        try {
                                            active.play()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error calling play(): ${e.message}")
                                        }
                                    }
                                }
                            }

                            val chunkAmplitude = AudioUtils.calculateRmsNormalized(firstChunk, firstChunk.size)
                            val written = writeToTrack(firstChunk, firstPacket.generation, chunkAmplitude)
                            if (firstPacket.generation != playbackGeneration.current()) continue
                            if (written < firstChunk.size) {
                                failCurrentPlayback(
                                    "Audio output could not write the complete PCM chunk ($written/${firstChunk.size} bytes).",
                                    expectedGeneration = firstPacket.generation
                                )
                                continue
                            }
                            publishPlaybackProgress(firstPacket.generation)
                        } else {
                            if (firstPacket == null && audioQueue.isEmpty()) onPlaybackProgress(0L)
                        }
                        continue
                    }

                    val packet = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                    if (packet != null && packet.bytes.isNotEmpty()) {
                        iterationGeneration = packet.generation
                        if (packet.generation != playbackGeneration.current()) continue
                        silenceCounter = 0
                        val chunk = packet.bytes
                        val chunkAmplitude = AudioUtils.calculateRmsNormalized(chunk, chunk.size)
                        val written = writeToTrack(chunk, packet.generation, chunkAmplitude)
                        if (packet.generation != playbackGeneration.current()) continue
                        if (written < chunk.size) {
                            failCurrentPlayback(
                                "Audio output could not write the complete PCM chunk ($written/${chunk.size} bytes).",
                                expectedGeneration = packet.generation
                            )
                            continue
                        }
                        publishPlaybackProgress(packet.generation)
                    } else {
                        silenceCounter++
                        // Don't report speech as finished just because the network queue is empty.
                        // Wait until AudioTrack's hardware playhead has actually rendered all PCM.
                        val idleGeneration = playbackGeneration.current()
                        val allSamplesPlayed = playedFramesThisTurn() >= framesWrittenThisTurn
                        // Queue mutations and state callbacks share a lock order (state -> completion)
                        // so an arriving chunk cannot be queued between the final empty check and IDLE.
                        val transitionedToFinished = synchronized(playbackStateLock) {
                            if (!playbackGeneration.isCurrent(idleGeneration)) {
                                false
                            } else {
                                val mayFinish = synchronized(completionLock) {
                                    PlaybackCompletionPolicy.shouldFinish(
                                        turnComplete = isTurnComplete.get(),
                                        queueEmpty = audioQueue.isEmpty(),
                                        allSamplesPlayed = allSamplesPlayed,
                                        idlePolls = silenceCounter
                                    )
                                }
                                if (mayFinish && isPlayingState.compareAndSet(true, false)) {
                                    playbackSegments.clear()
                                    lastPublishedAmplitude = 0f
                                    onPlaybackStateChanged(false)
                                    onPlaybackAmplitude(0f)
                                    true
                                } else false
                            }
                        }

                        if (transitionedToFinished) Log.d(TAG, "Playback turn drained; AudioTrack reached IDLE")
                        publishPlaybackProgress(idleGeneration)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected playback worker error: ${e.message}", e)
                    failCurrentPlayback(
                        "Audio playback failed unexpectedly: ${e.message ?: "unknown output error"}",
                        expectedGeneration = iterationGeneration
                    )
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueueAudio(pcmData: ByteArray) {
        if (pcmData.size >= 2) {
            // Copy to transfer ownership to the queue; the caller may reuse its receive buffer.
            val validLen = pcmData.size and 1.inv()
            val aligned = pcmData.copyOf(validLen)
            // Atomic with the worker's end-of-turn decision so a racing chunk cannot be lost.
            synchronized(playbackStateLock) {
                synchronized(completionLock) {
                    isTurnComplete.set(false)
                    audioQueue.offer(QueuedPcm(playbackGeneration.current(), aligned))
                }
            }
        }
    }

    /**
     * Instantly flushes audio playback upon interruption.
     */
    fun flush() {
        synchronized(playbackStateLock) {
            synchronized(completionLock) {
                playbackGeneration.advance()
                audioQueue.clear()
                playbackSegments.clear()
                lastPublishedAmplitude = 0f
                isTurnComplete.set(true)
            }
            synchronized(trackLock) {
                try {
                    audioTrack?.let { track ->
                        if (track.state == AudioTrack.STATE_INITIALIZED) {
                            // Non-blocking writes mean this lock is held only for one short native
                            // write. Pause + flush discards everything still queued at the sink.
                            try { track.pause() } catch (_: Exception) {}
                            try { track.flush() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
                }
            }
            if (isPlayingState.getAndSet(false)) {
                onPlaybackStateChanged(false)
                onPlaybackAmplitude(0f)
            }
            onPlaybackProgress(0L)
        }
    }

    fun isPlaying(): Boolean = isPlayingState.get() || !audioQueue.isEmpty()

    fun release() {
        synchronized(playbackStateLock) {
            synchronized(completionLock) {
                playbackGeneration.advance()
                audioQueue.clear()
                playbackSegments.clear()
                lastPublishedAmplitude = 0f
                isTurnComplete.set(true)
            }
            if (isPlayingState.getAndSet(false)) {
                onPlaybackStateChanged(false)
                onPlaybackAmplitude(0f)
            }
            onPlaybackProgress(0L)
        }
        isRunning.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        try { it.pause() } catch (_: Exception) {}
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack: ${e.message}", e)
            } finally {
                audioTrack = null
            }
        }
    }

    /** Fails the current audio turn visibly rather than silently dropping the rest of speech. */
    private fun failCurrentPlayback(message: String, expectedGeneration: Long? = null) {
        // A user interrupt may race a write error. Do not let an error from the old generation
        // clear the new turn or display a false playback error after flush has already won.
        val handled = synchronized(playbackStateLock) {
            if (expectedGeneration != null && !playbackGeneration.isCurrent(expectedGeneration)) {
                return@synchronized false
            }
            synchronized(completionLock) {
                playbackGeneration.advance()
                audioQueue.clear()
                playbackSegments.clear()
                lastPublishedAmplitude = 0f
                isTurnComplete.set(true)
            }
            synchronized(trackLock) {
                try {
                    audioTrack?.let { track ->
                        if (track.state == AudioTrack.STATE_INITIALIZED) {
                            try { track.pause() } catch (_: Exception) {}
                            try { track.flush() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to reset AudioTrack after write failure: ${e.message}")
                }
            }
            if (isPlayingState.getAndSet(false)) {
                onPlaybackStateChanged(false)
                onPlaybackAmplitude(0f)
            }
            onPlaybackProgress(0L)
            true
        }
        if (!handled) return
        Log.e(TAG, message)
        try { onPlaybackError(message) } catch (e: Exception) {
            Log.e(TAG, "Playback error callback failed: ${e.message}", e)
        }
    }

    private fun playedFramesThisTurn(): Long {
        val head = synchronized(trackLock) {
            val track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) null
            else track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } ?: return 0L
        val delta = if (head >= playbackHeadStart) head - playbackHeadStart
            else (1L shl 32) - playbackHeadStart + head
        return delta.coerceAtLeast(0L)
    }

    /**
     * Publish caption clock and output level from the actual AudioTrack playhead. The newest
     * queued packet may be far ahead of what's audible, so it must never drive the microphone's
     * echo threshold or the Orb amplitude directly.
     */
    private fun publishPlaybackProgress(expectedGeneration: Long) {
        synchronized(playbackStateLock) {
            if (!playbackGeneration.isCurrent(expectedGeneration)) return
            val playedFrames = playedFramesThisTurn().coerceAtMost(framesWrittenThisTurn)

            // Remove already-rendered segments, keeping only audio that is still at/after the
            // hardware playhead. A segment begins contributing once at least one of its frames
            // has actually played; at a boundary the level momentarily drops rather than looking
            // ahead to the next unrendered chunk.
            while (playbackSegments.isNotEmpty() && playbackSegments.peekFirst().endFrame <= playedFrames) {
                playbackSegments.removeFirst()
            }
            val activeSegment = playbackSegments.firstOrNull { segment ->
                segment.generation == expectedGeneration &&
                    playedFrames > segment.startFrame && playedFrames < segment.endFrame
            }
            val amplitude = if (isPlayingState.get() && activeSegment != null) {
                activeSegment.amplitude
            } else 0f

            if (lastPublishedAmplitude < 0f || kotlin.math.abs(amplitude - lastPublishedAmplitude) >= 0.008f) {
                lastPublishedAmplitude = amplitude
                if (playbackGeneration.isCurrent(expectedGeneration)) onPlaybackAmplitude(amplitude)
            }
            if (playbackGeneration.isCurrent(expectedGeneration)) {
                onPlaybackProgress(playedFrames * 1000L / SAMPLE_RATE)
            }
        }
    }

    private fun appendPlaybackSegmentLocked(
        generation: Long,
        startFrame: Long,
        endFrame: Long,
        amplitude: Float
    ) {
        if (endFrame <= startFrame) return
        val tail = playbackSegments.peekLast()
        if (tail != null && tail.generation == generation && tail.endFrame == startFrame &&
            kotlin.math.abs(tail.amplitude - amplitude) < 0.008f
        ) {
            tail.endFrame = endFrame
        } else {
            playbackSegments.addLast(PlaybackSegment(generation, startFrame, endFrame, amplitude))
        }
    }

    /**
     * Writes in small non-blocking operations. Android's blocking AudioTrack.write() can hold
     * trackLock for a long time, which makes flush wait while the user is trying to interrupt.
     * A generation change cancels an in-flight chunk; a zero-byte result is backpressure, not a
     * reason to drop the rest of that speech chunk.
     */
    private fun writeToTrack(chunk: ByteArray, generation: Long, amplitude: Float): Int {
        var offset = 0
        var recoveryUsed = false
        var zeroProgressSinceNs = 0L
        while (offset < chunk.size && isRunning.get() && generation == playbackGeneration.current()) {
            val writeResult = synchronized(trackLock) {
                if (!isRunning.get() || generation != playbackGeneration.current()) return offset
                var activeTrack = audioTrack
                if (activeTrack == null || activeTrack.state != AudioTrack.STATE_INITIALIZED) {
                    if (recoveryUsed) return offset
                    try { activeTrack?.release() } catch (_: Exception) {}
                    audioTrack = null
                    initAudioTrack()
                    activeTrack = audioTrack
                    recoveryUsed = true
                }
                val track = activeTrack ?: return offset
                if (track.state != AudioTrack.STATE_INITIALIZED) return offset
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try { track.play() } catch (e: Exception) {
                        Log.e(TAG, "AudioTrack play failed: ${e.message}", e)
                    }
                }
                try {
                    track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                } catch (e: Exception) {
                    Log.e(TAG, "Non-blocking AudioTrack write threw: ${e.message}", e)
                    AudioTrack.ERROR_INVALID_OPERATION
                }
            }

            // flush() advances the generation before waiting for trackLock. Drop the result of any
            // native call that raced the interrupt; it must not update captions or write counters.
            if (generation != playbackGeneration.current()) return offset

            when {
                writeResult > 0 -> {
                    offset += writeResult
                    zeroProgressSinceNs = 0L
                    // Update the played-vs-written gate after every partial native write, not after
                    // a potentially multi-second chunk has completely entered AudioTrack. This
                    // keeps caption time moving during large streaming packets.
                    val isCurrent = synchronized(playbackStateLock) {
                        if (generation == playbackGeneration.current()) {
                            val totalBytes = pendingWrittenByte + writeResult
                            val addedFrames = (totalBytes / 2).toLong()
                            val startFrame = framesWrittenThisTurn
                            framesWrittenThisTurn += addedFrames
                            pendingWrittenByte = totalBytes and 1
                            appendPlaybackSegmentLocked(
                                generation = generation,
                                startFrame = startFrame,
                                endFrame = framesWrittenThisTurn,
                                amplitude = amplitude
                            )
                            true
                        } else false
                    }
                    if (!isCurrent) return offset
                    publishPlaybackProgress(generation)
                }
                writeResult == 0 -> {
                    // Zero is documented backpressure for non-blocking writes, not a successful
                    // write. Retry while the sink drains, but don't spin forever if AudioTrack has
                    // died or its route is no longer accepting data.
                    val nowNs = System.nanoTime()
                    if (zeroProgressSinceNs == 0L) zeroProgressSinceNs = nowNs
                    val stalledForMs = (nowNs - zeroProgressSinceNs) / 1_000_000L
                    if (stalledForMs >= MAX_WRITE_STALL_MS) {
                        Log.e(TAG, "AudioTrack made no write progress for ${stalledForMs}ms")
                        return offset
                    }
                    try { Thread.sleep(2L) } catch (_: InterruptedException) { return offset }
                }
                !recoveryUsed -> {
                    Log.w(TAG, "AudioTrack write failed ($writeResult); rebuilding playback track once")
                    synchronized(trackLock) {
                        try { audioTrack?.release() } catch (_: Exception) {}
                        audioTrack = null
                        initAudioTrack()
                    }
                    recoveryUsed = true
                    if (audioTrack == null) break
                    // The old track may have had a different playhead. Restart the per-turn clock
                    // at the new sink so the unsigned 32-bit playhead wrap isn't mistaken for a
                    // completed multi-hour response.
                    if (generation != playbackGeneration.current()) return offset
                    synchronized(playbackStateLock) {
                        if (generation != playbackGeneration.current()) return offset
                        synchronized(trackLock) {
                            val newTrack = audioTrack
                            if (newTrack != null && newTrack.state == AudioTrack.STATE_INITIALIZED) {
                                playbackHeadStart = newTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                                // The previous sink's pending buffer was lost when it was released.
                                // Start a fresh completion clock on the replacement track; otherwise
                                // frames written to the dead track could keep IDLE unreachable forever.
                                framesWrittenThisTurn = 0L
                                pendingWrittenByte = 0
                                playbackSegments.clear()
                                lastPublishedAmplitude = -1f
                                // Recreating AudioTrack discards its hardware queue. Re-submit this
                                // chunk from its beginning rather than skipping bytes that were
                                // acknowledged by the old (now discarded) sink.
                                offset = 0
                                try { newTrack.play() } catch (_: Exception) {}
                            }
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "AudioTrack write unrecoverable ($writeResult); ${chunk.size - offset} bytes remain in this PCM chunk")
                    break
                }
            }
        }
        return offset
    }
}

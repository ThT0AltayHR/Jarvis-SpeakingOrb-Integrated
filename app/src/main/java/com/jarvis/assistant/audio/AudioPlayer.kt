package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit,
    private val onPlaybackProgress: (elapsedMs: Long) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private val isPlayingState = AtomicBoolean(false)
    @Volatile private var playbackHeadStart = 0L
    @Volatile private var framesWrittenThisTurn = 0L
    private var playbackThread: Thread? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // 96,000 bytes = 2 full seconds of 24kHz 16-bit mono PCM headroom.
        // Guarantees zero buffer underruns across network jitter and prevents deadlock.
        val bufferSize = maxOf(minBufferSize * 8, 96000)

        // USAGE_MEDIA maps to STREAM_MUSIC, ensuring audio plays strictly through the
        // phone's LOUDSPEAKER (or connected headphones), NEVER the call earpiece speaker.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        // Standard performance mode prevents MediaTek/Qualcomm FastMixer DMA ring buffer underrun loops ("aaaaaaaa")
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
    }

    private val isTurnComplete = AtomicBoolean(false)

    fun notifyTurnComplete() {
        isTurnComplete.set(true)
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        playbackThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            var silenceCounter = 0
            framesWrittenThisTurn = 0L

            while (isRunning.get()) {
                try {
                    if (!isPlayingState.get()) {
                        val firstChunk = audioQueue.poll(30, TimeUnit.MILLISECONDS)
                        if (firstChunk != null && firstChunk.isNotEmpty()) {
                            isTurnComplete.set(false)
                            isPlayingState.set(true)
                            silenceCounter = 0
                            framesWrittenThisTurn = 0L
                            onPlaybackProgress(0L)
                            onPlaybackStateChanged(true)

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

                            val written = writeToTrack(firstChunk)
                            framesWrittenThisTurn += (written / 2).toLong()
                            val amplitude = AudioUtils.calculateRmsNormalized(firstChunk, firstChunk.size)
                            onPlaybackAmplitude(amplitude)
                            publishPlaybackProgress()
                        } else {
                            onPlaybackProgress(0L)
                        }
                        continue
                    }

                    val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        silenceCounter = 0
                        val amplitude = AudioUtils.calculateRmsNormalized(chunk, chunk.size)
                        onPlaybackAmplitude(amplitude)
                        val written = writeToTrack(chunk)
                        framesWrittenThisTurn += (written / 2).toLong()
                        publishPlaybackProgress()
                    } else {
                        silenceCounter++
                        // Don't report speech as finished just because the network queue is empty.
                        // Wait until AudioTrack's hardware playhead has actually rendered all PCM.
                        val allSamplesPlayed = playedFramesThisTurn() >= framesWrittenThisTurn
                        val shouldFinish = allSamplesPlayed &&
                            ((isTurnComplete.get() && silenceCounter >= 8) || silenceCounter >= 40)

                        publishPlaybackProgress()
                        if (shouldFinish && isPlayingState.get()) {
                            isPlayingState.set(false)
                            onPlaybackStateChanged(false)
                            onPlaybackAmplitude(0f)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback worker: ${e.message}")
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueueAudio(pcmData: ByteArray) {
        if (pcmData.size >= 16) {
            val validLen = pcmData.size and 1.inv()
            val aligned = if (validLen == pcmData.size) pcmData else pcmData.copyOf(validLen)
            audioQueue.offer(aligned)
        }
    }

    /**
     * Instantly flushes audio playback upon interruption.
     */
    fun flush() {
        audioQueue.clear()
        isTurnComplete.set(true)
        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        try { it.pause() } catch (_: Exception) {}
                        try { it.flush() } catch (_: Exception) {}
                        try { it.stop() } catch (_: Exception) {}
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

    fun isPlaying(): Boolean = isPlayingState.get() || !audioQueue.isEmpty()

    fun release() {
        if (isPlayingState.getAndSet(false)) {
            onPlaybackStateChanged(false)
            onPlaybackAmplitude(0f)
        }
        onPlaybackProgress(0L)
        isRunning.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.clear()

        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
            } finally {
                audioTrack = null
            }
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

    private fun publishPlaybackProgress() {
        val playedFrames = playedFramesThisTurn().coerceAtMost(framesWrittenThisTurn)
        onPlaybackProgress(playedFrames * 1000L / SAMPLE_RATE)
    }

    private fun writeToTrack(chunk: ByteArray): Int {
        synchronized(trackLock) {
            var track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                initAudioTrack()
                track = audioTrack
            }
            var activeTrack = track ?: return 0
            var offset = 0
            var recoveryUsed = false

            while (offset < chunk.size) {
                if (activeTrack.state != AudioTrack.STATE_INITIALIZED) {
                    if (recoveryUsed) break
                    try { activeTrack.release() } catch (_: Exception) {}
                    initAudioTrack()
                    activeTrack = audioTrack ?: break
                    recoveryUsed = true
                }
                if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try { activeTrack.play() } catch (_: Exception) {}
                }

                val written = activeTrack.write(chunk, offset, chunk.size - offset)
                if (written > 0) {
                    offset += written
                } else if (written < 0 && !recoveryUsed) {
                    Log.w(TAG, "AudioTrack write failed ($written). Reinitializing AudioTrack...")
                    try { activeTrack.release() } catch (_: Exception) {}
                    initAudioTrack()
                    activeTrack = audioTrack ?: break
                    recoveryUsed = true
                } else {
                    // A blocking stream write should not return zero for a non-empty chunk.
                    // Stop rather than spin forever if the platform audio driver does.
                    if (written < 0) Log.e(TAG, "AudioTrack could not recover after write failure ($written)")
                    break
                }
            }
            return offset
        }
    }
}

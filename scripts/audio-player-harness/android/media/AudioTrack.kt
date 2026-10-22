package android.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AudioTrack private constructor(val bufferSizeInBytes: Int) {
    @Volatile var state: Int = STATE_INITIALIZED
    @Volatile var playState: Int = PLAYSTATE_STOPPED
    @Volatile var playbackHeadPosition: Int = 0
    @Volatile private var pendingFrames: Int = 0
    @Volatile private var released = false
    val bytesWrittenToThisTrack = AtomicLong(0)

    fun play() { if (!released) playState = PLAYSTATE_PLAYING }
    fun pause() { playState = PLAYSTATE_PAUSED }
    fun flush() { playbackHeadPosition = 0; pendingFrames = 0; flushes.incrementAndGet() }
    fun advancePlayback(frames: Int) {
        val advanced = minOf(frames.coerceAtLeast(0), pendingFrames)
        playbackHeadPosition += advanced
        pendingFrames -= advanced
    }
    fun stop() { playState = PLAYSTATE_STOPPED }
    fun release() { released = true; state = 0; playState = PLAYSTATE_STOPPED }

    fun write(data: ByteArray, offset: Int, sizeInBytes: Int, writeMode: Int): Int {
        writeStarted.countDown()
        val delay = writeDelayMillis
        if (delay > 0) Thread.sleep(delay.toLong())
        val writeCall = writeCalls.getAndIncrement()
        if (returnErrorAtWriteCall.compareAndSet(writeCall, -1)) return ERROR_INVALID_OPERATION
        if (alwaysReturnZero || zeroWritesRemaining.getAndUpdate { n -> if (n > 0) n - 1 else 0 } > 0) return 0
        if (returnErrorOnce.compareAndSet(true, false)) return ERROR_INVALID_OPERATION
        val count = minOf(sizeInBytes, maxWriteBytes)
        if (count <= 0) return 0
        totalBytesWritten.addAndGet(count.toLong())
        bytesWrittenToThisTrack.addAndGet(count.toLong())
        val frames = count / 2
        if (autoAdvancePlaybackHead) playbackHeadPosition += frames else pendingFrames += frames
        return count
    }

    class Builder {
        private var buffer = 48_000
        fun setAudioAttributes(value: AudioAttributes) = this
        fun setAudioFormat(value: AudioFormat) = this
        fun setBufferSizeInBytes(value: Int) = apply { buffer = value }
        fun setTransferMode(value: Int) = this
        fun build() = AudioTrack(buffer).also { lastCreatedTrack = it }
    }

    companion object {
        const val STATE_INITIALIZED = 1
        const val PLAYSTATE_STOPPED = 1
        const val PLAYSTATE_PAUSED = 2
        const val PLAYSTATE_PLAYING = 3
        const val MODE_STREAM = 1
        const val WRITE_NON_BLOCKING = 1
        const val ERROR_INVALID_OPERATION = -3

        @Volatile var maxWriteBytes: Int = 512
        @Volatile var writeDelayMillis: Int = 0
        @Volatile var alwaysReturnZero: Boolean = false
        val zeroWritesRemaining = AtomicInteger(0)
        val returnErrorOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val writeCalls = AtomicInteger(0)
        val returnErrorAtWriteCall = AtomicInteger(-1)
        val totalBytesWritten = AtomicLong(0)
        val flushes = AtomicInteger(0)
        @Volatile var autoAdvancePlaybackHead: Boolean = true
        @Volatile var lastCreatedTrack: AudioTrack? = null
        @Volatile var writeStarted: CountDownLatch = CountDownLatch(1)

        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
        fun resetForTest() {
            maxWriteBytes = 512
            writeDelayMillis = 0
            alwaysReturnZero = false
            zeroWritesRemaining.set(0)
            returnErrorOnce.set(false)
            writeCalls.set(0)
            returnErrorAtWriteCall.set(-1)
            totalBytesWritten.set(0)
            flushes.set(0)
            autoAdvancePlaybackHead = true
            lastCreatedTrack = null
            writeStarted = CountDownLatch(1)
        }
    }
}

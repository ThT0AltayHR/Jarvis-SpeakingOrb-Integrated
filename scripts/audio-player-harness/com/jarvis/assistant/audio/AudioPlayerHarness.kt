package com.jarvis.assistant.audio

import android.media.AudioTrack
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val end = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < end) {
        if (condition()) return true
        Thread.sleep(5L)
    }
    return condition()
}

fun main() {
    // Case 1: partial non-blocking writes and transient backpressure must preserve the whole PCM chunk.
    AudioTrack.resetForTest()
    AudioTrack.maxWriteBytes = 256
    AudioTrack.zeroWritesRemaining.set(4)
    val states = Collections.synchronizedList(mutableListOf<Boolean>())
    val errors = Collections.synchronizedList(mutableListOf<String>())
    val drained = CountDownLatch(1)
    val player = AudioPlayer(
        onPlaybackStateChanged = { playing -> states += playing; if (!playing) drained.countDown() },
        onPlaybackAmplitude = {},
        onPlaybackProgress = {},
        onPlaybackError = { errors += it }
    )
    player.start()
    val pcm = ByteArray(16_384) { (it % 127).toByte() }
    player.enqueueAudio(pcm)
    player.notifyTurnComplete()
    check(drained.await(5, TimeUnit.SECONDS)) { "normal audio did not drain to IDLE" }
    check(AudioTrack.totalBytesWritten.get() == pcm.size.toLong()) {
        "partial/backpressured PCM was lost: wrote ${AudioTrack.totalBytesWritten.get()} of ${pcm.size} bytes"
    }
    check(states.firstOrNull() == true && states.lastOrNull() == false) { "playback state callbacks were not SPEAKING -> IDLE: $states" }
    check(errors.isEmpty()) { "normal partial writes raised an error: $errors" }
    player.release()
    println("PASS production AudioPlayer drains a 16KB PCM chunk with 64 partial writes and transient zero-write backpressure")


    // Case 2: an empty software queue is not enough. Do not announce IDLE until the device playhead drains.
    AudioTrack.resetForTest()
    AudioTrack.autoAdvancePlaybackHead = false
    val waitForDrainStates = Collections.synchronizedList(mutableListOf<Boolean>())
    val waitForDrain = CountDownLatch(1)
    val playerDrain = AudioPlayer(
        onPlaybackStateChanged = { playing -> waitForDrainStates += playing; if (!playing) waitForDrain.countDown() },
        onPlaybackAmplitude = {},
        onPlaybackProgress = {},
        onPlaybackError = { throw AssertionError("unexpected drain test error: $it") }
    )
    playerDrain.start()
    val delayedPcm = ByteArray(4096) { 9 }
    playerDrain.enqueueAudio(delayedPcm)
    playerDrain.notifyTurnComplete()
    check(awaitCondition(1000) { AudioTrack.totalBytesWritten.get() == delayedPcm.size.toLong() }) { "queued audio was not written to the fake sink" }
    Thread.sleep(250)
    check(waitForDrainStates.lastOrNull() == true) { "player announced IDLE before the simulated hardware playhead drained: $waitForDrainStates" }
    AudioTrack.lastCreatedTrack?.advancePlayback(delayedPcm.size / 2)
    check(waitForDrain.await(1500, TimeUnit.MILLISECONDS)) { "player did not finish after the hardware playhead drained" }
    playerDrain.release()
    println("PASS queue-empty alone cannot finish a turn; actual simulated AudioTrack playhead drain is required")

    // Case 3: a user flush must cancel a write already in flight and prevent stale follow-up writes.
    AudioTrack.resetForTest()
    AudioTrack.writeDelayMillis = 160
    AudioTrack.maxWriteBytes = 512
    val secondErrors = Collections.synchronizedList(mutableListOf<String>())
    val player2 = AudioPlayer({ }, { }, { }, { secondErrors += it })
    player2.start()
    player2.enqueueAudio(ByteArray(64 * 1024) { 4 })
    check(AudioTrack.writeStarted.await(1, TimeUnit.SECONDS)) { "writer never entered the native write" }
    val flushDuration = measureTimeMillis { player2.flush() }
    val bytesAtFlush = AudioTrack.totalBytesWritten.get()
    Thread.sleep(250)
    check(AudioTrack.totalBytesWritten.get() == bytesAtFlush) { "stale PCM continued writing after flush returned" }
    check(flushDuration < 1000) { "flush was not prompt ($flushDuration ms)" }
    check(secondErrors.isEmpty()) { "user interruption was reported as a playback error: $secondErrors" }
    player2.release()
    println("PASS generation change cancels an in-flight PCM packet; flush returns in ${flushDuration}ms and no stale writes follow")

    // Case 4: a permanently stalled sink must report an error and leave SPEAKING, not hang forever.
    AudioTrack.resetForTest()
    AudioTrack.alwaysReturnZero = true
    val thirdStates = Collections.synchronizedList(mutableListOf<Boolean>())
    val thirdErrors = Collections.synchronizedList(mutableListOf<String>())
    val errorLatch = CountDownLatch(1)
    val player3 = AudioPlayer(
        onPlaybackStateChanged = { thirdStates += it },
        onPlaybackAmplitude = {},
        onPlaybackProgress = {},
        onPlaybackError = { thirdErrors += it; errorLatch.countDown() }
    )
    player3.start()
    player3.enqueueAudio(ByteArray(4096))
    check(errorLatch.await(4, TimeUnit.SECONDS)) { "permanently stalled sink did not produce a bounded error" }
    check(thirdStates.lastOrNull() == false) { "stalled sink left playback state active: $thirdStates" }
    check(thirdErrors.any { it.contains("complete PCM chunk") }) { "stalled sink error was not surfaced: $thirdErrors" }
    player3.release()
    println("PASS permanently stalled AudioTrack fails visibly and clears SPEAKING within bounded time")


    // Case 5: a delayed callback from a chunk must not overwrite the flush reset after barge-in.
    AudioTrack.resetForTest()
    val progressValues = Collections.synchronizedList(mutableListOf<Long>())
    val amplitudeValues = Collections.synchronizedList(mutableListOf<Float>())
    val metricEntered = CountDownLatch(1)
    val releaseMetric = CountDownLatch(1)
    val blockedOnce = AtomicInteger(0)
    val player4 = AudioPlayer(
        onPlaybackStateChanged = { },
        onPlaybackAmplitude = { amplitudeValues += it },
        onPlaybackProgress = { elapsed ->
            progressValues += elapsed
            if (elapsed > 0L && blockedOnce.compareAndSet(0, 1)) {
                metricEntered.countDown()
                releaseMetric.await(2, TimeUnit.SECONDS)
            }
        },
        onPlaybackError = { throw AssertionError("unexpected callback-race error: $it") }
    )
    player4.start()
    player4.enqueueAudio(ByteArray(8192) { 11 })
    check(metricEntered.await(1, TimeUnit.SECONDS)) { "playback progress callback never reached the deliberate race window" }
    val flushDone = CountDownLatch(1)
    Thread { player4.flush(); flushDone.countDown() }.start()
    Thread.sleep(40)
    releaseMetric.countDown()
    check(flushDone.await(1, TimeUnit.SECONDS)) { "flush did not serialize behind the in-flight callback" }
    val afterFlushProgressIndex = progressValues.size
    val afterFlushAmplitudeIndex = amplitudeValues.size
    Thread.sleep(100)
    check(progressValues.lastOrNull() == 0L) { "old playback progress replaced the reset after flush: ${progressValues.takeLast(8)}" }
    check(progressValues.drop(afterFlushProgressIndex).none { it > 0L }) { "stale non-zero progress arrived after flush returned" }
    check(amplitudeValues.drop(afterFlushAmplitudeIndex).none { it > 0f }) { "stale non-zero amplitude arrived after flush returned" }
    player4.release()
    println("PASS progress/amplitude callbacks serialize with flush; stale clock and Orb amplitude cannot reappear after interruption")

    // Case 6: amplitude is selected from the audible playhead segment, not the newest chunk written
    // ahead into the AudioTrack buffer. This keeps mic barge-in thresholds aligned with real output.
    AudioTrack.resetForTest()
    AudioTrack.autoAdvancePlaybackHead = false
    AudioTrack.maxWriteBytes = 4096
    val audibleAmplitudes = Collections.synchronizedList(mutableListOf<Float>())
    val levelsPlayer = AudioPlayer({ }, { level -> audibleAmplitudes += level }, { }, {
        throw AssertionError("unexpected level-timeline error: $it")
    })
    levelsPlayer.start()
    fun pcmWithSample(sample: Int, frames: Int): ByteArray {
        val result = ByteArray(frames * 2)
        for (i in 0 until frames) {
            result[i * 2] = (sample and 0xFF).toByte()
            result[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return result
    }
    val quietChunk = pcmWithSample(1200, 480)
    val loudChunk = pcmWithSample(12000, 480)
    levelsPlayer.enqueueAudio(quietChunk)
    levelsPlayer.enqueueAudio(loudChunk)
    levelsPlayer.notifyTurnComplete()
    check(awaitCondition(1500) { AudioTrack.totalBytesWritten.get() == (quietChunk.size + loudChunk.size).toLong() }) {
        "both chunks were not queued to the simulated sink"
    }
    Thread.sleep(70)
    check(audibleAmplitudes.none { it > 0.5f }) {
        "the queued but not-yet-played loud chunk leaked into live output amplitude: $audibleAmplitudes"
    }
    AudioTrack.lastCreatedTrack?.advancePlayback(120)
    check(awaitCondition(800) { audibleAmplitudes.any { it in 0.30f..0.40f } }) {
        "playhead did not publish the quiet chunk amplitude: $audibleAmplitudes"
    }
    val quietPeakIndex = audibleAmplitudes.indexOfLast { it in 0.30f..0.40f }
    AudioTrack.lastCreatedTrack?.advancePlayback(360)
    check(awaitCondition(800) { audibleAmplitudes.drop(quietPeakIndex + 1).any { it == 0f } }) {
        "boundary did not stop reporting the completed chunk amplitude: $audibleAmplitudes"
    }
    AudioTrack.lastCreatedTrack?.advancePlayback(1)
    check(awaitCondition(800) { audibleAmplitudes.any { it > 0.8f } }) {
        "loud amplitude was not published when the loud chunk reached the playhead: $audibleAmplitudes"
    }
    // Drain the remainder so the player exits without a false sink error.
    AudioTrack.lastCreatedTrack?.advancePlayback(479)
    levelsPlayer.release()
    println("PASS output amplitude follows the hardware playhead, not future PCM queued ahead")

    // Case 7: if AudioTrack dies after accepting a prefix, rebuilding the sink replays the current
    // PCM chunk from byte zero rather than silently skipping that prefix on the replacement track.
    AudioTrack.resetForTest()
    AudioTrack.maxWriteBytes = 512
    AudioTrack.returnErrorAtWriteCall.set(1)
    val recoveryErrors = Collections.synchronizedList(mutableListOf<String>())
    val recoveryDone = CountDownLatch(1)
    val recoveryPlayer = AudioPlayer(
        { playing -> if (!playing) recoveryDone.countDown() }, { }, { },
        { recoveryErrors += it }
    )
    recoveryPlayer.start()
    val recoveryPcm = ByteArray(4096) { 7 }
    recoveryPlayer.enqueueAudio(recoveryPcm)
    recoveryPlayer.notifyTurnComplete()
    check(recoveryDone.await(3, TimeUnit.SECONDS)) { "AudioTrack recovery did not finish the current PCM chunk" }
    check(AudioTrack.lastCreatedTrack?.bytesWrittenToThisTrack?.get() == recoveryPcm.size.toLong()) {
        "replacement sink did not receive the complete chunk from byte zero: ${AudioTrack.lastCreatedTrack?.bytesWrittenToThisTrack?.get()}"
    }
    check(recoveryErrors.isEmpty()) { "single sink rebuild should recover without terminal error: $recoveryErrors" }
    recoveryPlayer.release()
    println("PASS AudioTrack replacement replays a partially accepted PCM chunk instead of truncating its prefix")

}

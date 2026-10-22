package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBargeInDetectorTest {
    @Test
    fun headsetRequiresSeveralConsecutiveVoiceFrames() {
        val detector = VoiceBargeInDetector()
        repeat(VoiceBargeInDetector.HEADSET_REQUIRED_FRAMES - 1) {
            assertFalse(detector.observe(0.32f, 0.55f, 0.02f, true, false))
        }
        assertTrue(detector.observe(0.32f, 0.55f, 0.02f, true, false))
    }

    @Test
    fun briefLoudTransientDoesNotAccumulateAcrossQuietFrames() {
        val detector = VoiceBargeInDetector()
        repeat(VoiceBargeInDetector.SPEAKER_REQUIRED_FRAMES - 1) {
            assertFalse(detector.observe(0.38f, 0.60f, 0.02f, false, true))
        }
        assertFalse(detector.observe(0.02f, 0.60f, 0.02f, false, true))
        repeat(VoiceBargeInDetector.SPEAKER_REQUIRED_FRAMES - 1) {
            assertFalse(detector.observe(0.38f, 0.60f, 0.02f, false, true))
        }
        assertTrue(detector.observe(0.38f, 0.60f, 0.02f, false, true))
    }

    @Test
    fun speakerphoneFallbackRejectsOrdinaryEchoWithoutHardwareAec() {
        val detector = VoiceBargeInDetector()
        repeat(2_000) {
            assertFalse(detector.observe(0.15f, 0.60f, 0.02f, false, false))
        }
    }

    @Test
    fun speakerphoneFallbackCanDetectSustainedNearEndVoiceWithoutHardwareAec() {
        val detector = VoiceBargeInDetector()
        repeat(VoiceBargeInDetector.SPEAKER_FALLBACK_REQUIRED_FRAMES - 1) {
            assertFalse(detector.observe(0.38f, 0.60f, 0.02f, false, false))
        }
        assertTrue(detector.observe(0.38f, 0.60f, 0.02f, false, false))
    }


    @Test
    fun speakerphoneFallbackStillDetectsNearEndSpeechAtHighPlaybackLevel() {
        val detector = VoiceBargeInDetector()
        repeat(VoiceBargeInDetector.SPEAKER_FALLBACK_REQUIRED_FRAMES - 1) {
            assertFalse(detector.observe(0.46f, 0.85f, 0.02f, false, false))
        }
        assertTrue(detector.observe(0.46f, 0.85f, 0.02f, false, false))
    }

    @Test
    fun speakerphoneRequiresSustainedVoiceAbovePlaybackRelativeThreshold() {
        val detector = VoiceBargeInDetector()
        // Residual echo is below the AEC-aware threshold and must not interrupt.
        repeat(100) {
            assertFalse(detector.observe(0.12f, 0.60f, 0.02f, false, true))
        }
        // A sustained nearby voice rises above the output-relative threshold.
        repeat(VoiceBargeInDetector.SPEAKER_REQUIRED_FRAMES - 1) {
            assertFalse(detector.observe(0.28f, 0.60f, 0.02f, false, true))
        }
        assertTrue(detector.observe(0.28f, 0.60f, 0.02f, false, true))
    }

    @Test
    fun speakerphoneEchoBelowHalfTheOutputLevelDoesNotInterrupt() {
        val detector = VoiceBargeInDetector()
        // With no trusted AEC path, a leaked copy of the assistant at ~40% of playback RMS
        // must not stop its own reply. The ratio guard also resets after each below-threshold frame.
        repeat(2_000) {
            assertFalse(detector.observe(0.28f, 0.70f, 0.02f, false, false))
        }
    }

    @Test
    fun silenceResetsTheCandidate() {
        val detector = VoiceBargeInDetector()
        repeat(VoiceBargeInDetector.SPEAKER_REQUIRED_FRAMES - 2) {
            assertFalse(detector.observe(0.25f, 0.5f, 0.02f, false, true))
        }
        detector.reset()
        repeat(VoiceBargeInDetector.SPEAKER_REQUIRED_FRAMES - 1) {
            assertFalse(detector.observe(0.25f, 0.5f, 0.02f, false, true))
        }
        assertTrue(detector.observe(0.25f, 0.5f, 0.02f, false, true))
    }
}

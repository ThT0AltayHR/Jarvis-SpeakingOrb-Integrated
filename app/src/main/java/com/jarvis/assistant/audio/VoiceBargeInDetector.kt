package com.jarvis.assistant.audio

import kotlin.math.max

/**
 * Local, conservative VAD gate used only while Jarvis is audibly playing.
 * Hardware AEC allows a quicker speakerphone threshold; devices without it use a stricter
 * sustained-level fallback. RMS alone cannot perfectly separate voice from acoustic echo.
 */
internal class VoiceBargeInDetector {
    private var consecutiveFrames = 0

    fun observe(
        micAmplitude: Float,
        playbackAmplitude: Float,
        ambientNoiseFloor: Float,
        hasHeadset: Boolean,
        echoCancellationActive: Boolean
    ): Boolean {
        val mic = micAmplitude.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val playback = playbackAmplitude.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val noise = ambientNoiseFloor.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

        val requiredFrames: Int
        val threshold: Float
        if (hasHeadset) {
            // Headphones substantially reduce the far-end signal in the microphone.
            requiredFrames = HEADSET_REQUIRED_FRAMES
            threshold = max(HEADSET_MIN_AMPLITUDE, noise * HEADSET_NOISE_MULTIPLIER)
        } else if (echoCancellationActive) {
            requiredFrames = SPEAKER_REQUIRED_FRAMES
            threshold = max(
                SPEAKER_MIN_AMPLITUDE,
                max(playback * SPEAKER_TO_PLAYBACK_RATIO, noise * SPEAKER_NOISE_MULTIPLIER)
            )
        } else {
            // Conservative speakerphone fallback for devices without a hardware AEC effect.
            // It needs louder, sustained near-end speech relative to the playing output, so
            // ordinary far-end leakage is less likely to interrupt the assistant. RMS cannot
            // perfectly separate voices from echo; this fallback favours avoiding false cuts.
            requiredFrames = SPEAKER_FALLBACK_REQUIRED_FRAMES
            threshold = max(
                SPEAKER_FALLBACK_MIN_AMPLITUDE,
                max(playback * SPEAKER_FALLBACK_TO_PLAYBACK_RATIO, noise * SPEAKER_FALLBACK_NOISE_MULTIPLIER)
            )
        }

        if (mic >= threshold) {
            consecutiveFrames++
        } else {
            // A brief quiet gap breaks the candidate; this prevents one loud transient from
            // accumulating into a false interruption across unrelated audio samples.
            consecutiveFrames = 0
        }

        if (consecutiveFrames >= requiredFrames) {
            reset()
            return true
        }
        return false
    }

    fun hasCandidate(): Boolean = consecutiveFrames > 0

    fun reset() {
        consecutiveFrames = 0
    }

    companion object {
        const val HEADSET_REQUIRED_FRAMES = 4       // ~80 ms
        const val SPEAKER_REQUIRED_FRAMES = 8       // ~160 ms with hardware AEC
        const val SPEAKER_FALLBACK_REQUIRED_FRAMES = 12 // ~240 ms without hardware AEC
        const val HEADSET_MIN_AMPLITUDE = 0.18f
        const val SPEAKER_MIN_AMPLITUDE = 0.13f
        const val SPEAKER_TO_PLAYBACK_RATIO = 0.30f
        const val SPEAKER_FALLBACK_MIN_AMPLITUDE = 0.16f
        const val SPEAKER_FALLBACK_TO_PLAYBACK_RATIO = 0.34f
        const val SPEAKER_FALLBACK_NOISE_MULTIPLIER = 3.0f
        const val HEADSET_NOISE_MULTIPLIER = 2.4f
        const val SPEAKER_NOISE_MULTIPLIER = 3.0f
    }
}

package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCompletionPolicyTest {
    @Test
    fun temporaryNetworkGapNeverCompletesSpeechEvenAfterLongIdle() {
        for (idlePolls in listOf(8, 40, 100, 1_000, 10_000)) {
            assertFalse(
                "idlePolls=$idlePolls must not end a turn before server completion",
                PlaybackCompletionPolicy.shouldFinish(
                    turnComplete = false,
                    queueEmpty = true,
                    allSamplesPlayed = true,
                    idlePolls = idlePolls
                )
            )
        }
    }

    @Test
    fun completionWaitsUntilAudioTrackHasPlayedEveryFrame() {
        assertFalse(
            PlaybackCompletionPolicy.shouldFinish(
                turnComplete = true,
                queueEmpty = true,
                allSamplesPlayed = false,
                idlePolls = 100
            )
        )
    }

    @Test
    fun completionRequiresProviderSignalHardwareDrainAndShortDrainWindow() {
        assertFalse(PlaybackCompletionPolicy.shouldFinish(true, true, true, 7))
        assertTrue(PlaybackCompletionPolicy.shouldFinish(true, true, true, 8))
    }

    @Test
    fun completionNeverFinishesWhileAnyPcmChunkIsQueued() {
        assertFalse(PlaybackCompletionPolicy.shouldFinish(true, false, true, 100))
    }
}

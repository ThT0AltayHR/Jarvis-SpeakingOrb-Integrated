package com.jarvis.assistant.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStatePolicyTest {
    @Test
    fun duplicateStateUpdatesAreIgnored() {
        assertFalse(
            ConversationStatePolicy.shouldApply(
                current = ConversationState.SPEAKING,
                requested = ConversationState.SPEAKING,
                playbackActive = true,
                userInterrupting = false
            )
        )
    }

    @Test
    fun staleEventsCannotMoveOrbOutOfSpeakingWhileAudioIsActive() {
        for (staleState in listOf(ConversationState.IDLE, ConversationState.LISTENING, ConversationState.THINKING)) {
            assertFalse(
                "staleState=$staleState must not override physical playback",
                ConversationStatePolicy.shouldApply(
                    current = ConversationState.SPEAKING,
                    requested = staleState,
                    playbackActive = true,
                    userInterrupting = false
                )
            )
        }
    }

    @Test
    fun explicitUserBargeInCanLeaveSpeakingImmediately() {
        assertTrue(
            ConversationStatePolicy.shouldApply(
                current = ConversationState.SPEAKING,
                requested = ConversationState.LISTENING,
                playbackActive = true,
                userInterrupting = true
            )
        )
    }

    @Test
    fun afterPlaybackEndsStateCanReturnToIdle() {
        assertTrue(
            ConversationStatePolicy.shouldApply(
                current = ConversationState.SPEAKING,
                requested = ConversationState.IDLE,
                playbackActive = false,
                userInterrupting = false
            )
        )
    }
}

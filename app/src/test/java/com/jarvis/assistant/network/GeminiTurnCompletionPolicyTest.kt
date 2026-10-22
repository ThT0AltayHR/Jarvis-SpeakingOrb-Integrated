package com.jarvis.assistant.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTurnCompletionPolicyTest {
    @Test
    fun generationCompleteAloneDoesNotCompleteConversationTurn() {
        // generationComplete is deliberately not a policy input: it can precede turnComplete.
        assertFalse(GeminiTurnCompletionPolicy.isTurnComplete(turnComplete = false, endOfTurn = false))
    }

    @Test
    fun turnCompleteIsAuthoritative() {
        assertTrue(GeminiTurnCompletionPolicy.isTurnComplete(turnComplete = true, endOfTurn = false))
    }

    @Test
    fun legacyEndOfTurnRemainsSupported() {
        assertTrue(GeminiTurnCompletionPolicy.isTurnComplete(turnComplete = false, endOfTurn = true))
    }

    @Test
    fun interruptedFrameNeverProcessesItsModelTurnPayload() {
        assertFalse(GeminiTurnCompletionPolicy.shouldProcessModelTurn(interrupted = true))
        assertTrue(GeminiTurnCompletionPolicy.shouldProcessModelTurn(interrupted = false))
    }
}

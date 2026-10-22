package com.jarvis.assistant.network

/**
 * Gemini Live distinguishes generationComplete (model output generation stopped) from
 * turnComplete (the conversation turn is finalized). Only the latter may finalize playback.
 */
internal object GeminiTurnCompletionPolicy {
    fun isTurnComplete(turnComplete: Boolean, endOfTurn: Boolean): Boolean =
        turnComplete || endOfTurn

    /** An interrupted server frame terminates the current output; its modelTurn payload is stale. */
    fun shouldProcessModelTurn(interrupted: Boolean): Boolean = !interrupted
}

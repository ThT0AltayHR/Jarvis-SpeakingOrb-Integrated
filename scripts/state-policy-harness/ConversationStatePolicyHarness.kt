package com.jarvis.assistant.data.model

fun main() {
    val outputStates = ConversationState.values().filter { it != ConversationState.SPEAKING }
    for (current in ConversationState.values()) {
        for (requested in ConversationState.values()) {
            if (current == requested) {
                check(!ConversationStatePolicy.shouldApply(current, requested, playbackActive = true, userInterrupting = false)) {
                    "duplicate state must be a no-op: $current"
                }
            }
        }
    }
    for (current in ConversationState.values()) {
        for (requested in outputStates) {
            val applied = ConversationStatePolicy.shouldApply(
                current, requested, playbackActive = true, userInterrupting = false
            )
            check(!applied) {
                "stale $requested transition stole the Orb while playback was queued from $current"
            }
        }
    }
    check(ConversationStatePolicy.shouldApply(
        ConversationState.THINKING, ConversationState.SPEAKING,
        playbackActive = true, userInterrupting = false
    )) { "SPEAKING must be allowed when audio starts from THINKING" }
    check(ConversationStatePolicy.shouldApply(
        ConversationState.SPEAKING, ConversationState.LISTENING,
        playbackActive = true, userInterrupting = true
    )) { "confirmed user interruption must release SPEAKING lock" }
    check(ConversationStatePolicy.shouldApply(
        ConversationState.SPEAKING, ConversationState.LISTENING,
        playbackActive = false, userInterrupting = false
    )) { "listening may take over once output is drained" }
    check(!ConversationStatePolicy.shouldApply(
        ConversationState.SPEAKING, ConversationState.IDLE,
        playbackActive = true, userInterrupting = false
    )) { "delayed IDLE must not interrupt playback" }
    println("PASS production ConversationStatePolicy: ${ConversationState.values().size * ConversationState.values().size} duplicate/state combinations and interrupt exceptions")
}

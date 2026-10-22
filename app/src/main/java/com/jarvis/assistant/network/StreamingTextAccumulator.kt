package com.jarvis.assistant.network

/**
 * Appends provider-supplied transcript deltas without inventing word boundaries.
 * A duplicate literal space at a chunk boundary is collapsed, but punctuation,
 * partial words and newlines are preserved exactly as delivered.
 */
object StreamingTextAccumulator {
    fun append(target: StringBuilder, incoming: String) {
        if (incoming.isEmpty()) return
        val hasDuplicateBoundarySpace = target.isNotEmpty() && target.last() == ' ' && incoming.first() == ' '
        val start = if (hasDuplicateBoundarySpace) 1 else 0
        target.append(incoming, start, incoming.length)
    }
}

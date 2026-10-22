package com.jarvis.assistant.network

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTextAccumulatorTest {
    @Test fun partialWordChunksAreNotSeparatedByGuessedSpaces() {
        val out = StringBuilder()
        StreamingTextAccumulator.append(out, "Merha")
        StreamingTextAccumulator.append(out, "ba dünya")
        assertEquals("Merhaba dünya", out.toString())
    }

    @Test fun explicitBoundarySpaceIsPreserved() {
        val out = StringBuilder("Merhaba")
        StreamingTextAccumulator.append(out, " dünya")
        assertEquals("Merhaba dünya", out.toString())
    }

    @Test fun duplicateLiteralBoundarySpaceIsCollapsed() {
        val out = StringBuilder("Merhaba ")
        StreamingTextAccumulator.append(out, " dünya")
        assertEquals("Merhaba dünya", out.toString())
    }

    @Test fun newlinesAndPunctuationArePreserved() {
        val out = StringBuilder("İlk cümle.")
        StreamingTextAccumulator.append(out, "
İkinci cümle!")
        assertEquals("İlk cümle.
İkinci cümle!", out.toString())
    }

    @Test fun emptyDeltasDoNotChangeTranscript() {
        val out = StringBuilder("Jarvis")
        StreamingTextAccumulator.append(out, "")
        assertEquals("Jarvis", out.toString())
    }
}

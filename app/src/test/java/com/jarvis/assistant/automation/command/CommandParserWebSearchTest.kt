package com.jarvis.assistant.automation.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserWebSearchTest {
    @Test
    fun turkishWebSearchRunsInBackground() {
        val result = CommandParser.parse("web'de ara React Native")
        assertTrue(result.command is JarvisCommand.SearchWeb)
        assertEquals("react native", (result.command as JarvisCommand.SearchWeb).query)
    }

    @Test
    fun naturalTurkishSearchRunsInBackground() {
        val result = CommandParser.parse("internette Jarvis uygulaması ara")
        assertTrue(result.command is JarvisCommand.SearchWeb)
        assertEquals("jarvis uygulaması", (result.command as JarvisCommand.SearchWeb).query)
    }

    @Test
    fun explicitChromeSearchOpensBrowser() {
        val result = CommandParser.parse("Chrome'dan ara React Native")
        assertTrue(result.command is JarvisCommand.SearchWebInBrowser)
        assertEquals("react native", (result.command as JarvisCommand.SearchWebInBrowser).query)
    }

    @Test
    fun queryBeforeChromeAlsoOpensBrowser() {
        val result = CommandParser.parse("React Native Chrome'da ara")
        assertTrue(result.command is JarvisCommand.SearchWebInBrowser)
        assertEquals("react native", (result.command as JarvisCommand.SearchWebInBrowser).query)
    }
}

package com.amurayada.music.ui.screens.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsUtilsTest {

    @Test
    fun `parseLyrics parses 2 and 3 digit milliseconds and sorts chronologically`() {
        val lrc = """
            [00:15.500]Second line
            [00:05.25]First line
            [ti:Ignored metadata]
        """.trimIndent()

        val parsed = parseLyrics(lrc)
        assertEquals(2, parsed.size)
        assertEquals(5_250L, parsed[0].timestamp)
        assertEquals("First line", parsed[0].text)
        assertEquals(15_500L, parsed[1].timestamp)
        assertEquals("Second line", parsed[1].text)
        assertTrue(parseLyrics(null).isEmpty())
    }

    @Test
    fun `formatDuration formats minutes and padded seconds`() {
        assertEquals("0:05", formatDuration(5_000L))
        assertEquals("3:09", formatDuration(189_000L))
    }
}

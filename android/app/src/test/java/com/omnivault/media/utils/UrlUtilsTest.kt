package com.omnivault.media.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun `extractUrlFromText extracts and cleans tracking params`() {
        val shared = "Escucha esto https://youtu.be/abc123?si=xyz999&t=10 genial"
        assertEquals("https://youtu.be/abc123?t=10", UrlUtils.extractUrlFromText(shared))
        assertNull(UrlUtils.extractUrlFromText("texto sin enlaces"))
    }

    @Test
    fun `extractAllUrlsFromText deduplicates and trims trailing punctuation`() {
        val batch = "https://youtube.com/watch?v=111, https://youtu.be/222. https://youtube.com/watch?v=111"
        val urls = UrlUtils.extractAllUrlsFromText(batch)
        assertEquals(listOf("https://youtube.com/watch?v=111", "https://youtu.be/222"), urls)
    }

    @Test
    fun `playlist detection distinguishes direct hybrid and radio mix`() {
        assertTrue(UrlUtils.isDirectPlaylistUrl("https://www.youtube.com/playlist?list=PL12345"))
        assertFalse(UrlUtils.isDirectPlaylistUrl("https://www.youtube.com/watch?v=abc&list=PL12345"))

        assertTrue(UrlUtils.isHybridPlaylistUrl("https://www.youtube.com/watch?v=abc&list=PL12345"))
        assertFalse(UrlUtils.isHybridPlaylistUrl("https://www.youtube.com/watch?v=abc&list=RDabc"))
        assertFalse(UrlUtils.isHybridPlaylistUrl("https://www.youtube.com/playlist?list=PL12345"))
    }

    @Test
    fun `normalizeMediaUrl strips playlist and timestamp params on watch urls`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            UrlUtils.normalizeMediaUrl("https://www.youtube.com/watch?v=abc123&list=PL99&index=3&t=45&si=track")
        )
    }
}

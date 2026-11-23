package com.omnivault.media.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameStandardizerTest {

    @Test
    fun `standardizeTitle strips official video and lyric tags`() {
        assertEquals("Midnight City", FilenameStandardizer.standardizeTitle("Midnight City (Official Video)"))
        assertEquals("After Hours", FilenameStandardizer.standardizeTitle("After Hours [Lyric Video]"))
        assertEquals("Stay With Me", FilenameStandardizer.standardizeTitle("Stay With Me - [4K]"))
    }

    @Test
    fun `cleanArtistName removes Topic suffix and normalizes spaces`() {
        assertEquals("GABBI", FilenameStandardizer.cleanArtistName("GABBI  - Topic"))
        assertEquals("", FilenameStandardizer.cleanArtistName("   "))
        assertEquals("", FilenameStandardizer.cleanArtistName(null))
    }

    @Test
    fun `isValidArtist rejects placeholders`() {
        assertFalse(FilenameStandardizer.isValidArtist(null))
        assertFalse(FilenameStandardizer.isValidArtist("<unknown>"))
        assertFalse(FilenameStandardizer.isValidArtist("Artista desconocido"))
        assertFalse(FilenameStandardizer.isValidArtist("OmniVault"))
        assertTrue(FilenameStandardizer.isValidArtist("Lunara"))
    }

    @Test
    fun `formatStandardName prepends valid artist without duplicating and strips illegal chars`() {
        assertEquals(
            "GABBI - Stay with me",
            FilenameStandardizer.formatStandardName("Stay with me? (Official Video)", "GABBI - Topic")
        )
        assertEquals(
            "GABBI - Stay with me",
            FilenameStandardizer.formatStandardName("GABBI - Stay with me", "GABBI")
        )
        assertEquals(
            "Track 01",
            FilenameStandardizer.formatStandardName("Track: 01*", "<unknown>")
        )
    }

    @Test
    fun `isExcludedFromBulkStandardize skips messaging and camera files`() {
        assertTrue(FilenameStandardizer.isExcludedFromBulkStandardize("/storage/emulated/0/WhatsApp/Media/WhatsApp Audio/a.opus", "a.opus"))
        assertTrue(FilenameStandardizer.isExcludedFromBulkStandardize("/storage/emulated/0/Music/PTT-20250101-WA0001.opus", "PTT-20250101-WA0001.opus"))
        assertFalse(FilenameStandardizer.isExcludedFromBulkStandardize("/storage/emulated/0/Music/OmniVault/song.m4a", "song.m4a"))
    }
}

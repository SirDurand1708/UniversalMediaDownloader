package com.omnivault.media.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryExclusionHelperTest {

    @Test
    fun `reconcileRedownloadedExclusions unblocks deleted song when redownloaded`() {
        val path = "/storage/emulated/0/Music/OmniVault/Stay with me.m4a"
        val merged = listOf(
            SongKey(id = 2002L, title = "Stay with me", path = path, fileSize = 5_000_000L)
        )
        val downloads = listOf(
            SongKey(id = 999L, title = "Stay with me", path = "content://media/external/audio/media/2002", fileSize = 5_000_000L)
        )

        val result = LibraryExclusionHelper.reconcileRedownloadedExclusions(
            mergedSongs = merged,
            downloadedSongs = downloads,
            deletedPaths = setOf(path),
            hiddenIds = setOf(2002L)
        )

        assertTrue(result.changed)
        assertTrue(result.cleanedDeletedPaths.isEmpty())
        assertTrue(result.cleanedHiddenIds.isEmpty())
    }

    @Test
    fun `reconcileRedownloadedExclusions preserves manually hidden songs`() {
        val hiddenPath = "/storage/emulated/0/Music/OmniVault/Hidden Manually.m4a"
        val merged = listOf(
            SongKey(id = 3003L, title = "Hidden Manually", path = hiddenPath, fileSize = 3_000_000L)
        )
        val downloads = listOf(
            SongKey(id = 888L, title = "Hidden Manually", path = "content://media/external/audio/media/3003", fileSize = 3_000_000L)
        )

        val result = LibraryExclusionHelper.reconcileRedownloadedExclusions(
            mergedSongs = merged,
            downloadedSongs = downloads,
            deletedPaths = emptySet(),
            hiddenIds = setOf(3003L)
        )

        assertFalse(result.changed)
        assertEquals(setOf(3003L), result.cleanedHiddenIds)
    }

    @Test
    fun `mergeSongsDeduplicated prioritizes localSongs and deduplicates by content id or resolved path`() {
        val local = listOf(
            SongKey(id = 100L, title = "Track A", path = "/storage/emulated/0/Music/OmniVault/Track A.m4a", fileSize = 4_000L)
        )
        val downloads = listOf(
            SongKey(id = 500L, title = "Track A", path = "content://media/external/audio/media/100", fileSize = 4_000L),
            SongKey(id = 501L, title = "Track B", path = "/storage/emulated/0/Music/OmniVault/Track B.m4a", fileSize = 3_000L)
        )

        val merged = LibraryExclusionHelper.mergeSongsDeduplicated(
            localSongs = local,
            downloadedSongs = downloads,
            keySelector = { it }
        )

        assertEquals(2, merged.size)
        assertEquals(listOf(100L, 501L), merged.map { it.id })
    }

    @Test
    fun `isCallOrVoiceRecording filters phone calls and voice notes without false positives on Music`() {
        assertTrue(
            LibraryExclusionHelper.isCallOrVoiceRecording(
                path = "/storage/emulated/0/Recordings/Call/Llamada 996779727_260101.m4a",
                title = "Llamada 996779727_260101",
                album = "Call",
                artist = "<unknown>"
            )
        )
        assertTrue(
            LibraryExclusionHelper.isCallOrVoiceRecording(
                path = "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes/202601/PTT-20260101-WA0001.opus",
                title = "PTT-20260101-WA0001",
                album = "WhatsApp Voice Notes",
                artist = "<unknown>"
            )
        )
        assertFalse(
            LibraryExclusionHelper.isCallOrVoiceRecording(
                path = "/storage/emulated/0/Music/Llamada de emergencia.mp3",
                title = "Llamada de emergencia",
                album = "<unknown>",
                artist = "<unknown>"
            )
        )
        assertFalse(
            LibraryExclusionHelper.isCallOrVoiceRecording(
                path = "/storage/emulated/0/Music/OmniVault/Call.m4a",
                title = "Call",
                album = "Call",
                artist = "<unknown>"
            )
        )
    }
}

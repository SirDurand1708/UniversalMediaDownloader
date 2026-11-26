package com.amurayada.music.ui.viewmodel

import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.SortOption
import org.junit.Assert.assertEquals
import org.junit.Test

class VideosViewModelTest {

    private fun sampleVideos(): List<DownloadItem> = listOf(
        DownloadItem(
            id = "db_video_1",
            url = "https://youtu.be/1",
            format = MediaFormat.MP4_1080,
            title = "Zebra Clip",
            localFilePath = "/storage/Movies/OmniVault/zebra.mp4",
            durationFormatted = "01:30",
            fileSizeBytes = 5_000_000L,
            timestamp = 100L
        ),
        DownloadItem(
            id = "media_video_2",
            url = "/storage/DCIM/Camera/cam.mp4",
            format = MediaFormat.MP4_720,
            title = "Alpha Camera",
            localFilePath = "/storage/DCIM/Camera/cam.mp4",
            durationFormatted = "00:03",
            fileSizeBytes = 1_000_000L,
            timestamp = 300L
        ),
        DownloadItem(
            id = "media_video_3",
            url = "/storage/WhatsApp/Media/WhatsApp Video/VID-20260101-WA0001.mp4",
            format = MediaFormat.MP4_720,
            title = "VID-20260101-WA0001",
            localFilePath = "/storage/WhatsApp/Media/WhatsApp Video/VID-20260101-WA0001.mp4",
            durationFormatted = "00:45",
            fileSizeBytes = 3_000_000L,
            timestamp = 200L
        )
    )

    @Test
    fun `filterAndSortVideos hides WhatsApp and short clips when enabled`() {
        val result = filterAndSortVideos(
            list = sampleVideos(),
            query = "",
            sort = SortOption.DATE_DESC,
            hideShort = true,
            hideWa = true,
            onlyDl = false,
            onlyCam = false
        )
        assertEquals(listOf("db_video_1"), result.map { it.id })
    }

    @Test
    fun `filterAndSortVideos filters by camera and sorts by name`() {
        val result = filterAndSortVideos(
            list = sampleVideos(),
            query = "",
            sort = SortOption.NAME_ASC,
            hideShort = false,
            hideWa = true,
            onlyDl = false,
            onlyCam = false
        )
        assertEquals(listOf("media_video_2", "db_video_1"), result.map { it.id })

        val camOnly = filterAndSortVideos(
            list = sampleVideos(),
            query = "",
            sort = SortOption.DATE_DESC,
            hideShort = false,
            hideWa = false,
            onlyDl = false,
            onlyCam = true
        )
        assertEquals(listOf("media_video_2"), camOnly.map { it.id })
    }

    @Test
    fun `filterAndSortVideos filters by search query and sorts by size`() {
        val bySize = filterAndSortVideos(
            list = sampleVideos(),
            query = "",
            sort = SortOption.SIZE_DESC,
            hideShort = false,
            hideWa = false,
            onlyDl = false,
            onlyCam = false
        )
        assertEquals(listOf("db_video_1", "media_video_3", "media_video_2"), bySize.map { it.id })

        val searched = filterAndSortVideos(
            list = sampleVideos(),
            query = "alpha",
            sort = SortOption.DATE_DESC,
            hideShort = false,
            hideWa = false,
            onlyDl = false,
            onlyCam = false
        )
        assertEquals(listOf("media_video_2"), searched.map { it.id })
    }
}

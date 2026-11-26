package com.amurayada.music.ui.viewmodel

import com.amurayada.music.MainDispatcherRule
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.repository.DuplicateDeletionResult
import com.amurayada.music.data.repository.FakePreferencesRepository
import com.amurayada.music.data.repository.MediaCleanupRepository
import com.amurayada.music.data.repository.ReleaseInfo
import com.amurayada.music.data.repository.UpdateRepository
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeMediaCleanupRepository : MediaCleanupRepository {
        var nextVideoResult: DuplicateDeletionResult = DuplicateDeletionResult.DeletedDirectly(2, isVideo = true)
        var nextAudioResult: DuplicateDeletionResult = DuplicateDeletionResult.DeletedDirectly(3, isVideo = false)
        var videoCalls = 0
        var audioCalls = 0

        override fun observeVideoStoreChanges() = kotlinx.coroutines.flow.emptyFlow<Unit>()
        override fun observeAudioStoreChanges() = kotlinx.coroutines.flow.emptyFlow<Unit>()
        override suspend fun scanAllDeviceVideos(): List<DownloadItem> = emptyList()
        override fun getMimeTypeForUri(uriString: String): String? = null
        override fun resolveAudioUriAndName(rawPath: String): Pair<String?, String?> = null to null
        override suspend fun resolveAudioMediaStoreUri(song: Song): android.net.Uri = android.net.Uri.EMPTY
        override suspend fun deleteSingleVideo(item: DownloadItem) = com.amurayada.music.data.repository.SingleVideoDeleteResult.Deleted
        override suspend fun deleteBatchVideos(items: List<DownloadItem>) = com.amurayada.music.data.repository.BatchVideoDeleteResult.Completed(items)
        override suspend fun deleteAudioSong(song: Song) = com.amurayada.music.data.repository.AudioDeleteResult.Deleted
        override suspend fun findDuplicateAudios(songs: List<Song>): List<Pair<Song, Long>> = emptyList()
        override suspend fun findDuplicateVideos(): List<DownloadItem> = emptyList()

        override suspend fun deleteDuplicateVideos(items: List<DownloadItem>): DuplicateDeletionResult {
            videoCalls++
            return nextVideoResult
        }

        override suspend fun deleteDuplicateAudios(songs: List<Song>): DuplicateDeletionResult {
            audioCalls++
            return nextAudioResult
        }
    }

    private class FakeUpdateRepository(
        var result: Result<ReleaseInfo>
    ) : UpdateRepository {
        override suspend fun getLatestRelease(): Result<ReleaseInfo> = result
    }

    @Test
    fun `theme mode updates state and persists`() {
        val prefs = FakePreferencesRepository()
        val vm = SettingsViewModel(prefs, FakeMediaCleanupRepository(), FakeUpdateRepository(Result.failure(RuntimeException("unused"))), currentVersion = "2.5.61")

        assertEquals("system", vm.themeMode.value)
        assertNull(vm.isDarkThemeOverride.value)

        vm.setThemeMode("dark")
        assertEquals("dark", vm.themeMode.value)
        assertEquals(true, vm.isDarkThemeOverride.value)
        assertEquals("dark", prefs.storedThemeMode)

        vm.setThemeMode("light")
        assertEquals(false, vm.isDarkThemeOverride.value)
        assertEquals("Claro", vm.getThemeModeDisplay())
    }

    @Test
    fun `clipboard monitor and short audio filter persist via repository`() {
        val prefs = FakePreferencesRepository()
        val vm = SettingsViewModel(prefs, FakeMediaCleanupRepository(), FakeUpdateRepository(Result.failure(RuntimeException("unused"))), currentVersion = "2.5.61")

        assertTrue(vm.clipboardMonitorEnabled.value)
        vm.setClipboardMonitorEnabled(false)
        assertFalse(vm.clipboardMonitorEnabled.value)
        assertFalse(prefs.storedClipboardMonitorEnabled)

        assertTrue(vm.filterShortAudio.value)
        vm.setFilterShortAudio(false)
        assertFalse(vm.filterShortAudio.value)
        assertFalse(prefs.storedFilterShortAudio)
    }

    @Test
    fun `resetPersonalization clears visual overrides`() {
        val prefs = FakePreferencesRepository().apply {
            storedCustomPrimaryColor = 0xFF00FF
            storedCustomBgUri = "file:///bg.jpg"
            storedPlayerBgType = "solid"
            storedPlayerBgColor = 0x112233
            storedPlayerArtScale = 1.5f
        }
        val vm = SettingsViewModel(prefs, FakeMediaCleanupRepository(), FakeUpdateRepository(Result.failure(RuntimeException("unused"))), currentVersion = "2.5.61")

        vm.resetPersonalization()

        assertNull(vm.customPrimaryColor.value)
        assertNull(vm.customBackgroundImageUri.value)
        assertEquals("auto", vm.playerBackgroundType.value)
        assertNull(vm.playerBackgroundColor.value)
        assertEquals(1.0f, vm.playerAlbumArtScale.value)
        assertNull(prefs.storedCustomPrimaryColor)
        assertNull(prefs.storedCustomBgUri)
    }

    @Test
    fun `checkForUpdates detects newer release and up-to-date state`() = runTest {
        val prefs = FakePreferencesRepository()
        val release = ReleaseInfo(
            versionName = "v2.6.0",
            downloadUrl = "https://example.com/app.apk",
            body = "Mejoras"
        )
        val updateRepo = FakeUpdateRepository(Result.success(release))
        val vm = SettingsViewModel(prefs, FakeMediaCleanupRepository(), updateRepo, currentVersion = "2.5.61")

        vm.checkForUpdates()
        assertEquals(SettingsViewModel.UpdateStatus.UpdateAvailable(release), vm.updateStatus.value)

        updateRepo.result = Result.success(release.copy(versionName = "v2.5.61"))
        vm.checkForUpdates()
        assertEquals(SettingsViewModel.UpdateStatus.UpToDate, vm.updateStatus.value)
    }

    @Test
    fun `deleteDuplicateVideos and deleteDuplicateAudios emit one-shot results`() = runTest {
        val cleanupRepo = FakeMediaCleanupRepository()
        val vm = SettingsViewModel(FakePreferencesRepository(), cleanupRepo, FakeUpdateRepository(Result.failure(RuntimeException("unused"))), currentVersion = "2.5.61")

        vm.deleteDuplicateVideos(listOf(DownloadItem(url = "https://youtu.be/abc", format = MediaFormat.MP4_720)))
        val videoEvent = vm.duplicateDeletionEvents.first()
        assertEquals(1, cleanupRepo.videoCalls)
        assertEquals(DuplicateDeletionResult.DeletedDirectly(2, isVideo = true), videoEvent)

        vm.deleteDuplicateAudios(listOf(Song(1L, "Title", "Artist", "Album", 120_000L, null, "/music/a.mp3", 0L, 1L)))
        val audioEvent = vm.duplicateDeletionEvents.first()
        assertEquals(1, cleanupRepo.audioCalls)
        assertEquals(DuplicateDeletionResult.DeletedDirectly(3, isVideo = false), audioEvent)
    }
}

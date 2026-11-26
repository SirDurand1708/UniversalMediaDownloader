package com.amurayada.music.ui.viewmodel

import com.amurayada.music.MainDispatcherRule
import com.amurayada.music.data.model.Album
import com.amurayada.music.data.model.Artist
import com.amurayada.music.data.model.Playlist
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.repository.ArtistDetails
import com.amurayada.music.data.repository.HomeSection
import com.amurayada.music.data.repository.SearchRepository
import com.amurayada.music.data.repository.SearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeSearchRepository : SearchRepository {
        var shouldThrow: Exception? = null
        var nextSearchResult = SearchResult(
            songs = listOf(Song(1L, "Track 1", "Artist 1", "Album 1", 180_000L, null, "yt_1", 0L, 1L)),
            artists = listOf(Artist(20L, "Artist 1", 1, 5)),
            albums = listOf(Album(10L, "Album 1", "Artist 1", null, 5)),
            playlists = listOf(Playlist(30L, "Mix 1", emptyList(), 0L))
        )
        var nextSuggestions: List<String> = listOf("track one", "track two")
        var nextHomeSections: List<HomeSection> = listOf(HomeSection(title = "Recomendados"))

        override suspend fun search(query: String): SearchResult {
            shouldThrow?.let { throw it }
            return nextSearchResult
        }

        override suspend fun searchSongs(query: String): List<Song> {
            shouldThrow?.let { throw it }
            return nextSearchResult.songs
        }

        override suspend fun searchArtists(query: String): List<Artist> {
            shouldThrow?.let { throw it }
            return nextSearchResult.artists
        }

        override suspend fun searchAlbums(query: String): List<Album> {
            shouldThrow?.let { throw it }
            return nextSearchResult.albums
        }

        override suspend fun searchPlaylists(query: String): List<Playlist> {
            shouldThrow?.let { throw it }
            return nextSearchResult.playlists
        }

        override suspend fun getSearchSuggestions(query: String): List<String> = nextSuggestions
        override suspend fun getTrending(): List<Song> = nextSearchResult.songs
        override suspend fun getOnlineHomeSections(): List<HomeSection> = nextHomeSections
        override suspend fun getUserPlaylists(): List<Playlist> = emptyList()
        override suspend fun getArtistDetails(url: String): ArtistDetails =
            ArtistDetails(Artist(1L, "Artist", 1, 1), emptyList(), emptyList())
        override suspend fun getAlbumDetails(url: String): List<Song> = emptyList()
        override suspend fun getContinueHome(continuation: String): Result<List<HomeSection>> = Result.success(emptyList())
        override suspend fun getCachedHomeSections(): List<HomeSection>? = null
        override suspend fun saveCachedHomeSections(sections: List<HomeSection>) {}
    }

    @Test
    fun `search updates results and clearSearch resets state`() = runTest {
        val repo = FakeSearchRepository()
        val vm = SearchViewModel(repo)

        vm.search("rock")
        assertFalse(vm.state.isLoading)
        assertNull(vm.state.error)
        assertEquals(1, vm.searchResults.size)
        assertEquals("Track 1", vm.searchResults.first().title)
        assertEquals(1, vm.albumResults.size)
        assertEquals(1, vm.artistResults.size)
        assertEquals(1, vm.playlistResults.size)

        vm.clearSearch()
        assertTrue(vm.searchResults.isEmpty())
        assertTrue(vm.albumResults.isEmpty())
        assertNull(vm.state.error)
    }

    @Test
    fun `search failure degrades gracefully without crashing`() = runTest {
        val repo = FakeSearchRepository().apply {
            shouldThrow = RuntimeException("Sin conexión")
        }
        val vm = SearchViewModel(repo)

        vm.search("jazz")
        assertFalse(vm.state.isLoading)
        assertTrue(vm.searchResults.isEmpty())
        assertTrue(vm.artistResults.isEmpty())
        assertTrue(vm.albumResults.isEmpty())
        assertTrue(vm.playlistResults.isEmpty())
    }
}

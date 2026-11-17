package com.andymitch.dzr.data.repository

import com.google.common.truth.Truth.assertThat
import com.andymitch.dzr.data.model.LyricsSourcePreference
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.model.SyncedLine
import com.andymitch.dzr.data.network.deezer.DeezerLyricsAttributes
import com.andymitch.dzr.data.network.deezer.DeezerLyricsData
import com.andymitch.dzr.data.network.deezer.DeezerLyricsItem
import com.andymitch.dzr.data.network.deezer.DeezerLyricsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LyricsRepositoryImplTest {

    private fun song(contentUri: String) = Song(
        id = "42",
        title = "Song",
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = 1L,
        path = "",
        contentUriString = contentUri,
        albumArtUriString = null,
        duration = 1000L,
        mimeType = null,
        bitrate = null,
        sampleRate = null
    )

    @Test
    fun getLyrics_mapsDeezerLinesToPlainAndSyncedLyrics() = runTest {
        val deezer = mockk<DeezerRepository>()
        coEvery { deezer.getLyrics("42") } returns DeezerLyricsResponse(
            DeezerLyricsData(
                attributes = DeezerLyricsAttributes(
                    items = listOf(
                        DeezerLyricsItem(milliseconds = "1500", line = "First"),
                        DeezerLyricsItem(milliseconds = null, line = "Unsynced"),
                        DeezerLyricsItem(milliseconds = "4200", line = "Second")
                    )
                )
            )
        )

        val lyrics = LyricsRepositoryImpl(deezer)
            .getLyrics(song("deezer://track/42"), LyricsSourcePreference.API_FIRST, false)

        assertThat(lyrics?.plain).containsExactly("First", "Unsynced", "Second").inOrder()
        assertThat(lyrics?.synced).containsExactly(SyncedLine(1500, "First"), SyncedLine(4200, "Second")).inOrder()
        assertThat(lyrics?.areFromRemote).isTrue()
    }

    @Test
    fun getLyrics_returnsNullWhenDeezerHasNone() = runTest {
        val deezer = mockk<DeezerRepository>()
        coEvery { deezer.getLyrics(any()) } returns DeezerLyricsResponse(DeezerLyricsData(attributes = DeezerLyricsAttributes()))

        val repository = LyricsRepositoryImpl(deezer)

        assertThat(repository.getLyrics(song("deezer://track/42"), LyricsSourcePreference.API_FIRST, false)).isNull()
        assertThat(repository.fetchFromRemote(song("deezer://track/42")).isFailure).isTrue()
    }

    @Test
    fun getLyrics_skipsDeezerForNonDeezerSongs() = runTest {
        val deezer = mockk<DeezerRepository>()

        assertThat(LyricsRepositoryImpl(deezer).getLyrics(song("content://media/1"), LyricsSourcePreference.API_FIRST, false)).isNull()
        coVerify(exactly = 0) { deezer.getLyrics(any()) }
    }
}

package com.andymitch.dzr.data.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SharedArtworkContentProviderTest {

    @Test
    fun buildSongUri_usesDedicatedArtworkAuthority() {
        val uri = SharedArtworkContentProvider.buildSongUriString(
            packageName = "com.andymitch.dzr",
            songId = 42L
        )

        assertThat(uri).isEqualTo("content://com.andymitch.dzr.artwork/song/42")
    }

    @Test
    fun buildSongUri_preservesCacheBustToken() {
        val uri = SharedArtworkContentProvider.buildSongUriString(
            packageName = "com.andymitch.dzr",
            songId = 42L,
            cacheBustToken = "1234"
        )

        assertThat(uri)
            .isEqualTo("content://com.andymitch.dzr.artwork/song/42?t=1234")
    }

    @Test
    fun parseSongId_rejectsOtherAuthorities() {
        val songId = SharedArtworkContentProvider.parseSongId(
            uriString = "content://example.com.artwork/song/42",
            packageName = "com.andymitch.dzr"
        )

        assertThat(songId).isNull()
    }

    @Test
    fun parseSongId_readsSharedArtworkSongUri() {
        val songId = SharedArtworkContentProvider.parseSongId(
            uriString = "content://com.andymitch.dzr.artwork/song/42",
            packageName = "com.andymitch.dzr"
        )

        assertThat(songId).isEqualTo(42L)
    }
}

package com.andymitch.dzr.data.download

import com.andymitch.dzr.data.preferences.DeezerAudioQuality
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DownloadIndexTest {
    private fun track(id: String) = DownloadedTrack(id, id, "Artist", 1L, "Album", 2L, null, 1000L, "$id.mp3", DeezerAudioQuality.HIGH)

    private fun indexWith(vararg collections: DownloadedCollection) =
        DownloadIndex(
            tracks = collections.flatMap { it.trackIds }.associateWith(::track),
            collections = collections.toList()
        )

    @Test
    fun removingACollectionKeepsSongsAnotherCollectionStillHas() {
        val index = indexWith(DownloadedCollection("liked", "Liked", listOf("a", "b")), DownloadedCollection("album_9", "Album", listOf("b", "c")))

        val (updated, orphans) = index.remove("liked")

        assertThat(orphans).containsExactly("a")
        assertThat(updated.tracks.keys).containsExactly("b", "c")
        assertThat(updated.orderedTrackIds).containsExactly("b", "c").inOrder()
    }

    @Test
    fun updatingACollectionReportsSongsThatLeftIt() {
        val index = indexWith(DownloadedCollection("liked", "Liked", listOf("a", "b")), DownloadedCollection("album_9", "Album", listOf("b")))

        val (updated, orphans) = index.upsert(DownloadedCollection("liked", "Liked", listOf("c")))

        assertThat(orphans).containsExactly("a")
        assertThat(updated.collections.map { it.id }).containsExactly("album_9", "liked")
    }

    @Test
    fun orderedTracksSkipSongsNotDownloadedYet() {
        val index = DownloadIndex(tracks = mapOf("b" to track("b")), collections = listOf(DownloadedCollection("x", "X", listOf("a", "b", "b"))))

        assertThat(index.orderedTrackIds).containsExactly("b")
    }

    @Test
    fun progressCountsDownloadedSongsForCollectionsStillPending() {
        val index = DownloadIndex(
            tracks = mapOf("a" to track("a")),
            collections = listOf(DownloadedCollection("album_1", "Album", listOf("a", "b", "c")), DownloadedCollection("done", "Done", listOf("a"))),
            pending = mapOf("b" to track("b"), "c" to track("c"))
        )

        assertThat(index.progress()).containsExactly("album_1", 1 to 3)
    }
}

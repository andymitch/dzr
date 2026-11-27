package com.andymitch.dzr.data.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface SearchResultItem {
    data class SongItem(val song: Song) : SearchResultItem
    data class AlbumItem(val album: Album) : SearchResultItem
    data class ArtistItem(val artist: Artist) : SearchResultItem
    data class PlaylistItem(val playlist: Playlist) : SearchResultItem
    data class PodcastItem(val podcast: com.andymitch.dzr.data.podcast.Podcast) : SearchResultItem
}

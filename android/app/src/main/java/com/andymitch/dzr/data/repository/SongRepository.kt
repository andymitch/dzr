package com.andymitch.dzr.data.repository

import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.model.Album
import com.andymitch.dzr.data.model.Artist

import androidx.paging.PagingData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    fun getSongById(songId: Long): Flow<Song?>
    fun getArtistById(artistId: Long): Flow<Artist?>
    suspend fun getArtistIdByName(name: String): Long?
    fun getPaginatedArtists(
        sortOption: com.andymitch.dzr.data.model.SortOption,
        storageFilter: com.andymitch.dzr.data.model.StorageFilter
    ): Flow<PagingData<Artist>>
    fun getPaginatedSongs(sortOption: com.andymitch.dzr.data.model.SortOption, storageFilter: com.andymitch.dzr.data.model.StorageFilter): Flow<PagingData<Song>>
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getPaginatedFavoriteSongs(
        sortOption: com.andymitch.dzr.data.model.SortOption,
        storageFilter: com.andymitch.dzr.data.model.StorageFilter
    ): Flow<PagingData<Song>>
    fun getPaginatedAlbums(
        sortOption: com.andymitch.dzr.data.model.SortOption,
        storageFilter: com.andymitch.dzr.data.model.StorageFilter,
        minTracks: Int
    ): Flow<PagingData<Album>>
    suspend fun getFavoriteSongsOnce(
        storageFilter: com.andymitch.dzr.data.model.StorageFilter,
        sortOption: com.andymitch.dzr.data.model.SortOption = com.andymitch.dzr.data.model.SortOption.LikedSongDateLiked
    ): List<Song>
    fun getFavoriteSongCountFlow(
        storageFilter: com.andymitch.dzr.data.model.StorageFilter
    ): Flow<Int>
    fun getFavoriteSongIdsFlow(): Flow<Set<String>>
    suspend fun getFavoriteSongIdsOnce(): Set<String>
    suspend fun getFavoriteSongIdsSorted(
        sortOption: com.andymitch.dzr.data.model.SortOption,
        storageFilter: com.andymitch.dzr.data.model.StorageFilter
    ): List<Long>
    suspend fun setFavoriteStatus(songId: String, isFavorite: Boolean)
    
    suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        artistId: Long,
        artistsJson: String?,
        album: String,
        genre: String?,
        trackNumber: Int,
        discNumber: Int?
    )
    suspend fun updateSongAlbumId(songId: Long, albumId: Long)
    suspend fun updateSongAlbumArt(songId: Long, albumArtUri: String?)
    
    suspend fun saveSong(song: Song)
}

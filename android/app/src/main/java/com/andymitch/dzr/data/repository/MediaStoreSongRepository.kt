package com.andymitch.dzr.data.repository

import com.andymitch.dzr.data.model.ArtistRef
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.preferences.UserPreferencesRepository
import com.andymitch.dzr.utils.normalizeMetadataText
import com.andymitch.dzr.utils.normalizeMetadataTextOrEmpty
import com.andymitch.dzr.data.model.Artist

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.andymitch.dzr.data.database.MusicDao
import com.andymitch.dzr.data.database.FavoritesDao
import com.andymitch.dzr.data.database.toSong
import com.andymitch.dzr.utils.AlbumArtUtils
import com.andymitch.dzr.utils.DirectoryRuleResolver
import com.andymitch.dzr.utils.DirectoryFilterUtils
import com.andymitch.dzr.utils.LogUtils
import com.andymitch.dzr.utils.extractArtistsFromTitle
import com.andymitch.dzr.utils.splitArtistsByDelimiters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import com.andymitch.dzr.data.database.toAlbum
import com.andymitch.dzr.data.database.toArtist
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

private data class SearchPrefs(
    val favoriteIds: Set<Long>,
    val allowedDirs: Set<String>,
    val blockedDirs: Set<String>,
    val artistDelimiters: List<String>,
    val wordDelims: List<String>,
    val extractFromTitle: Boolean,
    val minDuration: Int
)

@Singleton
class MediaStoreSongRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoritesDao: FavoritesDao,
    private val musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository
) : SongRepository {


    private fun normalizePath(path: String): String = File(path).absolutePath








    override fun getSongById(songId: Long): Flow<Song?> {
        return musicDao.getSongById(songId).map { it?.toSong() }
    }

    override fun getArtistById(artistId: Long): Flow<Artist?> {
        return musicDao.getArtistById(artistId).map { it?.toArtist() }
    }

    override suspend fun getArtistIdByName(name: String): Long? {
        return musicDao.getArtistIdByName(name)
    }


    /**
     * Computes allowed parent directories by filtering out blocked directories.
     * Returns Pair(allowedDirs, applyFilter).
     */
    private suspend fun computeAllowedDirs(
        allowedDirs: Set<String>,
        blockedDirs: Set<String>
    ): Pair<List<String>, Boolean> {
        return DirectoryFilterUtils.computeAllowedParentDirs(
            allowedDirs = allowedDirs,
            blockedDirs = blockedDirs,
            getAllParentDirs = { musicDao.getDistinctParentDirectories() },
            normalizePath = ::normalizePath
        )
    }

    private val defaultPagingConfig = androidx.paging.PagingConfig(
        pageSize = 50,
        enablePlaceholders = true,
        maxSize = 250
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedSongs(sortOption: com.andymitch.dzr.data.model.SortOption, storageFilter: com.andymitch.dzr.data.model.StorageFilter): Flow<PagingData<Song>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            kotlinx.coroutines.flow.flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    androidx.paging.Pager(
                        config = defaultPagingConfig,
                        pagingSourceFactory = {
                            musicDao.getSongsPaginated(
                                allowedParentDirs = allowedParentDirs,
                                applyDirectoryFilter = applyDirectoryFilter,
                                sortOrder = sortOption.storageKey,
                                filterMode = storageFilter.value
                            )
                        }
                    ).flow
                )
            }.flatMapLatest { it }
        }.map { pagingData ->
            pagingData.map { entity -> entity.toSong() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedArtists(
        sortOption: com.andymitch.dzr.data.model.SortOption,
        storageFilter: com.andymitch.dzr.data.model.StorageFilter
    ): Flow<PagingData<Artist>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            kotlinx.coroutines.flow.flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    androidx.paging.Pager(
                        config = defaultPagingConfig,
                        pagingSourceFactory = {
                            musicDao.getArtistsPaginated(
                                allowedParentDirs = allowedParentDirs,
                                applyDirectoryFilter = applyDirectoryFilter,
                                sortOrder = sortOption.storageKey,
                                filterMode = storageFilter.value
                            )
                        }
                    ).flow
                )
            }.flatMapLatest { it }
        }.map { pagingData ->
            pagingData.map { entity -> entity.toArtist() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedAlbums(
        sortOption: com.andymitch.dzr.data.model.SortOption,
        storageFilter: com.andymitch.dzr.data.model.StorageFilter,
        minTracks: Int
    ): Flow<PagingData<com.andymitch.dzr.data.model.Album>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            kotlinx.coroutines.flow.flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    androidx.paging.Pager(
                        config = defaultPagingConfig,
                        pagingSourceFactory = {
                            musicDao.getAlbumsPaginated(
                                sortOrder = sortOption.storageKey,
                                minTracks = minTracks
                            )
                        }
                    ).flow
                )
            }.flatMapLatest { it }
        }.map { pagingData ->
            pagingData.map { entity ->
                entity.toAlbum()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedFavoriteSongs(
        sortOption: com.andymitch.dzr.data.model.SortOption,
        storageFilter: com.andymitch.dzr.data.model.StorageFilter
    ): Flow<PagingData<Song>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            kotlinx.coroutines.flow.flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    androidx.paging.Pager(
                        config = defaultPagingConfig,
                        pagingSourceFactory = {
                            musicDao.getFavoriteSongsPaginated(
                                allowedParentDirs = allowedParentDirs,
                                applyDirectoryFilter = applyDirectoryFilter,
                                sortOrder = sortOption.storageKey,
                                filterMode = storageFilter.value
                            )
                        }
                    ).flow
                )
            }.flatMapLatest { it }
        }.map { pagingData ->
            pagingData.map { entity -> entity.toSong().copy(isFavorite = true) }
        }
    }

    override suspend fun getFavoriteSongsOnce(
        storageFilter: com.andymitch.dzr.data.model.StorageFilter,
        sortOption: com.andymitch.dzr.data.model.SortOption
    ): List<Song> = withContext(Dispatchers.IO) {
        val allowedDirs = userPreferencesRepository.allowedDirectoriesFlow.first()
        val blockedDirs = userPreferencesRepository.blockedDirectoriesFlow.first()
        val (allowedParentDirs, applyDirectoryFilter) =
            computeAllowedDirs(allowedDirs, blockedDirs)
        musicDao.getFavoriteSongsSortedList(
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter,
            sortOrder = sortOption.storageKey,
            filterMode = storageFilter.value
        )
            .map { entity -> entity.toSong().copy(isFavorite = true) }
    }

    override suspend fun getFavoriteSongIdsSorted(
        sortOption: com.andymitch.dzr.data.model.SortOption,
        storageFilter: com.andymitch.dzr.data.model.StorageFilter
    ): List<Long> = withContext(Dispatchers.IO) {
        val allowedDirs = userPreferencesRepository.allowedDirectoriesFlow.first()
        val blockedDirs = userPreferencesRepository.blockedDirectoriesFlow.first()
        val (allowedParentDirs, applyDirectoryFilter) = computeAllowedDirs(allowedDirs, blockedDirs)
        musicDao.getFavoriteSongIdsSorted(
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter,
            sortOrder = sortOption.storageKey,
            filterMode = storageFilter.value
        )
    }

    override suspend fun setFavoriteStatus(songId: String, isFavorite: Boolean) {
        withContext(Dispatchers.IO) {
            val id = songId.toLongOrNull() ?: return@withContext
            if (isFavorite) {
                favoritesDao.setFavorite(com.andymitch.dzr.data.database.FavoritesEntity(songId = id))
            } else {
                favoritesDao.removeFavorite(id)
            }
        }
    }

    override suspend fun updateSongMetadata(
        songId: Long,
        title: String,
        artist: String,
        artistId: Long,
        artistsJson: String?,
        album: String,
        genre: String?,
        trackNumber: Int,
        discNumber: Int?
    ) {
        withContext(Dispatchers.IO) {
            musicDao.updateSongMetadata(songId, title, artist, artistId, artistsJson, album, genre, trackNumber, discNumber)
        }
    }

    override suspend fun updateSongAlbumId(songId: Long, albumId: Long) {
        withContext(Dispatchers.IO) {
            musicDao.updateSongAlbumId(songId, albumId)
        }
    }

    override suspend fun updateSongAlbumArt(songId: Long, albumArtUri: String?) {
        withContext(Dispatchers.IO) {
            musicDao.updateSongAlbumArt(songId, albumArtUri)
        }
    }

    override suspend fun saveSong(song: Song) {
        withContext(Dispatchers.IO) {
            val id = song.id.toLongOrNull() ?: return@withContext
            musicDao.insertArtistsIgnoreConflicts(listOf(
                com.andymitch.dzr.data.database.ArtistEntity(id = song.artistId, name = song.artist, trackCount = 0)
            ))
            musicDao.insertAlbumsIgnoreConflicts(listOf(
                com.andymitch.dzr.data.database.AlbumEntity(id = song.albumId, title = song.album, artistName = song.artist, artistId = song.artistId, albumArtUriString = song.albumArtUriString, songCount = 0, dateAdded = System.currentTimeMillis(), year = song.year ?: 0)
            ))
            musicDao.insertSongsIgnoreConflicts(listOf(
                com.andymitch.dzr.data.database.SongEntity(
                    id = id,
                    title = song.title,
                    artistName = song.artist,
                    artistId = song.artistId,
                    albumName = song.album,
                    albumId = song.albumId,
                    contentUriString = song.contentUriString,
                    albumArtUriString = song.albumArtUriString,
                    duration = song.duration,
                    genre = null,
                    trackNumber = song.trackNumber,
                    discNumber = song.discNumber,
                    filePath = "",
                    parentDirectoryPath = "",
                    sourceType = if (song.contentUriString.startsWith("deezer://")) com.andymitch.dzr.data.database.SourceType.DEEZER else com.andymitch.dzr.data.database.SourceType.LOCAL
                )
            ))
        }
    }

    override fun getFavoriteSongIdsFlow(): Flow<Set<String>> {
        return favoritesDao.getFavoriteSongIds().map { list -> list.map { it.toString() }.toSet() }
    }

    override suspend fun getFavoriteSongIdsOnce(): Set<String> = withContext(Dispatchers.IO) {
        favoritesDao.getFavoriteSongIdsOnce().map { it.toString() }.toSet()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getFavoriteSongCountFlow(
        storageFilter: com.andymitch.dzr.data.model.StorageFilter
    ): Flow<Int> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            kotlinx.coroutines.flow.flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(Pair(allowedParentDirs, applyDirectoryFilter))
            }.flatMapLatest { (allowedDirs, applyFilter) ->
                musicDao.getFavoriteSongCount(
                    allowedParentDirs = allowedDirs,
                    applyDirectoryFilter = applyFilter,
                    filterMode = storageFilter.value
                )
            }
        }
    }
}

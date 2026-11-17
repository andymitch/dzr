package com.andymitch.dzr.data.service

import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.preferences.UserPreferencesRepository
import com.andymitch.dzr.data.repository.DeezerRepository

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.andymitch.dzr.data.network.deezer.DeezerTrack
import com.andymitch.dzr.data.network.deezer.DeezerTrackAttributes
import com.andymitch.dzr.data.provider.SharedArtworkContentProvider
import com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong
import com.andymitch.dzr.utils.MediaItemBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CarLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deezer: DeezerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    companion object {
        const val ROOT = "car"
        private const val HOME = "car/home"
        private const val LIBRARY = "car/library"
        private const val LIKED = "car/liked"
        private const val PLAYLISTS = "car/playlists"
        private const val ALBUMS = "car/albums"
        private const val ARTISTS = "car/artists"
        private const val DISCOVERY = "car/discovery"
        private const val MY_TOP = "car/mytop"
        private const val PLAYLIST = "car/playlist/"
        private const val ALBUM = "car/album/"
        const val FLOW = "car/flow/"
        private const val ARTIST_MIX = "car/artist/"
        private const val SONG_MIX = "car/mix/"
        private const val SEARCH = "car/search/"

        fun isCarId(mediaId: String) = mediaId.startsWith("$ROOT/")
    }

    suspend fun isSignedIn() = !userPreferencesRepository.deezerAccessTokenFlow.first().isNullOrEmpty()

    fun root(): MediaItem = folder(ROOT, "dzr")

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(folder(HOME, "Home", grid = true), folder(LIBRARY, "Library"))
        HOME -> home()
        LIBRARY -> listOf(
            folder(LIKED, "Liked songs"),
            folder(PLAYLISTS, "Playlists", grid = true),
            folder(ALBUMS, "Albums", grid = true),
            folder(ARTISTS, "Artists", grid = true),
        )
        PLAYLISTS -> {
            val lovedId = deezer.lovedPlaylistId()
            deezer.getUserPlaylists()?.data?.included.orEmpty()
                .filter { it.id != lovedId }
                .map { folder(PLAYLIST + it.id, it.attributes?.title ?: it.attributes?.name ?: "Playlist", art = it.attributes?.image?.medium) }
        }
        ALBUMS -> deezer.getGcastLovedAlbums(limit = 1000)?.data?.included.orEmpty().mapNotNull { album ->
            val id = album.id ?: return@mapNotNull null
            folder(ALBUM + id, album.attributes?.name ?: "Album", album.attributes?.artist?.name, album.attributes?.image?.medium)
        }
        ARTISTS -> deezer.getGcastLovedArtists()?.data?.included.orEmpty().mapNotNull { artist ->
            val attributes = artist.attributes ?: return@mapNotNull null
            playable(ARTIST_MIX + attributes.id, attributes.name ?: "Artist", "Artist Mix", attributes.pictures?.medium)
        }
        else -> songs(parentId).map { song ->
            playable("$parentId#${song.id}", song.title, song.displayArtist, song.albumArtUriString)
        }
    }

    suspend fun queue(mediaId: String): Pair<List<MediaItem>, Int> {
        val containerId = mediaId.substringBeforeLast('#')
        val songs = songs(containerId)
        val startIndex = songs.indexOfFirst { it.id == mediaId.substringAfterLast('#', "") }.coerceAtLeast(0)
        return songs.map(MediaItemBuilder::build) to startIndex
    }

    suspend fun voiceMediaId(query: String): String =
        if (query.isBlank()) FLOW + flowConfigs().firstOrNull()?.id else SEARCH + query

    suspend fun search(query: String): List<MediaItem> = children(SEARCH + query)

    private suspend fun home(): List<MediaItem> = coroutineScope {
        val flows = async {
            flowConfigs().map {
                playable(FLOW + it.id, it.attributes?.title ?: "Flow", art = it.attributes?.images?.square?.medium, group = "Flow")
            }
        }
        val mixes = async {
            deezer.getHistory().distinctBy { it.id }.take(10).map {
                playable(SONG_MIX + it.id, it.attributes?.title ?: "Mix", it.attributes?.artistName, it.attributes?.image?.medium, "Mixes inspired by...")
            }
        }
        val playlists = async {
            val month = LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            listOf(
                folder(DISCOVERY, "Discovery", group = "Recommended playlists"),
                folder(MY_TOP, "My Top $month", group = "Recommended playlists"),
            ) + deezer.getRecommendedPlaylists()?.data?.included.orEmpty().map {
                folder(PLAYLIST + it.id, it.attributes?.title ?: it.attributes?.name ?: "Playlist", art = it.attributes?.image?.medium, group = "Recommended playlists")
            }
        }
        flows.await() + mixes.await() + playlists.await()
    }

    private suspend fun flowConfigs() = deezer.getMultiFlowConfigs()?.data?.included.orEmpty()

    private suspend fun songs(containerId: String): List<Song> {
        val id = containerId.substringAfterLast('/')
        val tracks = when {
            containerId == LIKED -> deezer.getAllLovedTracks()
            containerId == DISCOVERY -> deezer.getDiscoveryMix()?.data?.included.orEmpty()
            containerId == MY_TOP -> deezer.getTopTracks()
            containerId.startsWith(PLAYLIST) -> deezer.getPlaylistTracks(id)?.data?.included.orEmpty()
            containerId.startsWith(ALBUM) -> return albumSongs(id)
            containerId.startsWith(FLOW) -> flowConfigs().firstOrNull { it.id == id }?.links?.self
                ?.let { deezer.getMultiFlowTracks(it)?.data?.included }.orEmpty()
            containerId.startsWith(ARTIST_MIX) -> id.toLongOrNull()?.let { deezer.getArtistRadio(it) }.orEmpty()
            containerId.startsWith(SONG_MIX) -> id.toLongOrNull()?.let { deezer.getTrackMix(it) }.orEmpty()
            containerId.startsWith(SEARCH) -> searchTracks(containerId.removePrefix(SEARCH))
            else -> emptyList()
        }
        return tracks.filter { it.type == null || it.type == "track" }.map(::mapDeezerTrackToSong)
    }

    private suspend fun albumSongs(albumId: String): List<Song> {
        val album = albumId.toLongOrNull()?.let { deezer.getAlbumInfo(it) }?.data ?: return emptyList()
        val cover = album.attributes?.image?.medium ?: album.attributes?.image?.large
        return album.included.orEmpty().map { track ->
            mapDeezerTrackToSong(track).copy(album = album.attributes?.name ?: "", albumArtUriString = cover)
        }
    }

    private suspend fun searchTracks(query: String): List<DeezerTrack> =
        deezer.searchTrack(query)?.data.orEmpty().filter { it.type == "track" }.map { item ->
            val attributes = item.attributes
            DeezerTrack(
                id = item.id,
                type = "track",
                attributes = DeezerTrackAttributes(
                    title = attributes?.title,
                    artistName = attributes?.artistName,
                    artistId = attributes?.artistId,
                    albumName = attributes?.albumName,
                    albumId = attributes?.albumId,
                    duration = attributes?.duration ?: 0,
                    image = attributes?.image,
                    explicit = attributes?.explicit,
                    explicitLyrics = attributes?.explicitLyrics,
                    explicitContentLyrics = attributes?.explicitContentLyrics,
                ),
            )
        }

    private fun folder(id: String, title: String, subtitle: String? = null, art: String? = null, group: String? = null, grid: Boolean = false) =
        item(id, title, subtitle, art, group, browsable = true, grid = grid)

    private fun playable(id: String, title: String, subtitle: String? = null, art: String? = null, group: String? = null) =
        item(id, title, subtitle, art, group, browsable = false, grid = false)

    private fun item(id: String, title: String, subtitle: String?, art: String?, group: String?, browsable: Boolean, grid: Boolean): MediaItem {
        val extras = Bundle().apply {
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
            if (grid) {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(SharedArtworkContentProvider.buildRemoteUri(context, art))
                    .setIsBrowsable(browsable)
                    .setIsPlayable(!browsable)
                    .setMediaType(if (browsable) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
}

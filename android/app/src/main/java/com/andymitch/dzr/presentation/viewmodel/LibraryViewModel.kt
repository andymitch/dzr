package com.andymitch.dzr.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryStateHolder: LibraryStateHolder,
    private val deezerRepository: com.andymitch.dzr.data.repository.DeezerRepository
) : ViewModel() {

    val songsPagingFlow = libraryStateHolder.songsPagingFlow.cachedIn(viewModelScope)

    val albumsPagingFlow = libraryStateHolder.albumsPagingFlow.cachedIn(viewModelScope)

    val artistsPagingFlow = libraryStateHolder.artistsPagingFlow.cachedIn(viewModelScope)

    val favoritesPagingFlow = libraryStateHolder.favoritesPagingFlow.cachedIn(viewModelScope)

    val favoriteSongCountFlow = libraryStateHolder.favoriteSongCountFlow

    val isLoadingLibrary = libraryStateHolder.isLoadingLibrary

    private val _deezerFlowConfigs = kotlinx.coroutines.flow.MutableStateFlow<com.andymitch.dzr.data.network.deezer.DeezerMultiFlowConfigsResponse?>(null)
    val deezerFlowConfigs: kotlinx.coroutines.flow.StateFlow<com.andymitch.dzr.data.network.deezer.DeezerMultiFlowConfigsResponse?> = _deezerFlowConfigs

    private val _deezerRecommendedPlaylists = kotlinx.coroutines.flow.MutableStateFlow<com.andymitch.dzr.data.network.deezer.DeezerRecommendedPlaylistsResponse?>(null)
    val deezerRecommendedPlaylists: kotlinx.coroutines.flow.StateFlow<com.andymitch.dzr.data.network.deezer.DeezerRecommendedPlaylistsResponse?> = _deezerRecommendedPlaylists

    data class HomeMix(
        val id: String,
        val title: String,
        val featuring: String,
        val covers: List<String>,
        val songs: List<com.andymitch.dzr.data.model.Song>
    )

    private fun homeMix(id: String, title: String, songs: List<com.andymitch.dzr.data.model.Song>) = HomeMix(
        id = id,
        title = title,
        featuring = "Featuring " + songs.map { it.artist }.distinct().take(4).joinToString(", "),
        covers = songs.mapNotNull { it.highResAlbumArtUriString ?: it.albumArtUriString }.distinct().take(3),
        songs = songs
    )

    private val _myTop = kotlinx.coroutines.flow.MutableStateFlow<HomeMix?>(null)
    val myTop: kotlinx.coroutines.flow.StateFlow<HomeMix?> = _myTop

    private val _newReleases = kotlinx.coroutines.flow.MutableStateFlow<List<com.andymitch.dzr.data.repository.DeezerRepository.Release>>(emptyList())
    val newReleases: kotlinx.coroutines.flow.StateFlow<List<com.andymitch.dzr.data.repository.DeezerRepository.Release>> = _newReleases

    private val _newReleasesMix = kotlinx.coroutines.flow.MutableStateFlow<HomeMix?>(null)
    val newReleasesMix: kotlinx.coroutines.flow.StateFlow<HomeMix?> = _newReleasesMix

    suspend fun playlistSongs(playlistId: String): List<com.andymitch.dzr.data.model.Song> =
        deezerRepository.getPlaylistTracks(playlistId)?.data?.included.orEmpty()
            .filter { it.type == "track" }
            .map { com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong(it) }

    suspend fun albumSongs(release: com.andymitch.dzr.data.repository.DeezerRepository.Release): List<com.andymitch.dzr.data.model.Song> =
        deezerRepository.getAlbumTracks(release).map { com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong(it) }

    private val _recentlyPlayed = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<String, com.andymitch.dzr.data.network.deezer.DeezerPlaylist>>>(emptyList())
    val recentlyPlayed: kotlinx.coroutines.flow.StateFlow<List<Pair<String, com.andymitch.dzr.data.network.deezer.DeezerPlaylist>>> = _recentlyPlayed

    suspend fun history(): List<com.andymitch.dzr.data.model.Song> =
        deezerRepository.getHistory().map { com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong(it) }.distinctBy { it.id }

    private val _songMixSeeds = kotlinx.coroutines.flow.MutableStateFlow<List<com.andymitch.dzr.data.model.Song>>(emptyList())
    val songMixSeeds: kotlinx.coroutines.flow.StateFlow<List<com.andymitch.dzr.data.model.Song>> = _songMixSeeds

    suspend fun trackMix(song: com.andymitch.dzr.data.model.Song): List<com.andymitch.dzr.data.model.Song> =
        song.id.toLongOrNull()?.let { id ->
            deezerRepository.getTrackMix(id).map { com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong(it) }
        }.orEmpty()

    fun refreshRecentlyPlayed() {
        viewModelScope.launch { _recentlyPlayed.value = deezerRepository.getRecentlyPlayed() }
    }

    private val _yourDiscovery = kotlinx.coroutines.flow.MutableStateFlow<com.andymitch.dzr.data.network.deezer.DeezerPlaylistDetailData?>(null)
    val yourDiscovery: kotlinx.coroutines.flow.StateFlow<com.andymitch.dzr.data.network.deezer.DeezerPlaylistDetailData?> = _yourDiscovery

    init {
        viewModelScope.launch {
            val flowConfigs = deezerRepository.getMultiFlowConfigs()
            _deezerFlowConfigs.value = flowConfigs
        }
        viewModelScope.launch {
            val playlists = deezerRepository.getRecommendedPlaylists()
            _deezerRecommendedPlaylists.value = playlists
        }
        viewModelScope.launch {
            val top = deezerRepository.getTopTracks().map { com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong(it) }
            if (top.isNotEmpty()) {
                val month = java.time.LocalDate.now().month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                _myTop.value = homeMix("my_top", "My Top $month", top)
            }
        }
        viewModelScope.launch {
            val releases = deezerRepository.getRecommendedReleases()
            _newReleases.value = releases
            val firstTracks = kotlinx.coroutines.coroutineScope {
                releases.take(20).map { release -> async { albumSongs(release).firstOrNull() } }.awaitAll()
            }.filterNotNull()
            if (firstTracks.isNotEmpty()) _newReleasesMix.value = homeMix("new_releases", "New releases for you", firstTracks)
        }
        refreshRecentlyPlayed()
        viewModelScope.launch { _songMixSeeds.value = history().take(10) }
        viewModelScope.launch {
            val discovery = deezerRepository.getDiscoveryMix()?.data
            _yourDiscovery.value = discovery
        }
    }

    suspend fun getMultiFlowTracks(url: String): com.andymitch.dzr.data.network.deezer.DeezerMultiFlowResponse? {
        return deezerRepository.getMultiFlowTracks(url)
    }
}

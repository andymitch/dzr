package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryStateHolder: LibraryStateHolder,
    private val deezerRepository: com.lostf1sh.pixelplayeross.data.repository.DeezerRepository
) : ViewModel() {

    val songsPagingFlow = libraryStateHolder.songsPagingFlow.cachedIn(viewModelScope)

    val albumsPagingFlow = libraryStateHolder.albumsPagingFlow.cachedIn(viewModelScope)

    val artistsPagingFlow = libraryStateHolder.artistsPagingFlow.cachedIn(viewModelScope)

    val favoritesPagingFlow = libraryStateHolder.favoritesPagingFlow.cachedIn(viewModelScope)

    val favoriteSongCountFlow = libraryStateHolder.favoriteSongCountFlow

    val isLoadingLibrary = libraryStateHolder.isLoadingLibrary

    private val _deezerFlowConfigs = kotlinx.coroutines.flow.MutableStateFlow<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerMultiFlowConfigsResponse?>(null)
    val deezerFlowConfigs: kotlinx.coroutines.flow.StateFlow<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerMultiFlowConfigsResponse?> = _deezerFlowConfigs

    private val _deezerRecommendedPlaylists = kotlinx.coroutines.flow.MutableStateFlow<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerRecommendedPlaylistsResponse?>(null)
    val deezerRecommendedPlaylists: kotlinx.coroutines.flow.StateFlow<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerRecommendedPlaylistsResponse?> = _deezerRecommendedPlaylists

    private val _yourMixes = kotlinx.coroutines.flow.MutableStateFlow<List<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerPlaylistDetailData>>(emptyList())
    val yourMixes: kotlinx.coroutines.flow.StateFlow<List<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerPlaylistDetailData>> = _yourMixes

    val yourMixSongs: kotlinx.coroutines.flow.StateFlow<List<com.lostf1sh.pixelplayeross.data.model.Song>> = _yourMixes
        .map { mixes ->
            mixes.flatMap { mix -> 
                mix.included.filter { it.type == "track" }.map { com.lostf1sh.pixelplayeross.presentation.screens.mapDeezerTrackToSong(it) } 
            }.distinctBy { it.id }.take(50)
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    private val _yourDiscovery = kotlinx.coroutines.flow.MutableStateFlow<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerPlaylistDetailData?>(null)
    val yourDiscovery: kotlinx.coroutines.flow.StateFlow<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerPlaylistDetailData?> = _yourDiscovery

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
            val mixes = mutableListOf<com.lostf1sh.pixelplayeross.data.network.deezer.DeezerPlaylistDetailData>()
            for (i in 1..5) {
                val mix = deezerRepository.getInspiredByMix(i)?.data
                if (mix != null) {
                    mixes.add(mix.copy(id = "your_mix_$i"))
                }
            }
            _yourMixes.value = mixes
        }
        viewModelScope.launch {
            val discovery = deezerRepository.getDiscoveryMix()?.data
            _yourDiscovery.value = discovery
        }
    }

    suspend fun getMultiFlowTracks(url: String): com.lostf1sh.pixelplayeross.data.network.deezer.DeezerMultiFlowResponse? {
        return deezerRepository.getMultiFlowTracks(url)
    }
}

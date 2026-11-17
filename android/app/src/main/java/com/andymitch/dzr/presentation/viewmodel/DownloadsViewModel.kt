package com.andymitch.dzr.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andymitch.dzr.data.download.DownloadStore
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.preferences.DeezerAudioQuality
import com.andymitch.dzr.data.repository.DeezerRepository
import com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val store: DownloadStore,
    private val deezerRepository: DeezerRepository,
    private val cache: com.andymitch.dzr.data.download.SongCache,
) : ViewModel() {
    val wifiOnly = store.wifiOnly
    val cacheAhead = cache.ahead
    fun setWifiOnly(enabled: Boolean) = store.setWifiOnly(enabled)
    fun setCacheAhead(count: Int) = cache.setAhead(count)
    fun cacheBytes(): Long = cache.bytesUsed()
    fun clearCache() = cache.clear()

    val index = store.index
    val progress = store.progress
    val quality = store.quality

    fun songs(): List<Song> = store.songs()
    fun bytesUsed(): Long = store.bytesUsed()
    fun setQuality(quality: DeezerAudioQuality) = store.setQuality(quality)
    fun download(collectionId: String, title: String, songs: List<Song>) = store.download(collectionId, title, songs)
    fun remove(collectionId: String) = store.remove(collectionId)
    fun removeAll() = store.removeAll()

    fun downloadLiked() {
        viewModelScope.launch {
            val songs = deezerRepository.getAllLovedTracks().map(::mapDeezerTrackToSong)
            if (songs.isNotEmpty()) store.download(LIKED, "Liked songs", songs)
        }
    }

    companion object {
        const val LIKED = "liked"
    }
}

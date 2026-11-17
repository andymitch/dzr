package com.andymitch.dzr.presentation.viewmodel

import com.andymitch.dzr.data.model.Lyrics
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.preferences.UserPreferencesRepository
import com.andymitch.dzr.data.repository.LyricsSearchResult

import com.andymitch.dzr.R
import com.andymitch.dzr.data.media.AudioMetadataReader
import com.andymitch.dzr.data.model.LyricsSourcePreference
import com.andymitch.dzr.data.repository.MusicRepository
import com.andymitch.dzr.data.repository.NoLyricsFoundException
import com.andymitch.dzr.utils.LyricsUtils
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Callback interface for lyrics loading results.
 * Used to update StablePlayerState in PlayerViewModel.
 */
interface LyricsLoadCallback {
    fun onLoadingStarted(songId: String)
    fun onLyricsLoaded(songId: String, lyrics: Lyrics?)
}

/**
 * Manages lyrics loading, search state, and sync offset.
 * Extracted from PlayerViewModel to improve modularity.
 */
@Singleton
class LyricsStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private var scope: CoroutineScope? = null
    private var loadingJob: Job? = null
    private var loadCallback: LyricsLoadCallback? = null

    // Sync offset per song in milliseconds
    private val _currentSongSyncOffset = MutableStateFlow(0)
    val currentSongSyncOffset: StateFlow<Int> = _currentSongSyncOffset.asStateFlow()

    // Lyrics search UI state
    private val _searchUiState = MutableStateFlow<LyricsSearchUiState>(LyricsSearchUiState.Idle)
    val searchUiState: StateFlow<LyricsSearchUiState> = _searchUiState.asStateFlow()

    // Event to notify ViewModel of song updates (e.g. lyrics added)
    private val _songUpdates = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Song, Lyrics?>>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val songUpdates = _songUpdates.asSharedFlow()

    // Event for Toasts
    private val _messageEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val messageEvents = _messageEvents.asSharedFlow()

    /**
     * Initialize with coroutine scope and callback from ViewModel.
     */
    fun initialize(
        coroutineScope: CoroutineScope,
        callback: LyricsLoadCallback,
        stablePlayerState: StateFlow<com.andymitch.dzr.presentation.viewmodel.StablePlayerState>
    ) {
        scope = coroutineScope
        loadCallback = callback

        coroutineScope.launch {
            stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .collect { songId ->
                    if (songId != null) {
                        updateSyncOffsetForSong(songId)
                    }
                }
        }
    }

    /**
     * Load lyrics for a song.
     * @param song The song to load lyrics for
     * @param sourcePreference The preferred source for lyrics
     */
    fun loadLyricsForSong(song: Song, sourcePreference: LyricsSourcePreference) {
        loadingJob?.cancel()
        val targetSongId = song.id

        loadingJob = scope?.launch {
            loadCallback?.onLoadingStarted(targetSongId)

            val fetchedLyrics = try {
                withContext(Dispatchers.IO) {
                    musicRepository.getLyrics(
                        song = song,
                        sourcePreference = sourcePreference
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

            loadCallback?.onLyricsLoaded(targetSongId, fetchedLyrics)
        }
    }

    /**
     * Cancel any ongoing lyrics loading.
     */
    fun cancelLoading() {
        loadingJob?.cancel()
    }

    /**
     * Set sync offset for a song.
     */
    fun setSyncOffset(songId: String, offsetMs: Int) {
        scope?.launch {
            userPreferencesRepository.setLyricsSyncOffset(songId, offsetMs)
            _currentSongSyncOffset.value = offsetMs
        }
    }

    /**
     * Update sync offset from song ID (called when song changes).
     */
    suspend fun updateSyncOffsetForSong(songId: String) {
        val offset = userPreferencesRepository.getLyricsSyncOffset(songId)
        _currentSongSyncOffset.value = offset
    }


    /**
     * Reset the lyrics search state to idle.
     */
    fun resetSearchState() {
        _searchUiState.value = LyricsSearchUiState.Idle
    }












    fun onCleared() {
        loadingJob?.cancel()
        scope = null
        loadCallback = null
    }
}


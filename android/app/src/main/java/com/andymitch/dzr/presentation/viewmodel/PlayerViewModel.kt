package com.andymitch.dzr.presentation.viewmodel

import com.andymitch.dzr.data.model.LibraryTabId
import com.andymitch.dzr.data.model.Album
import com.andymitch.dzr.data.model.Artist
import com.andymitch.dzr.data.model.Genre
import com.andymitch.dzr.data.model.Lyrics
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.model.SortOption
import com.andymitch.dzr.data.preferences.FullPlayerLoadingTweaks
import com.andymitch.dzr.data.preferences.AlbumArtPaletteStyle
import com.andymitch.dzr.data.preferences.ThemePreferencesRepository
import com.andymitch.dzr.data.preferences.UserPreferencesRepository
import com.andymitch.dzr.data.repository.LyricsSearchResult
import com.andymitch.dzr.utils.AppShortcutManager

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Trace
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.Timeline
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.andymitch.dzr.R
import com.andymitch.dzr.data.EotStateHolder
import com.andymitch.dzr.data.model.FolderSource
import com.andymitch.dzr.data.model.LyricsSourcePreference
import com.andymitch.dzr.data.model.SearchFilterType
import com.andymitch.dzr.data.model.toLibraryTabIdOrNull
import com.andymitch.dzr.data.provider.SharedArtworkContentProvider
import com.andymitch.dzr.data.preferences.CarouselStyle
import com.andymitch.dzr.data.preferences.LibraryNavigationMode
import com.andymitch.dzr.data.preferences.NavBarStyle
import com.andymitch.dzr.data.preferences.AlbumArtQuality
import com.andymitch.dzr.data.preferences.ThemePreference
import com.andymitch.dzr.data.repository.MusicRepository
import com.andymitch.dzr.data.service.MusicNotificationProvider
import com.andymitch.dzr.data.service.MusicService
import com.andymitch.dzr.data.service.player.DualPlayerEngine
import com.andymitch.dzr.utils.QueueUtils
import com.andymitch.dzr.utils.MediaItemBuilder
import com.andymitch.dzr.utils.LocalArtworkUri
import com.andymitch.dzr.utils.LyricsUtils
import com.andymitch.dzr.utils.ZipShareHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import androidx.paging.PagingData
import androidx.paging.cachedIn
import coil.memory.MemoryCache

private const val ENABLE_FOLDERS_SOURCE_SWITCHING = true
private const val MAX_ALBUM_BATCH_SELECTION = 6
private const val SONG_ID_QUERY_CHUNK_SIZE = 900
private const val HOME_MIX_PREVIEW_LIMIT = 48
private const val EXTERNAL_SONG_ID_PREFIX = "external:"
private val LOCAL_PLAYBACK_SCHEMES = setOf("content", "file", "android.resource")

private fun List<Song>.toPlaybackQueue(): ImmutableList<Song> = when (this) {
    is PersistentList<Song> -> this
    is ImmutableList<Song> -> this
    else -> this.toPersistentList()
}

private fun ImmutableList<Song>.asPersistentPlaybackQueue(): PersistentList<Song> =
    this as? PersistentList<Song> ?: this.toPersistentList()

private fun ImmutableList<Song>.replaceSong(updatedSong: Song): ImmutableList<Song> {
    val index = indexOfFirst { it.id == updatedSong.id }
    if (index == -1) return this
    return asPersistentPlaybackQueue().set(index, updatedSong)
}


private fun ImmutableList<Song>.moveSong(fromIndex: Int, toIndex: Int): ImmutableList<Song> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return this
    val movedSong = this[fromIndex]
    return asPersistentPlaybackQueue()
        .removeAt(fromIndex)
        .add(toIndex, movedSong)
}

private fun moveQueueIndex(index: Int, fromIndex: Int, toIndex: Int): Int {
    if (index == C.INDEX_UNSET || fromIndex == toIndex) return index
    return when {
        index == fromIndex -> toIndex
        fromIndex < toIndex && index in (fromIndex + 1)..toIndex -> index - 1
        toIndex < fromIndex && index in toIndex until fromIndex -> index + 1
        else -> index
    }
}

private data class QueueTimelineSignature(
    val count: Int,
    val orderHash: Long,
    val firstMediaId: String?,
    val lastMediaId: String?
)

data class PlaybackAudioMetadata(
    val mediaId: String? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val bitDepth: Int? = null
)

private data class SortOptionsSnapshot(
    val songSort: SortOption,
    val albumSort: SortOption,
    val artistSort: SortOption,
    val folderSort: SortOption,
    val favoriteSort: SortOption,
)

private data class PreparedPlaybackQueueSegments(
    val beforeCurrent: List<MediaItem>,
    val afterCurrent: List<MediaItem>,
    val currentIndex: Int
)




private data class ResolvedAlbumSelection(
    val albums: List<Album>,
    val songs: List<Song>,
    val wasTrimmed: Boolean
)

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("LogNotTimber")
@OptIn(coil.annotation.ExperimentalCoilApi::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val deezerRepository: com.andymitch.dzr.data.repository.DeezerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val playlistPreferencesRepository: com.andymitch.dzr.data.preferences.PlaylistPreferencesRepository,

    private val dualPlayerEngine: DualPlayerEngine,
    private val appShortcutManager: AppShortcutManager,
    private val listeningStatsTracker: ListeningStatsTracker,
    private val dailyMixStateHolder: DailyMixStateHolder,
    private val lyricsStateHolder: LyricsStateHolder,
    private val queueStateHolder: QueueStateHolder,
    private val queueUndoStateHolder: QueueUndoStateHolder,
    private val playlistDismissUndoStateHolder: PlaylistDismissUndoStateHolder,
    private val playbackStateHolder: PlaybackStateHolder,
    private val connectivityStateHolder: ConnectivityStateHolder,
    private val sleepTimerStateHolder: SleepTimerStateHolder,
    private val searchStateHolder: SearchStateHolder,
    private val libraryStateHolder: LibraryStateHolder,
    private val libraryTabsStateHolder: LibraryTabsStateHolder,
    private val externalMediaStateHolder: ExternalMediaStateHolder,
    val themeStateHolder: ThemeStateHolder,
    val multiSelectionStateHolder: MultiSelectionStateHolder,
    val playlistSelectionStateHolder: PlaylistSelectionStateHolder,
    private val sessionToken: SessionToken,
    private val mediaControllerFactory: com.andymitch.dzr.data.media.MediaControllerFactory,
    private val flowContinuation: com.andymitch.dzr.data.service.FlowContinuation,
    private val castController: com.andymitch.dzr.data.service.CastController
) : ViewModel() {

    private val _playerUiState = MutableStateFlow(PlayerUiState())
    val playerUiState: StateFlow<PlayerUiState> = _playerUiState.asStateFlow()


    // Dedicated queue flow so the player sheet's MiniPlayer branch does not
    // recompose whenever the queue changes. Consumers that actually need the
    // queue (FullPlayer carousel, queue sheet) collect this narrower flow
    // directly, keeping the unrelated subtree stable.
    val queueFlow: StateFlow<ImmutableList<Song>> = _playerUiState
        .map { it.currentPlaybackQueue }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = persistentListOf()
        )

    private val _showNoInternetDialog = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val showNoInternetDialog: SharedFlow<Unit> = _showNoInternetDialog.asSharedFlow()

    val stablePlayerState: StateFlow<StablePlayerState> = playbackStateHolder.stablePlayerState
    val albumArtPaletteStyle: StateFlow<AlbumArtPaletteStyle> = themePreferencesRepository
        .albumArtPaletteStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AlbumArtPaletteStyle.default
        )
    /**
     * High-frequency playback position should not force global UI recomposition.
     * Keep a dedicated position flow for real-time UI elements (seek bars, lyrics timing).
     */
    val currentPlaybackPosition: StateFlow<Long> = playbackStateHolder.currentPosition
    val playbackHistory = listeningStatsTracker.playbackHistory

    // Removed: _masterAllSongs was a duplicate of libraryStateHolder.allSongs
    // All reads now delegate to libraryStateHolder.allSongs

    // Lyrics load callback for LyricsStateHolder
    private val lyricsLoadCallback = object : LyricsLoadCallback {
        override fun onLoadingStarted(songId: String) {
            playbackStateHolder.updateStablePlayerState { state ->
                if (state.currentSong?.id != songId) state
                else state.copy(isLoadingLyrics = true, lyrics = null)
            }
        }

        override fun onLyricsLoaded(songId: String, lyrics: Lyrics?) {
            playbackStateHolder.updateStablePlayerState { state ->
                if (state.currentSong?.id != songId) state
                else state.copy(isLoadingLyrics = false, lyrics = lyrics)
            }
        }
    }



    private val _playlistPickerStorageFilter = MutableStateFlow(com.andymitch.dzr.data.model.StorageFilter.OFFLINE)
    val playlistPickerStorageFilter: StateFlow<com.andymitch.dzr.data.model.StorageFilter> = _playlistPickerStorageFilter.asStateFlow()


    @OptIn(ExperimentalCoroutinesApi::class)
    val playlistPickerFavoriteSongs: Flow<PagingData<Song>> = combine(
        libraryStateHolder.currentSongSortOption,
        _playlistPickerStorageFilter
    ) { sortOption, storageFilter ->
        sortOption to storageFilter
    }
        .flatMapLatest { (sortOption, storageFilter) ->
            musicRepository.getPaginatedFavoriteSongs(
                sortOption = sortOption,
                storageFilter = storageFilter
            )
        }
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val playlistPickerSongs: Flow<PagingData<Song>> = combine(
        libraryStateHolder.currentSongSortOption,
        _playlistPickerStorageFilter
    ) { sortOption, storageFilter ->
        sortOption to storageFilter
    }
        .flatMapLatest { (sortOption, storageFilter) ->
            musicRepository.getPaginatedSongs(
                sortOption = sortOption,
                storageFilter = storageFilter
            )
        }
        .cachedIn(viewModelScope)

    private val offlinePlaybackObserverJob = viewModelScope.launch {
        connectivityStateHolder.offlinePlaybackBlocked.collect {
            Timber.w("Received offline blocked event. Showing dialog.")
            _showNoInternetDialog.emit(Unit)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSongArtists: StateFlow<List<Artist>> = stablePlayerState
        .map { it.currentSong?.id }
        .distinctUntilChanged()
        .flatMapLatest { songId ->
            val idLong = songId?.toLongOrNull()
            if (idLong == null) flowOf(emptyList())
            else musicRepository.getArtistsForSong(idLong)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sheetState = MutableStateFlow(PlayerSheetState.COLLAPSED)
    val sheetState: StateFlow<PlayerSheetState> = _sheetState.asStateFlow()
    private val _isSheetVisible = MutableStateFlow(false)
    private val _bottomBarHeight = MutableStateFlow(0)
    val bottomBarHeight: StateFlow<Int> = _bottomBarHeight.asStateFlow()
    private val _predictiveBackCollapseFraction = MutableStateFlow(0f)
    val predictiveBackCollapseFraction: StateFlow<Float> = _predictiveBackCollapseFraction.asStateFlow()
    private val _predictiveBackSwipeEdge = MutableStateFlow<Int?>(null)
    val predictiveBackSwipeEdge: StateFlow<Int?> = _predictiveBackSwipeEdge.asStateFlow()
    private val _isQueueSheetVisible = MutableStateFlow(false)

    val playerContentExpansionFraction = Animatable(0f)

    private val _isMiniPlayerDismissing = MutableStateFlow(false)
    val isMiniPlayerDismissing: StateFlow<Boolean> = _isMiniPlayerDismissing.asStateFlow()

    fun setMiniPlayerDismissing(dismissing: Boolean) {
        _isMiniPlayerDismissing.value = dismissing
    }

    private val _selectedSongForInfo = MutableStateFlow<Song?>(null)
    val selectedSongForInfo: StateFlow<Song?> = _selectedSongForInfo.asStateFlow()

    // Theme & Colors - delegated to ThemeStateHolder
    val currentAlbumArtColorSchemePair: StateFlow<ColorSchemePair?> = themeStateHolder.currentAlbumArtColorSchemePair
    val activePlayerColorSchemePair: StateFlow<ColorSchemePair?> = themeStateHolder.activePlayerColorSchemePair
    val currentThemedAlbumArtUri: StateFlow<String?> = themeStateHolder.currentAlbumArtUri

    val playerThemePreference: StateFlow<String> = themePreferencesRepository.playerThemePreferenceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemePreference.ALBUM_ART
        )

    val navBarCornerRadius: StateFlow<Int> = userPreferencesRepository.navBarCornerRadiusFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 32)

    val navBarStyle: StateFlow<String> = userPreferencesRepository.navBarStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = NavBarStyle.DEFAULT
        )

    val navBarCompactMode: StateFlow<Boolean> = userPreferencesRepository.navBarCompactModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val libraryNavigationMode: StateFlow<String> = userPreferencesRepository.libraryNavigationModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryNavigationMode.TAB_ROW
        )

    val carouselStyle: StateFlow<String> = userPreferencesRepository.carouselStyleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CarouselStyle.NO_PEEK
        )

    val fullPlayerLoadingTweaks: StateFlow<FullPlayerLoadingTweaks> = userPreferencesRepository.fullPlayerLoadingTweaksFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FullPlayerLoadingTweaks()
        )

    val showPlayerFileInfo: StateFlow<Boolean> = userPreferencesRepository.showPlayerFileInfoFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /**
     * Whether tapping the background of the player sheet toggles its state.
     * When disabled, users must use gestures or buttons to expand/collapse.
     */
    val tapBackgroundClosesPlayer: StateFlow<Boolean> = userPreferencesRepository.tapBackgroundClosesPlayerFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val hapticsEnabled: StateFlow<Boolean> = userPreferencesRepository.hapticsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    // Lyrics sync offset - now managed by LyricsStateHolder
    val currentSongLyricsSyncOffset: StateFlow<Int> = lyricsStateHolder.currentSongSyncOffset

    // Lyrics source preference (API_FIRST, EMBEDDED_FIRST, LOCAL_FIRST)
    val lyricsSourcePreference: StateFlow<LyricsSourcePreference> = userPreferencesRepository.lyricsSourcePreferenceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LyricsSourcePreference.EMBEDDED_FIRST
        )

    val immersiveLyricsEnabled: StateFlow<Boolean> = userPreferencesRepository.immersiveLyricsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val immersiveLyricsTimeout: StateFlow<Long> = userPreferencesRepository.immersiveLyricsTimeoutFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 4000L
        )

    private val _isImmersiveTemporarilyDisabled = MutableStateFlow(false)
    val isImmersiveTemporarilyDisabled: StateFlow<Boolean> = _isImmersiveTemporarilyDisabled.asStateFlow()

    fun setImmersiveTemporarilyDisabled(disabled: Boolean) {
        _isImmersiveTemporarilyDisabled.value = disabled
    }

    val albumArtQuality: StateFlow<AlbumArtQuality> = userPreferencesRepository.albumArtQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlbumArtQuality.MEDIUM)

    val deezerAudioQuality: StateFlow<com.andymitch.dzr.data.preferences.DeezerAudioQuality> = userPreferencesRepository.deezerAudioQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.andymitch.dzr.data.preferences.DeezerAudioQuality.HIGH)

    private val _currentTrackDeezerQuality = MutableStateFlow<com.andymitch.dzr.data.preferences.DeezerAudioQuality?>(null)
    val currentTrackDeezerQuality: StateFlow<com.andymitch.dzr.data.preferences.DeezerAudioQuality?> = _currentTrackDeezerQuality.asStateFlow()

    fun setDeezerAudioQuality(quality: com.andymitch.dzr.data.preferences.DeezerAudioQuality) {
        viewModelScope.launch {
            userPreferencesRepository.setDeezerAudioQuality(quality)
        }
    }

    fun changeCurrentTrackQuality(quality: com.andymitch.dzr.data.preferences.DeezerAudioQuality) {
        val controller = mediaController ?: return
        val currentIndex = controller.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return

        val currentItem = controller.currentMediaItem ?: return
        val currentUri = currentItem.localConfiguration?.uri ?: return
        if (currentUri.scheme != "deezer" || currentUri.host != "track") return

        val newUri = currentUri.buildUpon()
            .clearQuery()
            .appendQueryParameter("quality", quality.name)
            .build()

        val newItem = currentItem.buildUpon().setUri(newUri).build()
        val position = controller.currentPosition.coerceAtLeast(0L)

        controller.replaceMediaItem(currentIndex, newItem)
        controller.seekTo(currentIndex, position)
        
        _currentTrackDeezerQuality.value = quality
        // Also update local temporary state if needed for UI, but exoPlayer will naturally fire transition
    }

    fun setLyricsSyncOffset(songId: String, offsetMs: Int) {
        lyricsStateHolder.setSyncOffset(songId, offsetMs)
    }

    val useSmoothCorners: StateFlow<Boolean> = userPreferencesRepository.useSmoothCornersFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )



    private val _isInitialThemePreloadComplete = MutableStateFlow(false)

    val isEndOfTrackTimerActive: StateFlow<Boolean> = sleepTimerStateHolder.isEndOfTrackTimerActive
    val activeTimerValueDisplay: StateFlow<String?> = sleepTimerStateHolder.activeTimerValueDisplay
    val activeTimerDurationMinutes: StateFlow<Int?> = sleepTimerStateHolder.activeTimerDurationMinutes
    val playCount: StateFlow<Float> = sleepTimerStateHolder.playCount


    private var bufferingDebounceJob: Job? = null



    // Toast Events
    private val _toastEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastEvents = _toastEvents.asSharedFlow()

    // MediaStore write-permission request (needed for metadata editing without MANAGE_EXTERNAL_STORAGE)

    // MediaStore delete-permission request (for deletion without MANAGE_EXTERNAL_STORAGE)


    private val _albumNavigationRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val albumNavigationRequests = _albumNavigationRequests.asSharedFlow()
    private val _artistNavigationRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val artistNavigationRequests = _artistNavigationRequests.asSharedFlow()
    private val _searchNavDoubleTapEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val searchNavDoubleTapEvents = _searchNavDoubleTapEvents.asSharedFlow()
    
    // New event for scrolling to a specific index in the songs list
    private val _scrollToIndexEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val scrollToIndexEvent = _scrollToIndexEvent.asSharedFlow()
    
    private var albumNavigationJob: Job? = null
    private var artistNavigationJob: Job? = null
    private var fullQueuePlaybackJob: Job? = null
    private var fullQueuePlaybackToken: Long = 0L
    private var directPlaybackJob: Job? = null
    private var directPlaybackToken: Long = 0L
    private var pendingQueueSegmentsJob: Job? = null


    fun requestLocateCurrentFavoriteSong(sortOption: com.andymitch.dzr.data.model.SortOption, storageFilter: com.andymitch.dzr.data.model.StorageFilter) {
        val currentSong = stablePlayerState.value.currentSong ?: return

        viewModelScope.launch {
            try {
                val sortedIds = musicRepository.getFavoriteSongIdsSorted(sortOption, storageFilter)

                val unifiedId = currentSong.id.toLongOrNull()
                    ?: currentSong.contentUriString
                        .takeIf { it.isNotBlank() }
                        ?.let { musicRepository.getSongIdByContentUri(it) }

                val index = unifiedId?.let { sortedIds.indexOf(it) } ?: -1

                if (index != -1) {
                    _scrollToIndexEvent.emit(index)
                } else {
                    sendToast(context.getString(R.string.player_song_not_found_in_list))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to locate current favorite song")
                sendToast(context.getString(R.string.player_could_not_locate_song))
            }
        }
    }


    fun showAndPlaySongFromFavorites(
        song: Song,
        queueName: String = "Liked Songs",
        isVoluntaryPlay: Boolean = true
    ) {
        launchLatestFullQueuePlayback(
            song = song,
            queueName = queueName,
            isVoluntaryPlay = isVoluntaryPlay,
            failureMessage = "Failed to build favorites queue for songId=%s"
        ) {
            val sortOption = playerUiState.value.currentFavoriteSortOption
            val storageFilter = playerUiState.value.currentStorageFilter
            musicRepository.getFavoriteSongIdsSorted(sortOption, storageFilter)
        }
    }

    suspend fun getSongsForCurrentLibrarySelection(): List<Song> {
        val sortOption = playerUiState.value.currentSongSortOption
        val storageFilter = playerUiState.value.currentStorageFilter
        val sortedIds = musicRepository.getSongIdsSorted(sortOption, storageFilter)
        return resolvePlaybackQueueFromSortedIds(sortedIds)
    }

    private fun launchLatestFullQueuePlayback(
        song: Song,
        queueName: String,
        isVoluntaryPlay: Boolean,
        failureMessage: String,
        sortedIdsProvider: suspend () -> List<Long>
    ) {
        cancelPendingFullQueuePlayback()
        cancelPendingDirectPlayback()
        val requestToken = fullQueuePlaybackToken

        fullQueuePlaybackJob = viewModelScope.launch {
            try {
                val sortedIds = sortedIdsProvider()
                throwIfFullQueuePlaybackRequestIsStale(requestToken)

                val fullQueue = resolvePlaybackQueueFromSortedIds(sortedIds)
                throwIfFullQueuePlaybackRequestIsStale(requestToken)

                showAndPlaySong(
                    song = song,
                    contextSongs = fullQueue.ifEmpty { listOf(song) },
                    queueName = queueName,
                    isVoluntaryPlay = isVoluntaryPlay,
                    cancelPendingQueueBuild = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestToken != fullQueuePlaybackToken) {
                    return@launch
                }

                Timber.e(error, failureMessage, song.id)
                val fallbackQueue = libraryStateHolder.allSongs.value.takeIf { songs ->
                    songs.isNotEmpty() && songs.any { it.id == song.id }
                } ?: listOf(song)
                showAndPlaySong(
                    song = song,
                    contextSongs = fallbackQueue,
                    queueName = queueName,
                    isVoluntaryPlay = isVoluntaryPlay,
                    cancelPendingQueueBuild = false
                )
            }
        }
    }

    private fun cancelPendingFullQueuePlayback() {
        fullQueuePlaybackToken += 1L
        fullQueuePlaybackJob?.cancel()
        fullQueuePlaybackJob = null
    }

    private fun throwIfFullQueuePlaybackRequestIsStale(requestToken: Long) {
        if (requestToken != fullQueuePlaybackToken) {
            throw CancellationException("Stale full-queue playback request")
        }
    }

    private fun beginDirectPlaybackRequest(): Long {
        directPlaybackToken += 1L
        directPlaybackJob?.cancel()
        directPlaybackJob = null
        pendingQueueSegmentsJob?.cancel()
        pendingQueueSegmentsJob = null
        return directPlaybackToken
    }

    private fun cancelPendingDirectPlayback() {
        cancelPendingDirectPlaybackBuild()
        pendingQueueSegmentsJob?.cancel()
        pendingQueueSegmentsJob = null
    }

    private fun cancelPendingDirectPlaybackBuild() {
        directPlaybackToken += 1L
        directPlaybackJob?.cancel()
        directPlaybackJob = null
    }

    private fun throwIfDirectPlaybackRequestIsStale(requestToken: Long) {
        if (requestToken != directPlaybackToken) {
            throw CancellationException("Stale direct playback request")
        }
    }

    private suspend fun resolvePlaybackQueueFromSortedIds(sortedIds: List<Long>): List<Song> {
        if (sortedIds.isEmpty()) return emptyList()

        val orderedIds = sortedIds.map(Long::toString)
        val cachedSongsById = libraryStateHolder.allSongsById.value
        val missingIds = ArrayList<String>()
        val cachedQueue = ArrayList<Song>(orderedIds.size)

        withContext(Dispatchers.Default) {
            orderedIds.forEach { songId ->
                val cachedSong = cachedSongsById[songId]
                if (cachedSong != null) {
                    cachedQueue.add(cachedSong)
                } else {
                    missingIds.add(songId)
                }
            }
        }

        if (missingIds.isEmpty()) {
            return cachedQueue
        }

        val missingSongsById = getSongsByIdsChunked(missingIds).associateBy { it.id }
        return withContext(Dispatchers.Default) {
            val finalQueue = ArrayList<Song>(orderedIds.size)
            orderedIds.forEach { songId ->
                val resolvedSong = cachedSongsById[songId] ?: missingSongsById[songId]
                if (resolvedSong != null) {
                    finalQueue.add(resolvedSong)
                }
            }
            finalQueue
        }
    }

    private suspend fun getSongsByIdsChunked(songIds: List<String>): List<Song> {
        if (songIds.isEmpty()) return emptyList()
        if (songIds.size <= SONG_ID_QUERY_CHUNK_SIZE) {
            return musicRepository.getSongsByIds(songIds).first()
        }

        return withContext(Dispatchers.IO) {
            buildList(songIds.size) {
                songIds.chunked(SONG_ID_QUERY_CHUNK_SIZE).forEach { chunk ->
                    addAll(musicRepository.getSongsByIds(chunk).first())
                }
            }
        }
    }

    // Connectivity state delegated to ConnectivityStateHolder
    val isWifiEnabled: StateFlow<Boolean> = connectivityStateHolder.isWifiEnabled
    val isWifiRadioOn: StateFlow<Boolean> = connectivityStateHolder.isWifiRadioOn
    val wifiName: StateFlow<String?> = connectivityStateHolder.wifiName
    val isBluetoothEnabled: StateFlow<Boolean> = connectivityStateHolder.isBluetoothEnabled
    val bluetoothName: StateFlow<String?> = connectivityStateHolder.bluetoothName
    val bluetoothAudioDeviceStates: StateFlow<List<BluetoothAudioDeviceState>> = connectivityStateHolder.bluetoothAudioDeviceStates
    val bluetoothAudioDevices: StateFlow<List<String>> = connectivityStateHolder.bluetoothAudioDevices



    // Connectivity is now managed by ConnectivityStateHolder

    private val _trackVolume = MutableStateFlow(1.0f)


    @Inject
    lateinit var mediaMapper: com.andymitch.dzr.data.media.MediaMapper

    @Inject
    lateinit var imageCacheManager: com.andymitch.dzr.data.media.ImageCacheManager

    init {
        // Initialize helper classes with our coroutine scope
        listeningStatsTracker.initialize(viewModelScope)
        dailyMixStateHolder.initialize(viewModelScope)
        lyricsStateHolder.initialize(viewModelScope, lyricsLoadCallback, playbackStateHolder.stablePlayerState)
        playbackStateHolder.initialize(coroutineScope = viewModelScope)
        themeStateHolder.initialize(viewModelScope)

        // On cold start, the MediaController connects asynchronously, leaving stablePlayerState.currentSong
        // null until that happens. Pre-load the palette from the persisted snapshot so the mini player
        // has the correct colors immediately on first render, before the controller is ready.
        viewModelScope.launch {
            val snapshot = runCatching {
                userPreferencesRepository.getPlaybackQueueSnapshotOnce()
            }.getOrNull() ?: return@launch

            val currentItem = if (snapshot.currentMediaId != null) {
                snapshot.items.find { it.mediaId == snapshot.currentMediaId }
            } else {
                snapshot.items.getOrNull(snapshot.currentIndex)
            } ?: return@launch

            val artworkUri = currentItem.artworkUri?.takeIf { it.isNotBlank() } ?: return@launch

            themeStateHolder.extractAndGenerateColorScheme(
                albumArtUriAsUri = artworkUri.toUri(),
                currentSongUriString = artworkUri,
                isPreload = false
            )
        }

        stablePlayerState
            .map { it.currentSong?.albumArtUriString?.takeIf { uri -> uri.isNotBlank() } }
            .distinctUntilChanged()
            .onEach { artworkUri ->
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = artworkUri?.toUri(),
                    currentSongUriString = artworkUri,
                    isPreload = false
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            lyricsStateHolder.songUpdates.collect { update: Pair<com.andymitch.dzr.data.model.Song, com.andymitch.dzr.data.model.Lyrics?> ->
                val song = update.first
                val lyrics = update.second
                // Check if this update is relevant to the currently playing song OR the selected song
                if (playbackStateHolder.stablePlayerState.value.currentSong?.id == song.id) {
                    // MERGE FIX: if song comes back empty (e.g. from reset), preserve current metadata
                    val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                    val safeSong = if (song.title.isEmpty() && currentSong != null) {
                        currentSong.copy(lyrics = "")
                    } else {
                        song
                    }
                    updateSongInStates(safeSong, lyrics)
                }
                if (_selectedSongForInfo.value?.id == song.id) {
                    val currentSelected = _selectedSongForInfo.value
                    if (song.title.isEmpty() && currentSelected != null) {
                        _selectedSongForInfo.value = currentSelected.copy(lyrics = "")
                    } else {
                        _selectedSongForInfo.value = song
                    }
                }
            }
        }

        lyricsStateHolder.messageEvents
            .onEach { msg: String -> _toastEvents.emit(msg) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .flatMapLatest { songId ->
                    if (songId.isNullOrBlank()) flowOf(null)
                    else musicRepository.getSong(songId)
                }
                .collect { repositorySong ->
                    val currentState = playbackStateHolder.stablePlayerState.value
                    val currentSong = currentState.currentSong ?: return@collect
                    if (repositorySong == null || repositorySong.id != currentSong.id) {
                        return@collect
                    }

                    val hydratedSong = currentSong.withRepositoryHydration(repositorySong)
                    val persistedLyrics = parsePersistedLyrics(hydratedSong.lyrics)
                    val shouldApplyPersistedLyrics = currentState.lyrics == null && persistedLyrics != null
                    val shouldRefreshSong = hydratedSong != currentSong
                    val shouldReloadLyrics =
                        !shouldApplyPersistedLyrics &&
                            currentState.lyrics == null &&
                            hydratedSong.improvesLyricsLookupComparedTo(currentSong)

                    if (shouldApplyPersistedLyrics || shouldReloadLyrics) {
                        lyricsStateHolder.cancelLoading()
                    }

                    if (shouldRefreshSong || shouldApplyPersistedLyrics) {
                        updateSongInStates(
                            updatedSong = hydratedSong,
                            newLyrics = if (shouldApplyPersistedLyrics) persistedLyrics else null,
                            isLoadingLyrics = if (shouldApplyPersistedLyrics) false else null
                        )

                        if (_selectedSongForInfo.value?.id == hydratedSong.id) {
                            _selectedSongForInfo.value = hydratedSong
                        }
                    }

                    if (shouldReloadLyrics) {
                        lyricsStateHolder.loadLyricsForSong(hydratedSong, lyricsSourcePreference.value)
                    }
                }
        }

        viewModelScope.launch {
            stablePlayerState
                .map { it.currentSong }
                .distinctUntilChanged()
                .collectLatest { currentSong ->
                    if (currentSong == null) return@collectLatest
                    val songId = currentSong.id
                    val isDeezer = songId.startsWith("deezer_") || currentSong.contentUriString.startsWith("deezer://track/")
                    if (!isDeezer) return@collectLatest
                    
                    val rawId = if (songId.startsWith("deezer_")) songId.removePrefix("deezer_") else currentSong.contentUriString.removePrefix("deezer://track/")
                    try {
                        val trackInfo = deezerRepository.getTrackInfo(rawId)
                        if (trackInfo != null) {
                            val artistsStr = trackInfo.contributors.mapNotNull { it.name }.joinToString(", ")
                            val coverXl = trackInfo.album?.coverXl ?: trackInfo.md5Image?.let { "https://e-cdns-images.dzcdn.net/images/cover/$it/1000x1000-000000-80-0-0.jpg" }
                            
                            if (artistsStr.isNotBlank() || coverXl != null) {
                                val stateSong = playbackStateHolder.stablePlayerState.value.currentSong
                                if (stateSong?.id == songId) {
                                    var updatedSong = stateSong.copy(
                                        artist = if (artistsStr.isNotBlank()) artistsStr else stateSong.artist,
                                        albumArtUriString = coverXl ?: stateSong.albumArtUriString
                                    )
                                    val songIdLong = songId.removePrefix("deezer_").toLongOrNull()
                                    if (songIdLong != null) {
                                        // Update DB so that PlayerViewModel.repository observation picks it up
                                        musicRepository.updateSongMetadata(
                                            songId = songIdLong,
                                            title = updatedSong.title,
                                            artist = updatedSong.artist,
                                            artistId = updatedSong.artistId,
                                            artistsJson = null,
                                            album = updatedSong.album,
                                            genre = updatedSong.genre,
                                            trackNumber = updatedSong.trackNumber,
                                            discNumber = updatedSong.discNumber
                                        )
                                        if (coverXl != null) {
                                            musicRepository.updateSongAlbumArt(songIdLong, coverXl)
                                        }
                                        trackInfo.album?.id?.let { deezerAlbumId ->
                                            if (deezerAlbumId != 0L) {
                                                musicRepository.updateSongAlbumId(songIdLong, deezerAlbumId)
                                                updatedSong = updatedSong.copy(albumId = deezerAlbumId)
                                            }
                                        }
                                        playbackStateHolder.updateStablePlayerState { state ->
                                            if (state.currentSong?.id == songId) {
                                                state.copy(currentSong = updatedSong)
                                            } else {
                                                state
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
        }
    }


    fun sendToast(message: String) {
        viewModelScope.launch {
            _toastEvents.emit(message)
        }
    }

    fun onSearchNavIconDoubleTapped() {
        _searchNavDoubleTapEvents.tryEmit(Unit)
    }


    // Last Library Tab Index
    val lastLibraryTabIndexFlow: StateFlow<Int> =
        userPreferencesRepository.lastLibraryTabIndexFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0 // Default to Songs tab
        )

    val libraryTabsFlow: StateFlow<List<String>> = userPreferencesRepository.libraryTabsOrderFlow
        .map { orderJson ->
            val defaultOrder = listOf("PLAYLISTS", "LIKED", "ALBUMS", "ARTIST", "PODCASTS", "RECENT")
            val order = if (orderJson != null) {
                try {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(orderJson).toMutableList()
                } catch (e: Exception) {
                    defaultOrder.toMutableList()
                }
            } else {
                defaultOrder.toMutableList()
            }
            order.remove("SONGS")
            order.remove("FOLDERS")
            if (!order.contains("RECENT")) {
                order.add("RECENT")
            }
            if (!order.contains("PODCASTS")) {
                order.add(order.indexOf("RECENT"), "PODCASTS")
            }
            order.toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("PLAYLISTS", "LIKED", "ALBUMS", "ARTIST", "PODCASTS", "RECENT"))

    private val _loadedTabs = MutableStateFlow(emptySet<String>())

    private val _currentLibraryTabId = MutableStateFlow(LibraryTabId.PLAYLISTS)
    val currentLibraryTabId: StateFlow<LibraryTabId> = _currentLibraryTabId.asStateFlow()

    private val _isSortingSheetVisible = MutableStateFlow(false)
    val isSortingSheetVisible: StateFlow<Boolean> = _isSortingSheetVisible.asStateFlow()

    val availableSortOptions: StateFlow<List<SortOption>> =
        currentLibraryTabId.map { tabId ->
            Trace.beginSection("PlayerViewModel.availableSortOptionsMapping")
            try {
                when (tabId) {
                    LibraryTabId.RECENT -> SortOption.PODCASTS
                    LibraryTabId.PODCASTS -> emptyList()
                    LibraryTabId.ALBUMS -> SortOption.ALBUMS
                    LibraryTabId.ARTISTS -> SortOption.ARTISTS
                    LibraryTabId.PLAYLISTS -> SortOption.PLAYLISTS
                    LibraryTabId.LIKED -> SortOption.LIKED
                }
            } finally {
                Trace.endSection()
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SortOption.PLAYLISTS
        )

    val isSyncingStateFlow: StateFlow<Boolean> = playlistPreferencesRepository.isSyncingLibraryFlow.asStateFlow()

    private val _isInitialDataLoaded = MutableStateFlow(false)


    // Genres StateFlow - delegated to LibraryStateHolder
    val genres: StateFlow<ImmutableList<Genre>> = libraryStateHolder.genres
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = persistentListOf()
        )




    val hasCloudSongsFlow: StateFlow<Boolean?> = musicRepository.getCloudSongCountFlow()
        .map<Int, Boolean?> { it > 0 }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val albumsFlow: StateFlow<ImmutableList<Album>> = libraryStateHolder.albums

    var searchQuery by mutableStateOf("")
        private set

    fun refreshRecentlySearched() = searchStateHolder.fetchRecentlySearched()

    suspend fun refreshDeezerLibrary() {
        playlistPreferencesRepository.syncLovedTracks()
        playlistPreferencesRepository.syncUserPlaylists()
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
        searchStateHolder.updateSearchQuery(query)
    }

    private var mediaController: MediaController? = null
    private var mediaControllerPlaybackListener: Player.Listener? = null
    private val _isMediaControllerReady = MutableStateFlow(false)
    val isMediaControllerReady: StateFlow<Boolean> = _isMediaControllerReady.asStateFlow()
    // SessionToken injected via constructor
    private val mediaControllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (command.customAction == MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE) {
                val enabled = args.getBoolean(
                    MusicNotificationProvider.EXTRA_SHUFFLE_ENABLED,
                    false
                )
                viewModelScope.launch {
                    if (enabled != playbackStateHolder.stablePlayerState.value.isShuffleEnabled) {
                        toggleShuffle()
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }
    private val mediaControllerFuture: ListenableFuture<MediaController> =
        mediaControllerFactory.create(context, sessionToken, mediaControllerListener)
    private var pendingRepeatMode: Int? = null

    private var pendingPlaybackAction: (() -> Unit)? = null
    private var metadataProbeJob: Job? = null
    private var metadataProbeMediaId: String? = null

    private val _playbackAudioMetadata = MutableStateFlow(PlaybackAudioMetadata())
    val playbackAudioMetadata: StateFlow<PlaybackAudioMetadata> = _playbackAudioMetadata.asStateFlow()

    val favoriteSongIds: StateFlow<Set<String>> = musicRepository
        .getFavoriteSongIdsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isCurrentSongFavorite: StateFlow<Boolean> = combine(
        stablePlayerState
            .map { it.currentSong }
            .distinctUntilChanged { old, new ->
                old?.id == new?.id &&
                    old?.contentUriString == new?.contentUriString &&
                    old?.path == new?.path
            }
            .flatMapLatest { song ->
                kotlinx.coroutines.flow.flow {
                    emit(resolveFavoriteSongId(song))
                }
            },
        favoriteSongIds
    ) { favoriteSongId, ids ->
        favoriteSongId?.let { ids.contains(it) } ?: false
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ---------------------------------------------------------------------------
    // FullPlayerSlice — consolidates 11 independent flows into ONE subscription.
    // Previously FullPlayerContent had ~13 separate collectAsStateWithLifecycle()
    // calls. Each emission from any of them caused a recompose of the entire 2k-line
    // composable. Now a single collect + distinctUntilChanged batches all settings.
    // ---------------------------------------------------------------------------
    data class FullPlayerSlice(
        val currentSongArtists: List<Artist> = emptyList(),
        val lyricsSyncOffset: Int = 0,
        val albumArtQuality: AlbumArtQuality = AlbumArtQuality.MEDIUM,
        val audioMetadata: PlaybackAudioMetadata = PlaybackAudioMetadata(),
        val showPlayerFileInfo: Boolean = true,
        val immersiveLyricsEnabled: Boolean = false,
        val immersiveLyricsTimeout: Long = 4000L,
        val isImmersiveTemporarilyDisabled: Boolean = false,
        val isBluetoothEnabled: Boolean = false,
        val bluetoothName: String? = null
    )

    // Intermediate combine #1: 5 settings flows
    private val fullPlayerSlicePart1 = combine(
        currentSongArtists,
        currentSongLyricsSyncOffset,
        albumArtQuality,
        playbackAudioMetadata,
        showPlayerFileInfo
    ) { artists: List<Artist>, syncOffset: Int, artQuality: AlbumArtQuality,
        audioMeta: PlaybackAudioMetadata, showFileInfo: Boolean ->
        FullPlayerSlicePart1(artists, syncOffset, artQuality, audioMeta, showFileInfo)
    }

    private data class BluetoothSlice(val enabled: Boolean, val name: String?)

    private val bluetoothSlice = combine(isBluetoothEnabled, bluetoothName) { bt, btName ->
        BluetoothSlice(bt, btName)
    }

    // Intermediate combine #2: remaining flows (≤5 for Kotlin type inference)
    private val fullPlayerSlicePart2 = combine(
        immersiveLyricsEnabled,
        immersiveLyricsTimeout,
        isImmersiveTemporarilyDisabled,
        bluetoothSlice
    ) { immersive: Boolean, immersiveTimeout: Long, immersiveDisabled: Boolean, bt: BluetoothSlice ->
        FullPlayerSlicePart2(immersive, immersiveTimeout, immersiveDisabled, bt.enabled, bt.name)
    }

    private data class FullPlayerSlicePart1(
        val currentSongArtists: List<Artist>,
        val lyricsSyncOffset: Int,
        val albumArtQuality: AlbumArtQuality,
        val audioMetadata: PlaybackAudioMetadata,
        val showPlayerFileInfo: Boolean
    )

    private data class FullPlayerSlicePart2(
        val immersiveLyricsEnabled: Boolean,
        val immersiveLyricsTimeout: Long,
        val isImmersiveTemporarilyDisabled: Boolean,
        val isBluetoothEnabled: Boolean,
        val bluetoothName: String?
    )

    val fullPlayerSlice: StateFlow<FullPlayerSlice> = combine(
        fullPlayerSlicePart1,
        fullPlayerSlicePart2
    ) { p1, p2 ->
        FullPlayerSlice(
            currentSongArtists = p1.currentSongArtists,
            lyricsSyncOffset = p1.lyricsSyncOffset,
            albumArtQuality = p1.albumArtQuality,
            audioMetadata = p1.audioMetadata,
            showPlayerFileInfo = p1.showPlayerFileInfo,
            immersiveLyricsEnabled = p2.immersiveLyricsEnabled,
            immersiveLyricsTimeout = p2.immersiveLyricsTimeout,
            isImmersiveTemporarilyDisabled = p2.isImmersiveTemporarilyDisabled,
            isBluetoothEnabled = p2.isBluetoothEnabled,
            bluetoothName = p2.bluetoothName
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FullPlayerSlice())

    // ---------------------------------------------------------------------------
    // PlayerConfigSlice — consolidates 7 infrequently-changing preference flows
    // into ONE subscription. Previously the player sheet had 7 separate
    // collectAsStateWithLifecycle() calls for config values, each causing a full
    // sheet recomposition when any preference changed.
    // ---------------------------------------------------------------------------
    data class PlayerConfigSlice(
        val navBarCornerRadius: Int = 32,
        val navBarStyle: String = NavBarStyle.DEFAULT,
        val carouselStyle: String = CarouselStyle.NO_PEEK,
        val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks = FullPlayerLoadingTweaks(),
        val tapBackgroundClosesPlayer: Boolean = false,
        val useSmoothCorners: Boolean = true,
        val playerThemePreference: String = ThemePreference.ALBUM_ART
    )

    private val playerConfigSlicePart1 = combine(
        navBarCornerRadius,
        navBarStyle,
        carouselStyle,
        fullPlayerLoadingTweaks,
        tapBackgroundClosesPlayer
    ) { radius, style, carousel, tweaks, tapClose ->
        PlayerConfigSlicePart1(radius, style, carousel, tweaks, tapClose)
    }

    private data class PlayerConfigSlicePart1(
        val navBarCornerRadius: Int,
        val navBarStyle: String,
        val carouselStyle: String,
        val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks,
        val tapBackgroundClosesPlayer: Boolean
    )

    val playerConfigSlice: StateFlow<PlayerConfigSlice> = combine(
        playerConfigSlicePart1,
        useSmoothCorners,
        playerThemePreference
    ) { p1, smoothCorners, themePref ->
        PlayerConfigSlice(
            navBarCornerRadius = p1.navBarCornerRadius,
            navBarStyle = p1.navBarStyle,
            carouselStyle = p1.carouselStyle,
            fullPlayerLoadingTweaks = p1.fullPlayerLoadingTweaks,
            tapBackgroundClosesPlayer = p1.tapBackgroundClosesPlayer,
            useSmoothCorners = smoothCorners,
            playerThemePreference = themePref
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerConfigSlice())

    // Library State - delegated to LibraryStateHolder
    // Favorites now use paginated flow from LibraryStateHolder (DB-level sort & filter)
    val favoritesPagingFlow = libraryStateHolder.favoritesPagingFlow

    // Daily mix state is now managed by DailyMixStateHolder
    val dailyMixSongs: StateFlow<ImmutableList<Song>> = dailyMixStateHolder.dailyMixSongs
    val yourMixSongs: StateFlow<ImmutableList<Song>> = dailyMixStateHolder.yourMixSongs

    fun removeFromDailyMix(songId: String) {
        dailyMixStateHolder.removeFromDailyMix(songId)
    }

    /**
     * Observes a song by ID from Room DB, combined with the latest favorite status.
     * Uses direct Room query instead of scanning the full in-memory list.
     */
    fun observeSong(songId: String?): Flow<Song?> {
        if (songId == null) return flowOf(null)
        return combine(
            musicRepository.getSong(songId),
            favoriteSongIds
        ) { song, favorites ->
            song?.copy(isFavorite = favorites.contains(songId))
        }.distinctUntilChanged()
    }



    private fun updateDailyMix() {
        // Delegate to DailyMixStateHolder
        dailyMixStateHolder.updateDailyMix(
            favoriteSongIdsFlow = favoriteSongIds
        )
    }

    fun shuffleAllSongs(queueName: String = "All Songs (Shuffled)") {
        Timber.tag("ShuffleDebug").d("shuffleAllSongs called.")
        
        // Load random songs from DB instead of materializing the entire library
        viewModelScope.launch {
            val randomSongs = musicRepository.getRandomSongs(limit = 500)
            if (randomSongs.isNotEmpty()) {
                playSongsShuffled(randomSongs, queueName, startAtZero = true)
            }
        }
    }

    /**
     * Called from Quick Settings tile. Unlike shuffleAllSongs(), this always starts
     * fresh playback regardless of current state, and correctly handles the case
     * where the MediaController isn't ready yet (cold start from tile).
     *
     * Queries a bounded random sample directly from the repository so the tile does
     * not depend on the eager in-memory song cache being populated first.
     */
    fun triggerShuffleAllFromTile() {
        Timber.d("[TileDebug] triggerShuffleAllFromTile called. mediaController=${mediaController != null}")
        val action: () -> Unit = {
            Timber.d("[TileDebug] action() invoked")
            viewModelScope.launch {
                var songs = musicRepository.getRandomSongs(limit = 500)
                Timber.d("[TileDebug] Repository returned ${songs.size} random songs immediately")

                if (songs.isEmpty()) {
                    // Cold start or stale DB state: trigger a sync and retry the bounded query.
                    Timber.d("[TileDebug] No songs available yet")
                    songs = withTimeoutOrNull(30_000L) {
                        var refreshedSongs = emptyList<Song>()
                        while (refreshedSongs.isEmpty()) {
                            refreshedSongs = musicRepository.getRandomSongs(limit = 500)
                            if (refreshedSongs.isEmpty()) {
                                delay(500L)
                            }
                        }
                        refreshedSongs
                    }
                        ?: emptyList()
                    Timber.d("[TileDebug] After retry, repository returned ${songs.size} songs")
                }

                if (songs.isNotEmpty()) {
                    Timber.d("[TileDebug] Calling playSongsShuffled with ${songs.size} songs")
                    playSongsShuffled(songs, "All Songs (Shuffled)", startAtZero = true)
                } else {
                    Timber.w("[TileDebug] No songs found even after sync - library may be empty")
                    sendToast(context.getString(R.string.player_no_songs_in_library_toast))
                }
            }
        }

        if (mediaController == null) {
            Timber.d("[TileDebug] mediaController null, queuing as pendingPlaybackAction")
            pendingPlaybackAction = action
        } else {
            Timber.d("[TileDebug] mediaController ready, calling action immediately")
            action()
        }
    }


    fun shuffleFavoriteSongs() {
        Timber.tag("ShuffleDebug").d("shuffleFavoriteSongs called.")

        // Load favorite songs from DB on-demand instead of holding them in memory
        viewModelScope.launch {
            val favSongs = musicRepository.getFavoriteSongsOnce(playerUiState.value.currentStorageFilter)
            if (favSongs.isNotEmpty()) {
                playSongsShuffled(favSongs, "Liked Songs (Shuffled)", startAtZero = true)
            }
        }
    }

    fun playFavoriteSongs() {
        Timber.tag("PlayDebug").d("playFavoriteSongs called.")

        viewModelScope.launch {
            val favSongs = musicRepository.getFavoriteSongsOnce(
                playerUiState.value.currentStorageFilter,
                playerUiState.value.currentFavoriteSortOption
            )
            if (favSongs.isNotEmpty()) {
                playSongs(favSongs, favSongs.first(), "Liked Songs")
            }
        }
    }

    fun shuffleRandomAlbum() {
        viewModelScope.launch {
            val allAlbums = musicRepository.getAlbums().first()
            if (allAlbums.isNotEmpty()) {
                val randomAlbum = allAlbums.random()
                val albumSongs = musicRepository.getSongsForAlbum(randomAlbum.id).first()
                if (albumSongs.isNotEmpty()) {
                    playSongsShuffled(albumSongs, randomAlbum.title, startAtZero = true)
                }
            }
        }
    }

    fun shuffleRandomArtist() {
        viewModelScope.launch {
            val allArtists = musicRepository.getArtists().first()
            if (allArtists.isNotEmpty()) {
                val randomArtist = allArtists.random()
                val artistSongs = musicRepository.getSongsForArtist(randomArtist.id).first()
                if (artistSongs.isNotEmpty()) {
                    playSongsShuffled(artistSongs, randomArtist.name, startAtZero = true)
                }
            }
        }
    }

    fun shuffleRandomPlaylist() {
        viewModelScope.launch {
            val allPlaylists = playlistPreferencesRepository.userPlaylistsFlow.first()
            if (allPlaylists.isNotEmpty()) {
                val randomPlaylist = allPlaylists.random()
                val playlistSongs = musicRepository.getSongsByIds(randomPlaylist.songIds).first()
                if (playlistSongs.isNotEmpty()) {
                    playSongsShuffled(playlistSongs, randomPlaylist.name, startAtZero = true)
                }
            }
        }
    }


    private fun loadPersistedDailyMix() {
        // Delegate to DailyMixStateHolder
        dailyMixStateHolder.loadPersistedDailyMix()
    }


    private var transitionSchedulerJob: Job? = null

    private fun incrementSongScore(song: Song) {
        listeningStatsTracker.onVoluntarySelection(song.id)
    }

    // MIN_SESSION_LISTEN_MS, currentSession, and ListeningStatsTracker class
    // have been moved to ListeningStatsTracker.kt for better modularity


    fun updatePredictiveBackCollapseFraction(fraction: Float) {
        _predictiveBackCollapseFraction.value = fraction.coerceIn(0f, 1f)
    }

    fun updatePredictiveBackSwipeEdge(edge: Int?) {
        _predictiveBackSwipeEdge.value = edge
    }

    fun resetPredictiveBackState() {
        _predictiveBackCollapseFraction.value = 0f
        _predictiveBackSwipeEdge.value = null
    }

    fun updateQueueSheetVisibility(visible: Boolean) {
        _isQueueSheetVisible.value = visible
    }

    // Helper to resolve stored sort keys against the allowed group
    private fun resolveSortOption(
        optionKey: String?,
        allowed: Collection<SortOption>,
        fallback: SortOption
    ): SortOption {
        return SortOption.fromStorageKey(optionKey, allowed, fallback)
    }

    private data class FolderSourceState(
        val source: FolderSource,
        val rootPath: String,
        val isSdCardAvailable: Boolean
    )


    // Connectivity refresh delegated to ConnectivityStateHolder
    fun refreshLocalConnectionInfo(refreshBluetoothDevices: Boolean = false) {
        connectivityStateHolder.refreshLocalConnectionInfo(refreshBluetoothDevices)
    }

    init {
        // Must pair with the Trace.endSection() at the bottom of this init block — an
        // unmatched endSection pops whatever section the caller had open on this thread.
        Trace.beginSection("PlayerViewModel.init")
        Timber.tag("PlayerViewModel").i("init started.")


        viewModelScope.launch {
            userPreferencesRepository.migrateTabOrder()
        }

        viewModelScope.launch {
            val savedQueueName = userPreferencesRepository.getPlaybackQueueNameOnce()
            _playerUiState.update { if (it.currentQueueSourceName.isBlank()) it.copy(currentQueueSourceName = savedQueueName) else it }
            _playerUiState.map { it.currentQueueSourceName }.distinctUntilChanged().drop(1).collect {
                userPreferencesRepository.setPlaybackQueueName(it)
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.ensureLibrarySortDefaults()
        }

        viewModelScope.launch {
            val legacyFavoriteIds = userPreferencesRepository.favoriteSongIdsFlow.first()
            if (legacyFavoriteIds.isNotEmpty()) {
                val roomFavoriteIds = musicRepository.getFavoriteSongIdsOnce()
                if (roomFavoriteIds.isEmpty()) {
                    legacyFavoriteIds.forEach { songId ->
                        musicRepository.setFavoriteStatus(songId, true)
                    }
                }
                userPreferencesRepository.clearFavoriteSongIds()
            }
        }

        viewModelScope.launch {
            combine(libraryTabsFlow, lastLibraryTabIndexFlow) { tabs, index ->
                tabs.getOrNull(index)?.toLibraryTabIdOrNull() ?: LibraryTabId.PLAYLISTS
            }.collect { tabId ->
                _currentLibraryTabId.value = tabId
            }
        }

        // Load initial sort options ONCE at startup.
        viewModelScope.launch {
            val initialSongSort = resolveSortOption(
                userPreferencesRepository.songsSortOptionFlow.first(),
                SortOption.SONGS,
                SortOption.SongTitleAZ
            )
            val initialAlbumSort = resolveSortOption(
                userPreferencesRepository.albumsSortOptionFlow.first(),
                SortOption.ALBUMS,
                SortOption.AlbumTitleAZ
            )
            val initialArtistSort = resolveSortOption(
                userPreferencesRepository.artistsSortOptionFlow.first(),
                SortOption.ARTISTS,
                SortOption.ArtistNameAZ
            )
            val initialFolderSort = resolveSortOption(
                userPreferencesRepository.foldersSortOptionFlow.first(),
                SortOption.FOLDERS,
                SortOption.FolderNameAZ
            )
            val initialLikedSort = resolveSortOption(
                userPreferencesRepository.likedSongsSortOptionFlow.first(),
                SortOption.LIKED,
                SortOption.LikedSongDateLiked
            )

            _playerUiState.update {
                it.copy(
                    currentSongSortOption = initialSongSort,
                    currentAlbumSortOption = initialAlbumSort,
                    currentArtistSortOption = initialArtistSort,
                    currentFolderSortOption = initialFolderSort,
                    currentFavoriteSortOption = initialLikedSort
                )
            }
            // Also update the dedicated flow for favorites to ensure consistency
            // _currentFavoriteSortOptionStateFlow.value = initialLikedSort // Delegated to LibraryStateHolder

            sortSongs(initialSongSort, persist = false)
            sortAlbums(initialAlbumSort, persist = false)
            sortArtists(initialArtistSort, persist = false)
            sortFolders(initialFolderSort, persist = false)
            sortFavoriteSongs(initialLikedSort, persist = false)
        }

        viewModelScope.launch {
            val isPersistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
            if (isPersistent) {
                // If persistent shuffle is on, read the last used shuffle state (On/Off)
                val savedShuffle = userPreferencesRepository.isShuffleOnFlow.first()
                // Update the UI state so the shuffle button reflects the saved setting immediately
                playbackStateHolder.updateStablePlayerState { it.copy(isShuffleEnabled = savedShuffle) }
            }
        }

        // launchColorSchemeProcessor() - Handled by ThemeStateHolder and on-demand calls

        loadPersistedDailyMix()
        loadSearchHistory()

        viewModelScope.launch {
            isSyncingStateFlow.collect { isSyncing ->
                val oldSyncingLibraryState = _playerUiState.value.isSyncingLibrary
                _playerUiState.update { it.copy(isSyncingLibrary = isSyncing) }

                if (oldSyncingLibraryState && !isSyncing) {
                    Timber.tag("PlayerViewModel").i("Sync completed. Calling resetAndLoadInitialData from isSyncingStateFlow observer.")
                    resetAndLoadInitialData("isSyncingStateFlow observer")
                }
            }
        }

        viewModelScope.launch {
            if (!isSyncingStateFlow.value && !_isInitialDataLoaded.value && libraryStateHolder.allSongs.value.isEmpty()) {
                Timber.tag("PlayerViewModel").i("Initial check: Sync not active and initial data not loaded. Calling resetAndLoadInitialData.")
                resetAndLoadInitialData("Initial Check")
            }
        }

        mediaControllerFuture.addListener({
            try {
                mediaController = mediaControllerFuture.get()
                // Pass controller to PlaybackStateHolder
                playbackStateHolder.setMediaController(mediaController)
                _isMediaControllerReady.value = true


                setupMediaControllerListeners()
                flushPendingRepeatMode()
                syncShuffleStateWithSession(playbackStateHolder.stablePlayerState.value.isShuffleEnabled)
                // Execute any pending action that was queued while the controller was connecting
                pendingPlaybackAction?.invoke()
                pendingPlaybackAction = null
            } catch (e: Exception) {
                _playerUiState.update { it.copy(isLoadingInitialSongs = false, isLoadingLibraryCategories = false) }
                Timber.tag("PlayerViewModel").e(e, "Error setting up MediaController")
            }
        }, ContextCompat.getMainExecutor(context))


        // Initialize connectivity monitoring (WiFi/Bluetooth)
        connectivityStateHolder.initialize()

        // Initialize sleep timer state holder. The song-id flow is created once and shared:
        // building a new stateIn(...) inside the provider would spin up a permanently-hot
        // flow on every end-of-track timer activation, none of which ever get cancelled.
        val currentSongIdFlow = stablePlayerState.map { it.currentSong?.id }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        sleepTimerStateHolder.initialize(
            scope = viewModelScope,
            toastEmitter = { msg -> _toastEvents.emit(msg) },
            mediaControllerProvider = { mediaController },
            currentSongIdProvider = { currentSongIdFlow },
            songTitleResolver = { songId -> libraryStateHolder.allSongsById.value[songId]?.title ?: "Unknown" }
        )

        // Initialize SearchStateHolder
        searchStateHolder.initialize(viewModelScope)

        // Collect SearchStateHolder flows
        viewModelScope.launch {
            combine(
                searchStateHolder.searchResults,
                searchStateHolder.selectedSearchFilter,
                searchStateHolder.searchHistory,
                searchStateHolder.bestSearchResults,
                searchStateHolder.recentlySearched
            ) { results, filter, history, bestResults, recentlySearched ->
                object {
                    val results = results
                    val filter = filter
                    val history = history
                    val bestResults = bestResults
                    val recentlySearched = recentlySearched
                }
            }.combine(searchStateHolder.isSearching) { state1, isSearching ->
                _playerUiState.update {
                    it.copy(
                        searchResults = state1.results,
                        selectedSearchFilter = state1.filter,
                        searchHistory = state1.history,
                        bestSearchResults = state1.bestResults,
                        recentlySearched = state1.recentlySearched,
                        isSearching = isSearching
                    )
                }
            }.collect { }
        }

        // Initialize LibraryStateHolder
        libraryStateHolder.initialize(viewModelScope)

        // Sync library folders and loading states
        viewModelScope.launch {
            combine(
                libraryStateHolder.musicFolders,
                libraryStateHolder.isLoadingLibrary,
                libraryStateHolder.isLoadingCategories,
            ) { folders, loadingLibrary, loadingCategories ->
                Triple(folders, loadingLibrary, loadingCategories)
            }.collect { (folders, loadingLibrary, loadingCategories) ->
                _playerUiState.update {
                    it.copy(
                        musicFolders = folders,
                        isLoadingInitialSongs = loadingLibrary,
                        isLoadingLibraryCategories = loadingCategories,
                    )
                }
            }
        }

        // Sync sort options and storage filter
        viewModelScope.launch {
            combine(
                libraryStateHolder.currentSongSortOption,
                libraryStateHolder.currentAlbumSortOption,
                libraryStateHolder.currentArtistSortOption,
                libraryStateHolder.currentFolderSortOption,
                libraryStateHolder.currentFavoriteSortOption,
            ) { songSort, albumSort, artistSort, folderSort, favoriteSort ->
                SortOptionsSnapshot(songSort, albumSort, artistSort, folderSort, favoriteSort)
            }.collect { snapshot ->
                _playerUiState.update {
                    it.copy(
                        currentSongSortOption = snapshot.songSort,
                        currentAlbumSortOption = snapshot.albumSort,
                        currentArtistSortOption = snapshot.artistSort,
                        currentFolderSortOption = snapshot.folderSort,
                        currentFavoriteSortOption = snapshot.favoriteSort,
                    )
                }
            }
        }
        viewModelScope.launch {
            libraryStateHolder.currentStorageFilter.collect { filter ->
                _playerUiState.update { it.copy(currentStorageFilter = filter) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.hideLocalMediaFlow.collect { hide ->
                _playerUiState.update { it.copy(hideLocalMedia = hide) }
            }
        }


        viewModelScope.launch {
            // Repeat preference is only a startup restore value.
            // Keeping a live collector here creates a feedback path:
            // player -> DataStore -> collector -> player, which can cause
            // repeat mode oscillation if a transient player state is persisted.
            val savedRepeatMode = userPreferencesRepository.repeatModeFlow.first()
            applyPreferredRepeatMode(savedRepeatMode)
        }

        viewModelScope.launch {
            stablePlayerState
                .map { it.isShuffleEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    syncShuffleStateWithSession(enabled)
                }
        }

        // Auto-hide undo bar when a new song starts playing
        playlistDismissUndoStateHolder.observeUndoStateAgainstPlayback(
            scope = viewModelScope,
            currentSongIdFlow = stablePlayerState.map { it.currentSong?.id },
            getUiState = { _playerUiState.value },
            onHideDismissUndoBar = { hideDismissUndoBar() }
        )

        Trace.endSection() // End PlayerViewModel.init
    }

    fun onMainActivityStart() {
        Trace.beginSection("PlayerViewModel.onMainActivityStart")
        try {
            preloadThemesAndInitialData()
            checkAndUpdateDailyMixIfNeeded()
        } finally {
            Trace.endSection()
        }
    }


    private fun checkAndUpdateDailyMixIfNeeded() {
        // Delegate to DailyMixStateHolder
        dailyMixStateHolder.checkAndUpdateIfNeeded(
            favoriteSongIdsFlow = favoriteSongIds
        )
    }

    private fun preloadThemesAndInitialData() {
        Trace.beginSection("PlayerViewModel.preloadThemesAndInitialData")
        try {
            viewModelScope.launch {
                _isInitialThemePreloadComplete.value = false
                if (isSyncingStateFlow.value && !_isInitialDataLoaded.value) {
                    // Sync is active - defer to sync completion handler
                } else if (!_isInitialDataLoaded.value && libraryStateHolder.allSongs.value.isEmpty()) {
                    resetAndLoadInitialData("preloadThemesAndInitialData")
                }
                _isInitialThemePreloadComplete.value = true
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun loadInitialLibraryDataParallel() {
        libraryStateHolder.loadSongsFromRepository()
        libraryStateHolder.loadAlbumsFromRepository()
        libraryStateHolder.loadArtistsFromRepository()
        libraryStateHolder.loadFoldersFromRepository()
    }

    private fun resetAndLoadInitialData(caller: String = "Unknown") {
        Trace.beginSection("PlayerViewModel.resetAndLoadInitialData")
        try {
            Timber.tag("PlayerViewModel").d("resetAndLoadInitialData called by $caller")
            loadInitialLibraryDataParallel()
            updateDailyMix()
        } finally {
            Trace.endSection()
        }
    }

    fun loadSongsIfNeeded() = libraryStateHolder.loadSongsIfNeeded()
    fun loadAlbumsIfNeeded() = libraryStateHolder.loadAlbumsIfNeeded()
    fun loadArtistsIfNeeded() = libraryStateHolder.loadArtistsIfNeeded()
    fun loadFoldersFromRepository() = libraryStateHolder.loadFoldersFromRepository()

    fun setStorageFilter(filter: com.andymitch.dzr.data.model.StorageFilter) {
        libraryStateHolder.setStorageFilter(filter)
    }

    fun setPlaylistPickerStorageFilter(filter: com.andymitch.dzr.data.model.StorageFilter) {
        _playlistPickerStorageFilter.value = filter
    }

    fun setHideLocalMedia(hide: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setHideLocalMedia(hide)
        }
    }

    fun toggleStorageFilter() {
        val current = _playerUiState.value.currentStorageFilter
        val next = when (current) {
            com.andymitch.dzr.data.model.StorageFilter.ALL -> com.andymitch.dzr.data.model.StorageFilter.ONLINE
            com.andymitch.dzr.data.model.StorageFilter.ONLINE -> com.andymitch.dzr.data.model.StorageFilter.OFFLINE
            com.andymitch.dzr.data.model.StorageFilter.OFFLINE -> com.andymitch.dzr.data.model.StorageFilter.ALL
        }
        setStorageFilter(next)
    }

    fun showAndPlaySong(
        song: Song,
        contextSongs: List<Song>,
        queueName: String = "Current Context",
        isVoluntaryPlay: Boolean = true,
        cancelPendingQueueBuild: Boolean = true,
        playlistId: String? = null,
        indexInQueue: Int? = null
    ) {
        if (cancelPendingQueueBuild) {
            cancelPendingFullQueuePlayback()
        }
        val playbackContext =
            if (contextSongs.any { it.id == song.id }) contextSongs else listOf(song)
        val controller = mediaController
        val currentQueue = _playerUiState.value.currentPlaybackQueue
        val songIndexInQueue = indexInQueue ?: currentQueue.indexOfFirst { it.id == song.id }
        val queueMatchesContext = currentQueue.matchesSongOrder(playbackContext)
        val reusableTargetIndex = if (
            controller != null &&
            controller.isConnected &&
            !dualPlayerEngine.isTransitionRunning() &&
            songIndexInQueue != -1 &&
            queueMatchesContext
        ) {
            controller.resolveReusablePlaybackTargetIndex(
                songIndexInQueue = songIndexInQueue,
                songId = song.id,
                isExplicitQueueTarget = indexInQueue != null
            )
        } else {
            null
        }

        if (controller != null && reusableTargetIndex != null) {
            cancelPendingDirectPlaybackBuild()
            playLoadedControllerItem(controller, reusableTargetIndex)
            if (isVoluntaryPlay) {
                incrementSongScore(song)
                if (playlistId != null && queueName != "None") {
                    appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
                }
            }
        } else {
            if (isVoluntaryPlay) incrementSongScore(song)
            playSongs(playbackContext, song, queueName, playlistId)
        }
        resetPredictiveBackState()
    }

    fun showAndPlaySong(song: Song) {
        Timber.tag("ShuffleDebug").d("showAndPlaySong (single song overload) called for '${song.title}'")
        showAndPlaySong(song, listOf(song), "Library")
    }

    private fun List<Song>.matchesSongOrder(contextSongs: List<Song>): Boolean {
        if (size != contextSongs.size) return false
        return indices.all { this[it].id == contextSongs[it].id }
    }

    private fun MediaController.resolveReusablePlaybackTargetIndex(
        songIndexInQueue: Int,
        songId: String,
        isExplicitQueueTarget: Boolean = false
    ): Int? {
        if (!isExplicitQueueTarget) {
            currentMediaItem?.takeIf { it.mediaId == songId }?.let {
                return currentMediaItemIndex.takeIf { index -> index != C.INDEX_UNSET } ?: 0
            }
        }

        if (songIndexInQueue !in 0 until mediaItemCount) return null

        val mediaIdAtTarget = runCatching { getMediaItemAt(songIndexInQueue).mediaId }.getOrNull()
        return songIndexInQueue.takeIf { mediaIdAtTarget == songId }
    }

    private fun playLoadedControllerItem(controller: MediaController, targetIndex: Int) {
        val shouldSeekToStart =
            controller.currentMediaItemIndex != targetIndex ||
                controller.playbackState == Player.STATE_ENDED

        if (shouldSeekToStart) {
            controller.seekTo(targetIndex, 0L)
        }
        if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
            controller.prepare()
        }
        controller.play()
    }

    private fun Song.requiresHydration(): Boolean {
        return contentUriString.isBlank()
    }

    private suspend fun hydrateSongsIfNeeded(songs: List<Song>): List<Song> {
        if (songs.isEmpty() || songs.none { it.requiresHydration() }) return songs
        val hydratedSongs = getSongsByIdsChunked(songs.map { it.id })
        if (hydratedSongs.isEmpty()) return songs
        val hydratedById = hydratedSongs.associateBy { it.id }
        return songs.mapNotNull { original ->
            hydratedById[original.id] ?: original.takeIf { !original.requiresHydration() }
        }
    }

    fun playAlbum(album: Album) {
        Timber.tag("ShuffleDebug").d("playAlbum called for album: ${album.title}")
        viewModelScope.launch {
            try {
                val songsList: List<Song> = withContext(Dispatchers.IO) {
                    musicRepository.getSongsForAlbum(album.id).first()
                }

                if (songsList.isNotEmpty()) {
                    val sortedSongs = songsList.sortedWith(
                        compareBy<Song> { it.discNumber ?: 1 }
                            .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                            .thenBy { it.title.lowercase() }
                    )

                    playSongs(sortedSongs, sortedSongs.first(), album.title, null)
                    _isSheetVisible.value = true // Show player
                } else {
                    Timber.tag("PlayerViewModel").w("Album '${album.title}' has no playable songs.")
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error playing album ${album.title}")
            }
        }
    }

    fun playArtist(artist: Artist) {
        Timber.tag("ShuffleDebug").d("playArtist called for artist: ${artist.name}")
        viewModelScope.launch {
            try {
                val songsList: List<Song> = withContext(Dispatchers.IO) {
                    musicRepository.getSongsForArtist(artist.id).first()
                }

                if (songsList.isNotEmpty()) {
                    playSongs(songsList, songsList.first(), artist.name, null)
                    _isSheetVisible.value = true
                } else {
                    Timber.tag("PlayerViewModel").w("Artist '${artist.name}' has no playable songs.")
                    // you could emit a Toast event
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error playing artist ${artist.name}")
            }
        }
    }

    fun removeSongFromQueue(songId: String) {
        queueUndoStateHolder.removeSongFromQueue(
            scope = viewModelScope,
            mediaController = mediaController,
            songId = songId,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) }
        )
    }

    fun undoRemoveSongFromQueue() {
        queueUndoStateHolder.undoRemoveSongFromQueue(
            mediaController = mediaController,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) }
        )
    }

    fun hideQueueItemUndoBar() {
        queueUndoStateHolder.hideQueueItemUndoBar { mutation ->
            _playerUiState.update(mutation)
        }
    }

    fun reorderQueueItem(fromIndex: Int, toIndex: Int) {
        mediaController?.let { controller ->
            if (fromIndex >= 0 && fromIndex < controller.mediaItemCount &&
                toIndex >= 0 && toIndex < controller.mediaItemCount) {
                val currentIndexBeforeMove = controller.currentMediaItemIndex
                    .takeIf { it != C.INDEX_UNSET }
                    ?: playbackStateHolder.stablePlayerState.value.currentMediaItemIndex
                val updatedCurrentIndex = moveQueueIndex(currentIndexBeforeMove, fromIndex, toIndex)

                // Move the item in the MediaController's timeline.
                // This is the source of truth for playback.
                controller.moveMediaItem(fromIndex, toIndex)

                // Optimistically mirror the committed move in UI state. The drag preview stays
                // local while dragging, so this single state update does not add per-frame work.
                _playerUiState.update { state ->
                    val updatedQueue = state.currentPlaybackQueue.moveSong(fromIndex, toIndex)
                    if (updatedQueue === state.currentPlaybackQueue) {
                        state
                    } else {
                        state.copy(currentPlaybackQueue = updatedQueue)
                    }
                }

                playbackStateHolder.updateStablePlayerState { state ->
                    if (updatedCurrentIndex == C.INDEX_UNSET ||
                        state.currentMediaItemIndex == updatedCurrentIndex
                    ) {
                        state
                    } else {
                        state.copy(currentMediaItemIndex = updatedCurrentIndex)
                    }
                }
            }
        }
    }

    fun togglePlayerSheetState(resetPredictiveState: Boolean = true) {
        _sheetState.value = if (_sheetState.value == PlayerSheetState.COLLAPSED) {
            PlayerSheetState.EXPANDED
        } else {
            PlayerSheetState.COLLAPSED
        }
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun expandPlayerSheet(resetPredictiveState: Boolean = true) {
        _sheetState.value = PlayerSheetState.EXPANDED
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun collapsePlayerSheet(resetPredictiveState: Boolean = true) {
        _sheetState.value = PlayerSheetState.COLLAPSED
        if (resetPredictiveState) {
            resetPredictiveBackState()
        }
    }

    fun triggerAlbumNavigationFromPlayer(albumId: Long) {
        val existingJob = albumNavigationJob
        if (existingJob != null && existingJob.isActive) {

            return
        }

        albumNavigationJob?.cancel()
        albumNavigationJob = viewModelScope.launch {
            val currentSong = playbackStateHolder.stablePlayerState.value.currentSong

            var resolvedAlbumId = albumId
            // If albumId is 0 or -1, try to fetch the real album ID from track metadata
            if (resolvedAlbumId <= 0L && currentSong != null) {
                val isDeezer = currentSong.id.startsWith("deezer_") || currentSong.contentUriString.startsWith("deezer://track/")
                if (isDeezer) {
                    val rawId = if (currentSong.id.startsWith("deezer_")) currentSong.id.removePrefix("deezer_") else currentSong.contentUriString.removePrefix("deezer://track/")

                    try {
                        val trackInfo = deezerRepository.getTrackInfo(rawId)
                        val fetchedAlbumId = trackInfo?.album?.id ?: 0L

                        if (fetchedAlbumId != 0L) {
                            resolvedAlbumId = fetchedAlbumId
                            // Also update the song's albumId in DB and state for next time
                            val songIdLong = rawId.toLongOrNull()
                            if (songIdLong != null) {
                                musicRepository.updateSongAlbumId(songIdLong, fetchedAlbumId)
                            }
                            playbackStateHolder.updateStablePlayerState { state ->
                                val s = state.currentSong
                                if (s?.id == currentSong.id) {
                                    state.copy(currentSong = s.copy(albumId = fetchedAlbumId))
                                } else state
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
            }

            if (resolvedAlbumId <= 0L) {

                return@launch
            }

            collapsePlayerSheet()

            withTimeoutOrNull(900) {
                awaitSheetState(PlayerSheetState.COLLAPSED)
                awaitPlayerCollapse()
            }

            _albumNavigationRequests.emit(resolvedAlbumId)
        }
    }

    fun triggerArtistNavigationFromPlayer(artistId: Long) {

        val existingJob = artistNavigationJob
        if (existingJob != null && existingJob.isActive) {
            Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer ignored; navigation already in progress for artistId=$artistId")
            return
        }

        artistNavigationJob?.cancel()
        artistNavigationJob = viewModelScope.launch {
            var resolvedId = artistId
            val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
            
            if (resolvedId <= 0L && currentSong != null) {
                val isDeezer = currentSong.id.startsWith("deezer_") || currentSong.contentUriString.startsWith("deezer://track/")
                if (isDeezer) {
                    val rawId = if (currentSong.id.startsWith("deezer_")) currentSong.id.removePrefix("deezer_") else currentSong.contentUriString.removePrefix("deezer://track/")
                    try {
                        val trackInfo = deezerRepository.getTrackInfo(rawId)
                        val fetchedArtistId = trackInfo?.contributors?.firstOrNull()?.id ?: 0L
                        if (fetchedArtistId != 0L) {
                            resolvedId = fetchedArtistId
                        }
                    } catch (e: Exception) {
                    }
                } else {
                    val idFromName = musicRepository.getArtistIdByName(currentSong.artist)
                    if (idFromName != null) {
                        resolvedId = idFromName
                    }
                }
            }

            if (resolvedId <= 0L) {
                Timber.tag("ArtistDebug").d("triggerArtistNavigationFromPlayer: could not resolve artistId for name=${currentSong?.artist}")
                return@launch
            }

            Timber.tag("ArtistDebug").d(
                "triggerArtistNavigationFromPlayer: artistId=$resolvedId, songId=${currentSong?.id}, title=${currentSong?.title}"
            )
            collapsePlayerSheet()

            withTimeoutOrNull(900) {
                awaitSheetState(PlayerSheetState.COLLAPSED)
                awaitPlayerCollapse()
            }

            _artistNavigationRequests.emit(resolvedId)
        }
    }

    suspend fun awaitSheetState(target: PlayerSheetState) {
        sheetState.first { it == target }
    }

    suspend fun awaitPlayerCollapse(threshold: Float = 0.1f, timeoutMillis: Long = 800L) {
        withTimeoutOrNull(timeoutMillis) {
            snapshotFlow { playerContentExpansionFraction.value }
                .first { it <= threshold }
        }
    }

    private fun resolveSongFromMediaItem(
        mediaItem: MediaItem,
        allSongsById: Map<String, Song>? = null
    ): Song? {
        val resolvedSong =
            allSongsById?.get(mediaItem.mediaId)
                ?: libraryStateHolder.allSongsById.value[mediaItem.mediaId]
                ?: _playerUiState.value.currentPlaybackQueue.find { it.id == mediaItem.mediaId }
                ?: mediaMapper.resolveSongFromMediaItem(mediaItem)

        return resolvedSong?.let { normalizeArtworkForResolvedSong(it, mediaItem) }
    }

    private fun normalizeArtworkForResolvedSong(song: Song, mediaItem: MediaItem): Song {
        val metadataArtwork =
            mediaItem.mediaMetadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }
                ?: mediaItem.mediaMetadata.extras
                    ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART)
                    ?.takeIf { it.isNotBlank() }

        return when {
            metadataArtwork == null && song.albumArtUriString != null -> song.copy(albumArtUriString = null)
            metadataArtwork != null && song.albumArtUriString != metadataArtwork ->
                song.copy(albumArtUriString = metadataArtwork)
            else -> song
        }
    }

    private var lastQueueUpdateRequestId = 0L
    private var lastQueueSignature: QueueTimelineSignature? = null
    private var lastQueueUpdateJob: Job? = null

    private fun updateCurrentPlaybackQueueFromPlayer(playerCtrl: MediaController?) {
        val currentMediaController = playerCtrl ?: mediaController ?: return
        val requestId = ++lastQueueUpdateRequestId
        lastQueueUpdateJob?.cancel()
        lastQueueUpdateJob = viewModelScope.launch {
            // Debounce slightly to handle rapid-fire timeline events
            delay(100)

            val isWindowed = dualPlayerEngine.isUsingWindowedQueue()
            val mediaItems = if (isWindowed) {
                dualPlayerEngine.getFullQueue()
            } else {
                val timeline = currentMediaController.currentTimeline
                val windowCount = timeline.windowCount
                val list = ArrayList<MediaItem>(windowCount)
                val window = Timeline.Window()
                for (i in 0 until windowCount) {
                    list.add(timeline.getWindow(i, window).mediaItem)
                    if (i % 500 == 0) yield()
                }
                list
            }

            val count = mediaItems.size
            if (count == 0) {
                if (requestId != lastQueueUpdateRequestId) return@launch
                val emptySignature = QueueTimelineSignature(
                    count = 0,
                    orderHash = 0L,
                    firstMediaId = null,
                    lastMediaId = null
                )
                if (lastQueueSignature != emptySignature) {
                    lastQueueSignature = emptySignature
                    _playerUiState.update { it.copy(currentPlaybackQueue = persistentListOf()) }
                }
                return@launch
            }

            var orderHash = 1125899906842597L
            var firstMediaId: String? = null
            var lastMediaId: String? = null

            for (i in 0 until count) {
                val mediaItem = mediaItems[i]
                val mediaId = mediaItem.mediaId
                if (i == 0) firstMediaId = mediaId
                if (i == count - 1) lastMediaId = mediaId
                orderHash = (orderHash * 31) + mediaId.hashCode()
                if (i % 500 == 0) yield()
            }

            val signature = QueueTimelineSignature(
                count = count,
                orderHash = orderHash,
                firstMediaId = firstMediaId,
                lastMediaId = lastMediaId
            )
            if (requestId != lastQueueUpdateRequestId) return@launch
            if (signature == lastQueueSignature) return@launch

            val allSongsById = libraryStateHolder.allSongsById.value
            
            val queue = withContext(Dispatchers.Default) {
                mediaItems.mapNotNull { mediaItem ->
                    resolveSongFromMediaItem(mediaItem, allSongsById)
                }
            }

            if (requestId != lastQueueUpdateRequestId) return@launch

            lastQueueSignature = signature
            _playerUiState.update { it.copy(currentPlaybackQueue = queue.toPlaybackQueue()) }
            if (queue.isNotEmpty()) {
                _isSheetVisible.value = true
            }
        }
    }

    private fun applyPreferredRepeatMode(@Player.RepeatMode mode: Int) {
        playbackStateHolder.updateStablePlayerState { it.copy(repeatMode = mode) }

        val controller = mediaController
        if (controller == null) {
            pendingRepeatMode = mode
            return
        }

        if (controller.repeatMode != mode) {
            controller.repeatMode = mode
        }
        pendingRepeatMode = null
    }

    private fun flushPendingRepeatMode() {
        pendingRepeatMode?.let { applyPreferredRepeatMode(it) }
    }

    private fun resetPlaybackAudioMetadata() {
        metadataProbeJob?.cancel()
        metadataProbeJob = null
        metadataProbeMediaId = null
        _playbackAudioMetadata.value = PlaybackAudioMetadata()
    }

    private fun preparePlaybackAudioMetadataForMedia(mediaId: String?) {
        metadataProbeJob?.cancel()
        metadataProbeJob = null
        metadataProbeMediaId = null
        _playbackAudioMetadata.value = PlaybackAudioMetadata(mediaId = mediaId)
    }

    private fun extractBitDepthFromPcmEncoding(pcmEncoding: Int): Int? {
        return when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }

    private fun refreshPlaybackAudioMetadata(player: Player, tracks: Tracks = player.currentTracks) {
        runCatching {
            val mediaId = player.currentMediaItem?.mediaId
            if (mediaId == null) {
                resetPlaybackAudioMetadata()
                return@runCatching
            }

            val selectedAudioFormat = tracks.groups
                .asSequence()
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group ->
                    (0 until group.length)
                        .asSequence()
                        .filter { index -> group.isTrackSelected(index) }
                        .map { index -> group.getTrackFormat(index) }
                }
                .firstOrNull()

            val current = _playbackAudioMetadata.value.takeIf { it.mediaId == mediaId }
            val metadata = PlaybackAudioMetadata(
                mediaId = mediaId,
                mimeType = selectedAudioFormat?.sampleMimeType
                    ?: selectedAudioFormat?.containerMimeType
                    ?: current?.mimeType,
                bitrate = selectedAudioFormat?.bitrate?.takeIf { it > 0 }
                    ?: current?.bitrate,
                sampleRate = selectedAudioFormat?.sampleRate?.takeIf { it > 0 }
                    ?: current?.sampleRate,
                channelCount = selectedAudioFormat?.channelCount?.takeIf { it > 0 } ?: current?.channelCount,
                bitDepth = selectedAudioFormat?.pcmEncoding?.let(::extractBitDepthFromPcmEncoding) ?: current?.bitDepth
            )

            _playbackAudioMetadata.value = metadata
            maybeProbeMissingPlaybackAudioMetadata(player, metadata)
        }.onFailure { throwable ->
            Timber.w(throwable, "Failed to refresh playback audio metadata")
        }
    }

    private fun maybeProbeMissingPlaybackAudioMetadata(
        player: Player,
        metadata: PlaybackAudioMetadata
    ) {
        val shouldProbe = metadata.mimeType.isNullOrBlank() || metadata.bitrate == null || metadata.sampleRate == null
        if (!shouldProbe) return

        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        val uri = mediaItem.localConfiguration?.uri ?: return

        if (metadataProbeMediaId == mediaId && metadataProbeJob?.isActive == true) return

        metadataProbeJob?.cancel()
        metadataProbeMediaId = mediaId
        metadataProbeJob = viewModelScope.launch(Dispatchers.IO) {
            val probedMetadata = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val mimeType = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        ?.takeIf { it.isNotBlank() }
                        ?: context.contentResolver.getType(uri)
                    val bitrate = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                    val sampleRate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                            ?.toIntOrNull()
                            ?.takeIf { it > 0 }
                    } else null
                    PlaybackAudioMetadata(
                        mediaId = mediaId,
                        mimeType = mimeType,
                        bitrate = bitrate,
                        sampleRate = sampleRate
                    )
                } finally {
                    retriever.release()
                }
            }.getOrNull() ?: return@launch

            _playbackAudioMetadata.update { current ->
                val isSameMediaItem = current.mediaId == mediaId
                if (!isSameMediaItem) return@update current
                current.copy(
                    mimeType = current.mimeType ?: probedMetadata.mimeType,
                    bitrate = current.bitrate ?: probedMetadata.bitrate,
                    sampleRate = current.sampleRate ?: probedMetadata.sampleRate
                )
            }
        }
    }

    private fun syncPlaybackPositionFromPlayer(
        mediaId: String?,
        reportedPositionMs: Long
    ): Long {
        playbackStateHolder.syncCurrentPositionFromPlayer(mediaId, reportedPositionMs)
        return playbackStateHolder.currentPosition.value
    }

    private fun syncDisplayedMediaItemIfChanged(player: Player) {

        val mediaItem = player.currentMediaItem ?: return
        val currentSongId = playbackStateHolder.stablePlayerState.value.currentSong?.id
        val currentIndex = playbackStateHolder.stablePlayerState.value.currentMediaItemIndex
        val expectedIndex = if (dualPlayerEngine.isUsingWindowedQueue()) {
            dualPlayerEngine.getCurrentAbsoluteIndex()
        } else {
            player.currentMediaItemIndex
        }
        if (currentSongId == mediaItem.mediaId && currentIndex == expectedIndex) return

        playbackStateHolder.onPlaybackOccurrenceTransition(mediaItem.mediaId)
        preparePlaybackAudioMetadataForMedia(mediaItem.mediaId)
        transitionSchedulerJob?.cancel()
        lyricsStateHolder.cancelLoading()
        resetLyricsSearchState()

        val song = resolveSongFromMediaItem(mediaItem)
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val resolvedDuration = if (song != null) {
            playbackStateHolder.resolveDurationForPlaybackState(
                reportedDurationMs = player.duration,
                songDurationHintMs = song.duration.coerceAtLeast(0L),
                currentPositionMs = currentPosition
            )
        } else {
            0L
        }

        playbackStateHolder.updateStablePlayerState {
            it.copy(
                currentSong = song,
                currentMediaItemIndex = expectedIndex,
                totalDuration = resolvedDuration,
                lyrics = null,
                isLoadingLyrics = song != null,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady
            )
        }
        syncPlaybackPositionFromPlayer(mediaItem.mediaId, currentPosition)

        song?.let { currentSongValue ->
            viewModelScope.launch {
                val uri = currentSongValue.albumArtUriString?.toUri()
                val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                themeStateHolder.extractAndGenerateColorScheme(uri, currentUri)
            }
            loadLyricsForCurrentSong()
        }
    }

    private fun setupMediaControllerListeners() {
        Trace.beginSection("PlayerViewModel.setupMediaControllerListeners")
        val playerCtrl = mediaController ?: return Trace.endSection()
        _trackVolume.value = playerCtrl.volume
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                isShuffleEnabled = it.isShuffleEnabled,
                repeatMode = playerCtrl.repeatMode,
                isPlaying = playerCtrl.isPlaying,
                playWhenReady = playerCtrl.playWhenReady
            )
        }
        preparePlaybackAudioMetadataForMedia(playerCtrl.currentMediaItem?.mediaId)
        refreshPlaybackAudioMetadata(playerCtrl)

        updateCurrentPlaybackQueueFromPlayer(playerCtrl)

        playerCtrl.currentMediaItem?.let { mediaItem ->
            playbackStateHolder.ensureCurrentPlaybackOccurrence(mediaItem.mediaId)
            val song = resolveSongFromMediaItem(mediaItem)

            if (song != null) {
                val initialPosition = playerCtrl.currentPosition.coerceAtLeast(0L)
                val resolvedDuration = playbackStateHolder.resolveDurationForPlaybackState(
                    reportedDurationMs = playerCtrl.duration,
                    songDurationHintMs = song.duration.coerceAtLeast(0L),
                    currentPositionMs = initialPosition
                )
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        currentSong = song,
                        totalDuration = resolvedDuration
                    )
                }
                syncPlaybackPositionFromPlayer(mediaItem.mediaId, initialPosition)
                viewModelScope.launch {
                    val uri = song.albumArtUriString?.toUri()
                    val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                    themeStateHolder.extractAndGenerateColorScheme(uri, currentUri)
                }
                loadLyricsForCurrentSong()
                if (playerCtrl.isPlaying) {
                    _isSheetVisible.value = true
                    startProgressUpdates()
                }
            } else {
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        currentSong = null,
                        isPlaying = false,
                        playWhenReady = false
                    )
                }
                playbackStateHolder.clearCurrentPositionHints()
                playbackStateHolder.setCurrentPosition(0L)
                resetPlaybackAudioMetadata()
            }
        }

        mediaControllerPlaybackListener?.let(playerCtrl::removeListener)
        mediaControllerPlaybackListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // The service auto-skips the bad track; let the user know why playback jumped.
                viewModelScope.launch {
                    _toastEvents.emit(context.getString(R.string.player_error_skipping))
                }
            }

            override fun onVolumeChanged(volume: Float) {
                _trackVolume.value = volume
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        isPlaying = isPlaying,
                        playWhenReady = playerCtrl.playWhenReady
                    )
                }
                val shouldKeepSampling = playerCtrl.playWhenReady &&
                    playerCtrl.playbackState != Player.STATE_IDLE &&
                    playerCtrl.playbackState != Player.STATE_ENDED
                if (isPlaying || shouldKeepSampling) {
                    _isSheetVisible.value = true
                    if (isPlaying) {
                        clearPreparingSongIfMatching(playerCtrl.currentMediaItem?.mediaId)
                    }
                    startProgressUpdates()
                } else {
                    stopProgressUpdates()
                    val pausedPosition = playerCtrl.currentPosition.coerceAtLeast(0L)
                    syncPlaybackPositionFromPlayer(playerCtrl.currentMediaItem?.mediaId, pausedPosition)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playbackStateHolder.updateStablePlayerState { it.copy(playWhenReady = playWhenReady) }
                if (
                    playWhenReady &&
                    playerCtrl.playbackState != Player.STATE_IDLE &&
                    playerCtrl.playbackState != Player.STATE_ENDED
                ) {
                    startProgressUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val qualityParam = mediaItem?.localConfiguration?.uri?.getQueryParameter("quality")
                _currentTrackDeezerQuality.value = qualityParam?.let { runCatching { com.andymitch.dzr.data.preferences.DeezerAudioQuality.valueOf(it) }.getOrNull() }
                playbackStateHolder.onPlaybackOccurrenceTransition(mediaItem?.mediaId)
                preparePlaybackAudioMetadataForMedia(mediaItem?.mediaId)
                transitionSchedulerJob?.cancel()
                lyricsStateHolder.cancelLoading()
                transitionSchedulerJob = viewModelScope.launch {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        val activeEotSongId = EotStateHolder.eotTargetSongId.value
                        val previousSongId = playerCtrl.run { if (previousMediaItemIndex != C.INDEX_UNSET) getMediaItemAt(previousMediaItemIndex).mediaId else null }

                        if (isEndOfTrackTimerActive.value && activeEotSongId != null && previousSongId != null && previousSongId == activeEotSongId) {
                            playerCtrl.seekTo(0L)
                            playerCtrl.pause()

                            val finishedSongTitle = libraryStateHolder.allSongsById.value[previousSongId]?.title
                                ?: context.getString(R.string.player_default_track_title)

                            viewModelScope.launch {
                                _toastEvents.emit(
                                    context.getString(R.string.player_playback_stopped_eot, finishedSongTitle),
                                )
                            }
                            cancelSleepTimer(suppressDefaultToast = true)
                        }
                    }

                    mediaItem?.let { transitionedItem ->
                        val song = resolveSongFromMediaItem(transitionedItem)
                        
                        val resolvedDuration = if (song != null) {
                            playbackStateHolder.resolveDurationForPlaybackState(
                                reportedDurationMs = playerCtrl.duration,
                                songDurationHintMs = song.duration.coerceAtLeast(0L),
                                currentPositionMs = playerCtrl.currentPosition.coerceAtLeast(0L)
                            )
                        } else {
                            0L
                        }
                        resetLyricsSearchState()
                        playbackStateHolder.updateStablePlayerState {
                            it.copy(
                                currentSong = song,
                                currentMediaItemIndex = if (dualPlayerEngine.isUsingWindowedQueue()) {
                                    dualPlayerEngine.getCurrentAbsoluteIndex()
                                } else {
                                    playerCtrl.currentMediaItemIndex
                                },
                                totalDuration = resolvedDuration,
                                lyrics = null,
                                isLoadingLyrics = song != null,
                                playWhenReady = playerCtrl.playWhenReady
                            )
                        }

                        song?.let { currentSongValue ->
                            launch {
                                val uri = currentSongValue.albumArtUriString?.toUri()
                                val currentUri = playbackStateHolder.stablePlayerState.value.currentSong?.albumArtUriString
                                themeStateHolder.extractAndGenerateColorScheme(uri, currentUri)
                            }
                            loadLyricsForCurrentSong()
                        }
                        
                    } ?: run {
                        lyricsStateHolder.cancelLoading()
                        playbackStateHolder.updateStablePlayerState {
                            it.copy(
                                currentSong = null,
                                isPlaying = false,
                                playWhenReady = false,
                                lyrics = null,
                                isLoadingLyrics = false,
                                totalDuration = 0L
                            )
                        }
                        playbackStateHolder.clearCurrentPositionHints()
                        resetPlaybackAudioMetadata()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshPlaybackAudioMetadata(playerCtrl)
                syncDisplayedMediaItemIfChanged(playerCtrl)

                // Debounce buffering state to avoid flickering
                bufferingDebounceJob?.cancel()
                if (playbackState == Player.STATE_BUFFERING) {
                    bufferingDebounceJob = viewModelScope.launch {
                        delay(500) // Wait 500ms before showing buffering indicator
                        playbackStateHolder.updateStablePlayerState { state ->
                            state.copy(isBuffering = true)
                        }
                    }
                } else {
                    // Immediately hide buffering when not buffering
                    playbackStateHolder.updateStablePlayerState { state ->
                        state.copy(isBuffering = false)
                    }
                }

                if (playbackState == Player.STATE_READY) {
                    clearPreparingSongIfMatching(playerCtrl.currentMediaItem?.mediaId)
                    val readyPosition = playerCtrl.currentPosition.coerceAtLeast(0L)
                    val songDurationHint = playbackStateHolder.stablePlayerState.value.currentSong?.duration ?: 0L
                    val resolvedDuration = playbackStateHolder.resolveDurationForPlaybackState(
                        reportedDurationMs = playerCtrl.duration,
                        songDurationHintMs = songDurationHint,
                        currentPositionMs = readyPosition
                    )
                    syncPlaybackPositionFromPlayer(playerCtrl.currentMediaItem?.mediaId, readyPosition)
                    playbackStateHolder.updateStablePlayerState { it.copy(totalDuration = resolvedDuration) }
                    startProgressUpdates()
                }
                if (playbackState == Player.STATE_IDLE && playerCtrl.mediaItemCount == 0) {
                    clearPreparingSongIfMatching()
                    lyricsStateHolder.cancelLoading()
                    playbackStateHolder.updateStablePlayerState {
                        it.copy(
                            currentSong = null,
                            isPlaying = false,
                            playWhenReady = false,
                            lyrics = null,
                            isLoadingLyrics = false,
                            totalDuration = 0L
                        )
                    }
                    playbackStateHolder.clearCurrentPositionHints()
                    playbackStateHolder.setCurrentPosition(0L)
                    resetPlaybackAudioMetadata()
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                refreshPlaybackAudioMetadata(playerCtrl, tracks)
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                syncDisplayedMediaItemIfChanged(playerCtrl)
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // IMPORTANT: We don't use ExoPlayer's shuffle mode anymore
                // Instead, we manually shuffle the queue to fix crossfade issues
                // If ExoPlayer's shuffle gets enabled (e.g., from media button), turn it off and use our toggle
                if (shuffleModeEnabled) {
                    playerCtrl.shuffleModeEnabled = false
                    // Trigger our manual shuffle instead
                    if (!playbackStateHolder.stablePlayerState.value.isShuffleEnabled) {
                        toggleShuffle()
                    }
                }
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                playbackStateHolder.updateStablePlayerState { it.copy(repeatMode = repeatMode) }
                viewModelScope.launch { userPreferencesRepository.setRepeatMode(repeatMode) }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                syncDisplayedMediaItemIfChanged(playerCtrl)
                // Skip updates during crossfade transitions to prevent UI freeze and jumpy state.
                if (dualPlayerEngine.isTransitionRunning()) return

                transitionSchedulerJob?.cancel()
                
                // Only refresh full queue on structural changes or source updates (metadata)
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ||
                    reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                    updateCurrentPlaybackQueueFromPlayer(mediaController)
                    dualPlayerEngine.triggerAdjacentPreResolution()
                }
            }
        }
        playerCtrl.addListener(checkNotNull(mediaControllerPlaybackListener))
        Trace.endSection()
    }


    // rebuildPlayerQueue functionality moved to PlaybackStateHolder (simplified)
    fun playSongs(songsToPlay: List<Song>, startSong: Song, queueName: String = "None", playlistId: String? = null, flowUrl: String? = null) {
        cancelPendingFullQueuePlayback()
        val requestToken = beginDirectPlaybackRequest()
        directPlaybackJob = viewModelScope.launch {
            transitionSchedulerJob?.cancel()

            if (flowUrl != null) {
                flowContinuation.start(songsToPlay.map { MediaItemBuilder.build(it) }) {
                    deezerRepository.getMultiFlowTracks(flowUrl)?.data?.included.orEmpty()
                        .filter { it.type == "track" }
                        .map { MediaItemBuilder.build(com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong(it)) }
                }
            } else {
                flowContinuation.stop()
            }

            val validSongs = hydrateSongsIfNeeded(songsToPlay)
            throwIfDirectPlaybackRequestIsStale(requestToken)

            if (validSongs.isEmpty()) {
                _toastEvents.emit(context.getString(R.string.no_valid_songs))
                return@launch
            }

            // Adjust startSong if it was filtered out
            val validStartSong =
                validSongs.firstOrNull { it.id == startSong.id } ?: validSongs.first()

            // Store the original order so we can "unshuffle" later if the user turns shuffle off
            queueStateHolder.setOriginalQueueOrder(validSongs)
            queueStateHolder.saveOriginalQueueState(validSongs, queueName)

            // Check if the user wants shuffle to be persistent across different albums
            val isPersistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
            throwIfDirectPlaybackRequestIsStale(requestToken)
            // Check if shuffle is currently active in the player
            val isShuffleOn = playbackStateHolder.stablePlayerState.value.isShuffleEnabled

            // If Persistent Shuffle is OFF, we reset shuffle to "false" every time a new album starts
            if (!isPersistent) {
                playbackStateHolder.updateStablePlayerState { it.copy(isShuffleEnabled = false) }
            }

            // If shuffle is persistent and currently ON, we shuffle the new songs immediately
            val finalSongsToPlay = if (isPersistent && isShuffleOn) {
                // Shuffle the list but make sure the song you clicked stays at its current index or starts first
                withContext(Dispatchers.Default) {
                    QueueUtils.buildAnchoredShuffleQueueSuspending(
                        validSongs,
                        validSongs.indexOfFirst { it.id == validStartSong.id }.coerceAtLeast(0)
                    )
                }
            } else {
                // Otherwise, just use the normal sequential order
                validSongs
            }
            throwIfDirectPlaybackRequestIsStale(requestToken)

            // Send the final list (shuffled or not) to the player engine
            internalPlaySongs(finalSongsToPlay, validStartSong, queueName, playlistId)
            if (requestToken == directPlaybackToken) {
                directPlaybackJob = null
            }
        }
    }

    // Start playback with shuffle enabled in one coroutine to avoid racing queue updates
    fun playSongsShuffled(
        songsToPlay: List<Song>, 
        queueName: String = "None", 
        playlistId: String? = null,
        startAtZero: Boolean = false
    ) {
        cancelPendingFullQueuePlayback()
        val requestToken = beginDirectPlaybackRequest()
        flowContinuation.stop()
        directPlaybackJob = viewModelScope.launch {
            val result = queueStateHolder.prepareShuffledQueueSuspending(songsToPlay, queueName, startAtZero)
            throwIfDirectPlaybackRequestIsStale(requestToken)
            if (result == null) {
                sendToast(context.getString(R.string.player_no_songs_to_shuffle))
                return@launch
            }

            val (shuffledQueue, startSong) = result
            transitionSchedulerJob?.cancel()

            // Optimistically update shuffle state
            playbackStateHolder.updateStablePlayerState { it.copy(isShuffleEnabled = true) }
            launch { userPreferencesRepository.setShuffleOn(true) }

            internalPlaySongs(shuffledQueue, startSong, queueName, playlistId)
            if (requestToken == directPlaybackToken) {
                directPlaybackJob = null
            }
        }
    }

    fun playExternalUri(uri: Uri) {
        viewModelScope.launch {
            val externalResult = externalMediaStateHolder.buildExternalSongFromUri(uri)
            if (externalResult == null) {
                sendToast(context.getString(R.string.external_playback_error))
                return@launch
            }

            transitionSchedulerJob?.cancel()

            val queueSongs = externalMediaStateHolder.buildExternalQueue(externalResult, uri)
            val immutableQueue = queueSongs.toPlaybackQueue()

            _playerUiState.update { state ->
                state.copy(
                    currentPlaybackQueue = immutableQueue,
                    currentQueueSourceName = context.getString(R.string.external_queue_label),
                    showDismissUndoBar = false,
                    dismissedSong = null,
                    dismissedQueue = persistentListOf(),
                    dismissedQueueName = "",
                    dismissedPosition = 0L
                )
            }
            playbackStateHolder.setCurrentPosition(0L)

            playbackStateHolder.updateStablePlayerState { state ->
                state.copy(
                    currentSong = externalResult.song,
                    isPlaying = true,
                    playWhenReady = true,
                    totalDuration = externalResult.song.duration,
                    lyrics = null,
                    isLoadingLyrics = false
                )
            }

            _sheetState.value = PlayerSheetState.COLLAPSED
            _isSheetVisible.value = true

            internalPlaySongs(queueSongs, externalResult.song, context.getString(R.string.external_queue_label), null)
            showPlayer()
        }
    }

    fun showPlayer() {
        if (stablePlayerState.value.currentSong != null) {
            _isSheetVisible.value = true
        }
    }

    private fun setPreparingSong(songId: String?) {
        _playerUiState.update { state ->
            if (state.preparingSongId == songId) state else state.copy(preparingSongId = songId)
        }
    }

    private fun beginPreparingSong(song: Song) {
        // Skip the "Preparing playback…" pill for local files: they reach STATE_READY
        // in milliseconds, and transient STATE_BUFFERING from audio HAL/offload init
        // (or a re-tap of an already-loaded song) can otherwise leave the pill stuck.
        // Always write the new value (null for local, song.id for remote) so a stale
        // preparingSongId from a previous remote song cannot outlive a local track switch.
        if (!isLocalPlaybackSong(song)) {
            setPreparingSong(song.id)
        } else {
            setPreparingSong(null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val albumArtUri = song.albumArtUriString
            if (albumArtUri.isNullOrBlank()) {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = null,
                    currentSongUriString = null,
                    isPreload = false
                )
            } else {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = albumArtUri.toUri(),
                    currentSongUriString = albumArtUri,
                    isPreload = false
                )
            }
        }
    }

    private fun isLocalPlaybackSong(song: Song): Boolean {
        val scheme = MediaItemBuilder.playbackUri(song).scheme?.lowercase()
        return scheme == null || scheme in LOCAL_PLAYBACK_SCHEMES
    }

    private fun clearPreparingSongIfMatching(mediaId: String? = null) {
        val preparingSongId = _playerUiState.value.preparingSongId ?: return
        if (mediaId == null || preparingSongId == mediaId) {
            setPreparingSong(null)
        }
    }

    private suspend fun preparePlaybackQueueSegments(
        songsToPlay: List<Song>,
        startSongId: String,
        playlistId: String?
    ): PreparedPlaybackQueueSegments = withContext(Dispatchers.Default) {
        val currentIndex = songsToPlay
            .indexOfFirst { it.id == startSongId }
            .takeIf { it >= 0 }
            ?: 0

        val beforeCurrent = List(currentIndex) { index ->
            buildPlaybackMediaItem(songsToPlay[index], playlistId)
        }
        val afterStartIndex = currentIndex + 1
        val afterCurrent = List((songsToPlay.size - afterStartIndex).coerceAtLeast(0)) { offset ->
            buildPlaybackMediaItem(songsToPlay[afterStartIndex + offset], playlistId)
        }

        PreparedPlaybackQueueSegments(
            beforeCurrent = beforeCurrent,
            afterCurrent = afterCurrent,
            currentIndex = currentIndex
        )
    }

    private suspend fun attachPreparedQueueSegmentsIfCurrent(
        player: Player,
        startSongId: String,
        preparedSegments: PreparedPlaybackQueueSegments
    ) {
        if (player.currentMediaItem?.mediaId != startSongId) return
        if (player.mediaItemCount != 1) return
        if (player.getMediaItemAt(0).mediaId != startSongId) return

        val batchSize = 200

        if (preparedSegments.beforeCurrent.isNotEmpty()) {
            var insertedCount = 0
            while (insertedCount < preparedSegments.beforeCurrent.size) {
                val end = (insertedCount + batchSize).coerceAtMost(preparedSegments.beforeCurrent.size)
                val batch = preparedSegments.beforeCurrent.subList(insertedCount, end)
                player.addMediaItems(insertedCount, batch)
                insertedCount = end
                yield()
            }
        }

        if (preparedSegments.afterCurrent.isNotEmpty()) {
            var insertedCount = 0
            while (insertedCount < preparedSegments.afterCurrent.size) {
                val end = (insertedCount + batchSize).coerceAtMost(preparedSegments.afterCurrent.size)
                val batch = preparedSegments.afterCurrent.subList(insertedCount, end)
                player.addMediaItems(preparedSegments.beforeCurrent.size + 1 + insertedCount, batch)
                insertedCount = end
                yield()
            }
        }

        playbackStateHolder.updateStablePlayerState {
            it.copy(currentMediaItemIndex = preparedSegments.currentIndex)
        }
    }



    private suspend fun internalPlaySongs(songsToPlay: List<Song>, startSong: Song, queueName: String = "None", playlistId: String? = null) {
        if (songsToPlay.isEmpty()) {
            clearPreparingSongIfMatching()
            return
        }
        val effectiveStartSong = songsToPlay.firstOrNull { it.id == startSong.id } ?: songsToPlay.first()

        if (playlistId != null && queueName != "None") {
            appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
        }

        beginPreparingSong(effectiveStartSong)
        _playerUiState.update {
            it.copy(
                currentPlaybackQueue = songsToPlay.toPlaybackQueue(),
                currentQueueSourceName = queueName
            )
        }
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                currentSong = effectiveStartSong,
                currentMediaItemIndex = 0,
                isPlaying = true,
                playWhenReady = true,
                totalDuration = effectiveStartSong.duration.coerceAtLeast(0L)
            )
        }
        _isSheetVisible.value = true

        val startMediaItem = buildResolvedPlaybackMediaItem(effectiveStartSong)

        val castingController = mediaController.takeIf { castController.isCasting.value }
        if (castingController != null) {
            val startIndex = songsToPlay.indexOfFirst { it.id == effectiveStartSong.id }.coerceAtLeast(0)
            val window = songsToPlay.drop(startIndex).take(100).map { MediaItemBuilder.build(it) }
            castingController.setMediaItems(window, 0, 0L)
            castingController.prepare()
            castingController.play()
            _playerUiState.update { it.copy(isLoadingInitialSongs = false) }
            return
        }

        val playSongsAction = {
            dualPlayerEngine.cancelNext()
            val enginePlayer = dualPlayerEngine.masterPlayer

            enginePlayer.setMediaItem(startMediaItem, 0L)
            enginePlayer.prepare()
            enginePlayer.play()
            _playerUiState.update { it.copy(isLoadingInitialSongs = false) }

            if (songsToPlay.size > 1) {
                pendingQueueSegmentsJob?.cancel()
                pendingQueueSegmentsJob = viewModelScope.launch {
                    val preparedSegments = preparePlaybackQueueSegments(
                        songsToPlay = songsToPlay,
                        startSongId = effectiveStartSong.id,
                        playlistId = playlistId
                    )
                    withContext(Dispatchers.Main.immediate) {
                        attachPreparedQueueSegmentsIfCurrent(
                            player = dualPlayerEngine.masterPlayer,
                            startSongId = effectiveStartSong.id,
                            preparedSegments = preparedSegments
                        )
                    }
                }
            }
        }

        if (mediaController == null) {
            Timber.w("MediaController not available. Queuing playback action.")
            pendingPlaybackAction = playSongsAction
        } else {
            playSongsAction()
        }
    }

    private suspend fun buildResolvedPlaybackMediaItem(song: Song): MediaItem {
        val mediaItem = MediaItemBuilder.build(song)
        val originalUri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val scheme = originalUri.scheme
        if (
            scheme != "navidrome" &&
            scheme != "jellyfin"
        ) {
            return mediaItem
        }

        val resolvedUri = dualPlayerEngine.resolveCloudUri(originalUri)
        return if (resolvedUri == originalUri) {
            mediaItem
        } else {
            mediaItem.buildUpon().setUri(resolvedUri).build()
        }
    }


    private fun loadAndPlaySong(song: Song) {
        cancelPendingFullQueuePlayback()
        beginPreparingSong(song)
        playbackStateHolder.updateStablePlayerState {
            it.copy(
                currentSong = song,
                isPlaying = true,
                playWhenReady = true
            )
        }
        _isSheetVisible.value = true

        val controller = mediaController
        if (controller == null) {
            pendingPlaybackAction = {
                loadAndPlaySong(song)
            }
            return
        }

        viewModelScope.launch {
            val mediaItem = buildResolvedPlaybackMediaItem(song)
            if (controller.currentMediaItem?.mediaId == song.id) {
                if (!controller.isPlaying) controller.play()
            } else {
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
            }
        }
    }

// buildMediaMetadataForSong moved to MediaItemBuilder

    private fun syncShuffleStateWithSession(enabled: Boolean) {
        val controller = mediaController ?: return
        val args = Bundle().apply {
            putBoolean(MusicNotificationProvider.EXTRA_SHUFFLE_ENABLED, enabled)
        }
        controller.sendCustomCommand(
            SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE, Bundle()),
            args
        )
    }

    fun toggleShuffle(currentSongOverride: Song? = null) {
        cancelPendingFullQueuePlayback()
        val currentQueue = _playerUiState.value.currentPlaybackQueue.toList()
        val currentSong = currentSongOverride
            ?: playbackStateHolder.stablePlayerState.value.currentSong
            ?: mediaController?.currentMediaItem?.let { resolveSongFromMediaItem(it) }
            ?: currentQueue.firstOrNull()

        playbackStateHolder.toggleShuffle(
            currentSongs = currentQueue,
            currentSong = currentSong,
            currentQueueSourceName = _playerUiState.value.currentQueueSourceName,
            updateQueueCallback = { newQueue ->
                _playerUiState.update { it.copy(currentPlaybackQueue = newQueue.toPlaybackQueue()) }
            }
        )
    }

    fun cycleRepeatMode() {
        playbackStateHolder.cycleRepeatMode()
    }

    private suspend fun setFavoriteStatusEverywhere(songId: String, isFavorite: Boolean) {
        musicRepository.setFavoriteStatus(songId, isFavorite)
    }

    fun toggleFavorite() {
        val currentSong = playbackStateHolder.stablePlayerState.value.currentSong ?: return
        viewModelScope.launch {
            val favoriteSongId = resolveFavoriteSongId(currentSong) ?: return@launch
            val currentlyFavorite = favoriteSongIds.value.contains(favoriteSongId)
            val targetState = !currentlyFavorite
            if (targetState) {
                musicRepository.saveSong(currentSong)
            }
            setFavoriteStatusEverywhere(favoriteSongId, targetState)
            
            if (currentSong.contentUriString.startsWith("deezer://")) {
                val numericId = favoriteSongId.toLongOrNull() ?: return@launch
                val success = if (targetState) {
                    deezerRepository.likeTrack(numericId)
                } else {
                    deezerRepository.unlikeTrack(numericId)
                }
                
                if (!success) {
                    setFavoriteStatusEverywhere(favoriteSongId, currentlyFavorite)
                    sendToast(context.getString(R.string.player_could_not_locate_song).replace("locate", if (targetState) "like" else "unlike"))
                }
            }
        }
    }

    fun toggleFavoriteSpecificSong(song: Song, removing: Boolean = false) {
        viewModelScope.launch {
            val favoriteSongId = resolveFavoriteSongId(song) ?: return@launch
            val currentlyFavorite = favoriteSongIds.value.contains(favoriteSongId)
            val targetFavoriteState = if (removing) false else !currentlyFavorite
            if (targetFavoriteState) {
                musicRepository.saveSong(song)
            }
            setFavoriteStatusEverywhere(favoriteSongId, targetFavoriteState)
            
            if (song.contentUriString.startsWith("deezer://")) {
                val numericId = favoriteSongId.toLongOrNull() ?: return@launch
                val success = if (targetFavoriteState) {
                    deezerRepository.likeTrack(numericId)
                } else {
                    deezerRepository.unlikeTrack(numericId)
                }
                
                if (!success) {
                    setFavoriteStatusEverywhere(favoriteSongId, currentlyFavorite)
                    sendToast(context.getString(R.string.player_could_not_locate_song).replace("locate", if (targetFavoriteState) "like" else "unlike"))
                }
            }
        }
    }

    private suspend fun resolveFavoriteSongId(song: Song?): String? {
        song ?: return null
        if (song.id.toLongOrNull() != null) {
            return song.id
        }

        val contentUriCandidates = buildList {
            if (song.id.startsWith(EXTERNAL_SONG_ID_PREFIX)) {
                add(song.id.removePrefix(EXTERNAL_SONG_ID_PREFIX))
            }
            add(song.contentUriString)
        }.filter { it.isNotBlank() }.distinct()

        for (candidate in contentUriCandidates) {
            musicRepository.getSongIdByContentUri(candidate)?.let { return it.toString() }
            parseMediaStoreAudioId(candidate)?.let { return it.toString() }
        }

        val pathCandidates = buildList {
            add(song.path)
            contentUriCandidates.forEach { candidate ->
                parseFileUriPath(candidate)?.let(::add)
            }
        }.filter { it.isNotBlank() }.distinct()

        for (candidate in pathCandidates) {
            musicRepository.getSongByPath(candidate)?.id?.takeIf { it.toLongOrNull() != null }?.let {
                return it
            }
        }

        return null
    }

    private fun parseMediaStoreAudioId(uriString: String): Long? {
        val normalizedUri = uriString.substringBefore('?').substringBefore('#')
        if (
            !normalizedUri.startsWith("content://media/", ignoreCase = true) ||
            !normalizedUri.contains("/audio/media/", ignoreCase = true)
        ) {
            return null
        }

        return normalizedUri.substringAfterLast('/').toLongOrNull()?.takeIf { it > 0L }
    }

    private fun parseFileUriPath(uriString: String): String? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return uri.takeIf { it.scheme == "file" }?.path?.takeIf { it.isNotBlank() }
    }

    fun addSongToQueue(song: Song) {
        mediaController?.let { controller ->
            val mediaItem = buildPlaybackMediaItem(song)
            controller.addMediaItem(mediaItem)
            // Queue UI is synced via onTimelineChanged listener
        }
    }

    fun appendSongsSilent(songs: List<Song>) {
        mediaController?.let { controller ->
            val mediaItems = songs.map { buildPlaybackMediaItem(it) }
            controller.addMediaItems(mediaItems)
        }
    }

    fun addSongNextToQueue(song: Song) {
        mediaController?.let { controller ->
            val mediaItem = buildPlaybackMediaItem(song)

            val insertionIndex = if (controller.currentMediaItemIndex != C.INDEX_UNSET) {
                (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount)
            } else {
                controller.mediaItemCount
            }

            controller.addMediaItem(insertionIndex, mediaItem)
            // Queue UI is synced via onTimelineChanged listener
        }
    }

    private fun buildPlaybackMediaItem(song: Song, playlistId: String? = null): MediaItem {
        val baseItem = MediaItemBuilder.build(song)
        if (playlistId == null) {
            return baseItem
        }

        val mergedExtras = Bundle(baseItem.mediaMetadata.extras ?: Bundle()).apply {
            putString("playlistId", playlistId)
        }

        return baseItem.buildUpon()
            .setMediaMetadata(
                baseItem.mediaMetadata.buildUpon()
                    .setExtras(mergedExtras)
                    .build()
            )
            .build()
    }

    // =====================================================
    // Multi-Selection Batch Operations
    // =====================================================

    /**
     * Plays all selected songs, preserving their selection order.
     * Clears selection after starting playback.
     */
    fun playSelectedSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        playSongs(songs, songs.first(), "Selected Songs")
        multiSelectionStateHolder.clearSelection()
    }

    /**
     * Adds all selected songs to the end of the queue.
     * Clears selection after adding.
     */
    fun addSelectedToQueue(songs: List<Song>) {
        songs.forEach { addSongToQueue(it) }
        viewModelScope.launch {
            val n = songs.size
            _toastEvents.emit(
                context.resources.getQuantityString(R.plurals.n_songs_added_to_queue, n, n),
            )
        }
        multiSelectionStateHolder.clearSelection()
    }

    /**
     * Adds all selected songs to play next, preserving selection order.
     * Songs are inserted in reverse order so they play in the correct sequence.
     * Clears selection after adding.
     */
    fun addSelectedAsNext(songs: List<Song>) {
        songs.reversed().forEach { addSongNextToQueue(it) }
        viewModelScope.launch {
            val n = songs.size
            _toastEvents.emit(
                context.resources.getQuantityString(R.plurals.n_songs_will_play_next, n, n),
            )
        }
        multiSelectionStateHolder.clearSelection()
    }

    fun playSelectedAlbums(albums: List<Album>) {
        if (albums.isEmpty()) return
        viewModelScope.launch {
            try {
                val resolvedSelection = resolveSelectedAlbumSongs(albums)
                if (resolvedSelection.songs.isEmpty()) {
                    _toastEvents.emit(context.getString(R.string.player_no_playable_songs_in_albums))
                    return@launch
                }

                val queueName = if (resolvedSelection.albums.size == 1) {
                    resolvedSelection.albums.first().title
                } else {
                    context.getString(R.string.player_queue_name_selected_albums)
                }

                playSongs(resolvedSelection.songs, resolvedSelection.songs.first(), queueName, null)
                _isSheetVisible.value = true

                if (resolvedSelection.wasTrimmed) {
                    _toastEvents.emit(
                        context.getString(R.string.player_only_first_n_albums_queued, MAX_ALBUM_BATCH_SELECTION),
                    )
                } else {
                    _toastEvents.emit(
                        context.getString(
                            R.string.player_albums_queued_format,
                            resolvedSelection.albums.size,
                            resolvedSelection.songs.size,
                        ),
                    )
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error playing selected albums")
                _toastEvents.emit(context.getString(R.string.player_could_not_queue_albums))
            }
        }
    }

    fun addSelectedAlbumsAsNext(albums: List<Album>) {
        if (albums.isEmpty()) return

        viewModelScope.launch {
            try {
                val resolvedSelection = resolveSelectedAlbumSongs(albums)
                if (resolvedSelection.songs.isEmpty()) {
                    _toastEvents.emit("No playable songs found in selected albums")
                    return@launch
                }

                resolvedSelection.songs
                    .asReversed()
                    .forEach(::addSongNextToQueue)

                if (resolvedSelection.wasTrimmed) {
                    _toastEvents.emit("Only the first $MAX_ALBUM_BATCH_SELECTION albums were added as next")
                } else {
                    _toastEvents.emit("${resolvedSelection.albums.size} albums will play next")
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error adding selected albums as next")
                _toastEvents.emit("Could not add selected albums as next")
            }
        }
    }

    fun addSelectedAlbumsToQueue(albums: List<Album>) {
        if (albums.isEmpty()) return

        viewModelScope.launch {
            try {
                val resolvedSelection = resolveSelectedAlbumSongs(albums)
                if (resolvedSelection.songs.isEmpty()) {
                    _toastEvents.emit("No playable songs found in selected albums")
                    return@launch
                }

                resolvedSelection.songs.forEach(::addSongToQueue)

                if (resolvedSelection.wasTrimmed) {
                    _toastEvents.emit("Only the first $MAX_ALBUM_BATCH_SELECTION albums were added to queue")
                } else {
                    _toastEvents.emit("${resolvedSelection.albums.size} albums added to queue")
                }
            } catch (e: Exception) {
                Timber.tag("PlayerViewModel").e(e, "Error adding selected albums to queue")
                _toastEvents.emit("Could not add selected albums to queue")
            }
        }
    }


    /**
     * Adds all selected songs to favorites.
     * Clears selection after liking.
     */
    fun likeSelectedSongs(songs: List<Song>) {
        viewModelScope.launch {
            val favIds = favoriteSongIds.value.toMutableSet()
            var likedCount = 0
            songs.forEach { song ->
                if (!favIds.contains(song.id)) {
                    setFavoriteStatusEverywhere(song.id, true)
                    favIds.add(song.id)
                    likedCount++
                }
            }
            if (likedCount > 0) {
                _toastEvents.emit(
                    context.resources.getQuantityString(R.plurals.n_songs_added_to_favorites, likedCount, likedCount),
                )
            } else {
                _toastEvents.emit(context.getString(R.string.player_all_songs_already_in_favorites))
            }
            multiSelectionStateHolder.clearSelection()
        }
    }

    /**
     * Removes all selected songs from favorites.
     * Clears selection after unliking.
     */
    fun unlikeSelectedSongs(songs: List<Song>) {
        viewModelScope.launch {
            val favIds = favoriteSongIds.value.toMutableSet()
            var unlikedCount = 0
            songs.forEach { song ->
                if (favIds.contains(song.id)) {
                    setFavoriteStatusEverywhere(song.id, false)
                    favIds.remove(song.id)
                    unlikedCount++
                }
            }
            if (unlikedCount > 0) {
                _toastEvents.emit(
                    context.resources.getQuantityString(
                        R.plurals.n_songs_removed_from_favorites,
                        unlikedCount,
                        unlikedCount,
                    ),
                )
            } else {
                _toastEvents.emit(context.getString(R.string.player_no_songs_were_in_favorites))
            }
            multiSelectionStateHolder.clearSelection()
        }
    }

    /**
     * Shares all selected songs as a ZIP file.
     * Clears selection after initiating share.
     */
    fun shareSelectedAsZip(songs: List<Song>) {
        viewModelScope.launch {
            _toastEvents.emit(context.getString(R.string.player_creating_zip))

            val result = ZipShareHelper.createAndShareZip(context, songs)

            result.onSuccess {
                multiSelectionStateHolder.clearSelection()
            }.onFailure { error ->
                _toastEvents.emit(
                    context.getString(R.string.player_share_zip_failed_format, error.localizedMessage ?: ""),
                )
                println(
                    "Failed to share: ${error.localizedMessage}"
                )
            }
        }
    }

    private suspend fun resolveSelectedAlbumSongs(albums: List<Album>): ResolvedAlbumSelection {
        val albumsToProcess = albums.take(MAX_ALBUM_BATCH_SELECTION)
        val wasTrimmed = albums.size > albumsToProcess.size

        val songs = withContext(Dispatchers.IO) {
            buildList {
                albumsToProcess.forEach { album ->
                    val albumSongs = musicRepository.getSongsForAlbum(album.id).first()
                    if (albumSongs.isNotEmpty()) {
                        addAll(sortSongsForAlbumSelection(albumSongs))
                    }
                }
            }
        }

        return ResolvedAlbumSelection(
            albums = albumsToProcess,
            songs = songs,
            wasTrimmed = wasTrimmed
        )
    }

    private fun sortSongsForAlbumSelection(songs: List<Song>): List<Song> {
        return songs.sortedWith(
            compareBy<Song> { it.discNumber ?: 1 }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )
    }

    /**
     * Deletes all selected songs from device with confirmation.
     * Shows a single confirmation dialog for all songs.
     */







    /**
     * Signal from the player sheet whether the slider-bearing UI is currently
     * rendered. Drives the position-ticker's resolution (250 ms vs 1 s).
     */
    fun setSliderUiMounted(mounted: Boolean) {
        playbackStateHolder.setSliderUiMounted(mounted)
    }

    fun playPause() {
        val controller = mediaController
        if (controller == null || !controller.isConnected) {
            playbackStateHolder.playPause()
            return
        }

        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.currentMediaItem == null) {
                val currentQueue = _playerUiState.value.currentPlaybackQueue
                val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
                when {
                    currentQueue.isNotEmpty() && currentSong != null -> {
                        viewModelScope.launch {
                            transitionSchedulerJob?.cancel()
                            internalPlaySongs(
                                currentQueue.toList(),
                                currentSong,
                                _playerUiState.value.currentQueueSourceName
                            )
                        }
                    }
                    currentSong != null -> loadAndPlaySong(currentSong)
                    else -> {
                        viewModelScope.launch {
                            val fallbackSong = musicRepository.getFirstPlayableSong()
                            if (fallbackSong != null) {
                                loadAndPlaySong(fallbackSong)
                            } else {
                                controller.play()
                            }
                        }
                    }
                }
            } else {
                if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
                    controller.prepare()
                }
                controller.play()
            }
        }
    }

    fun seekTo(position: Long) {
        playbackStateHolder.seekTo(position)
    }

    fun nextSong() {
        playbackStateHolder.nextSong()
    }

    fun previousSong() {
        playbackStateHolder.previousSong()
    }

    private fun startProgressUpdates() {
        playbackStateHolder.startProgressUpdates()
    }

    private fun stopProgressUpdates() {
        playbackStateHolder.stopProgressUpdates()
    }

    fun observeSongs(songIds: List<String>): Flow<List<Song>> {
        return musicRepository.getSongsByIds(songIds)
    }

    fun searchSongs(query: String): Flow<List<Song>> {
        return musicRepository.searchSongs(query)
    }

    suspend fun getSongs(songIds: List<String>) : List<Song>{
        return musicRepository.getSongsByIds(songIds).first()
    }

    //Sorting
    fun sortSongs(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortSongs(sortOption, persist)
    }

    fun sortAlbums(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortAlbums(sortOption, persist)
    }

    fun sortArtists(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortArtists(sortOption, persist)
    }

    fun sortFavoriteSongs(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortFavoriteSongs(sortOption, persist)
    }

    fun sortFolders(sortOption: SortOption, persist: Boolean = true) {
        libraryStateHolder.sortFolders(sortOption, persist)
    }


    fun setFoldersSource(source: FolderSource) {
        if (!ENABLE_FOLDERS_SOURCE_SWITCHING) return
        viewModelScope.launch {
            userPreferencesRepository.setFoldersSource(source)
        }
    }



    fun setAlbumsListView(isList: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAlbumsListView(isList)
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        searchStateHolder.updateSearchFilter(filterType)
    }

    fun loadSearchHistory(limit: Int = 15) {
        searchStateHolder.loadSearchHistory(limit)
    }

    fun onSearchQuerySubmitted(query: String) {
        searchStateHolder.onSearchQuerySubmitted(query)
    }

    fun performSearch(query: String) {
        searchStateHolder.performSearch(query)
    }

    fun deleteSearchHistoryItem(query: String) {
        searchStateHolder.deleteSearchHistoryItem(query)
    }

    fun clearSearchHistory() {
        searchStateHolder.clearSearchHistory()
    }

    fun clearQueueExceptCurrent() {
        mediaController?.let { controller ->
            val currentSongIndex = controller.currentMediaItemIndex
            if (currentSongIndex == C.INDEX_UNSET) return@let
            val indicesToRemove = (0 until controller.mediaItemCount)
                .filter { it != currentSongIndex }
                .sortedDescending()

            for (index in indicesToRemove) {
                controller.removeMediaItem(index)
            }
        }
    }


    override fun onCleared() {
        val controllerToRelease = mediaController
        mediaControllerPlaybackListener?.let { listener ->
            controllerToRelease?.removeListener(listener)
            mediaControllerPlaybackListener = null
        }
        playbackStateHolder.clearMediaController(controllerToRelease)
        controllerToRelease?.release()
        mediaController = null
        mediaControllerFuture.cancel(true)
        super.onCleared()
        stopProgressUpdates()
        playbackStateHolder.onCleared()
        listeningStatsTracker.onCleared()
        dailyMixStateHolder.onCleared()
        lyricsStateHolder.onCleared()
        themeStateHolder.onCleared()
        searchStateHolder.onCleared()
        libraryStateHolder.onCleared()
        sleepTimerStateHolder.onCleared()
        connectivityStateHolder.onCleared()
        queueUndoStateHolder.onCleared()
        playlistDismissUndoStateHolder.onCleared()
    }

    // Sleep Timer Control Functions - delegated to SleepTimerStateHolder
    fun setSleepTimer(durationMinutes: Int) {
        sleepTimerStateHolder.setSleepTimer(durationMinutes)
    }

    fun playCounted(count: Int) {
        sleepTimerStateHolder.playCounted(count)
    }

    fun cancelCountedPlay() {
        sleepTimerStateHolder.cancelCountedPlay()
    }

    fun setEndOfTrackTimer(enable: Boolean) {
        val currentSongId = stablePlayerState.value.currentSong?.id
        sleepTimerStateHolder.setEndOfTrackTimer(enable, currentSongId)
    }

    fun cancelSleepTimer(overrideToastMessage: String? = null, suppressDefaultToast: Boolean = false) {
        sleepTimerStateHolder.cancelSleepTimer(overrideToastMessage, suppressDefaultToast)
    }

    fun dismissPlaylistAndShowUndo() {
        setMiniPlayerDismissing(false)
        playlistDismissUndoStateHolder.dismissPlaylistAndShowUndo(
            scope = viewModelScope,
            currentSong = playbackStateHolder.stablePlayerState.value.currentSong,
            queue = _playerUiState.value.currentPlaybackQueue,
            queueName = _playerUiState.value.currentQueueSourceName,
            position = playbackStateHolder.currentPosition.value,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            disconnectRemoteIfNeeded = {},
            clearPlayback = {
                mediaController?.stop()
                mediaController?.clearMediaItems()
            },
            clearStablePlaybackState = {
                playbackStateHolder.updateStablePlayerState {
                    it.copy(
                        currentSong = null,
                        isPlaying = false,
                        playWhenReady = false,
                        totalDuration = 0L
                    )
                }
            },
            setCurrentPosition = { playbackStateHolder.setCurrentPosition(it) },
            setSheetVisible = { _isSheetVisible.value = it }
        )
    }

    fun hideDismissUndoBar() {
        playlistDismissUndoStateHolder.hideDismissUndoBar { mutation ->
            _playerUiState.update(mutation)
        }
    }

    fun undoDismissPlaylist() {
        setMiniPlayerDismissing(false)
        playlistDismissUndoStateHolder.undoDismissPlaylist(
            scope = viewModelScope,
            getUiState = { _playerUiState.value },
            updateUiState = { mutation -> _playerUiState.update(mutation) },
            playSongs = { songs, startSong, queueName ->
                playSongs(songs, startSong, queueName)
            },
            seekTo = { position -> mediaController?.seekTo(position) },
            setSheetVisible = { _isSheetVisible.value = it },
            setSheetCollapsed = { _sheetState.value = PlayerSheetState.COLLAPSED },
            emitToast = { message -> _toastEvents.emit(message) }
        )
    }


    fun saveLastLibraryTabIndex(tabIndex: Int) {
        viewModelScope.launch {
            userPreferencesRepository.saveLastLibraryTabIndex(tabIndex)
        }
    }

    fun showSortingSheet() {
        libraryTabsStateHolder.showSortingSheet(_isSortingSheetVisible)
    }

    fun hideSortingSheet() {
        libraryTabsStateHolder.hideSortingSheet(_isSortingSheetVisible)
    }

    fun onLibraryTabSelected(tabIndex: Int) {
        libraryTabsStateHolder.onLibraryTabSelected(
            tabIndex = tabIndex,
            libraryTabs = libraryTabsFlow.value,
            loadedTabs = _loadedTabs,
            currentLibraryTabId = _currentLibraryTabId,
            saveLastTabIndex = { index -> userPreferencesRepository.saveLastLibraryTabIndex(index) },
            scope = viewModelScope,
            loadSongs = { loadSongsIfNeeded() },
            loadAlbums = { loadAlbumsIfNeeded() },
            loadArtists = { loadArtistsIfNeeded() },
            loadFolders = { loadFoldersFromRepository() }
        )
    }

    fun saveLibraryTabsOrder(tabs: List<String>) {
        viewModelScope.launch {
            val orderJson = Json.encodeToString(tabs)
            userPreferencesRepository.saveLibraryTabsOrder(orderJson)
        }
    }

    fun resetLibraryTabsOrder() {
        viewModelScope.launch {
            userPreferencesRepository.resetLibraryTabsOrder()
        }
    }

    fun selectSongForInfo(song: Song) {
        _selectedSongForInfo.value = song
        viewModelScope.launch {
            val hydrated = withContext(Dispatchers.IO) {
                musicRepository.getSong(song.id).first()
            } ?: return@launch
            if (_selectedSongForInfo.value?.id == song.id) {
                _selectedSongForInfo.value = hydrated
            }
        }
    }

    private fun loadLyricsForCurrentSong() {
        val currentSong = playbackStateHolder.stablePlayerState.value.currentSong ?: return
        // Delegate to LyricsStateHolder
        lyricsStateHolder.loadLyricsForSong(currentSong, lyricsSourcePreference.value)
    }








    private fun invalidateCoverArtCaches(vararg uriStrings: String?) {
        imageCacheManager.invalidateCoverArtCaches(*uriStrings)
    }





    private fun updateSongInStates(
        updatedSong: Song,
        newLyrics: Lyrics? = null,
        isLoadingLyrics: Boolean? = null
    ) {
        // Update the queue first
        val currentQueue = _playerUiState.value.currentPlaybackQueue
        val updatedQueue = currentQueue.replaceSong(updatedSong)

        if (updatedQueue !== currentQueue) {
            _playerUiState.update { it.copy(currentPlaybackQueue = updatedQueue) }
        }

        // Then, update the stable state
        playbackStateHolder.updateStablePlayerState { state ->
            // Only update lyrics if they are explicitly passed
            val finalLyrics = newLyrics ?: state.lyrics
            state.copy(
                currentSong = updatedSong,
                lyrics = if (state.currentSong?.id == updatedSong.id) finalLyrics else state.lyrics,
                isLoadingLyrics = isLoadingLyrics ?: state.isLoadingLyrics
            )
        }
    }









    /**
     * Resets the lyrics search state to Idle.
     */
    fun resetLyricsSearchState() {
        lyricsStateHolder.resetSearchState()
    }


    fun playSong(song: Song) {
        viewModelScope.launch {
            val controller = mediaController ?: return@launch
            val mediaItem = buildResolvedPlaybackMediaItem(song)

            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()

            _isSheetVisible.value = true
            _sheetState.value = PlayerSheetState.EXPANDED
        }
    }









    fun addCustomGenre(genre: String, iconResId: Int? = null) {
        viewModelScope.launch {
            userPreferencesRepository.addCustomGenre(genre, iconResId)
        }
    }
}

internal fun Song.withRepositoryHydration(repositorySong: Song): Song {
    if (id != repositorySong.id) return this

    val hydratedArtworkUri = when {
        repositorySong.albumArtUriString.isNullOrBlank() -> albumArtUriString
        albumArtUriString.isNullOrBlank() -> repositorySong.albumArtUriString
        areEquivalentArtworkUrisForSong(id, albumArtUriString, repositorySong.albumArtUriString) ->
            albumArtUriString
        else -> repositorySong.albumArtUriString
    }

    return repositorySong.copy(
        contentUriString = repositorySong.contentUriString.ifBlank { contentUriString },
        albumArtUriString = hydratedArtworkUri,
        duration = repositorySong.duration.takeIf { it > 0L } ?: duration,
        lyrics = repositorySong.lyrics ?: lyrics
    )
}

internal fun areEquivalentArtworkUrisForSong(
    songId: String,
    firstUri: String?,
    secondUri: String?
): Boolean {
    if (firstUri == secondUri) return true
    if (firstUri.isNullOrBlank() || secondUri.isNullOrBlank()) return false

    val targetSongId = songId.toLongOrNull() ?: return false

    fun resolveUriSongId(uri: String): Long? {
        return LocalArtworkUri.parseSongId(uri)
            ?: SharedArtworkContentProvider.parseSongId(uri)
    }

    val firstSongId = resolveUriSongId(firstUri)
    val secondSongId = resolveUriSongId(secondUri)
    return firstSongId == targetSongId && secondSongId == targetSongId
}

internal fun Song.improvesLyricsLookupComparedTo(previousSong: Song): Boolean {
    return (previousSong.lyrics.isNullOrBlank() && !lyrics.isNullOrBlank()) ||
        (previousSong.path.isBlank() && path.isNotBlank()) ||
        (previousSong.contentUriString.isBlank() && contentUriString.isNotBlank())
}

internal fun parsePersistedLyrics(rawLyrics: String?): Lyrics? {
    val normalizedLyrics = rawLyrics?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parsedLyrics = LyricsUtils.parseLyrics(normalizedLyrics)
    return parsedLyrics.takeIf {
        !it.synced.isNullOrEmpty() || !it.plain.isNullOrEmpty()
    }
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.andymitch.dzr.presentation.screens

import com.andymitch.dzr.data.model.LibraryTabId
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.model.SortOption
import com.andymitch.dzr.presentation.viewmodel.StablePlayerState

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import com.andymitch.dzr.R
import com.andymitch.dzr.data.model.StorageFilter
import com.andymitch.dzr.presentation.components.ExpressiveScrollBar
import com.andymitch.dzr.presentation.components.MiniPlayerHeight
import com.andymitch.dzr.presentation.components.songFastScrollLabel
import com.andymitch.dzr.presentation.components.subcomps.EnhancedSongListItem
import com.andymitch.dzr.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryFavoritesTab(
    favoriteSongs: LazyPagingItems<Song>,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    onMoreOptionsClick: (Song) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isSelectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSongLongPress: (Song) -> Unit = {},
    onSongSelectionToggle: (Song) -> Unit = {},
    getSelectionIndex: (String) -> Int? = { null },
    sortOption: SortOption,
    onLocateCurrentSongVisibilityChanged: (Boolean) -> Unit = {},
    onRegisterLocateCurrentSongAction: ((() -> Unit)?) -> Unit = {},
    storageFilter: StorageFilter = StorageFilter.ALL,
    hasCurrentSong: Boolean = false,
    isSyncingLibrary: Boolean = false
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val visibilityCallback by rememberUpdatedState(onLocateCurrentSongVisibilityChanged)
    val registerActionCallback by rememberUpdatedState(onRegisterLocateCurrentSongAction)
    val favoriteFastScrollLabelProvider = remember(favoriteSongs, sortOption) {
        { index: Int ->
            songFastScrollLabel(
                song = favoriteSongs.peek(index),
                sortOption = sortOption
            )
        }
    }
    var lastHandledFavoriteSortKey by remember { mutableStateOf(sortOption.storageKey) }
    var pendingFavoriteSortScrollReset by remember { mutableStateOf(false) }
    var favoriteSortSawRefreshLoading by remember { mutableStateOf(false) }
    val currentSongId by remember(playerViewModel) {
        playerViewModel.stablePlayerState
            .map { it.currentSong?.id }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    val currentSongListIndex = remember(favoriteSongs.itemCount, currentSongId) {
        if (currentSongId == null) -1
        else {
            val items = favoriteSongs.itemSnapshotList
            items.indexOfFirst { it?.id == currentSongId }
        }
    }
    val locateCurrentSongAction: (() -> Unit)? = remember(currentSongId, sortOption, storageFilter) {
        if (currentSongId == null) {
            null
        } else {
            {
                playerViewModel.requestLocateCurrentFavoriteSong(sortOption, storageFilter)
            }
        }
    }
    // Scroll Handler from ViewModel
    LaunchedEffect(Unit) {
        playerViewModel.scrollToIndexEvent.collect { index ->
            if (index >= 0) {
                 val firstVisible = listState.firstVisibleItemIndex
                 if (Math.abs(index - firstVisible) > 20) {
                     listState.scrollToItem(index)
                 } else {
                     listState.animateScrollToItem(index)
                 }
            }
        }
    }

    LaunchedEffect(locateCurrentSongAction) {
        registerActionCallback(locateCurrentSongAction)
    }

    LaunchedEffect(sortOption) {
        val currentSortKey = sortOption.storageKey
        if (currentSortKey == lastHandledFavoriteSortKey) return@LaunchedEffect
        lastHandledFavoriteSortKey = currentSortKey
        pendingFavoriteSortScrollReset = true
        favoriteSortSawRefreshLoading = false
        listState.scrollToItem(0)
    }

    LaunchedEffect(favoriteSongs.loadState.refresh, pendingFavoriteSortScrollReset) {
        if (!pendingFavoriteSortScrollReset) return@LaunchedEffect
        if (favoriteSongs.loadState.refresh is LoadState.Loading) {
            favoriteSortSawRefreshLoading = true
            return@LaunchedEffect
        }
        if (!favoriteSortSawRefreshLoading) return@LaunchedEffect
        listState.scrollToItem(0)
        pendingFavoriteSortScrollReset = false
    }

    LaunchedEffect(currentSongListIndex, favoriteSongs.itemCount, listState, currentSongId) {
        if (currentSongId == null || favoriteSongs.itemCount == 0) {
            visibilityCallback(false)
            return@LaunchedEffect
        }

        if (currentSongListIndex == -1) {
             visibilityCallback(true)
             return@LaunchedEffect
        }

        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                false
            } else {
                currentSongListIndex in visibleItems.first().index..visibleItems.last().index
            }
        }
            .distinctUntilChanged()
            .collect { isVisible ->
                visibilityCallback(!isVisible)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            visibilityCallback(false)
            registerActionCallback(null)
        }
    }

    if (favoriteSongs.itemCount == 0 && favoriteSongs.loadState.refresh !is LoadState.Loading) {
        LibraryExpressiveEmptyState(
            tabId = LibraryTabId.LIKED,
            storageFilter = storageFilter,
            bottomBarHeight = bottomBarHeight,
            isSyncing = isSyncingLibrary
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val songsPullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                state = songsPullToRefreshState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = songsPullToRefreshState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(start = 12.dp, end = if (listState.canScrollForward || listState.canScrollBackward) 22.dp else 12.dp, bottom = 6.dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 26.dp,
                                    topEnd = 26.dp,
                                    bottomStart = PlayerSheetCollapsedCornerRadius,
                                    bottomEnd = PlayerSheetCollapsedCornerRadius
                                )
                            ),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + 30.dp)
                    ) {
                        items(
                            count = favoriteSongs.itemCount,
                            key = { index -> favoriteSongs.peek(index)?.id ?: index },
                            contentType = { "song" }
                        ) { index ->
                            val song = favoriteSongs[index]
                            if (song != null) {
                                LibraryPlaybackAwareSongItem(
                                    song = song,
                                    playerViewModel = playerViewModel,
                                    onMoreOptionsClick = { onMoreOptionsClick(song) },
                                    isSelected = selectedSongIds.contains(song.id),
                                    selectionIndex = if (isSelectionMode) getSelectionIndex(song.id) else null,
                                    isSelectionMode = isSelectionMode,
                                    onLongPress = { onSongLongPress(song) },
                                    onClick = {
                                        if (isSelectionMode) {
                                            onSongSelectionToggle(song)
                                        } else {
                                            playerViewModel.showAndPlaySongFromFavorites(song)
                                        }
                                    }
                                )
                            } else {
                                EnhancedSongListItem(
                                    song = Song.emptySong(),
                                    isPlaying = false,
                                    isLoading = true,
                                    isCurrentSong = false,
                                    onMoreOptionsClick = {},
                                    onClick = {}
                                )
                            }
                        }
                    }

                    val bottomPadding = if (hasCurrentSong)
                        bottomBarHeight + MiniPlayerHeight + 16.dp
                    else
                        bottomBarHeight + 16.dp

                    ExpressiveScrollBar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                        listState = listState,
                        dragLabelProvider = favoriteFastScrollLabelProvider
                    )
                }
            }
        }
    }
}


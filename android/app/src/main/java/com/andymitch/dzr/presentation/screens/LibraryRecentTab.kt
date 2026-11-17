package com.andymitch.dzr.presentation.screens

import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.presentation.viewmodel.LibraryViewModel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andymitch.dzr.presentation.components.subcomps.EnhancedSongListItem
import com.andymitch.dzr.presentation.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryRecentTab(
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    onMoreOptionsClick: (Song) -> Unit,
    libraryViewModel: LibraryViewModel = hiltViewModel()
) {
    val songs by produceState<List<Song>?>(null) { value = libraryViewModel.history() }
    val playerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val list = songs
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing played yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomBarHeight + 96.dp)
        ) {
            itemsIndexed(list, key = { _, song -> song.id }) { _, song ->
                EnhancedSongListItem(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    song = song,
                    isPlaying = playerState.isPlaying,
                    isCurrentSong = playerState.currentSong?.id == song.id,
                    onMoreOptionsClick = onMoreOptionsClick,
                    onClick = { playerViewModel.playSongs(list, song, queueName = "Recently Played") }
                )
            }
        }
    }
}

package com.andymitch.dzr.presentation.viewmodel

import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.model.Lyrics

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player

@Immutable
data class StablePlayerState(
    val currentSong: Song? = null,
    val currentMediaItemIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val totalDuration: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val isShuffleTransitionInProgress: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isLoadingLyrics: Boolean = false,
    val lyrics: Lyrics? = null,
    val isBuffering: Boolean = false
)

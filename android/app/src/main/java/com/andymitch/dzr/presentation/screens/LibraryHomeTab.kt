package com.andymitch.dzr.presentation.screens

import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.presentation.viewmodel.LibraryViewModel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.andymitch.dzr.data.network.deezer.DeezerTrack
import com.andymitch.dzr.data.network.deezer.DeezerPlaylist
import com.andymitch.dzr.presentation.viewmodel.PlayerViewModel
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.draw.clip




fun mapDeezerTrackToSong(track: DeezerTrack): Song {
    return Song(
        id = track.id,
        title = track.attributes?.title ?: "Unknown",
        artist = track.attributes?.artistName ?: "Unknown",
        artistId = track.attributes?.artistId ?: 0L,
        album = track.attributes?.albumName ?: "Unknown",
        albumId = track.attributes?.albumId ?: 0L,
        duration = (track.attributes?.duration ?: 0) * 1000L,
        contentUriString = "deezer://track/${track.id}",
        path = "deezer://track/${track.id}",
        mimeType = "audio/mpeg",
        albumArtUriString = track.attributes?.image?.medium ?: track.attributes?.image?.large,
        highResAlbumArtUriString = track.attributes?.image?.large ?: track.attributes?.image?.full,
        bitrate = 320,
        sampleRate = 44100,
        isExplicit = track.attributes?.explicitLyrics == true || track.attributes?.explicitContentLyrics == 1 || track.attributes?.explicit == true
    )
}

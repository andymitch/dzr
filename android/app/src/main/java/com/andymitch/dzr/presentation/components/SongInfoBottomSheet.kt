package com.andymitch.dzr.presentation.components

import com.andymitch.dzr.data.podcast.isEpisode
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.utils.shapes.RoundedStarShape
import com.andymitch.dzr.presentation.viewmodel.SongInfoBottomSheetViewModel
import com.andymitch.dzr.presentation.viewmodel.SongInfoBottomSheetViewModel.ToneTarget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.andymitch.dzr.R
import com.andymitch.dzr.presentation.components.subcomps.AutoSizingTextToFill
import com.andymitch.dzr.utils.formatDuration
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.andymitch.dzr.ui.theme.MontserratFamily
import kotlinx.coroutines.launch

import androidx.compose.ui.graphics.TransformOrigin
import com.andymitch.dzr.presentation.screens.TabAnimation
import com.andymitch.dzr.ui.theme.RoundedSans
import com.andymitch.dzr.utils.AudioMetaUtils
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun SongInfoBottomSheet(
    song: Song,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onPlaySong: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddNextToQueue: () -> Unit,
    onAddToPlayList: () -> Unit,
    onNavigateToAlbum: () -> Unit,
    onNavigateToArtist: () -> Unit,
    onNavigateToArtistById: (Long) -> Unit = { onNavigateToArtist() },
    removeFromListTrigger: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    songInfoViewModel: SongInfoBottomSheetViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var showArtistPicker by remember { mutableStateOf(false) }

    val evenCornerRadiusElems = 26.dp

    val albumArtShape = remember(evenCornerRadiusElems) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = evenCornerRadiusElems, smoothnessAsPercentBR = 60, cornerRadiusBR = evenCornerRadiusElems,
            smoothnessAsPercentTL = 60, cornerRadiusTL = evenCornerRadiusElems, smoothnessAsPercentBL = 60,
            cornerRadiusBL = evenCornerRadiusElems, smoothnessAsPercentTR = 60
        )
    }
    val playButtonShape = remember(evenCornerRadiusElems) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = evenCornerRadiusElems, smoothnessAsPercentBR = 60, cornerRadiusBR = evenCornerRadiusElems,
            smoothnessAsPercentTL = 60, cornerRadiusTL = evenCornerRadiusElems, smoothnessAsPercentBL = 60,
            cornerRadiusBL = evenCornerRadiusElems, smoothnessAsPercentTR = 60
        )
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true }
    )

    val favoriteButtonCornerRadius by animateDpAsState(
        targetValue = if (isFavorite) evenCornerRadiusElems else 60.dp,
        animationSpec = tween(durationMillis = 300), label = "FavoriteCornerAnimation"
    )
    val favoriteButtonContainerColor by animateColorAsState(
        targetValue = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 300), label = "FavoriteContainerColorAnimation"
    )
    val favoriteButtonContentColor by animateColorAsState(
        targetValue = if (isFavorite) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 300), label = "FavoriteContentColorAnimation"
    )
    val favoriteButtonShape = remember(favoriteButtonCornerRadius) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = favoriteButtonCornerRadius, smoothnessAsPercentBR = 60, cornerRadiusBR = favoriteButtonCornerRadius,
            smoothnessAsPercentTL = 60, cornerRadiusTL = favoriteButtonCornerRadius, smoothnessAsPercentBL = 60,
            cornerRadiusBL = favoriteButtonCornerRadius, smoothnessAsPercentTR = 60
        )
    }
    val infoSegmentContainerShape = remember {
        RoundedCornerShape(20.dp)
    }
    val infoSegmentItemShape = remember {
        RoundedCornerShape(8.dp)
    }

    val audioMeta by songInfoViewModel.audioMeta.collectAsStateWithLifecycle()
    val resolvedArtists by songInfoViewModel.resolvedArtists.collectAsStateWithLifecycle()
    val audioMetaLabel = remember(audioMeta) {
        val meta = audioMeta ?: return@remember null
        val formatLabel = AudioMetaUtils.mimeTypeToFormat(meta.mimeType)
            .takeIf { it != "-" }
            ?.uppercase(java.util.Locale.getDefault())
        val parts = buildList {
            meta.sampleRate?.takeIf { it > 0 }
                ?.let { add(String.format(java.util.Locale.US, "%.1f kHz", it / 1000.0)) }
            meta.bitrate?.takeIf { it > 0 }
                ?.let { add("${it / 1000} kbps") }
            formatLabel?.let { add(it) }
        }
        parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    val activity = androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity
    val playerViewModel: com.andymitch.dzr.presentation.viewmodel.PlayerViewModel = hiltViewModel(activity)
    val playingSongId by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val isCurrentSong = playingSongId == song.id

    LaunchedEffect(song.id) {
        songInfoViewModel.loadAudioMeta(song)
        songInfoViewModel.loadArtistsForSong(song)
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // HERE WE APPLY THE FIX: Null out the overscroll factory for everything inside here
        CompositionLocalProvider(
            LocalOverscrollFactory provides null
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                    ) {
                        // Row for the album art and the title (Always visible)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmartImage(
                                model = song.albumArtUriString,
                                contentDescription = stringResource(R.string.widget_album_art),
                                shape = albumArtShape,
                                modifier = Modifier.size(80.dp),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                AutoSizingTextToFill(
                                    modifier = Modifier.padding(end = 4.dp),
                                    fontWeight = FontWeight.Light,
                                    text = song.title
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!isCurrentSong) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    MediumExtendedFloatingActionButton(
                                        modifier = Modifier
                                            .weight(0.5f)
                                            .fillMaxHeight(),
                                        onClick = onPlaySong,
                                        elevation = FloatingActionButtonDefaults.elevation(0.dp),
                                        shape = playButtonShape,
                                        icon = {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.cd_play_song_action))
                                        },
                                        text = {
                                            Text(
                                                modifier = Modifier.padding(end = 10.dp),
                                                text = stringResource(R.string.play_playback)
                                            )
                                        }
                                    )

                                    FilledIconButton(
                                        modifier = Modifier
                                            .weight(0.25f)
                                            .fillMaxHeight(),
                                        onClick = onToggleFavorite,
                                        shape = favoriteButtonShape,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = favoriteButtonContainerColor,
                                            contentColor = favoriteButtonContentColor
                                        )
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(FloatingActionButtonDefaults.LargeIconSize),
                                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            contentDescription = stringResource(
                                                if (isFavorite) R.string.cd_remove_from_favorites else R.string.cd_add_to_favorites
                                            )
                                        )
                                    }
                                }
                            }
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    FilledTonalButton(
                                        modifier = Modifier
                                            .weight(0.6f)
                                            .heightIn(min = 66.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        ),
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                        shape = CircleShape,
                                        onClick = onAddToQueue
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.QueueMusic,
                                            contentDescription = stringResource(R.string.cd_add_to_queue)
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Text(stringResource(R.string.action_add_to_queue))
                                    }
                                    FilledTonalButton(
                                        modifier = Modifier
                                            .weight(0.4f)
                                            .heightIn(min = 66.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                            contentColor = MaterialTheme.colorScheme.onTertiary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                        shape = CircleShape,
                                        onClick = onAddNextToQueue
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.QueueMusic,
                                            contentDescription = stringResource(R.string.cd_play_next_in_queue)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.action_queue_next))
                                    }
                                }
                            }
                        }
                        if (onRemoveFromPlaylist != null) {
                            item {
                                FilledTonalButton(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp),
                                    contentPadding = PaddingValues(horizontal = 0.dp),
                                    shape = CircleShape,
                                    onClick = {
                                        onRemoveFromPlaylist()
                                        onDismiss()
                                    }
                                ) {
                                    Icon(painterResource(R.drawable.rounded_delete_24), contentDescription = null)
                                    Spacer(Modifier.width(14.dp))
                                    Text("Remove from playlist")
                                }
                            }
                        }
                        if (!song.isEpisode) item {
                            val scope = androidx.compose.runtime.rememberCoroutineScope()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (song.contentUriString.startsWith("deezer://")) {
                                    FilledTonalButton(
                                        modifier = Modifier.weight(1f).heightIn(min = 66.dp),
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                        shape = CircleShape,
                                        onClick = {
                                            scope.launch {
                                                val mix = songInfoViewModel.trackMix(song)
                                                mix.firstOrNull()?.let {
                                                    playerViewModel.playSongs(mix, it, queueName = "${song.title} Mix")
                                                    onDismiss()
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(painterResource(R.drawable.rounded_all_inclusive_24), contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Track Mix")
                                    }
                                }
                                FilledTonalButton(
                                    modifier = Modifier.weight(1f).heightIn(min = 66.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 0.dp),
                                    shape = CircleShape,
                                    onClick = onAddToPlayList
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.PlaylistAdd,
                                        contentDescription = stringResource(R.string.cd_add_to_playlist)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.shortcut_playlist_short))
                                }
                            }
                        }
                        if (song.contentUriString.startsWith("deezer://")) {
                            item {
                                val scope = androidx.compose.runtime.rememberCoroutineScope()
                                FilledTonalButton(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                    contentPadding = PaddingValues(horizontal = 0.dp),
                                    shape = CircleShape,
                                    onClick = {
                                        scope.launch {
                                            val banned = songInfoViewModel.banSong(song)
                                            if (banned) {
                                                playerViewModel.removeSongFromQueue(song.id)
                                                onDismiss()
                                            }
                                            android.widget.Toast.makeText(context, if (banned) "dzr won't play \"${song.title}\" again" else "Couldn't update Deezer", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Block, contentDescription = null)
                                    Spacer(Modifier.width(14.dp))
                                    Text("Don't play this song")
                                }
                            }
                            item {
                                val isCasting by songInfoViewModel.isCasting.collectAsStateWithLifecycle()
                                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                    FilledTonalButton(
                                        modifier = Modifier.matchParentSize(),
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                        shape = CircleShape,
                                        onClick = {}
                                    ) {
                                        Icon(Icons.Rounded.Cast, contentDescription = null)
                                        Spacer(Modifier.width(14.dp))
                                        Text(if (isCasting) "Casting" else "Cast to a device")
                                    }
                                    androidx.media3.cast.MediaRouteButton(
                                        modifier = Modifier.matchParentSize().clip(CircleShape).graphicsLayer { alpha = 0f }
                                    )
                                }
                            }
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(infoSegmentContainerShape),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SongInfoSegmentedListItem(
                                    headline = stringResource(R.string.song_info_label_duration),
                                    supporting = formatDuration(song.duration),
                                    icon = Icons.Rounded.Schedule,
                                    iconDescription = stringResource(R.string.cd_duration_icon),
                                    shape = infoSegmentItemShape,
                                )

                                if (!song.genre.isNullOrEmpty()) {
                                    SongInfoSegmentedListItem(
                                        headline = stringResource(R.string.song_field_genre),
                                        supporting = song.genre,
                                        icon = Icons.Rounded.MusicNote,
                                        iconDescription = stringResource(R.string.cd_genre_icon),
                                        shape = infoSegmentItemShape,
                                    )
                                }

                                SongInfoSegmentedListItem(
                                    headline = stringResource(R.string.song_field_album),
                                    supporting = song.album,
                                    icon = Icons.Rounded.Album,
                                    iconDescription = stringResource(R.string.cd_album_icon),
                                    shape = infoSegmentItemShape,
                                    onClick = onNavigateToAlbum,
                                )

                                SongInfoSegmentedListItem(
                                    headline = stringResource(R.string.song_field_artist),
                                    supporting = song.displayArtist,
                                    icon = Icons.Rounded.Person,
                                    iconDescription = stringResource(R.string.cd_artist_icon),
                                    shape = infoSegmentItemShape,
                                    onClick = {
                                        if (song.artists.size > 1) {
                                            showArtistPicker = true
                                        } else {
                                            onNavigateToArtist()
                                        }
                                    },
                                )

                                if (!audioMetaLabel.isNullOrEmpty()) {
                                    SongInfoSegmentedListItem(
                                        headline = stringResource(R.string.song_info_label_song_metadata),
                                        supporting = audioMetaLabel,
                                        icon = Icons.Rounded.Info,
                                        iconDescription = stringResource(R.string.cd_audio_format_icon),
                                        shape = infoSegmentItemShape,
                                    )
                                }

                            }
                        }
                        }
                    }
                }
            }
        }
    }

    val artistPickerSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (showArtistPicker && resolvedArtists.isNotEmpty()) {
        com.andymitch.dzr.presentation.components.player.PlayerArtistPickerBottomSheet(
            song = song,
            artists = resolvedArtists,
            sheetState = artistPickerSheetState,
            onDismiss = { showArtistPicker = false },
            onArtistClick = { artist ->
                showArtistPicker = false
                onNavigateToArtistById(artist.id)
            }
        )
    }
}






private val ToneTarget.titleResId: Int
    get() = when (this) {
        ToneTarget.Ringtone -> R.string.song_info_tone_ringtone_title
        ToneTarget.Notification -> R.string.song_info_tone_notification_title
        ToneTarget.Alarm -> R.string.song_info_tone_alarm_title
    }

private val ToneTarget.subtitleResId: Int
    get() = when (this) {
        ToneTarget.Ringtone -> R.string.song_info_tone_ringtone_subtitle
        ToneTarget.Notification -> R.string.song_info_tone_notification_subtitle
        ToneTarget.Alarm -> R.string.song_info_tone_alarm_subtitle
    }

private val ToneTarget.confirmLabelResId: Int
    get() = when (this) {
        ToneTarget.Ringtone -> R.string.song_info_tone_ringtone_label
        ToneTarget.Notification -> R.string.song_info_tone_notification_label
        ToneTarget.Alarm -> R.string.song_info_tone_alarm_label
    }

@Composable
private fun SongInfoSegmentedListItem(
    headline: String,
    supporting: String,
    icon: ImageVector,
    iconDescription: String,
    shape: Shape,
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .let { baseModifier ->
            if (onClick != null) {
                baseModifier.clickable(onClick = onClick)
            } else {
                baseModifier
            }
        }

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(headline) },
            supportingContent = { Text(supporting) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = iconDescription,
                )
            }
        )
    }
}

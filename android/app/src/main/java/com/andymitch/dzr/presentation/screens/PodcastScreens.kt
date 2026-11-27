package com.andymitch.dzr.presentation.screens

import android.text.format.DateUtils
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.andymitch.dzr.data.podcast.Episode
import com.andymitch.dzr.data.podcast.Podcast
import com.andymitch.dzr.data.podcast.PodcastRepository
import com.andymitch.dzr.presentation.components.MiniPlayerHeight
import com.andymitch.dzr.presentation.navigation.Screen
import com.andymitch.dzr.presentation.navigation.navigateSafely
import com.andymitch.dzr.presentation.viewmodel.PlayerViewModel
import com.andymitch.dzr.presentation.viewmodel.PodcastViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastDetailScreen(
    feedUrl: String,
    playerViewModel: PlayerViewModel,
    onBackClick: () -> Unit,
    podcastViewModel: PodcastViewModel = hiltViewModel(),
) {
    val shows by podcastViewModel.shows.collectAsStateWithLifecycle()
    val progress by podcastViewModel.progress.collectAsStateWithLifecycle()
    val playerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    var podcast by remember(feedUrl) { mutableStateOf<Podcast?>(null) }
    var failed by remember(feedUrl) { mutableStateOf(false) }
    LaunchedEffect(feedUrl) {
        podcast = podcastViewModel.load(feedUrl)
        podcast = podcastViewModel.refresh(feedUrl) ?: podcast
        failed = podcast == null
    }
    val subscribed = shows.any { it.feedUrl == feedUrl }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    FilledTonalIconButton(
                        modifier = Modifier.padding(start = 10.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = onBackClick
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        val show = podcast
        if (show == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                if (failed) Text("Couldn't load this podcast", color = MaterialTheme.colorScheme.onSurfaceVariant) else LoadingIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), start = 16.dp, end = 16.dp, bottom = MiniPlayerHeight + 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    AsyncImage(
                        model = show.artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(show.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                        if (show.author.isNotBlank()) {
                            Text(show.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.basicMarquee())
                        }
                        Spacer(Modifier.padding(top = 8.dp))
                        if (subscribed) {
                            FilledTonalButton(onClick = { podcastViewModel.unsubscribe(feedUrl) }) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Subscribed")
                            }
                        } else {
                            Button(onClick = { podcastViewModel.subscribe(show) }) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Subscribe")
                            }
                        }
                    }
                }
            }
            if (show.episodes.isEmpty()) {
                item(key = "empty") {
                    Text("No episodes yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(show.episodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    progress = progress[episode.id],
                    isCurrent = playerState.currentSong?.id == episode.id,
                    isPlaying = playerState.isPlaying,
                    onClick = {
                        if (playerState.currentSong?.id == episode.id) playerViewModel.playPause()
                        else podcastViewModel.play(playerViewModel, show, episode)
                    }
                )
            }
        }
    }
}

@Composable
fun EpisodeRow(
    episode: Episode,
    progress: Long?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    showTitle: String? = null,
) {
    val played = progress == PodcastRepository.PLAYED
    val position = progress?.takeIf { it > 0 } ?: 0L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (showTitle != null) {
                Text(showTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.basicMarquee())
            }
            Text(
                episode.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (played && !isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
            Text(
                episodeDetails(episode, position, played),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (position > 0 && episode.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (position.toFloat() / episode.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.5f).padding(top = 6.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        FilledTonalIconButton(onClick = onClick) {
            Icon(if (isCurrent && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isCurrent && isPlaying) "Pause" else "Play")
        }
    }
}

private fun episodeDetails(episode: Episode, position: Long, played: Boolean): String = listOfNotNull(
    episode.publishedMs.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString() },
    when {
        played -> "Played"
        position > 0 && episode.durationMs > position -> "${formatMinutes(episode.durationMs - position)} left"
        episode.durationMs > 0 -> formatMinutes(episode.durationMs)
        else -> null
    }
).joinToString(" · ")

private fun formatMinutes(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

@Composable
fun PodcastShowRow(podcast: Podcast, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = podcast.artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(podcast.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
            Text(
                podcast.author.ifBlank { "Podcast" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryPodcastsTab(
    navController: NavController,
    bottomBarHeight: Dp,
    podcastViewModel: PodcastViewModel = hiltViewModel(),
) {
    val shows by podcastViewModel.shows.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomBarHeight + 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "add") {
            FilledTonalButton(onClick = { showAddDialog = true }, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(Icons.Rounded.RssFeed, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add by RSS link")
            }
        }
        if (shows.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Podcasts, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(
                        "Search for a podcast to subscribe, or add one by its RSS link.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        items(shows.sortedBy { it.title.lowercase() }, key = { it.feedUrl }) { show ->
            PodcastShowRow(show, onClick = { navController.navigateSafely(Screen.PodcastDetail.createRoute(show.feedUrl)) })
        }
    }
    if (showAddDialog) {
        AddPodcastLinkDialog(
            podcastViewModel = podcastViewModel,
            onDismiss = { showAddDialog = false },
            onAdded = { podcast ->
                showAddDialog = false
                navController.navigateSafely(Screen.PodcastDetail.createRoute(podcast.feedUrl))
            }
        )
    }
}

@Composable
private fun AddPodcastLinkDialog(
    podcastViewModel: PodcastViewModel,
    onDismiss: () -> Unit,
    onAdded: (Podcast) -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a podcast") },
        text = {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; failed = false },
                label = { Text("RSS link") },
                singleLine = true,
                isError = failed,
                supportingText = if (failed) {
                    { Text("Couldn't read a podcast from that link") }
                } else null
            )
        },
        confirmButton = {
            TextButton(
                enabled = link.isNotBlank() && !adding,
                onClick = {
                    adding = true
                    scope.launch {
                        val podcast = podcastViewModel.addByLink(link)
                        adding = false
                        if (podcast != null) onAdded(podcast) else failed = true
                    }
                }
            ) { Text(if (adding) "Adding…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

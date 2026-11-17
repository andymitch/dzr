package com.andymitch.dzr.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andymitch.dzr.data.download.deezerTrackId
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.presentation.viewmodel.DownloadsViewModel

@Composable
fun DownloadButton(
    collectionId: String,
    title: String,
    songs: List<Song>?,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(LocalContext.current as ComponentActivity),
) {
    val index by viewModel.index.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val downloaded = index.collections.any { it.id == collectionId }
    val running = progress[collectionId]
    var confirmRemove by remember { mutableStateOf(false) }
    val trackIds = songs?.mapNotNull { it.deezerTrackId }

    if (songs != null && trackIds.isNullOrEmpty()) return

    LaunchedEffect(trackIds, downloaded) {
        if (!downloaded || running != null) return@LaunchedEffect
        if (songs == null) viewModel.downloadLiked() else viewModel.download(collectionId, title, songs)
    }

    FilledTonalIconButton(
        modifier = modifier,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        ),
        onClick = {
            when {
                running != null -> Unit
                downloaded -> confirmRemove = true
                songs == null -> viewModel.downloadLiked()
                else -> viewModel.download(collectionId, title, songs)
            }
        }
    ) {
        if (running != null) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { running.first.toFloat() / running.second.coerceAtLeast(1) },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp
                )
            }
        } else {
            Icon(
                if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                contentDescription = if (downloaded) "Downloaded" else "Download"
            )
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove download?") },
            text = { Text("$title will need a connection to play again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.remove(collectionId)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

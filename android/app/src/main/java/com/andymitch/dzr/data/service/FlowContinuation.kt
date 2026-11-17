package com.andymitch.dzr.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FlowContinuation @Inject constructor() {
    private var fetch: (suspend () -> List<MediaItem>)? = null
    private var songIds = emptySet<String>()
    private var loading = false

    fun start(items: List<MediaItem>, fetch: suspend () -> List<MediaItem>) {
        this.fetch = fetch
        songIds = items.map { it.mediaId }.toSet()
    }

    fun stop() {
        fetch = null
        songIds = emptySet()
    }

    fun extendIfNeeded(player: Player, scope: CoroutineScope) {
        val fetch = fetch ?: return
        if (loading || player.currentMediaItem?.mediaId !in songIds) return
        if (player.currentMediaItemIndex < player.mediaItemCount - 3) return
        loading = true
        scope.launch {
            try {
                val queued = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                var more = emptyList<MediaItem>()
                repeat(3) {
                    if (more.isEmpty()) more = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrDefault(emptyList()) }.filter { it.mediaId !in queued }
                }
                if (this@FlowContinuation.fetch === fetch && more.isNotEmpty()) {
                    songIds = songIds + more.map { it.mediaId }
                    player.addMediaItems(more)
                }
            } finally {
                loading = false
            }
        }
    }
}

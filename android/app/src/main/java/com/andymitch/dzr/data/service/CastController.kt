package com.andymitch.dzr.data.service

import android.content.Context
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.andymitch.dzr.data.preferences.DeezerAudioQuality
import com.andymitch.dzr.data.preferences.UserPreferencesRepository
import com.andymitch.dzr.data.repository.DeezerRepository
import com.google.android.gms.cast.MediaQueueItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class CastController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deezerRepository: DeezerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    private val streams = ConcurrentHashMap<String, Pair<String, String>>()
    private val originals = ConcurrentHashMap<String, MediaItem>()

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    var remote: RemoteCastPlayer? = null
        private set

    private val converter = object : MediaItemConverter {
        private val default = DefaultMediaItemConverter()

        override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
            val id = trackId(mediaItem) ?: return default.toMediaQueueItem(mediaItem)
            val (url, mime) = streams[id] ?: runBlocking(Dispatchers.IO) { resolve(id) } ?: return default.toMediaQueueItem(mediaItem)
            originals[url] = mediaItem
            return default.toMediaQueueItem(mediaItem.buildUpon().setUri(url).setMimeType(mime).build())
        }

        override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
            val item = default.toMediaItem(mediaQueueItem)
            return originals[item.localConfiguration?.uri.toString()] ?: item
        }
    }

    fun start(onAvailable: (RemoteCastPlayer) -> Unit, onUnavailable: (RemoteCastPlayer) -> Unit) {
        val player = runCatching { RemoteCastPlayer.Builder(context).setMediaItemConverter(converter).build() }.getOrNull() ?: return
        remote = player
        player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = onAvailable(player)
            override fun onCastSessionUnavailable() = onUnavailable(player)
        })
    }

    fun setCasting(casting: Boolean) {
        _isCasting.value = casting
        if (!casting) {
            streams.clear()
            originals.clear()
        }
    }

    fun release() {
        remote?.setSessionAvailabilityListener(null)
        remote?.release()
        remote = null
    }

    suspend fun warm(items: List<MediaItem>) = coroutineScope {
        val permits = Semaphore(8)
        items.mapNotNull(::trackId).distinct().filter { !streams.containsKey(it) }
            .map { id -> async(Dispatchers.IO) { permits.withPermit { resolve(id) } } }
            .awaitAll()
        Unit
    }

    private suspend fun resolve(id: String): Pair<String, String>? {
        val quality = userPreferencesRepository.deezerAudioQualityFlow.first()
        val (url, q) = deezerRepository.streamUrl(id.toLongOrNull() ?: return null, quality) ?: return null
        return (url to if (q == DeezerAudioQuality.HIFI) "audio/flac" else "audio/mpeg").also { streams[id] = it }
    }

    private fun trackId(item: MediaItem): String? =
        item.localConfiguration?.uri?.takeIf { it.scheme == "deezer" && it.host == "track" }?.pathSegments?.firstOrNull()
            ?: item.mediaId.removePrefix("deezer_").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

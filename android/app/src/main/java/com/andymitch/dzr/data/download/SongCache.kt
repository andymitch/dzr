package com.andymitch.dzr.data.download

import android.content.Context
import com.andymitch.dzr.data.preferences.DeezerAudioQuality
import com.andymitch.dzr.data.preferences.UserPreferencesRepository
import com.andymitch.dzr.data.repository.DeezerRepository
import com.andymitch.dzr.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongCache @Inject constructor(
    @ApplicationContext context: Context,
    private val deezerRepository: DeezerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val downloads: DownloadStore,
    @AppScope private val scope: CoroutineScope,
) {
    private val dir = File(context.cacheDir, "songs")
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private var job: Job? = null

    val ahead = MutableStateFlow(prefs.getInt("cache_ahead", 5))

    init {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach(File::delete)
    }

    fun setAhead(count: Int) {
        ahead.value = count.coerceIn(MIN_AHEAD, MAX_AHEAD)
        prefs.edit().putInt("cache_ahead", ahead.value).apply()
    }

    fun localFile(trackId: String): File? =
        dir.listFiles { f -> f.name.substringBefore('.') == trackId && !f.name.endsWith(".part") }
            ?.firstOrNull()
            ?.also { it.setLastModified(System.currentTimeMillis()) }

    fun bytesUsed(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    fun clear() {
        job?.cancel()
        dir.deleteRecursively()
    }

    fun prefetch(upcomingTrackIds: List<String>) {
        val wanted = upcomingTrackIds.distinct().take(ahead.value)
            .filter { downloads.localFile(it) == null && localFile(it) == null }
        if (wanted.isEmpty()) return
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val quality = userPreferencesRepository.deezerAudioQualityFlow.first()
            for (id in wanted) {
                if (!isActive) break
                val (url, q) = deezerRepository.streamUrl(id.toLongOrNull() ?: continue, quality) ?: continue
                downloadTo(url, File(dir, "$id.${if (q == DeezerAudioQuality.HIFI) "flac" else "mp3"}"))
                trim()
            }
        }
    }

    private fun trim() {
        val files = dir.listFiles { f -> !f.name.endsWith(".part") }.orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        files.forEach { f ->
            total += f.length()
            if (total > MAX_BYTES) f.delete()
        }
    }

    companion object {
        const val MIN_AHEAD = 2
        const val MAX_AHEAD = 10
        private const val MAX_BYTES = 1L shl 30
    }
}

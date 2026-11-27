package com.andymitch.dzr.data.podcast

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastRepository @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val file = File(context.filesDir, "podcasts.json")
    private val prefs = context.getSharedPreferences("podcasts", Context.MODE_PRIVATE)

    private val _index = MutableStateFlow(
        runCatching { gson.fromJson(file.readText(), PodcastIndex::class.java) }.getOrNull() ?: PodcastIndex()
    )
    val index: StateFlow<PodcastIndex> = _index.asStateFlow()

    private val progressPrefs = context.getSharedPreferences("podcast_progress", Context.MODE_PRIVATE)
    private val _progress = MutableStateFlow(progressPrefs.all.mapNotNull { (id, value) -> (value as? Long)?.let { id to it } }.toMap())
    val progress: StateFlow<Map<String, Long>> = _progress.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat("speed", 1f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _cutSilence = MutableStateFlow(prefs.getBoolean("cut_silence", false))
    val cutSilence: StateFlow<Boolean> = _cutSilence.asStateFlow()

    fun setSpeed(speed: Float) {
        _speed.value = speed
        prefs.edit().putFloat("speed", speed).apply()
    }

    fun setCutSilence(enabled: Boolean) {
        _cutSilence.value = enabled
        prefs.edit().putBoolean("cut_silence", enabled).apply()
    }

    suspend fun search(term: String): List<Podcast> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("limit", "25")
            .addQueryParameter("term", term)
            .build()
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                JsonParser.parseString(response.body.string()).asJsonObject.getAsJsonArray("results").mapNotNull { element ->
                    val o = element.asJsonObject
                    val feed = o.get("feedUrl")?.asString ?: return@mapNotNull null
                    Podcast(
                        feedUrl = feed,
                        title = o.get("collectionName")?.asString ?: feed,
                        author = o.get("artistName")?.asString ?: "",
                        artwork = (o.get("artworkUrl600") ?: o.get("artworkUrl100"))?.asString,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetch(feedUrl: String): Podcast? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(feedUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseFeed(feedUrl, response.body.byteStream())
            }
        }.getOrNull()
    }

    fun show(feedUrl: String): Podcast? = _index.value.shows.firstOrNull { it.feedUrl == feedUrl }

    fun subscribe(podcast: Podcast) {
        _index.update { it.copy(shows = it.shows.filter { show -> show.feedUrl != podcast.feedUrl } + podcast) }
        save()
    }

    fun unsubscribe(feedUrl: String) {
        _index.update { it.copy(shows = it.shows.filter { show -> show.feedUrl != feedUrl }) }
        save()
    }

    private var refreshedAt = 0L

    suspend fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - refreshedAt < REFRESH_EVERY_MS) return
        refreshedAt = now
        refresh()
    }

    suspend fun refresh() = coroutineScope {
        val fresh = _index.value.shows.map { show -> async { fetch(show.feedUrl) } }.awaitAll().filterNotNull().associateBy { it.feedUrl }
        if (fresh.isEmpty()) return@coroutineScope
        _index.update { index -> index.copy(shows = index.shows.map { fresh[it.feedUrl] ?: it }) }
        save()
    }

    fun position(episodeId: String): Long = _progress.value[episodeId]?.takeIf { it > 0 } ?: 0L

    fun savePosition(episodeId: String, positionMs: Long, durationMs: Long) {
        if (durationMs > 0 && positionMs >= durationMs - FINISHED_WITHIN_MS) return markPlayed(episodeId)
        if (positionMs < 1_000 || _progress.value[episodeId] == positionMs) return
        setProgress(episodeId, positionMs)
    }

    fun markPlayed(episodeId: String) = setProgress(episodeId, PLAYED)

    private fun setProgress(episodeId: String, value: Long) {
        _progress.update { it + (episodeId to value) }
        progressPrefs.edit().putLong(episodeId, value).apply()
    }

    @Synchronized
    private fun save() {
        val snapshot = _index.value
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(gson.toJson(snapshot))
            temp.renameTo(file)
        }
    }

    companion object {
        const val FINISHED_WITHIN_MS = 30_000L
        const val PLAYED = -1L
        private const val REFRESH_EVERY_MS = 30 * 60_000L
    }
}

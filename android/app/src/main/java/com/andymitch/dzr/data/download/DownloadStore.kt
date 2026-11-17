package com.andymitch.dzr.data.download

import android.content.Context
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.preferences.DeezerAudioQuality
import com.andymitch.dzr.data.repository.DeezerRepository
import com.andymitch.dzr.di.AppScope
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val art: String?,
    val duration: Long,
    val file: String,
    val quality: DeezerAudioQuality,
) {
    fun toSong(dir: File) = Song(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        path = File(dir, file).path,
        contentUriString = "deezer://track/$id",
        albumArtUriString = art,
        duration = duration,
        mimeType = "audio/mpeg",
        bitrate = null,
        sampleRate = null,
    )
}

val Song.deezerTrackId: String?
    get() = contentUriString.removePrefix("deezer://track/").substringBefore('?').takeIf { contentUriString.startsWith("deezer://track/") && it.all(Char::isDigit) }

fun Song.toDownloadedTrack(id: String) = DownloadedTrack(
    id = id,
    title = title,
    artist = displayArtist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    art = highResAlbumArtUriString ?: albumArtUriString,
    duration = duration,
    file = "",
    quality = DeezerAudioQuality.HIGH,
)

fun downloadTo(url: String, dest: File): Boolean {
    val temp = File(dest.parentFile, "${dest.name}.part")
    return runCatching {
        dest.parentFile?.mkdirs()
        URL(url).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }.getInputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
        temp.renameTo(dest)
    }.getOrDefault(false).also { if (!it) temp.delete() }
}

data class DownloadedCollection(val id: String, val title: String, val trackIds: List<String>)

data class DownloadIndex(
    val tracks: Map<String, DownloadedTrack> = emptyMap(),
    val collections: List<DownloadedCollection> = emptyList(),
    val pending: Map<String, DownloadedTrack>? = emptyMap(),
) {
    val wanted: Set<String>
        get() = collections.flatMap { it.trackIds }.toSet()

    fun progress(): Map<String, Pair<Int, Int>> =
        collections.filter { c -> c.trackIds.any { it in pending.orEmpty() } }
            .associate { c -> c.id to (c.trackIds.count { it in tracks } to c.trackIds.size) }

    fun upsert(collection: DownloadedCollection): Pair<DownloadIndex, List<String>> {
        val previous = collections.firstOrNull { it.id == collection.id }?.trackIds.orEmpty()
        val updated = copy(collections = collections.filter { it.id != collection.id } + collection)
        val kept = updated.collections.flatMap { it.trackIds }.toSet()
        return updated to previous.filter { it !in kept && it in tracks }
    }

    fun remove(collectionId: String): Pair<DownloadIndex, List<String>> {
        val removed = collections.firstOrNull { it.id == collectionId } ?: return this to emptyList()
        val remaining = collections.filter { it.id != collectionId }
        val kept = remaining.flatMap { it.trackIds }.toSet()
        val orphans = removed.trackIds.filter { it !in kept && it in tracks }
        return copy(collections = remaining, tracks = tracks - orphans.toSet()) to orphans
    }

    val orderedTrackIds: List<String>
        get() = collections.flatMap { it.trackIds }.distinct().filter { it in tracks }
}

@Singleton
class DownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deezerRepository: DeezerRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val gson = Gson()
    private val dir = File(context.filesDir, "downloads")
    private val indexFile = File(dir, "index.json")
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    private val _index = MutableStateFlow(
        runCatching { gson.fromJson(indexFile.readText(), DownloadIndex::class.java) }.getOrNull() ?: DownloadIndex()
    )
    val index: StateFlow<DownloadIndex> = _index.asStateFlow()

    val progress: StateFlow<Map<String, Pair<Int, Int>>> = _index.map { it.progress() }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val wifiOnly = MutableStateFlow(prefs.getBoolean("wifi_only", true))

    init {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach(File::delete)
    }

    private val _quality = MutableStateFlow(
        runCatching { DeezerAudioQuality.valueOf(prefs.getString("quality", null)!!) }.getOrDefault(DeezerAudioQuality.HIGH)
    )
    val quality: StateFlow<DeezerAudioQuality> = _quality.asStateFlow()

    fun setQuality(quality: DeezerAudioQuality) {
        _quality.value = quality
        prefs.edit().putString("quality", quality.name).apply()
    }

    fun localFile(trackId: String): File? =
        _index.value.tracks[trackId]?.let { File(dir, it.file) }?.takeIf { it.exists() }

    fun songs(): List<Song> = _index.value.let { index -> index.orderedTrackIds.mapNotNull { index.tracks[it]?.toSong(dir) } }

    fun bytesUsed(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    fun download(collectionId: String, title: String, songs: List<Song>) {
        val deezerSongs = songs.filter { it.deezerTrackId != null }.distinctBy { it.deezerTrackId }
        val ids = deezerSongs.mapNotNull { it.deezerTrackId }
        val (updated, orphans) = _index.value.upsert(DownloadedCollection(collectionId, title, ids))
        val newPending = deezerSongs.filter { it.deezerTrackId !in updated.tracks }.associate { it.deezerTrackId!! to it.toDownloadedTrack(it.deezerTrackId!!) }
        _index.value = updated.copy(tracks = updated.tracks - orphans.toSet(), pending = updated.pending.orEmpty() + newPending)
        deleteFiles(orphans)
        save()
        if (newPending.isNotEmpty()) DownloadWorker.enqueue(context, wifiOnly.value)
    }

    suspend fun runPending(onProgress: suspend (done: Int, total: Int) -> Unit) = withContext(Dispatchers.IO) {
        val permits = Semaphore(3)
        while (true) {
            val wanted = _index.value.wanted
            val batch = _index.value.pending.orEmpty().values.filter { it.id in wanted }
            _index.update { it.copy(pending = it.pending.orEmpty().filterKeys { id -> id in wanted }) }
            if (batch.isEmpty()) break
            val done = java.util.concurrent.atomic.AtomicInteger()
            batch.map { track ->
                async {
                    permits.withPermit { fetch(track) }
                    _index.update { it.copy(pending = it.pending.orEmpty() - track.id) }
                    save()
                    onProgress(done.incrementAndGet(), batch.size)
                }
            }.awaitAll()
            if (_index.value.pending.orEmpty().keys.none { it in _index.value.wanted }) break
        }
        save()
    }

    fun setWifiOnly(enabled: Boolean) {
        wifiOnly.value = enabled
        prefs.edit().putBoolean("wifi_only", enabled).apply()
        if (_index.value.pending.orEmpty().isNotEmpty()) DownloadWorker.enqueue(context, enabled, replace = true)
    }

    fun remove(collectionId: String) {
        val (updated, orphans) = _index.value.remove(collectionId)
        _index.value = updated
        deleteFiles(orphans)
        save()
    }

    fun removeAll() {
        DownloadWorker.cancel(context)
        dir.deleteRecursively()
        _index.value = DownloadIndex()
    }

    private suspend fun fetch(track: DownloadedTrack) {
        val (url, quality) = deezerRepository.streamUrl(track.id.toLong(), _quality.value) ?: return
        val file = "${track.id}.${if (quality == DeezerAudioQuality.HIFI) "flac" else "mp3"}"
        if (downloadTo(url, File(dir, file))) {
            _index.update { it.copy(tracks = it.tracks + (track.id to track.copy(file = file, quality = quality))) }
        }
    }

    private fun deleteFiles(ids: List<String>) {
        ids.forEach { id -> dir.listFiles { f -> f.name.substringBefore('.') == id }.orEmpty().forEach(File::delete) }
    }

    private fun save() {
        dir.mkdirs()
        runCatching { indexFile.writeText(gson.toJson(_index.value)) }
    }
}

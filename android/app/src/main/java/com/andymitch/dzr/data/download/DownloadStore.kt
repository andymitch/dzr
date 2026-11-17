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

data class DownloadedCollection(val id: String, val title: String, val trackIds: List<String>)

data class DownloadIndex(
    val tracks: Map<String, DownloadedTrack> = emptyMap(),
    val collections: List<DownloadedCollection> = emptyList(),
) {
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
    @ApplicationContext context: Context,
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

    private val _progress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val progress: StateFlow<Map<String, Pair<Int, Int>>> = _progress.asStateFlow()

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
        _index.value = updated.copy(tracks = updated.tracks - orphans.toSet())
        deleteFiles(orphans)
        save()
        val missing = deezerSongs.filter { localFile(it.deezerTrackId!!) == null }
        if (missing.isEmpty() || collectionId in _progress.value) return
        _progress.update { it + (collectionId to (0 to missing.size)) }
        scope.launch(Dispatchers.IO) {
            val permits = Semaphore(3)
            missing.map { song ->
                async {
                    permits.withPermit { fetch(song) }
                    _progress.update { p -> p[collectionId]?.let { (done, total) -> p + (collectionId to (done + 1 to total)) } ?: p }
                }
            }.awaitAll()
            _progress.update { it - collectionId }
            save()
        }
    }

    fun remove(collectionId: String) {
        val (updated, orphans) = _index.value.remove(collectionId)
        _index.value = updated
        deleteFiles(orphans)
        save()
    }

    fun removeAll() {
        dir.deleteRecursively()
        _index.value = DownloadIndex()
        _progress.value = emptyMap()
    }

    private suspend fun fetch(song: Song) {
        val id = song.deezerTrackId ?: return
        if (_index.value.collections.none { id in it.trackIds }) return
        val trackId = id.toLong()
        val (url, quality) = deezerRepository.streamUrl(trackId, _quality.value) ?: return
        val file = "$id.${if (quality == DeezerAudioQuality.HIFI) "flac" else "mp3"}"
        val dest = File(dir, file)
        val temp = File(dir, "$file.part")
        runCatching {
            dir.mkdirs()
            URL(url).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }.getInputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(dest)) error("rename failed")
            val track = DownloadedTrack(
                id = id,
                title = song.title,
                artist = song.displayArtist,
                artistId = song.artistId,
                album = song.album,
                albumId = song.albumId,
                art = song.highResAlbumArtUriString ?: song.albumArtUriString,
                duration = song.duration,
                file = file,
                quality = quality,
            )
            _index.update { it.copy(tracks = it.tracks + (id to track)) }
        }.onFailure { temp.delete() }
    }

    private fun deleteFiles(ids: List<String>) {
        ids.forEach { id -> dir.listFiles { f -> f.name.substringBefore('.') == id }.orEmpty().forEach(File::delete) }
    }

    private fun save() {
        dir.mkdirs()
        runCatching { indexFile.writeText(gson.toJson(_index.value)) }
    }
}

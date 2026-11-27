package com.andymitch.dzr.data.podcast

import com.andymitch.dzr.data.model.Song
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.SAXParserFactory

const val EPISODE_ID_PREFIX = "episode:"

data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artwork: String?,
    val episodes: List<Episode> = emptyList(),
)

data class Episode(
    val id: String,
    val title: String,
    val audioUrl: String,
    val mimeType: String?,
    val publishedMs: Long,
    val durationMs: Long,
    val artwork: String?,
)

data class PodcastIndex(val shows: List<Podcast> = emptyList())

val Song.isEpisode: Boolean get() = id.startsWith(EPISODE_ID_PREFIX)

fun isEpisodeId(mediaId: String?): Boolean = mediaId?.startsWith(EPISODE_ID_PREFIX) == true

fun Episode.toSong(podcast: Podcast) = Song(
    id = id,
    title = title,
    artist = podcast.title,
    artistId = -1L,
    album = podcast.title,
    albumId = -1L,
    path = audioUrl,
    contentUriString = audioUrl,
    albumArtUriString = artwork ?: podcast.artwork,
    highResAlbumArtUriString = artwork ?: podcast.artwork,
    duration = durationMs,
    dateAdded = publishedMs,
    mimeType = mimeType ?: "audio/mpeg",
    bitrate = null,
    sampleRate = null,
)

fun parseFeed(feedUrl: String, input: InputStream): Podcast? {
    val handler = FeedHandler(feedUrl)
    val factory = SAXParserFactory.newInstance()
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.newSAXParser().parse(input, handler) }.onFailure { return null }
    val title = handler.show["title"] ?: return null
    return Podcast(
        feedUrl = feedUrl,
        title = title,
        author = handler.show["itunes:author"] ?: "",
        artwork = handler.show["itunes:image"] ?: handler.show["url"],
        episodes = handler.episodes.sortedByDescending { it.publishedMs }.take(MAX_EPISODES),
    )
}

private const val MAX_EPISODES = 500

private class FeedHandler(private val feedUrl: String) : DefaultHandler() {
    val show = mutableMapOf<String, String>()
    val episodes = mutableListOf<Episode>()
    private var item: MutableMap<String, String>? = null
    private var inImage = false
    private val text = StringBuilder()

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
        text.setLength(0)
        val fields = item ?: show
        when (qName) {
            "item" -> item = mutableMapOf()
            "image" -> inImage = true
            "itunes:image" -> attributes.getValue("href")?.takeIf { it.isNotBlank() }?.let { fields.putIfAbsent("itunes:image", it.trim()) }
            "enclosure" -> item?.let { episode ->
                attributes.getValue("url")?.let { episode["enclosure"] = it.trim() }
                attributes.getValue("type")?.let { episode["type"] = it.trim() }
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val value = text.toString().trim()
        text.setLength(0)
        val episode = item
        when {
            qName == "item" -> {
                item = null
                episode?.toEpisode()?.let(episodes::add)
            }
            qName == "image" -> inImage = false
            value.isEmpty() -> Unit
            episode != null -> episode.putIfAbsent(qName, value)
            inImage -> if (qName == "url") show.putIfAbsent("url", value)
            else -> show.putIfAbsent(qName, value)
        }
    }

    private fun Map<String, String>.toEpisode(): Episode? {
        val url = this["enclosure"]?.ifBlank { null } ?: return null
        return Episode(
            id = EPISODE_ID_PREFIX + UUID.nameUUIDFromBytes("$feedUrl|${this["guid"] ?: url}".toByteArray()),
            title = this["title"] ?: "Episode",
            audioUrl = url,
            mimeType = this["type"]?.ifBlank { null },
            publishedMs = this["pubDate"]?.let(::parseRssDate) ?: 0L,
            durationMs = this["itunes:duration"]?.let(::parseDuration) ?: 0L,
            artwork = this["itunes:image"],
        )
    }
}

internal fun parseDuration(value: String): Long {
    val parts = value.trim().split(':').map { it.toDoubleOrNull() ?: return 0L }
    return (parts.fold(0.0) { total, part -> total * 60 + part } * 1000).toLong()
}

internal fun parseRssDate(value: String): Long? =
    listOf("EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z")
        .firstNotNullOfOrNull { pattern -> runCatching { SimpleDateFormat(pattern, Locale.US).parse(value.trim())?.time }.getOrNull() }

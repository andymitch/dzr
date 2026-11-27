package com.andymitch.dzr.data.podcast

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PodcastFeedTest {
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Indie Show</title>
            <itunes:author>Someone</itunes:author>
            <itunes:image href="https://example.com/show.jpg"/>
            <item>
              <title>Older</title>
              <guid>ep-1</guid>
              <pubDate>Mon, 03 Nov 2025 10:00:00 +0000</pubDate>
              <itunes:duration>45:30</itunes:duration>
              <enclosure url="https://example.com/1.mp3" type="audio/mpeg" length="1"/>
            </item>
            <item>
              <title>No audio</title>
              <guid>ep-x</guid>
            </item>
            <item>
              <title>Newer</title>
              <pubDate>Tue, 4 Nov 2025 10:00:00 GMT</pubDate>
              <itunes:duration>3600</itunes:duration>
              <itunes:image href="https://example.com/2.jpg"/>
              <enclosure url="https://example.com/2.m4a" type="audio/x-m4a"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private fun parse() = parseFeed("https://example.com/feed", feed.byteInputStream())!!

    @Test
    fun readsTheShowAndItsEpisodesNewestFirst() {
        val podcast = parse()

        assertThat(podcast.title).isEqualTo("Indie Show")
        assertThat(podcast.author).isEqualTo("Someone")
        assertThat(podcast.artwork).isEqualTo("https://example.com/show.jpg")
        assertThat(podcast.episodes.map { it.title }).containsExactly("Newer", "Older").inOrder()
        assertThat(podcast.episodes[0].artwork).isEqualTo("https://example.com/2.jpg")
        assertThat(podcast.episodes[0].durationMs).isEqualTo(3_600_000L)
        assertThat(podcast.episodes[1].durationMs).isEqualTo(2_730_000L)
        assertThat(podcast.episodes[1].mimeType).isEqualTo("audio/mpeg")
    }

    @Test
    fun episodeIdsAreStableAndMarkedAsEpisodes() {
        val first = parse().episodes
        val second = parse().episodes

        assertThat(first.map { it.id }).isEqualTo(second.map { it.id })
        assertThat(first.all { isEpisodeId(it.id) }).isTrue()
        assertThat(first.map { it.id }.distinct()).hasSize(2)
    }

    @Test
    fun durationsInEveryItunesFormat() {
        assertThat(parseDuration("1:02:03")).isEqualTo(3_723_000L)
        assertThat(parseDuration("90")).isEqualTo(90_000L)
        assertThat(parseDuration("nope")).isEqualTo(0L)
    }

    @Test
    fun rejectsSomethingThatIsNotAFeed() {
        assertThat(parseFeed("https://example.com", "<html><body/></html>".byteInputStream())).isNull()
    }
}

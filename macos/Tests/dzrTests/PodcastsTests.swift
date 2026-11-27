import Foundation
import Testing
@testable import dzr

@Suite struct PodcastsTests {
    let feed = URL(string: "https://example.com/feed")!
    let xml = """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
      <channel>
        <title>Indie Show</title>
        <itunes:author>Someone</itunes:author>
        <image><url>https://example.com/fallback.jpg</url><title>Not the show title</title></image>
        <itunes:image href="https://example.com/show.jpg"/>
        <item>
          <title>Older</title>
          <guid>ep-1</guid>
          <pubDate>Mon, 03 Nov 2025 10:00:00 +0000</pubDate>
          <itunes:duration>45:30</itunes:duration>
          <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
        </item>
        <item><title>No audio</title></item>
        <item>
          <title><![CDATA[Newer & better]]></title>
          <pubDate>Tue, 4 Nov 2025 10:00:00 GMT</pubDate>
          <itunes:duration>3600</itunes:duration>
          <enclosure url="https://example.com/2.m4a" type="audio/x-m4a"/>
        </item>
      </channel>
    </rss>
    """

    @Test func readsTheShowAndItsEpisodesNewestFirst() throws {
        let podcast = try #require(parseFeed(feed, Data(xml.utf8)))
        #expect(podcast.title == "Indie Show")
        #expect(podcast.author == "Someone")
        #expect(podcast.artwork?.absoluteString == "https://example.com/show.jpg")
        #expect(podcast.episodes.map(\.title) == ["Newer & better", "Older"])
        #expect(podcast.episodes.map(\.duration) == [3600, 2730])
        #expect(podcast.episodes.allSatisfy { $0.kind == .episode && $0.subtitle == "Indie Show" && $0.artwork == podcast.artwork })
        #expect(podcast.episodes.last?.url?.absoluteString == "https://example.com/1.mp3")
    }

    @Test func episodeIdsAreStable() throws {
        let first = try #require(parseFeed(feed, Data(xml.utf8))).episodes.map(\.id)
        let second = try #require(parseFeed(feed, Data(xml.utf8))).episodes.map(\.id)
        #expect(first == second)
        #expect(Set(first).count == 2)
    }

    @Test func durationsInEveryItunesFormat() {
        #expect(parseDuration("1:02:03") == 3723)
        #expect(parseDuration("90") == 90)
        #expect(parseDuration("nope") == 0)
    }

    @Test func rejectsSomethingThatIsNotAFeed() {
        #expect(parseFeed(feed, Data("<html><body/></html>".utf8)) == nil)
    }
}

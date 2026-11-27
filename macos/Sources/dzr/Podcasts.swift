import AVFoundation
import CryptoKit
import MediaToolbox
import Foundation
import Observation

struct Podcast: Codable, Equatable {
    var feed: URL
    var title: String
    var author: String
    var artwork: URL?
    var episodes: [Item] = []

    var item: Item { Item(kind: .podcast, id: feed.absoluteString, title: title, subtitle: author, artwork: artwork) }
}

@MainActor @Observable
final class Podcasts {
    static let played: Double = -1
    private static let finishedWithin: Double = 30
    private static let refreshEvery: TimeInterval = 30 * 60

    private(set) var shows: [Podcast] = []
    private(set) var progress: [String: Double]
    var speed: Double { didSet { UserDefaults.standard.set(speed, forKey: "podcastSpeed") } }
    var cutSilence: Bool { didSet { UserDefaults.standard.set(cutSilence, forKey: "podcastCutSilence") } }
    private var refreshedAt = Date.distantPast

    private static var url: URL { Session.url.deletingLastPathComponent().appendingPathComponent("podcasts.json") }

    init() {
        let defaults = UserDefaults.standard
        progress = defaults.dictionary(forKey: "podcastProgress") as? [String: Double] ?? [:]
        speed = defaults.object(forKey: "podcastSpeed") as? Double ?? 1
        cutSilence = defaults.bool(forKey: "podcastCutSilence")
        if let data = try? Data(contentsOf: Self.url), let saved = try? JSONDecoder().decode([Podcast].self, from: data) {
            shows = saved
        }
    }

    var newEpisodes: [Item] {
        shows.flatMap { $0.episodes.prefix(3) }
            .filter { progress[$0.id] != Self.played }
            .sorted { ($0.published ?? .distantPast) > ($1.published ?? .distantPast) }
            .prefix(20).map { $0 }
    }

    func show(_ feed: URL) -> Podcast? { shows.first { $0.feed == feed } }

    func search(_ term: String) async -> [Item] {
        var components = URLComponents(string: "https://itunes.apple.com/search")!
        components.queryItems = [.init(name: "media", value: "podcast"), .init(name: "entity", value: "podcast"), .init(name: "limit", value: "25"), .init(name: "term", value: term)]
        struct R: Decodable { var results: [P]; struct P: Decodable { var collectionName: String?; var artistName: String?; var artworkUrl600: String?; var feedUrl: String? } }
        guard let (data, _) = try? await URLSession.shared.data(from: components.url!), let r = try? JSONDecoder().decode(R.self, from: data) else { return [] }
        return r.results.compactMap { p in
            guard let feed = p.feedUrl.flatMap(URL.init(string:)) else { return nil }
            return Podcast(feed: feed, title: p.collectionName ?? feed.absoluteString, author: p.artistName ?? "", artwork: p.artworkUrl600.flatMap(URL.init(string:))).item
        }
    }

    func fetch(_ feed: URL) async -> Podcast? {
        guard let (data, response) = try? await URLSession.shared.data(from: feed), (response as? HTTPURLResponse)?.statusCode ?? 200 < 300 else { return nil }
        return parseFeed(feed, data)
    }

    func load(_ feed: URL) async -> Podcast? {
        if let fresh = await fetch(feed) {
            if show(feed) != nil { subscribe(fresh) }
            return fresh
        }
        return show(feed)
    }

    func subscribe(_ podcast: Podcast) {
        if let i = shows.firstIndex(where: { $0.feed == podcast.feed }) { shows[i] = podcast } else { shows.append(podcast) }
        save()
    }

    func unsubscribe(_ feed: URL) {
        shows.removeAll { $0.feed == feed }
        save()
    }

    func refreshIfStale() async {
        guard Date().timeIntervalSince(refreshedAt) > Self.refreshEvery else { return }
        refreshedAt = Date()
        for show in shows {
            if let fresh = await fetch(show.feed) { subscribe(fresh) }
        }
    }

    func position(_ id: String) -> Double { max(progress[id] ?? 0, 0) }

    func savePosition(_ id: String, _ position: Double, duration: Double) {
        if duration > 0, position >= duration - Self.finishedWithin { return markPlayed(id) }
        guard position >= 1, abs((progress[id] ?? 0) - position) >= 1 else { return }
        setProgress(id, position)
    }

    func markPlayed(_ id: String) { setProgress(id, Self.played) }

    private func setProgress(_ id: String, _ value: Double) {
        progress[id] = value
        UserDefaults.standard.set(progress, forKey: "podcastProgress")
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(shows) else { return }
        try? FileManager.default.createDirectory(at: Self.url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: Self.url, options: .atomic)
    }
}

func parseFeed(_ feed: URL, _ data: Data) -> Podcast? {
    let handler = FeedParser(feed: feed)
    let parser = XMLParser(data: data)
    parser.shouldResolveExternalEntities = false
    parser.delegate = handler
    guard parser.parse(), let title = handler.show["title"] else { return nil }
    let artwork = (handler.show["itunes:image"] ?? handler.show["url"]).flatMap(URL.init(string:))
    let episodes = handler.episodes.map { fields -> Item in
        var item = episodeItem(feed: feed, fields: fields, show: title)
        if item.artwork == nil { item.artwork = artwork }
        return item
    }
    .sorted { ($0.published ?? .distantPast) > ($1.published ?? .distantPast) }
    return Podcast(feed: feed, title: title, author: handler.show["itunes:author"] ?? "", artwork: artwork, episodes: Array(episodes.prefix(500)))
}

private func episodeItem(feed: URL, fields: [String: String], show: String) -> Item {
    let key = "\(feed.absoluteString)|\(fields["guid"] ?? fields["enclosure"] ?? "")"
    let id = "episode:" + Insecure.MD5.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
    var item = Item(kind: .episode, id: id, title: fields["title"] ?? "Episode", subtitle: show, duration: parseDuration(fields["itunes:duration"] ?? ""))
    item.url = fields["enclosure"].flatMap(URL.init(string:))
    item.artwork = fields["itunes:image"].flatMap(URL.init(string:))
    item.published = fields["pubDate"].flatMap(parseRSSDate)
    return item
}

func parseDuration(_ value: String) -> TimeInterval {
    value.trimmingCharacters(in: .whitespaces).split(separator: ":").reduce(0.0) { total, part in
        guard let n = Double(part) else { return .nan }
        return total * 60 + n
    }.nanToZero
}

private extension Double {
    var nanToZero: Double { isNaN ? 0 : self }
}

private func parseRSSDate(_ value: String) -> Date? {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    for format in ["EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z"] {
        f.dateFormat = format
        if let d = f.date(from: value.trimmingCharacters(in: .whitespacesAndNewlines)) { return d }
    }
    return nil
}

private final class FeedParser: NSObject, XMLParserDelegate {
    let feed: URL
    var show: [String: String] = [:]
    var episodes: [[String: String]] = []
    private var item: [String: String]?
    private var inImage = false
    private var text = ""

    init(feed: URL) { self.feed = feed }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        text = ""
        switch name {
        case "item": item = [:]
        case "image": inImage = true
        case "itunes:image":
            if let href = attributes["href"], !href.isEmpty {
                if var current = item {
                    current["itunes:image"] = current["itunes:image"] ?? href
                    item = current
                } else if show["itunes:image"] == nil {
                    show["itunes:image"] = href
                }
            }
        case "enclosure":
            if item != nil, let url = attributes["url"] { item?["enclosure"] = url }
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, foundCDATA block: Data) { text += String(decoding: block, as: UTF8.self) }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        text = ""
        if name == "item" {
            if let item, item["enclosure"] != nil { episodes.append(item) }
            item = nil
        } else if name == "image" {
            inImage = false
        } else if value.isEmpty {
            return
        } else if item != nil {
            if item?[name] == nil { item?[name] = value }
        } else if inImage {
            if name == "url", show["url"] == nil { show["url"] = value }
        } else if show[name] == nil {
            show[name] = value
        }
    }
}

enum SilenceDetector {
    private static let threshold: Float = 0.01
    private static let minimumSilence = 0.4

    private final class State: @unchecked Sendable {
        let onChange: @MainActor (Bool) -> Void
        var sampleRate = 0.0
        var silentFrames = 0
        var silent = false

        init(onChange: @escaping @MainActor (Bool) -> Void) { self.onChange = onChange }

        func update(peak: Float, frames: Int) {
            guard sampleRate > 0 else { return }
            silentFrames = peak < SilenceDetector.threshold ? silentFrames + frames : 0
            let now = Double(silentFrames) >= sampleRate * SilenceDetector.minimumSilence
            guard now != silent else { return }
            silent = now
            let onChange = onChange
            DispatchQueue.main.async { MainActor.assumeIsolated { onChange(now) } }
        }
    }

    static func attach(to item: AVPlayerItem, onChange: @escaping @MainActor (Bool) -> Void) async {
        guard let track = try? await item.asset.loadTracks(withMediaType: .audio).first else { return }
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: Unmanaged.passRetained(State(onChange: onChange)).toOpaque(),
            init: { _, clientInfo, storage in storage.pointee = clientInfo },
            finalize: { tap in Unmanaged<State>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release() },
            prepare: { tap, _, format in
                let f = format.pointee
                let isFloat = f.mFormatFlags & kAudioFormatFlagIsFloat != 0 && f.mBitsPerChannel == 32
                Unmanaged<State>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue().sampleRate = isFloat ? f.mSampleRate : 0
            },
            unprepare: nil,
            process: { tap, frames, _, buffers, framesOut, flagsOut in
                guard MTAudioProcessingTapGetSourceAudio(tap, frames, buffers, flagsOut, nil, framesOut) == noErr else { return }
                var peak: Float = 0
                for buffer in UnsafeMutableAudioBufferListPointer(buffers) {
                    guard let data = buffer.mData else { continue }
                    let samples = UnsafeBufferPointer(start: data.assumingMemoryBound(to: Float.self), count: Int(buffer.mDataByteSize) / MemoryLayout<Float>.size)
                    for sample in samples { peak = max(peak, abs(sample)) }
                }
                Unmanaged<State>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue().update(peak: peak, frames: Int(framesOut.pointee))
            }
        )
        var tap: MTAudioProcessingTap?
        guard MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap) == noErr, let tap else { return }
        let parameters = AVMutableAudioMixInputParameters(track: track)
        parameters.audioTapProcessor = tap
        let mix = AVMutableAudioMix()
        mix.inputParameters = [parameters]
        await MainActor.run { item.audioMix = mix }
    }
}

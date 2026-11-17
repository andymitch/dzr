import AVFoundation
import AVKit
import AppKit
import MediaPlayer
import Observation

@MainActor @Observable
final class Player {
    private let deezer: Deezer
    private let downloads: Downloads
    private let cache: SongCache
    private let av = AVQueuePlayer()
    private var timeObserver: Any?
    private var itemObserver: NSKeyValueObservation?
    private var terminateObserver: NSObjectProtocol?
    private var loadTask: Task<Void, Never>?
    private var prepareTask: Task<Void, Never>?
    private var prepared: (index: Int, item: AVPlayerItem, quality: Quality)?
    private var fetchingMore = false
    private var flacFailed = false
    private var lastSave = Date.distantPast

    private(set) var queue = PlayQueue()
    var isPlaying = false
    var position: TimeInterval = 0
    var duration: TimeInterval = 0
    var quality = Quality.flac { didSet { flacFailed = false; save() } }
    var playingQuality: Quality?
    var error: String?
    var onUnauthorized: () -> Void = {}
    var sleepEnd: Date?
    var sleepAtEndOfSong = false
    private var sleepTask: Task<Void, Never>?
    private var endObserver: NSObjectProtocol?

    var current: Item? { queue.current }
    var upNext: ArraySlice<Item> { queue.upNext }
    var index: Int { queue.index }
    var repeatMode: Repeat { queue.repeatMode }
    var shuffle: Bool {
        get { queue.shuffle }
        set { queue.shuffle = newValue }
    }

    private var streamQuality: Quality { flacFailed && quality == .flac ? .high : quality }

    init(deezer: Deezer, downloads: Downloads, cache: SongCache) {
        self.deezer = deezer
        self.downloads = downloads
        self.cache = cache
        av.actionAtItemEnd = .advance
        restore()
        timeObserver = av.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t) }
        }
        itemObserver = av.observe(\.currentItem) { [weak self] _, _ in
            Task { @MainActor in self?.currentItemChanged() }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.songEnded() }
        }
        terminateObserver = NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.save() }
        }
        setupRemote()
        updateNowPlaying()
    }

    func play(_ tracks: [Item], at i: Int, from source: Item? = nil) {
        guard queue.play(tracks, at: i, from: source) else { return }
        load()
    }

    func jump(to i: Int) {
        guard queue.tracks.indices.contains(i) else { return }
        queue.index = i
        load()
    }

    func playNext(_ t: Item) {
        guard current != nil else { return play([t], at: 0) }
        queue.playNext(t)
        queueChanged()
    }

    func enqueue(_ t: Item) {
        queue.enqueue(t)
        if queue.tracks.count == 1 { load() } else { queueChanged() }
    }

    func moveUpNext(from: IndexSet, to: Int) {
        queue.moveUpNext(from: from, to: to)
        queueChanged()
    }

    func removeUpNext(at offsets: IndexSet) {
        queue.removeUpNext(at: offsets)
        queueChanged()
    }

    func toggleShuffle() {
        queue.toggleShuffle()
        queueChanged()
    }

    func cycleRepeat() {
        queue.cycleRepeat()
        queueChanged()
    }

    func toggle() {
        guard current != nil else { return }
        if av.currentItem == nil {
            load(startAt: position)
            return
        }
        isPlaying ? av.pause() : av.play()
        isPlaying.toggle()
        updateNowPlaying()
        save()
    }

    func next() {
        guard let n = queue.nextIndex(auto: false) else { return }
        queue.index = n
        load()
    }

    func previous() {
        if position > 3 { return seek(to: 0) }
        guard let n = queue.previousIndex() else { return }
        queue.index = n
        load()
    }

    func seek(to t: TimeInterval) {
        av.seek(to: CMTime(seconds: max(0, t), preferredTimescale: 600))
        position = t
        updateNowPlaying()
    }

    func attach(_ picker: AVRoutePickerView) {
        picker.player = av
    }

    func remove(_ track: Item) {
        if current?.id == track.id {
            guard let n = queue.nextIndex(auto: false) else {
                av.pause()
                isPlaying = false
                updateNowPlaying()
                return
            }
            queue.index = n
            load()
        }
        queue.removeOthers(track.id)
        queueChanged()
    }

    func setSleepTimer(minutes: Int?) {
        sleepTask?.cancel()
        sleepEnd = nil
        sleepAtEndOfSong = false
        av.actionAtItemEnd = .advance
        if av.currentItem != nil { prepareNext() }
        guard let minutes else { return }
        sleepEnd = Date().addingTimeInterval(TimeInterval(minutes * 60))
        sleepTask = Task {
            try? await Task.sleep(for: .seconds(minutes * 60))
            guard !Task.isCancelled else { return }
            sleepEnd = nil
            if isPlaying { toggle() }
        }
    }

    func sleepAfterCurrentSong() {
        setSleepTimer(minutes: nil)
        sleepAtEndOfSong = true
        dropPrepared()
        av.actionAtItemEnd = .pause
    }

    private func songEnded() {
        guard sleepAtEndOfSong else { return }
        sleepAtEndOfSong = false
        av.actionAtItemEnd = .advance
        isPlaying = false
        av.removeAllItems()
        if let n = queue.nextIndex(auto: true) { queue.index = n }
        position = 0
        duration = current?.duration ?? 0
        updateNowPlaying()
        save()
    }

    func save() {
        let saved = Saved(queue: queue, position: position, quality: quality)
        guard let data = try? JSONEncoder().encode(saved) else { return }
        try? FileManager.default.createDirectory(at: Self.savedURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: Self.savedURL, options: .atomic)
        lastSave = Date()
    }

    private struct Saved: Codable {
        var queue: PlayQueue
        var position: TimeInterval
        var quality: Quality
    }

    private static var savedURL: URL { Session.url.deletingLastPathComponent().appendingPathComponent("player.json") }

    private func restore() {
        guard let data = try? Data(contentsOf: Self.savedURL), let saved = try? JSONDecoder().decode(Saved.self, from: data) else { return }
        queue = saved.queue
        quality = saved.quality
        position = saved.position
        duration = current?.duration ?? 0
    }

    private func queueChanged() {
        save()
        if av.currentItem != nil { prepareNext() }
    }

    private func load(startAt: TimeInterval = 0) {
        guard let t = current else { return }
        loadTask?.cancel()
        dropPrepared()
        error = nil
        position = startAt
        duration = t.duration
        updateNowPlaying()
        save()
        loadTask = Task {
            do {
                let (url, q) = try await source(for: t)
                guard !Task.isCancelled else { return }
                let item = AVPlayerItem(url: url)
                av.removeAllItems()
                av.insert(item, after: nil)
                if startAt > 0 { await av.seek(to: CMTime(seconds: startAt, preferredTimescale: 600)) }
                guard !Task.isCancelled else { return }
                av.play()
                isPlaying = true
                playingQuality = q
                updateNowPlaying()
                prepareNext()
            } catch DeezerError.unauthorized {
                onUnauthorized()
            } catch {
                guard !Task.isCancelled else { return }
                if error is URLError, let n = queue.tracks.indices.first(where: { $0 > queue.index && downloads.entry(queue.tracks[$0].id) != nil }) {
                    queue.index = n
                    load()
                    return
                }
                self.error = error.localizedDescription
            }
        }
    }

    private func source(for track: Item) async throws -> (URL, Quality) {
        if let local = downloads.localURL(track.id) ?? cache.localURL(track.id) { return local }
        return try await deezer.streamURL(track.id, quality: streamQuality)
    }

    private func cacheAhead() {
        var upcoming = Array(queue.upNext)
        if queue.repeatMode == .all { upcoming += queue.tracks.prefix(queue.index) }
        cache.prefetch(upcoming, quality: streamQuality) { [downloads] in downloads.entry($0) != nil }
    }

    private func dropPrepared() {
        prepareTask?.cancel()
        if let p = prepared { av.remove(p.item) }
        prepared = nil
    }

    private func prepareNext() {
        dropPrepared()
        cacheAhead()
        if sleepAtEndOfSong { return }
        if queue.needsMore { fetchMore() }
        guard let n = queue.nextIndex(auto: true) else { return }
        let t = queue.tracks[n]
        prepareTask = Task {
            guard let (url, q) = try? await source(for: t), !Task.isCancelled,
                  queue.tracks.indices.contains(n), queue.tracks[n].id == t.id,
                  let cur = av.currentItem, av.items().count == 1 else { return }
            let item = AVPlayerItem(url: url)
            av.insert(item, after: cur)
            prepared = (n, item, q)
        }
    }

    private func fetchMore() {
        guard !fetchingMore, let source = queue.source, source.kind == .flow else { return }
        fetchingMore = true
        Task {
            defer { fetchingMore = false }
            let more = (try? await deezer.flowTracks(source.id)) ?? []
            guard queue.source == source, queue.append(more) > 0 else { return }
            save()
            if prepared == nil, av.currentItem != nil { prepareNext() }
        }
    }

    private func currentItemChanged() {
        guard loadTask != nil else { return }
        if let p = prepared, av.currentItem === p.item {
            prepared = nil
            queue.index = p.index
            position = 0
            duration = queue.tracks[p.index].duration
            playingQuality = p.quality
            updateNowPlaying()
            save()
            prepareNext()
        } else if av.currentItem == nil, isPlaying {
            guard let n = queue.nextIndex(auto: true) else {
                isPlaying = false
                updateNowPlaying()
                save()
                return
            }
            queue.index = n
            load()
        }
    }

    private func tick(_ t: CMTime) {
        guard av.currentItem != nil else { return }
        position = t.seconds.isFinite ? t.seconds : 0
        if let d = av.currentItem?.duration.seconds, d.isFinite, d > 0 { duration = d }
        if av.currentItem?.status == .failed, playingQuality == .flac {
            playingQuality = nil
            flacFailed = true
            error = "FLAC failed, falling back to 320 kbps"
            load(startAt: position)
        }
        if isPlaying, Date().timeIntervalSince(lastSave) > 10 { save() }
    }

    private func setupRemote() {
        let c = MPRemoteCommandCenter.shared()
        c.togglePlayPauseCommand.addTarget { [weak self] _ in self?.toggle(); return .success }
        c.playCommand.addTarget { [weak self] _ in if self?.isPlaying == false { self?.toggle() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in if self?.isPlaying == true { self?.toggle() }; return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in self?.next(); return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in self?.previous(); return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            self?.seek(to: (e as? MPChangePlaybackPositionCommandEvent)?.positionTime ?? 0)
            return .success
        }
    }

    private var artwork: (URL, MPMediaItemArtwork)?

    private func updateNowPlaying() {
        let center = MPNowPlayingInfoCenter.default()
        guard let t = current else {
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            return
        }
        let parts = t.subtitle.components(separatedBy: " · ")
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: parts.first ?? "",
            MPMediaItemPropertyAlbumTitle: parts.dropFirst().first ?? "",
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let a = artwork, a.0 == t.artwork { info[MPMediaItemPropertyArtwork] = a.1 }
        center.nowPlayingInfo = info
        center.playbackState = isPlaying ? .playing : .paused
        if let url = t.artwork, artwork?.0 != url {
            Task {
                guard let (data, _) = try? await URLSession.shared.data(from: url), let img = NSImage(data: data) else { return }
                artwork = (url, MPMediaItemArtwork(boundsSize: img.size) { _ in img })
                updateNowPlaying()
            }
        }
    }
}

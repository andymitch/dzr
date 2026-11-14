import AVFoundation
import AppKit
import MediaPlayer
import Observation

enum Repeat: CaseIterable { case off, all, one }

@MainActor @Observable
final class Player {
    private let deezer: Deezer
    private let av = AVPlayer()
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var loadTask: Task<Void, Never>?

    var queue: [Item] = []
    private var original: [Item] = []
    var index = 0
    var isPlaying = false
    var position: TimeInterval = 0
    var duration: TimeInterval = 0
    var shuffle = false
    var repeatMode = Repeat.off
    var quality = Quality.flac
    var playingQuality: Quality?
    var error: String?
    var onUnauthorized: () -> Void = {}

    var current: Item? { queue.indices.contains(index) ? queue[index] : nil }
    var upNext: ArraySlice<Item> { queue.indices.contains(index + 1) ? queue[(index + 1)...] : [] }

    func jump(to i: Int) {
        guard queue.indices.contains(i) else { return }
        index = i
        load()
    }

    init(deezer: Deezer) {
        self.deezer = deezer
        timeObserver = av.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t) }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.advance(1, auto: true) }
        }
        setupRemote()
    }

    func play(_ tracks: [Item], at i: Int) {
        let playable = tracks.filter { $0.kind == .track && $0.readable }
        guard let start = playable.firstIndex(where: { $0.id == tracks[i].id }) else { return }
        original = playable
        queue = playable
        index = start
        if shuffle { shuffleRest() }
        load()
    }

    func enqueue(_ t: Item) {
        queue.append(t)
        original.append(t)
        if queue.count == 1 { load() }
    }

    func toggle() {
        guard current != nil else { return }
        isPlaying ? av.pause() : av.play()
        isPlaying.toggle()
        updateNowPlaying()
    }

    func next() { advance(1, auto: false) }

    func previous() {
        if position > 3 { seek(to: 0) } else { advance(-1, auto: false) }
    }

    func seek(to t: TimeInterval) {
        av.seek(to: CMTime(seconds: max(0, t), preferredTimescale: 600))
        position = t
        updateNowPlaying()
    }

    func toggleShuffle() {
        shuffle.toggle()
        guard let cur = current else { return }
        if shuffle {
            shuffleRest()
        } else {
            queue = original
            index = queue.firstIndex(of: cur) ?? 0
        }
    }

    func cycleRepeat() {
        let all = Repeat.allCases
        repeatMode = all[(all.firstIndex(of: repeatMode)! + 1) % all.count]
    }

    private func shuffleRest() {
        guard index + 1 < queue.count else { return }
        queue[(index + 1)...].shuffle()
    }

    private func advance(_ d: Int, auto: Bool) {
        guard !queue.isEmpty else { return }
        if auto && repeatMode == .one { return load() }
        var n = index + d
        if !queue.indices.contains(n) {
            guard repeatMode == .all else {
                if auto { isPlaying = false; updateNowPlaying() }
                return
            }
            n = (n + queue.count) % queue.count
        }
        index = n
        load()
    }

    private func load() {
        guard let t = current else { return }
        loadTask?.cancel()
        error = nil
        position = 0
        duration = t.duration
        updateNowPlaying()
        loadTask = Task {
            do {
                let (url, q) = try await deezer.streamURL(t.id, quality: quality)
                guard !Task.isCancelled else { return }
                av.replaceCurrentItem(with: AVPlayerItem(url: url))
                av.play()
                isPlaying = true
                playingQuality = q
                updateNowPlaying()
            } catch DeezerError.unauthorized {
                onUnauthorized()
            } catch {
                guard !Task.isCancelled else { return }
                self.error = error.localizedDescription
            }
        }
    }

    private func tick(_ t: CMTime) {
        position = t.seconds.isFinite ? t.seconds : 0
        if let d = av.currentItem?.duration.seconds, d.isFinite, d > 0 { duration = d }
        if av.currentItem?.status == .failed, playingQuality == .flac {
            playingQuality = nil
            quality = .high
            error = "FLAC failed, falling back to 320 kbps"
            load()
        }
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

import AppKit
import SwiftUI

@main
struct DzrApp: App {
    @NSApplicationDelegateAdaptor private var delegate: AppDelegate
    @State private var model = AppModel()
    @AppStorage("showInDock") private var showInDock = true

    init() {
        UserDefaults.standard.set("WhenScrolling", forKey: "AppleShowScrollBars")
    }

    var body: some Scene {
        Window("dzr", id: "main") {
            RootView()
                .environment(model)
                .environment(model.player)
                .environment(model.downloads)
                .frame(minWidth: 900, minHeight: 560)
                .onAppear { applyDockPolicy(); Theme.shared.apply() }
                .onChange(of: model.player.current?.artwork) { _, url in Theme.shared.follow(url) }
                .task(id: model.player.current?.id) { await model.loadLyrics(for: model.player.current?.id) }
                .onChange(of: showInDock) { applyDockPolicy() }
        }
        .commands {
            CommandMenu("Playback") {
                Button(model.player.isPlaying ? "Pause" : "Play") { model.player.toggle() }
                    .keyboardShortcut(.space, modifiers: [])
                Button("Next") { model.player.next() }.keyboardShortcut(.rightArrow, modifiers: .command)
                Button("Previous") { model.player.previous() }.keyboardShortcut(.leftArrow, modifiers: .command)
                Divider()
                Button("Shuffle") { model.player.toggleShuffle() }.keyboardShortcut("s", modifiers: .command)
                Button("Repeat") { model.player.cycleRepeat() }.keyboardShortcut("r", modifiers: .command)
                Button("Like") { model.toggleLike() }.keyboardShortcut("l", modifiers: .command)
                Divider()
                SleepTimerMenu(player: model.player)
            }
            CommandGroup(after: .appInfo) {
                Button("Check for Updates…") { Task { await Updates.check(manual: true) } }
            }
            CommandGroup(after: .appSettings) {
                Button("Sign Out") { model.signOut() }.disabled(!model.signedIn)
            }
        }

        Settings {
            SettingsView()
                .environment(model)
        }

        MenuBarExtra {
            MiniPlayer()
                .environment(model)
                .environment(model.player)
        } label: {
            Image(nsImage: .pixelHeart)
        }
        .menuBarExtraStyle(.window)
    }

    private func applyDockPolicy() {
        NSApp.setActivationPolicy(showInDock ? .regular : .accessory)
        NSApp.activate()
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationDidFinishLaunching(_ notification: Notification) {
        Updates.startDailyChecks()
    }
}

@MainActor @Observable
final class AppModel {
    let deezer = Deezer(session: Session.load())
    let downloads: Downloads
    let cache: SongCache
    let player: Player
    let podcasts = Podcasts()
    var signedIn: Bool
    var liked: Set<String> = []
    var showLyrics = false
    var myPlaylists: [Item] = []
    var playlistsVersion = 0
    var newPlaylistTracks: [String]?
    var lyrics: [Deezer.LyricLine]?
    var infoTrack: Item?
    var notice: String?
    private var lyricsFor: String?

    init() {
        downloads = Downloads(deezer: deezer)
        cache = SongCache(deezer: deezer)
        player = Player(deezer: deezer, downloads: downloads, cache: cache, podcasts: podcasts)
        signedIn = deezer.session != nil
        player.onUnauthorized = { [weak self] in self?.signOut() }
    }

    func signedIn(_ s: Session) {
        deezer.session = s
        signedIn = true
    }

    func signOut() {
        Session.clear()
        deezer.session = nil
        signedIn = false
    }

    func refreshPlaylists() async {
        guard let mine = try? await deezer.playlists() else { return }
        myPlaylists = mine.filter { $0.creatorId == deezer.session?.userId }
        playlistsVersion += 1
    }

    func playlistAction(_ f: @escaping () async throws -> Void) {
        Task {
            _ = await run(f)
            await refreshPlaylists()
        }
    }

    func play(_ item: Item) {
        Task {
            if case .success(let opened) = await run({ try await deezer.open(item) }),
               let tracks = opened.first(where: { $0.items.first?.kind == .track })?.items, !tracks.isEmpty {
                player.shuffle = false
                player.play(tracks, at: 0, from: item)
            }
        }
    }

    func refreshLiked() async {
        if let ids = try? await deezer.likedIds() { liked = ids }
    }

    func toggleLike(_ track: Item? = nil) {
        guard let t = track ?? player.current else { return }
        let like = !liked.contains(t.id)
        if like { liked.insert(t.id) } else { liked.remove(t.id) }
        Task {
            do { try await deezer.setLiked(t.id, like) } catch {
                if like { liked.remove(t.id) } else { liked.insert(t.id) }
            }
        }
    }

    func loadLyrics(for id: String?) async {
        guard let id, id != lyricsFor else { return }
        lyricsFor = id
        lyrics = nil
        let lines = player.isEpisode ? [] : (try? await deezer.lyrics(id)) ?? []
        guard lyricsFor == id else { return }
        lyrics = lines
        if lines.isEmpty { showLyrics = false }
    }

    func ban(_ track: Item) {
        Task {
            if case .success = await run({ try await deezer.ban(track.id) }) {
                player.remove(track)
                notice = "You won't hear \(track.title) again."
            }
        }
    }

    func playTrackMix(_ track: Item) {
        Task {
            if case .success(let mix) = await run({ try await deezer.trackMix(track.id) }), !mix.isEmpty {
                player.shuffle = false
                player.play(mix, at: 0, from: Item(kind: .mix, id: "trackmix-\(track.id)", title: "\(track.title) Mix", subtitle: ""))
            }
        }
    }

    func saveTrackMix(_ track: Item) {
        Task {
            if case .success(let mix) = await run({ try await deezer.trackMix(track.id) }), !mix.isEmpty {
                playlistAction { _ = try await self.deezer.createPlaylist("\(track.title) Mix", tracks: mix.map(\.id)) }
                notice = "Saved \(track.title) Mix to your playlists."
            }
        }
    }

    func run<T>(_ f: () async throws -> T) async -> Result<T, Error> {
        do { return .success(try await f()) } catch DeezerError.unauthorized {
            signOut()
            return .failure(DeezerError.unauthorized)
        } catch { return .failure(error) }
    }
}

extension NSImage {
    static let pixelHeart: NSImage = {
        let grid = [".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."]
        let cell: CGFloat = 2, gap: CGFloat = 0.4
        let size = NSSize(width: CGFloat(grid[0].count) * cell, height: CGFloat(grid.count) * cell)
        let img = NSImage(size: size, flipped: true) { _ in
            NSColor.black.setFill()
            for (y, row) in grid.enumerated() {
                for (x, ch) in row.enumerated() where ch == "X" {
                    NSBezierPath(rect: NSRect(x: CGFloat(x) * cell, y: CGFloat(y) * cell, width: cell - gap, height: cell - gap)).fill()
                }
            }
            return true
        }
        img.isTemplate = true
        return img
    }()
}

func format(_ t: TimeInterval) -> String {
    let s = Int(t.isFinite ? t : 0)
    return String(format: "%d:%02d", s / 60, s % 60)
}

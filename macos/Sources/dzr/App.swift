import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

@main
struct DzrApp: App {
    @State private var model = AppModel()

    init() {
        NSApplication.shared.setActivationPolicy(.regular)
    }

    var body: some Scene {
        WindowGroup("dzr") {
            RootView()
                .environment(model)
                .environment(model.player)
                .frame(minWidth: 820, minHeight: 520)
                .onAppear { NSApp.activate(ignoringOtherApps: true) }
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
            }
            CommandGroup(after: .appSettings) {
                Button("Sign Out") { model.signOut() }
            }
        }
    }
}

enum Destination: Hashable {
    case home, search, playlists, albums, artists
}

@MainActor @Observable
final class AppModel {
    let deezer = Deezer(session: Session.load())
    let player: Player
    var signedIn: Bool
    var liked: Set<String> = []

    init() {
        player = Player(deezer: deezer)
        signedIn = deezer.session != nil
        player.onUnauthorized = { [weak self] in self?.signOut() }
    }

    func signedIn(_ s: Session) {
        deezer.session = s
        signedIn = true
        Task { await refreshLiked() }
    }

    func signOut() {
        Session.clear()
        deezer.session = nil
        signedIn = false
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

    func run<T>(_ f: () async throws -> T) async -> Result<T, Error> {
        do { return .success(try await f()) } catch DeezerError.unauthorized {
            signOut()
            return .failure(DeezerError.unauthorized)
        } catch { return .failure(error) }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.signedIn {
            MainView().task { await model.refreshLiked() }
        } else {
            LoginView()
        }
    }
}

struct LoginView: View {
    @Environment(AppModel.self) private var model
    @State private var code: LoginCode?
    @State private var error: String?

    var body: some View {
        VStack(spacing: 20) {
            Text("Sign in to Deezer").font(.largeTitle.bold())
            if let code {
                if let qr = qrImage(code.url.absoluteString) {
                    Image(nsImage: qr).interpolation(.none).resizable().frame(width: 180, height: 180)
                        .padding(12).background(.white, in: RoundedRectangle(cornerRadius: 12))
                }
                Text("Scan with your phone, or open dzr.fm/tv and enter").foregroundStyle(.secondary)
                Text(code.code.uppercased()).font(.system(size: 40, weight: .bold, design: .monospaced)).foregroundStyle(Color.accent)
                Link("Open \(code.url.absoluteString)", destination: code.url)
                ProgressView().controlSize(.small)
            } else if let error {
                Text(error).foregroundStyle(.red)
                Button("Try again") { self.error = nil }
            } else {
                ProgressView()
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: error) {
            guard error == nil else { return }
            do {
                let c = try await Deezer.startLogin()
                code = c
                model.signedIn(try await Deezer.pollLogin(c.code))
            } catch is CancellationError {
            } catch {
                self.error = error.localizedDescription
                code = nil
            }
        }
    }

    private func qrImage(_ s: String) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        guard let ci = f.outputImage else { return nil }
        let rep = NSCIImageRep(ciImage: ci)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}

extension Color {
    static let accent = Color(red: 0.635, green: 0.22, blue: 1)
}

struct MainView: View {
    @State private var selection: Destination? = .home
    @State private var path = NavigationPath()
    @State private var query = ""
    @State private var submitted = ""

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Label("Home", systemImage: "house").tag(Destination.home)
                Label("Search", systemImage: "magnifyingglass").tag(Destination.search)
                Section("Library") {
                    Label("Playlists", systemImage: "music.note.list").tag(Destination.playlists)
                    Label("Albums", systemImage: "square.stack").tag(Destination.albums)
                    Label("Artists", systemImage: "music.mic").tag(Destination.artists)
                }
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 190)
        } detail: {
            NavigationStack(path: $path) {
                root
                    .navigationDestination(for: Item.self) { ItemPage(item: $0) }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { NowPlayingBar() }
        .searchable(text: $query, placement: .toolbar, prompt: "Search Deezer")
        .onSubmit(of: .search) {
            submitted = query
            selection = .search
            path = NavigationPath()
        }
        .onChange(of: selection) { path = NavigationPath() }
        .tint(.accent)
    }

    @ViewBuilder private var root: some View {
        switch selection ?? .home {
        case .home: SectionsPage(title: "Home") { try await $0.home() }
        case .search:
            if submitted.isEmpty {
                ContentUnavailableView("Search Deezer", systemImage: "magnifyingglass", description: Text("Tracks, artists, albums and playlists"))
            } else {
                SectionsPage(title: "Search: \(submitted)") { [submitted] in try await $0.search(submitted) }.id(submitted)
            }
        case .playlists: SectionsPage(title: "Playlists") { [Shelf(title: "Playlists", items: try await $0.playlists())] }
        case .albums: SectionsPage(title: "Albums") { [Shelf(title: "Albums", items: try await $0.albums())] }
        case .artists: SectionsPage(title: "Artists") { [Shelf(title: "Artists", items: try await $0.artists())] }
        }
    }
}

struct ItemPage: View {
    let item: Item
    var body: some View {
        SectionsPage(title: item.title, header: item) { try await $0.open(item) }
    }
}

struct SectionsPage: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    let title: String
    var header: Item?
    let load: (Deezer) async throws -> [Shelf]
    @State private var sections: [Shelf]?
    @State private var error: String?

    var body: some View {
        Group {
            if let sections {
                List {
                    if let header { Header(item: header, tracks: sections.flatMap(\.items).filter { $0.kind == .track }) }
                    ForEach(sections.filter { !$0.items.isEmpty }) { s in
                        Section(sections.count > 1 || header == nil ? s.title : "") {
                            ForEach(Array(s.items.enumerated()), id: \.offset) { i, it in
                                Row(item: it) {
                                    if it.kind == .track { player.play(s.items, at: i) }
                                }
                            }
                        }
                    }
                }
            } else if let error {
                ContentUnavailableView("Couldn't load", systemImage: "exclamationmark.triangle", description: Text(error))
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(title)
        .task {
            switch await model.run({ try await load(model.deezer) }) {
            case .success(let s): sections = s
            case .failure(let e): error = e.localizedDescription
            }
        }
    }
}

struct Header: View {
    @Environment(Player.self) private var player
    let item: Item
    let tracks: [Item]

    var body: some View {
        HStack(spacing: 16) {
            Artwork(url: item.artwork, size: 120, round: item.kind == .artist)
            VStack(alignment: .leading, spacing: 6) {
                Text(item.kind.rawValue.capitalized).font(.caption).foregroundStyle(.secondary)
                Text(item.title).font(.title.bold())
                Text(item.subtitle).foregroundStyle(.secondary)
                HStack {
                    Button { player.shuffle = false; player.play(tracks, at: 0) } label: { Label("Play", systemImage: "play.fill") }
                    Button { player.shuffle = true; player.play(tracks, at: Int.random(in: 0..<max(1, tracks.count))) } label: { Label("Shuffle", systemImage: "shuffle") }
                }
                .disabled(tracks.isEmpty)
                .padding(.top, 4)
            }
        }
        .padding(.vertical, 8)
        .listRowSeparator(.hidden)
    }
}

struct Row: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    let item: Item
    let play: () -> Void

    var body: some View {
        let playing = player.current?.id == item.id && item.kind == .track
        let content = HStack(spacing: 10) {
            Artwork(url: item.artwork, size: 36, round: item.kind == .artist)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).fontWeight(playing ? .semibold : .regular).foregroundStyle(playing ? Color.accent : .primary).lineLimit(1)
                Text(item.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if item.kind == .track {
                if model.liked.contains(item.id) { Image(systemName: "heart.fill").foregroundStyle(Color.accent).font(.caption) }
                Text(format(item.duration)).monospacedDigit().foregroundStyle(.secondary).font(.callout)
            } else {
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
        .opacity(item.readable ? 1 : 0.4)

        if item.kind == .track {
            content
                .onTapGesture(count: 2, perform: play)
                .contextMenu {
                    Button("Play", action: play)
                    Button("Add to Queue") { player.enqueue(item) }
                    Button(model.liked.contains(item.id) ? "Unlike" : "Like") { model.toggleLike(item) }
                    if item.albumId != 0 {
                        NavigationLink("Go to Album", value: Item(kind: .album, id: String(item.albumId), title: item.subtitle.components(separatedBy: " · ").last ?? "", subtitle: "", artwork: item.artwork))
                    }
                    if item.artistId != 0 {
                        NavigationLink("Go to Artist", value: Item(kind: .artist, id: String(item.artistId), title: item.subtitle.components(separatedBy: " · ").first ?? "", subtitle: ""))
                    }
                }
        } else {
            NavigationLink(value: item) { content }
        }
    }
}

struct Artwork: View {
    let url: URL?
    let size: CGFloat
    var round = false

    var body: some View {
        AsyncImage(url: url) { $0.resizable().aspectRatio(contentMode: .fill) } placeholder: {
            Rectangle().fill(Color.accent.opacity(0.15)).overlay(Image(systemName: "music.note").foregroundStyle(Color.accent))
        }
        .frame(width: size, height: size)
        .clipShape(round ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: size / 10)))
    }
}

struct NowPlayingBar: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @State private var scrub: Double?

    var body: some View {
        HStack(spacing: 16) {
            HStack(spacing: 10) {
                Artwork(url: player.current?.artwork, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.current?.title ?? "Not playing").fontWeight(.semibold).lineLimit(1)
                    Text(player.error ?? player.current?.subtitle ?? "").font(.caption).foregroundStyle(player.error == nil ? Color.secondary : Color.red).lineLimit(1)
                }
                if let t = player.current {
                    Button { model.toggleLike(t) } label: { Image(systemName: model.liked.contains(t.id) ? "heart.fill" : "heart") }
                        .buttonStyle(.borderless).foregroundStyle(Color.accent)
                }
            }
            .frame(width: 280, alignment: .leading)

            VStack(spacing: 4) {
                HStack(spacing: 18) {
                    Button(action: player.toggleShuffle) { Image(systemName: "shuffle") }.foregroundStyle(player.shuffle ? Color.accent : .secondary)
                    Button(action: player.previous) { Image(systemName: "backward.fill") }
                    Button(action: player.toggle) { Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 30)) }
                    Button(action: player.next) { Image(systemName: "forward.fill") }
                    Button(action: player.cycleRepeat) { Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat") }
                        .foregroundStyle(player.repeatMode == .off ? .secondary : Color.accent)
                }
                .buttonStyle(.borderless)
                HStack {
                    Text(format(scrub ?? player.position)).monospacedDigit().font(.caption).foregroundStyle(.secondary)
                    Slider(value: Binding(get: { scrub ?? player.position }, set: { scrub = $0 }), in: 0...max(1, player.duration)) { editing in
                        if !editing, let s = scrub { player.seek(to: s); scrub = nil }
                    }
                    .controlSize(.small)
                    Text(format(player.duration)).monospacedDigit().font(.caption).foregroundStyle(.secondary)
                }
            }
            .disabled(player.current == nil)

            @Bindable var p = player
            Picker("Quality", selection: $p.quality) {
                ForEach(Quality.allCases) { Text($0.rawValue).tag($0) }
            }
            .labelsHidden()
            .frame(width: 110)
            .help(player.playingQuality.map { "Playing \($0.rawValue)" } ?? "Preferred quality")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }
}

func format(_ t: TimeInterval) -> String {
    let s = Int(t.isFinite ? t : 0)
    return String(format: "%d:%02d", s / 60, s % 60)
}

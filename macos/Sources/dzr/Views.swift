import SwiftUI

enum Destination: Hashable {
    case home, search, liked, recent, playlists, albums, artists, downloads
}

struct MainView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: Destination? = .home
    @State private var path = NavigationPath()
    @State private var query = ""
    @State private var submitted = ""
    @FocusState private var searchFocused: Bool
    @State private var safeInsets = EdgeInsets()
    @State private var newPlaylistName = ""

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Label("Home", systemImage: "house").tag(Destination.home)
                Label("Search", systemImage: "magnifyingglass").tag(Destination.search)
                Section("Library") {
                    Label("Liked Songs", systemImage: "heart").tag(Destination.liked)
                    Label("Recently Played", systemImage: "clock").tag(Destination.recent)
                    Label("Playlists", systemImage: "music.note.list").tag(Destination.playlists)
                    Label("Albums", systemImage: "square.stack").tag(Destination.albums)
                    Label("Artists", systemImage: "music.microphone").tag(Destination.artists)
                    Label("Downloads", systemImage: "arrow.down.circle").tag(Destination.downloads)
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200)
        } detail: {
            HStack(spacing: 0) {
                NavigationStack(path: $path) {
                    root.withNowPlaying().navigationDestination(for: Item.self) { DetailPage(item: $0).withNowPlaying().environment(\.pageInsets, safeInsets) }
                }
                .onGeometryChange(for: EdgeInsets.self) { $0.safeAreaInsets } action: { safeInsets = $0 }
                .environment(\.pageInsets, safeInsets)
                if model.showLyrics {
                    Divider().ignoresSafeArea()
                    LyricsView()
                        .frame(width: 420)
                        .background(.background.secondary)
                        .transition(.move(edge: .trailing))
                }
            }
            .animation(.smooth(duration: 0.25), value: model.showLyrics)
        }
        .searchable(text: $query, placement: .toolbar, prompt: "Artists, songs, albums")
        .searchFocused($searchFocused)
        .background { Button("Find") { searchFocused = true }.keyboardShortcut("f").opacity(0) }
        .onSubmit(of: .search) {
            submitted = query
            selection = .search
            path = NavigationPath()
        }
        .onChange(of: selection) { path = NavigationPath() }
        .alert("New Playlist", isPresented: Binding(get: { model.newPlaylistTracks != nil }, set: { if !$0 { model.newPlaylistTracks = nil } })) {
            TextField("Name", text: $newPlaylistName)
            Button("Create") {
                let name = newPlaylistName.isEmpty ? "New Playlist" : newPlaylistName
                let tracks = model.newPlaylistTracks ?? []
                newPlaylistName = ""
                model.playlistAction { _ = try await model.deezer.createPlaylist(name, tracks: tracks) }
            }
            Button("Cancel", role: .cancel) { newPlaylistName = "" }
        }
        .sheet(item: Binding(get: { model.infoTrack }, set: { model.infoTrack = $0 })) { SongInfoView(track: $0) }
        .alert(model.notice ?? "", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
            Button("OK", role: .cancel) {}
        }
        .tint(.accent)
    }

    @ViewBuilder private var root: some View {
        switch selection ?? .home {
        case .home: ShelvesPage(title: "Home", surprise: true) { try await $0.home() }
        case .search:
            if submitted.isEmpty {
                ContentUnavailableView("Search Deezer", systemImage: "magnifyingglass", description: Text("Find tracks, artists, albums and playlists."))
                    .navigationTitle("Search")
            } else {
                ShelvesPage(title: submitted) { [submitted] in try await $0.search(submitted) }
                    .environment(\.playSource, Item(kind: .mix, id: "search", title: "Search: \(submitted)", subtitle: ""))
                    .id(submitted)
            }
        case .liked: TracksPage(title: "Liked Songs", symbol: "heart.fill", sourceId: "liked") { try await $0.lovedTracks() }
        case .recent: TracksPage(title: "Recently Played", symbol: "clock.fill", sourceId: "recent") { try await $0.history() }
        case .playlists:
            GridPage(title: "Playlists") { try await $0.playlists() }
                .id(model.playlistsVersion)
                .toolbar { Button { model.newPlaylistTracks = [] } label: { Label("New Playlist", systemImage: "plus") } }
        case .albums: GridPage(title: "Albums") { try await $0.albums() }
        case .artists: GridPage(title: "Artists") { try await $0.artists() }
        case .downloads: DownloadsPage()
        }
    }
}

extension View {
    func withNowPlaying() -> some View {
        contentMargins(.bottom, 96, for: .scrollContent)
            .scrollEdgeEffectHidden(true, for: .top)
            .toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
            .modifier(UnderSidebar())
            .overlay(alignment: .top) { HeaderFade() }
            .overlay(alignment: .bottom) { NowPlayingBar().padding(.horizontal, 20).padding(.bottom, 14) }
    }
}

struct HeaderFade: View {
    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
                .mask(LinearGradient(stops: [.init(color: .black.opacity(0.85), location: 0), .init(color: .black.opacity(0.5), location: 0.5), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
            LinearGradient(colors: [Color(nsColor: .windowBackgroundColor).opacity(0.3), .clear], startPoint: .top, endPoint: .bottom)
        }
        .frame(height: 14)
        .ignoresSafeArea(edges: [.top, .leading])
        .allowsHitTesting(false)
    }
}

struct UnderSidebar: ViewModifier {
    @Environment(\.pageInsets) private var insets

    func body(content: Content) -> some View {
        content
            .contentMargins(.leading, insets.leading, for: .scrollContent)
            .ignoresSafeArea(.container, edges: .leading)
    }
}

extension EnvironmentValues {
    @Entry var pageInsets = EdgeInsets()
}

struct Loader<T, Content: View>: View {
    @Environment(AppModel.self) private var model
    let title: String
    let load: (Deezer) async throws -> T
    @ViewBuilder let content: (T) -> Content
    @State private var value: T?
    @State private var error: String?

    var body: some View {
        Group {
            if let value {
                content(value)
            } else if let error {
                ContentUnavailableView("Couldn't Load", systemImage: "exclamationmark.triangle", description: Text(error))
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(title)
        .task {
            switch await model.run({ try await load(model.deezer) }) {
            case .success(let v): value = v
            case .failure(let e): error = e.localizedDescription
            }
        }
    }
}

struct ShelvesPage: View {
    @Environment(AppModel.self) private var model
    let title: String
    var surprise = false
    let load: (Deezer) async throws -> [Shelf]

    private func surpriseMe(_ shelves: [Shelf]) {
        let picks = shelves.filter { $0.title != "Recently played" }.flatMap(\.items).filter { [.flow, .mix, .playlist, .album].contains($0.kind) }
        if let pick = picks.randomElement() { model.play(pick) }
    }

    var body: some View {
        Loader(title: title, load: load) { shelves in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 28) {
                    ForEach(shelves.filter { !$0.items.isEmpty }) { shelf in
                        if shelf.items.first?.kind == .track {
                            TrackList(title: shelf.title, tracks: Array(shelf.items.prefix(8)), numbered: false)
                        } else {
                            ShelfRow(shelf: shelf)
                        }
                    }
                }
                .padding(.vertical, 20)
            }
            .toolbar {
                if surprise {
                    Button { surpriseMe(shelves) } label: { Label("Surprise Me", systemImage: "dice") }
                        .help("Play a random mix or playlist from Home")
                }
            }
        }
    }
}

struct TracksPage: View {
    @Environment(Player.self) private var player
    let title: String
    let symbol: String
    let sourceId: String
    let load: (Deezer) async throws -> [Item]

    var body: some View {
        Loader(title: title, load: load) { tracks in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    HStack(spacing: 18) {
                        Image(systemName: symbol)
                            .font(.system(size: 40, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 110, height: 110)
                            .background(LinearGradient(colors: [Color(red: 0.78, green: 0.42, blue: 1), .accent, Color(red: 0.42, green: 0.12, blue: 0.82)], startPoint: .topLeading, endPoint: .bottomTrailing), in: .rect(cornerRadius: 18))
                        VStack(alignment: .leading, spacing: 8) {
                            Text(title).font(.system(size: 30, weight: .bold))
                            Text("\(tracks.count) tracks").foregroundStyle(.secondary)
                            PlayButtons(tracks: tracks, download: sourceId == "liked" ? Item(kind: .playlist, id: "liked", title: title, subtitle: "") : nil)
                        }
                    }
                    .padding(.horizontal, 24)
                    TrackList(tracks: tracks, numbered: false)
                }
                .padding(.vertical, 20)
            }
            .environment(\.playSource, Item(kind: .mix, id: sourceId, title: title, subtitle: ""))
        }
    }
}

struct PlayButtons: View {
    @Environment(Player.self) private var player
    @Environment(\.playSource) private var source
    let tracks: [Item]
    var extra: (() -> Void)?
    var download: Item?

    var body: some View {
        HStack(spacing: 10) {
            Button { player.shuffle = false; player.play(tracks, at: 0, from: source) } label: {
                Label("Play", systemImage: "play.fill").padding(.horizontal, 6)
            }
            .buttonStyle(.glassProminent)
            Button { player.shuffle = true; player.play(tracks, at: Int.random(in: 0..<max(1, tracks.count)), from: source) } label: {
                Label("Shuffle", systemImage: "shuffle").padding(.horizontal, 6)
            }
            .buttonStyle(.glass)
            if let extra {
                Button(action: extra) { Label("Artist Mix", systemImage: "dot.radiowaves.left.and.right").padding(.horizontal, 6) }
                    .buttonStyle(.glass)
            }
            if let download {
                DownloadButton(item: download, tracks: tracks)
            }
        }
        .controlSize(.large)
        .disabled(tracks.isEmpty)
        .padding(.top, 4)
    }
}

struct GridPage: View {
    let title: String
    let load: (Deezer) async throws -> [Item]

    var body: some View {
        Loader(title: title, load: load) { items in
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 160, maximum: 200), spacing: 20)], alignment: .leading, spacing: 24) {
                    ForEach(items) { Card(item: $0) }
                }
                .padding(24)
            }
        }
    }
}

struct ShelfRow: View {
    let shelf: Shelf

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(shelf.title).font(.title2.bold()).padding(.horizontal, 24)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 18) {
                    ForEach(shelf.items) { Card(item: $0).frame(width: 160) }
                }
                .padding(.horizontal, 24)
            }
            .scrollIndicators(.never)
        }
    }
}

struct Card: View {
    @Environment(AppModel.self) private var model
    let item: Item
    @State private var hover = false
    @State private var playHover = false

    var body: some View {
        NavigationLink(value: item) {
            VStack(alignment: item.kind == .artist ? .center : .leading, spacing: 8) {
                Group {
                    if let covers = item.covers, covers.count == 4 {
                        Grid(horizontalSpacing: 0, verticalSpacing: 0) {
                            GridRow { Artwork(url: covers[0], radius: 0); Artwork(url: covers[1], radius: 0) }
                            GridRow { Artwork(url: covers[2], radius: 0); Artwork(url: covers[3], radius: 0) }
                        }
                        .clipShape(.rect(cornerRadius: 14))
                    } else {
                        Artwork(url: item.artwork, round: item.kind == .artist, radius: 14)
                            .overlay(alignment: .topLeading) {
                                if item.kind == .mix {
                                    Text(item.title)
                                        .font(.system(size: 17, weight: .heavy))
                                        .foregroundStyle(.black.opacity(0.85))
                                        .lineLimit(3)
                                        .minimumScaleFactor(0.7)
                                        .padding(12)
                                }
                            }
                    }
                }
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        if hover && item.kind != .track {
                            Button { model.play(item) } label: {
                                Image(systemName: "play.fill")
                                    .font(.title2)
                                    .foregroundStyle(.white)
                                    .frame(width: 52, height: 52)
                                    .background(.ultraThinMaterial.opacity(playHover ? 0 : 0.8), in: .circle)
                                    .glassEffect(playHover ? .clear.interactive() : .identity, in: .circle)
                                    .scaleEffect(playHover ? 1.1 : 1)
                            }
                            .buttonStyle(.plain)
                            .onHover { h in withAnimation(.snappy(duration: 0.2)) { playHover = h } }
                            .help("Play \(item.title)")
                            .transition(.scale(scale: 0.8).combined(with: .opacity))
                        }
                    }
                    .shadow(color: .black.opacity(hover ? 0.35 : 0.15), radius: hover ? 14 : 6, y: hover ? 8 : 3)
                    .scaleEffect(hover ? 1.03 : 1)
                VStack(alignment: item.kind == .artist ? .center : .leading, spacing: 2) {
                    Text(item.title).font(.callout.weight(.medium)).lineLimit(1)
                    Text(item.kind == .flow ? "Flow" : item.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(.snappy(duration: 0.2)) { hover = h; if !h { playHover = false } } }
    }
}

extension EnvironmentValues {
    @Entry var playSource: Item?
}

struct DetailPage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let item: Item
    @State private var renaming: String?

    private var owned: Bool { item.kind == .playlist && model.myPlaylists.contains { $0.id == item.id } }

    var body: some View {
        content
            .id(item.kind == .playlist ? model.playlistsVersion : 0)
            .environment(\.playSource, item)
            .toolbar {
                if owned {
                    Menu {
                        Button("Rename…") { renaming = item.title }
                        Button("Delete Playlist", role: .destructive) {
                            model.playlistAction { try await model.deezer.deletePlaylist(item.id) }
                            dismiss()
                        }
                    } label: { Label("Playlist", systemImage: "ellipsis.circle") }
                }
            }
            .alert("Rename Playlist", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Name", text: Binding(get: { renaming ?? "" }, set: { renaming = $0 }))
                Button("Rename") {
                    let name = renaming ?? ""
                    model.playlistAction { try await model.deezer.renamePlaylist(item.id, name) }
                }
                Button("Cancel", role: .cancel) {}
            }
    }

    private var content: some View {
        Loader(title: item.title, load: { try await $0.open(item) }) { shelves in
            let tracks = shelves.first { $0.items.first?.kind == .track }?.items ?? []
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    Hero(item: item, tracks: tracks)
                    ForEach(shelves.filter { !$0.items.isEmpty }) { shelf in
                        if shelf.items.first?.kind == .track {
                            TrackList(title: item.kind == .artist ? shelf.title : nil, tracks: item.kind == .artist ? Array(shelf.items.prefix(10)) : shelf.items, numbered: item.kind == .album)
                        } else {
                            ShelfRow(shelf: shelf)
                        }
                    }
                }
                .padding(.bottom, 20)
            }
            .ignoresSafeArea(.container, edges: .top)
        }
    }
}

struct Hero: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    let item: Item
    let tracks: [Item]

    private func artistMix() {
        Task {
            if case .success(let radio) = await model.run({ try await model.deezer.artistRadio(item.id) }), !radio.isEmpty {
                player.shuffle = false
                player.play(radio, at: 0, from: Item(kind: .mix, id: "artist-\(item.id)", title: "\(item.title) Mix", subtitle: ""))
            }
        }
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 24) {
            Artwork(url: item.artwork, round: item.kind == .artist, radius: 18)
                .frame(width: 200, height: 200)
                .shadow(color: .black.opacity(0.35), radius: 20, y: 10)
            VStack(alignment: .leading, spacing: 8) {
                Text(item.kind.label.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text(item.title).font(.system(size: 34, weight: .bold)).lineLimit(2)
                if item.kind != .flow && item.kind != .mix {
                    Text(item.subtitle).foregroundStyle(.secondary)
                }
                PlayButtons(tracks: tracks, extra: item.kind == .artist ? artistMix : nil, download: [.playlist, .album].contains(item.kind) ? item : nil)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 28)
        .padding(.top, 72)
        .padding(.bottom, 8)
        .background(alignment: .top) {
            Artwork(url: item.artwork, radius: 0)
                .scaledToFill()
                .frame(height: 340)
                .blur(radius: 70)
                .opacity(0.55)
                .mask(LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom))
                .clipped()
                .backgroundExtensionEffect()
        }
    }
}

extension Kind {
    var label: String {
        switch self {
        case .track: "Track"
        case .album: "Album"
        case .playlist: "Playlist"
        case .artist: "Artist"
        case .flow: "Flow"
        case .mix: "Mix"
        }
    }
}

struct TrackList: View {
    var title: String?
    let tracks: [Item]
    var numbered: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title { Text(title).font(.title2.bold()).padding(.horizontal, 24) }
            LazyVStack(spacing: 0) {
                ForEach(Array(tracks.enumerated()), id: \.offset) { i, t in
                    TrackRow(track: t, number: numbered ? i + 1 : nil, tracks: tracks, index: i)
                }
            }
            .padding(.horizontal, 12)
        }
    }
}

struct TrackRow: View {
    @Environment(AppModel.self) private var model
    @Environment(Downloads.self) private var downloads
    @Environment(Player.self) private var player
    @Environment(\.playSource) private var source
    let track: Item
    let number: Int?
    let tracks: [Item]
    let index: Int
    @State private var hover = false

    var body: some View {
        let playing = player.current?.id == track.id
        HStack(spacing: 12) {
            ZStack {
                if hover && !playing {
                    Image(systemName: "play.fill").font(.caption)
                } else if playing {
                    Image(systemName: player.isPlaying ? "waveform" : "pause.fill")
                        .symbolEffect(.variableColor.iterative, isActive: player.isPlaying)
                        .foregroundStyle(Color.accent)
                } else if let number {
                    Text("\(number)").monospacedDigit().foregroundStyle(.secondary)
                }
            }
            .frame(width: 22)
            if number == nil { Artwork(url: track.artwork, radius: 6).frame(width: 40, height: 40) }
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title).fontWeight(playing ? .semibold : .regular).foregroundStyle(playing ? Color.accent : .primary).lineLimit(1)
                Text(track.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Button { model.toggleLike(track) } label: {
                Image(systemName: model.liked.contains(track.id) ? "heart.fill" : "heart")
            }
            .buttonStyle(.borderless)
            .foregroundStyle(model.liked.contains(track.id) ? Color.accent : .secondary)
            .opacity(hover || model.liked.contains(track.id) ? 1 : 0)
            if downloads.entry(track.id) != nil {
                Image(systemName: "arrow.down.circle.fill").font(.caption).foregroundStyle(.secondary).help("Downloaded")
            }
            Text(format(track.duration)).monospacedDigit().font(.callout).foregroundStyle(.secondary).frame(width: 44, alignment: .trailing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(hover ? AnyShapeStyle(.quaternary) : AnyShapeStyle(.clear), in: .rect(cornerRadius: 10))
        .contentShape(.rect)
        .opacity(track.readable ? 1 : 0.4)
        .onHover { hover = $0 }
        .onTapGesture { player.play(tracks, at: index, from: source) }
        .contextMenu {
            Button("Play") { player.play(tracks, at: index, from: source) }
            TrackMenu(track: track)
        }
    }
}

struct Artwork: View {
    let url: URL?
    var round = false
    var radius: CGFloat = 8

    var body: some View {
        AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: {
            Rectangle().fill(Color.accent.opacity(0.12))
                .overlay(Image(systemName: "music.note").foregroundStyle(Color.accent.opacity(0.6)))
        }
        .clipShape(round ? AnyShape(.circle) : AnyShape(.rect(cornerRadius: radius)))
    }
}

struct DownloadButton: View {
    @Environment(Downloads.self) private var downloads
    let item: Item
    let tracks: [Item]

    var body: some View {
        Group {
            if let p = downloads.progress[item.id] {
                Label {
                    Text("Downloading \(p.done) of \(p.total)")
                } icon: {
                    ProgressView(value: Double(p.done), total: Double(max(p.total, 1))).progressViewStyle(.circular).controlSize(.small)
                }
                .padding(.horizontal, 6)
            } else if downloads.isDownloaded(item.id) {
                Menu {
                    Button("Remove Download", role: .destructive) { downloads.remove(item.id) }
                } label: {
                    Label("Downloaded", systemImage: "checkmark.circle.fill").padding(.horizontal, 6)
                }
                .menuIndicator(.hidden)
                .buttonStyle(.glass)
                .fixedSize()
            } else {
                Button { downloads.download(item, tracks: tracks) } label: {
                    Label("Download", systemImage: "arrow.down.circle").padding(.horizontal, 6)
                }
                .buttonStyle(.glass)
            }
        }
        .task(id: tracks.map(\.id)) {
            if downloads.isDownloaded(item.id), downloads.progress[item.id] == nil { downloads.download(item, tracks: tracks) }
        }
    }
}

struct DownloadsPage: View {
    @Environment(Downloads.self) private var downloads

    var body: some View {
        let tracks = downloads.tracks
        Group {
            if tracks.isEmpty {
                ContentUnavailableView("No Downloads", systemImage: "arrow.down.circle", description: Text("Download Liked Songs, playlists and albums to play them without a connection."))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Downloads").font(.system(size: 30, weight: .bold))
                            Text("\(tracks.count) songs · \(ByteCountFormatter.string(fromByteCount: downloads.bytesUsed, countStyle: .file))").foregroundStyle(.secondary)
                            PlayButtons(tracks: tracks)
                        }
                        .padding(.horizontal, 24)
                        TrackList(tracks: tracks, numbered: false)
                    }
                    .padding(.vertical, 20)
                }
                .environment(\.playSource, Item(kind: .mix, id: "downloads", title: "Downloads", subtitle: ""))
            }
        }
        .navigationTitle("Downloads")
    }
}

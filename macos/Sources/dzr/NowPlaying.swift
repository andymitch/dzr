import AppKit
import AVKit
import SwiftUI

struct Scrubber: View {
    @Environment(Player.self) private var player
    @State private var scrub: Double?

    var body: some View {
        HStack(spacing: 8) {
            Text(format(scrub ?? player.position)).frame(width: 36, alignment: .trailing)
            Slider(value: Binding(get: { scrub ?? player.position }, set: { scrub = $0 }), in: 0...max(1, player.duration)) { editing in
                if !editing, let s = scrub {
                    player.seek(to: s)
                    scrub = nil
                }
            }
            .controlSize(.mini)
            Text(format(player.duration)).frame(width: 36, alignment: .leading)
        }
        .font(.caption2.monospacedDigit())
        .foregroundStyle(.secondary)
    }
}

struct Transport: View {
    @Environment(Player.self) private var player
    var large = false

    var body: some View {
        HStack(spacing: large ? 22 : 16) {
            Button(action: player.toggleShuffle) { Image(systemName: "shuffle") }
                .foregroundStyle(player.shuffle ? Color.accent : .secondary)
                .help("Shuffle")
            Button(action: player.previous) { Image(systemName: "backward.fill") }
                .help("Previous")
            Button(action: player.toggle) {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .contentTransition(.symbolEffect(.replace))
                    .font(.system(size: large ? 26 : 20))
                    .frame(width: large ? 34 : 26)
            }
            .help(player.isPlaying ? "Pause" : "Play")
            Button(action: player.next) { Image(systemName: "forward.fill") }
                .help("Next")
            Button(action: player.cycleRepeat) { Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat") }
                .foregroundStyle(player.repeatMode == .off ? .secondary : Color.accent)
                .help("Repeat")
        }
        .buttonStyle(.borderless)
        .font(.system(size: large ? 16 : 14, weight: .semibold))
        .disabled(player.current == nil)
    }
}

struct LikeButton: View {
    @Environment(AppModel.self) private var model
    let track: Item

    var body: some View {
        let liked = model.liked.contains(track.id)
        Button { model.toggleLike(track) } label: {
            Image(systemName: liked ? "heart.fill" : "heart")
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.bounce, value: liked)
        }
        .buttonStyle(.borderless)
        .foregroundStyle(liked ? Color.accent : .secondary)
        .help(liked ? "Unlike" : "Like")
    }
}

struct QueueList: View {
    @Environment(Player.self) private var player

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Up Next").font(.headline)
            if let source = player.queue.source {
                Text("Playing from \(source.title)").font(.caption).foregroundStyle(.secondary)
            }
            Spacer().frame(height: 4)
            if player.upNext.isEmpty {
                Text("Nothing queued").foregroundStyle(.secondary)
            }
            List {
                ForEach(Array(player.upNext.enumerated()), id: \.offset) { offset, t in
                    HStack(spacing: 8) {
                        Artwork(url: t.artwork, radius: 4).frame(width: 28, height: 28)
                        VStack(alignment: .leading, spacing: 0) {
                            Marquee(text: t.title)
                            Text(t.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Button { player.jump(to: player.index + 1 + offset) } label: { Image(systemName: "play.fill") }
                            .buttonStyle(.borderless)
                            .help("Play now")
                    }
                    .contextMenu {
                        Button("Play Now") { player.jump(to: player.index + 1 + offset) }
                        Button("Remove from Up Next") { player.removeUpNext(at: [offset]) }
                    }
                }
                .onMove { player.moveUpNext(from: $0, to: $1) }
                .onDelete { player.removeUpNext(at: $0) }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .frame(height: min(CGFloat(max(player.upNext.count, 1)) * 40, 420))
            Text("Drag to reorder").font(.caption2).foregroundStyle(.tertiary)
        }
        .padding(14)
        .frame(width: 320)
    }
}

struct QualityMenu: View {
    @Environment(Player.self) private var player

    var body: some View {
        @Bindable var p = player
        Menu {
            Picker("Quality", selection: $p.quality) {
                ForEach(Quality.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.inline)
        } label: {
            Text(player.playingQuality?.badge ?? player.quality.badge)
                .font(.caption2.weight(.bold))
                .padding(.horizontal, 6).padding(.vertical, 2)
                .overlay(Capsule().strokeBorder(.secondary.opacity(0.5)))
                .foregroundStyle(.secondary)
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .fixedSize()
        .help("Streaming quality")
    }
}

extension Quality {
    var badge: String {
        switch self {
        case .flac: "FLAC"
        case .high: "320"
        case .standard: "128"
        }
    }
}

struct NowPlayingBar: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @State private var showQueue = false

    var body: some View {
        HStack(spacing: 18) {
            HStack(spacing: 12) {
                Artwork(url: player.current?.artwork, radius: 10).frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Marquee(text: player.current?.title ?? "Not Playing").font(.callout.weight(.semibold))
                    Text(player.error ?? player.current?.subtitle ?? "Pick something to play")
                        .font(.caption).lineLimit(1)
                        .foregroundStyle(player.error == nil ? Color.secondary : Color.red)
                }
                if let t = player.current { LikeButton(track: t) }
            }
            .frame(minWidth: 200, maxWidth: 280, alignment: .leading)
            .help(player.queue.source.map { "Playing from \($0.title)" } ?? "")

            VStack(spacing: 2) {
                Transport()
                Scrubber().frame(maxWidth: 460)
            }
            .frame(maxWidth: .infinity)

            HStack(spacing: 12) {
                SleepIndicator()
                AirPlayButton().frame(width: 20, height: 20)
                QualityMenu()
                if model.lyrics?.isEmpty == false {
                    Button { model.showLyrics.toggle() } label: { Image(systemName: "quote.bubble") }
                        .buttonStyle(.borderless)
                        .foregroundStyle(model.showLyrics ? Color.accent : .secondary)
                        .help("Lyrics")
                }
                if let t = player.current {
                    Menu { TrackMenu(track: t, showQueueActions: false) } label: { Image(systemName: "ellipsis") }
                        .menuStyle(.button)
                        .menuIndicator(.hidden)
                        .buttonStyle(.borderless)
                        .fixedSize()
                        .help("More")
                }
                Button { showQueue.toggle() } label: { Image(systemName: "list.bullet") }
                    .buttonStyle(.borderless)
                    .help("Up Next")
                    .popover(isPresented: $showQueue, arrowEdge: .top) { QueueList() }
            }
        }
        .padding(.leading, 10)
        .padding(.trailing, 20)
        .padding(.vertical, 10)
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
    }
}

struct MiniPlayer: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @Environment(\.openWindow) private var openWindow
    @AppStorage("showInDock") private var showInDock = true

    var body: some View {
        VStack(spacing: 14) {
            if let t = player.current {
                if let source = player.queue.source {
                    Text("PLAYING FROM \(source.title.uppercased())")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                }
                Artwork(url: t.artwork, radius: 16)
                    .frame(width: 256, height: 256)
                    .shadow(color: .black.opacity(0.3), radius: 16, y: 8)
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Marquee(text: t.title).font(.headline)
                        Text(player.error ?? t.subtitle).font(.subheadline).foregroundStyle(player.error == nil ? Color.secondary : Color.red).lineLimit(1)
                    }
                    Spacer()
                    LikeButton(track: t).font(.title3)
                }
                Scrubber()
                Transport(large: true)
                if let next = player.upNext.first {
                    HStack(spacing: 8) {
                        Text("Next").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        Artwork(url: next.artwork, radius: 4).frame(width: 22, height: 22)
                        Text("\(next.title) · \(next.subtitle.components(separatedBy: " · ").first ?? "")").font(.caption).lineLimit(1)
                        Spacer()
                    }
                    .padding(8)
                    .background(.quaternary.opacity(0.5), in: .rect(cornerRadius: 10))
                }
            } else {
                VStack(spacing: 10) {
                    Image(nsImage: .pixelHeart).resizable().interpolation(.none).frame(width: 42, height: 36).foregroundStyle(Color.accent)
                    Text(model.signedIn ? "Nothing playing" : "Not signed in").font(.headline)
                    Text(model.signedIn ? "Open dzr to pick some music." : "Open dzr to sign in to Deezer.").font(.caption).foregroundStyle(.secondary)
                }
                .frame(width: 256, height: 180)
            }

            Divider()
            HStack {
                Button {
                    openWindow(id: "main")
                    NSApp.activate()
                } label: { Label("Open dzr", systemImage: "macwindow") }
                .buttonStyle(.glass)
                Spacer()
                Menu {
                    Toggle("Show in Dock", isOn: $showInDock)
                    Divider()
                    Button("Quit dzr") { NSApp.terminate(nil) }
                } label: { Image(systemName: "ellipsis") }
                .menuStyle(.button)
                .menuIndicator(.hidden)
                .buttonStyle(.glass)
                .fixedSize()
            }
        }
        .padding(16)
        .frame(width: 288)
        .tint(.accent)
    }
}

struct LyricsView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @State private var lineMids: [Int: CGFloat] = [:]
    @State private var scrollNudge: CGFloat = 0

    private var currentIndex: Int? {
        guard let lines = model.lyrics else { return nil }
        return lines.lastIndex { ($0.time ?? .infinity) <= player.position + 0.3 }
    }

    var body: some View {
        Group {
            if player.current == nil {
                ContentUnavailableView("Lyrics", systemImage: "quote.bubble", description: Text("Play something to see its lyrics."))
            } else if let lines = model.lyrics, !lines.isEmpty {
                GeometryReader { geo in
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { i, l in
                            Text(l.line)
                                .font(.title3.weight(.bold))
                                .foregroundStyle(i == currentIndex ? .primary : .tertiary)
                                .blur(radius: abs(i - (currentIndex ?? 0)) > 2 ? 0.6 : 0)
                                .scaleEffect(i == currentIndex ? 1 : 0.97, anchor: .leading)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(.rect)
                                .onTapGesture { if let t = l.time { player.seek(to: t) } }
                                .onGeometryChange(for: CGFloat.self) { $0.frame(in: .named("lyrics")).midY } action: { lineMids[i] = $0 }
                        }
                    }
                    .padding(.horizontal, 20)
                    .offset(y: geo.size.height * 0.38 - (currentIndex.flatMap { lineMids[$0] } ?? 0) + scrollNudge)
                    .animation(.spring(duration: 0.7, bounce: 0.12), value: currentIndex)
                    .coordinateSpace(name: "lyrics")
                }
                .clipped()
                .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.12), .init(color: .black, location: 0.85), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
                .onScrollWheel { scrollNudge += $0 }
                .onChange(of: currentIndex) { scrollNudge = 0 }
            } else if model.lyrics != nil {
                ContentUnavailableView("No Lyrics", systemImage: "quote.bubble", description: Text("Deezer has no lyrics for this track."))
            } else {
                ProgressView()
            }
        }
        .onChange(of: player.current?.id) { lineMids = [:] }
    }
}

private struct ScrollWheel: NSViewRepresentable {
    let onScroll: (CGFloat) -> Void

    final class View: NSView {
        var onScroll: (CGFloat) -> Void = { _ in }
        override func scrollWheel(with event: NSEvent) { onScroll(event.scrollingDeltaY) }
    }

    func makeNSView(context: Context) -> View { View() }
    func updateNSView(_ v: View, context: Context) { v.onScroll = onScroll }
}

extension View {
    func onScrollWheel(_ action: @escaping (CGFloat) -> Void) -> some View {
        overlay(ScrollWheel(onScroll: action).allowsHitTesting(true))
    }
}

struct SleepTimerMenu: View {
    let player: Player

    var body: some View {
        Menu("Sleep Timer") {
            Button("Off") { player.setSleepTimer(minutes: nil) }
                .disabled(player.sleepEnd == nil && !player.sleepAtEndOfSong)
            Divider()
            ForEach([15, 30, 45, 60], id: \.self) { m in
                Button("\(m) Minutes") { player.setSleepTimer(minutes: m) }
            }
            Button("End of Current Song") { player.sleepAfterCurrentSong() }
                .disabled(player.current == nil)
        }
    }
}

struct SleepIndicator: View {
    @Environment(Player.self) private var player

    var body: some View {
        if player.sleepEnd != nil || player.sleepAtEndOfSong {
            Menu {
                SleepTimerMenu(player: player)
            } label: {
                Image(systemName: "moon.zzz.fill")
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.borderless)
            .foregroundStyle(Color.accent)
            .fixedSize()
            .help(sleepHelp)
        }
    }

    private var sleepHelp: String {
        if player.sleepAtEndOfSong { return "Stops at the end of this song" }
        guard let end = player.sleepEnd else { return "" }
        return "Stops at \(end.formatted(date: .omitted, time: .shortened))"
    }
}

struct TrackMenu: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @Environment(\.playSource) private var source
    let track: Item
    var showQueueActions = true

    var body: some View {
        if showQueueActions {
            Button("Play Next") { player.playNext(track) }
            Button("Add to Queue") { player.enqueue(track) }
        }
        Menu("Add to Playlist") {
            Button("New Playlist…") { model.newPlaylistTracks = [track.id] }
            if !model.myPlaylists.isEmpty { Divider() }
            ForEach(model.myPlaylists) { p in
                Button(p.title) { model.playlistAction { try await model.deezer.addToPlaylist(p.id, [track.id]) } }
            }
        }
        if let p = source, p.kind == .playlist, model.myPlaylists.contains(where: { $0.id == p.id }) {
            Button("Remove from Playlist") { model.playlistAction { try await model.deezer.removeFromPlaylist(p.id, [track.id]) } }
        }
        Divider()
        Button("Play Track Mix") { model.playTrackMix(track) }
        Button("Save Track Mix as Playlist") { model.saveTrackMix(track) }
        Divider()
        Button(model.liked.contains(track.id) ? "Unlike" : "Like") { model.toggleLike(track) }
        Button("Don't Play This Song") { model.ban(track) }
        Divider()
        if track.albumId != 0 {
            NavigationLink("Go to Album", value: Item(kind: .album, id: String(track.albumId), title: track.subtitle.components(separatedBy: " · ").last ?? "", subtitle: track.subtitle.components(separatedBy: " · ").first ?? "", artwork: track.artwork))
        }
        if track.artistId != 0 {
            NavigationLink("Go to Artist", value: Item(kind: .artist, id: String(track.artistId), title: track.subtitle.components(separatedBy: " · ").first ?? "", subtitle: ""))
        }
        Button("Song Info") { model.infoTrack = track }
    }
}

struct SongInfoView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(Player.self) private var player
    let track: Item

    var body: some View {
        let parts = track.subtitle.components(separatedBy: " · ")
        VStack(spacing: 16) {
            HStack(spacing: 16) {
                Artwork(url: track.artwork, radius: 12).frame(width: 96, height: 96)
                VStack(alignment: .leading, spacing: 4) {
                    Marquee(text: track.title).font(.title3.bold())
                    Text(parts.first ?? "").foregroundStyle(.secondary)
                }
                Spacer()
            }
            Form {
                LabeledContent("Artist", value: parts.first ?? "")
                LabeledContent("Album", value: parts.dropFirst().first ?? "")
                LabeledContent("Duration", value: format(track.duration))
                if player.current?.id == track.id, let q = player.playingQuality {
                    LabeledContent("Quality", value: q.rawValue)
                }
            }
            .formStyle(.grouped)
            .scrollDisabled(true)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 400)
    }
}

struct AirPlayButton: NSViewRepresentable {
    @Environment(Player.self) private var player

    func makeNSView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.isRoutePickerButtonBordered = false
        picker.toolTip = "AirPlay"
        return picker
    }

    func updateNSView(_ picker: AVRoutePickerView, context: Context) {
        player.attach(picker)
        picker.setRoutePickerButtonColor(.secondaryLabelColor, for: .normal)
        picker.setRoutePickerButtonColor(NSColor(Color.accent), for: .active)
    }
}

import AppKit
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
            Text("Up Next").font(.headline).padding(.bottom, 4)
            if player.upNext.isEmpty {
                Text("Nothing queued").foregroundStyle(.secondary)
            }
            ForEach(Array(player.upNext.prefix(30).enumerated()), id: \.offset) { offset, t in
                Button { player.jump(to: player.index + 1 + offset) } label: {
                    HStack(spacing: 8) {
                        Artwork(url: t.artwork, radius: 4).frame(width: 28, height: 28)
                        VStack(alignment: .leading, spacing: 0) {
                            Text(t.title).lineLimit(1)
                            Text(t.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .frame(width: 300)
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
    @Environment(Player.self) private var player
    @State private var showQueue = false

    var body: some View {
        HStack(spacing: 18) {
            HStack(spacing: 12) {
                Artwork(url: player.current?.artwork, radius: 10).frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.current?.title ?? "Not Playing").font(.callout.weight(.semibold)).lineLimit(1)
                    Text(player.error ?? player.current?.subtitle ?? "Pick something to play")
                        .font(.caption).lineLimit(1)
                        .foregroundStyle(player.error == nil ? Color.secondary : Color.red)
                }
                if let t = player.current { LikeButton(track: t) }
            }
            .frame(minWidth: 200, maxWidth: 280, alignment: .leading)

            VStack(spacing: 2) {
                Transport()
                Scrubber().frame(maxWidth: 460)
            }
            .frame(maxWidth: .infinity)

            HStack(spacing: 12) {
                QualityMenu()
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
                Artwork(url: t.artwork, radius: 16)
                    .frame(width: 256, height: 256)
                    .shadow(color: .black.opacity(0.3), radius: 16, y: 8)
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.title).font(.headline).lineLimit(1)
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

import AppKit
import SwiftUI

@main
struct DzrApp: App {
    @NSApplicationDelegateAdaptor private var delegate: AppDelegate
    @State private var model = AppModel()
    @AppStorage("showInDock") private var showInDock = true

    var body: some Scene {
        Window("dzr", id: "main") {
            RootView()
                .environment(model)
                .environment(model.player)
                .frame(minWidth: 900, minHeight: 560)
                .onAppear { applyDockPolicy() }
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
            }
            CommandGroup(after: .appSettings) {
                Toggle("Show in Dock", isOn: $showInDock)
                Button("Sign Out") { model.signOut() }.disabled(!model.signedIn)
            }
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
}

@MainActor @Observable
final class AppModel {
    let deezer = Deezer(session: Session.load())
    let player: Player
    var signedIn: Bool
    var liked: Set<String> = []
    var showLyrics = false

    init() {
        player = Player(deezer: deezer)
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

extension Color {
    static let accent = Color(red: 0.635, green: 0.22, blue: 1)
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

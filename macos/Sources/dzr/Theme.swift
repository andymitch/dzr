import AppKit
import CoreImage
import SwiftUI

enum Accent: String, CaseIterable, Identifiable {
    case purple, pink, red, orange, green, teal, blue, artwork
    var id: String { rawValue }

    var fixed: Color? {
        switch self {
        case .purple: Color(red: 0.635, green: 0.22, blue: 1)
        case .pink: Color(red: 0.91, green: 0.26, blue: 0.58)
        case .red: Color(red: 0.9, green: 0.22, blue: 0.21)
        case .orange: Color(red: 0.98, green: 0.55, blue: 0)
        case .green: Color(red: 0.26, green: 0.63, blue: 0.28)
        case .teal: Color(red: 0, green: 0.6, blue: 0.56)
        case .blue: Color(red: 0.12, green: 0.53, blue: 0.9)
        case .artwork: nil
        }
    }
}

enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }

    var nsAppearance: NSAppearance? {
        switch self {
        case .system: nil
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        }
    }
}

@MainActor @Observable
final class Theme {
    static let shared = Theme()

    var accent: Accent { didSet { UserDefaults.standard.set(accent.rawValue, forKey: "accent") } }
    var appearance: Appearance {
        didSet {
            UserDefaults.standard.set(appearance.rawValue, forKey: "appearance")
            NSApp.appearance = appearance.nsAppearance
        }
    }
    private var artworkColor: Color?
    private var artworkURL: URL?

    var color: Color { accent.fixed ?? artworkColor ?? Accent.purple.fixed! }

    private init() {
        accent = Accent(rawValue: UserDefaults.standard.string(forKey: "accent") ?? "") ?? .purple
        appearance = Appearance(rawValue: UserDefaults.standard.string(forKey: "appearance") ?? "") ?? .system
    }

    func apply() { NSApp.appearance = appearance.nsAppearance }

    func follow(_ url: URL?) {
        guard url != artworkURL else { return }
        artworkURL = url
        guard let url else { return }
        Task {
            guard let (data, _) = try? await URLSession.shared.data(from: url), let ci = CIImage(data: data),
                  let avg = CIFilter(name: "CIAreaAverage", parameters: [kCIInputImageKey: ci, kCIInputExtentKey: CIVector(cgRect: ci.extent)])?.outputImage
            else { return }
            var px = [UInt8](repeating: 0, count: 4)
            CIContext().render(avg, toBitmap: &px, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBA8, colorSpace: nil)
            let ns = NSColor(red: CGFloat(px[0]) / 255, green: CGFloat(px[1]) / 255, blue: CGFloat(px[2]) / 255, alpha: 1)
            var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0
            ns.getHue(&h, saturation: &s, brightness: &b, alpha: nil)
            guard artworkURL == url else { return }
            artworkColor = Color(hue: h, saturation: max(0.45, min(s * 1.6, 0.9)), brightness: max(0.75, b))
        }
    }
}

extension Color {
    @MainActor static var accent: Color { Theme.shared.color }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @AppStorage("showInDock") private var showInDock = true
    @Bindable private var theme = Theme.shared
    @State private var confirmRemove = false
    @State private var usedBytes: Int64 = 0
    @State private var cacheBytes: Int64 = 0

    private var downloads: Binding<Quality> { Binding(get: { model.downloads.quality }, set: { model.downloads.quality = $0 }) }

    var body: some View {
        Form {
            Picker("Appearance", selection: $theme.appearance) {
                Text("System").tag(Appearance.system)
                Text("Light").tag(Appearance.light)
                Text("Dark").tag(Appearance.dark)
            }
            .pickerStyle(.segmented)

            LabeledContent("Accent") {
                HStack(spacing: 10) {
                    ForEach(Accent.allCases) { a in
                        Button { theme.accent = a } label: {
                            Circle()
                                .fill(a.fixed.map { AnyShapeStyle($0) } ?? AnyShapeStyle(AngularGradient(colors: [.red, .orange, .yellow, .green, .blue, .purple, .red], center: .center)))
                                .frame(width: 22, height: 22)
                                .overlay(Circle().strokeBorder(.white, lineWidth: theme.accent == a ? 2.5 : 0))
                                .overlay(Circle().strokeBorder(.black.opacity(0.15)))
                        }
                        .buttonStyle(.plain)
                        .help(a == .artwork ? "Match the playing album's artwork" : a.rawValue.capitalized)
                    }
                }
            }

            Toggle("Show in Dock", isOn: $showInDock)

            Section("Downloads") {
                Picker("Download quality", selection: downloads) {
                    ForEach(Quality.allCases) { Text($0.rawValue).tag($0) }
                }
                LabeledContent("Storage used", value: ByteCountFormatter.string(fromByteCount: usedBytes, countStyle: .file))
                Button("Remove All Downloads…", role: .destructive) { confirmRemove = true }
                    .disabled(model.downloads.index.collections.isEmpty)
            }

            Section("Cache") {
                Stepper(value: Binding(get: { model.cache.ahead }, set: { model.cache.ahead = $0 }), in: SongCache.aheadRange) {
                    LabeledContent("Songs to cache ahead", value: "\(model.cache.ahead)")
                }
                LabeledContent("Cache size", value: ByteCountFormatter.string(fromByteCount: cacheBytes, countStyle: .file))
                Button("Clear Cache") {
                    model.cache.clear()
                    cacheBytes = 0
                }
            }
        }
        .confirmationDialog("Remove all downloads?", isPresented: $confirmRemove) {
            Button("Remove All Downloads", role: .destructive) {
                model.downloads.removeAll()
                usedBytes = 0
            }
        } message: {
            Text("Downloaded songs will need a connection to play again.")
        }
        .onAppear {
            usedBytes = model.downloads.bytesUsed
            cacheBytes = model.cache.bytesUsed
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .fixedSize()
        .tint(.accent)
    }
}

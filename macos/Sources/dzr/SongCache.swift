import Foundation
import Observation

@MainActor @Observable
final class SongCache {
    static let aheadRange = 2...10
    private static let maxBytes: Int64 = 1 << 30

    var ahead: Int {
        didSet { UserDefaults.standard.set(ahead, forKey: "cacheAhead") }
    }
    private let deezer: Deezer
    private var task: Task<Void, Never>?

    static var dir: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("dzr/songs", isDirectory: true)
    }

    init(deezer: Deezer) {
        self.deezer = deezer
        let saved = UserDefaults.standard.integer(forKey: "cacheAhead")
        ahead = Self.aheadRange.contains(saved) ? saved : 5
        for f in files() where f.pathExtension == "part" { try? FileManager.default.removeItem(at: f) }
    }

    func localURL(_ trackId: String) -> (URL, Quality)? {
        guard let f = files().first(where: { $0.deletingPathExtension().lastPathComponent == trackId && $0.pathExtension != "part" }) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: f.path)
        return (f, f.pathExtension == "flac" ? .flac : .high)
    }

    var bytesUsed: Int64 {
        files().reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    func clear() {
        task?.cancel()
        try? FileManager.default.removeItem(at: Self.dir)
    }

    func prefetch(_ tracks: [Item], quality: Quality, skip: (String) -> Bool) {
        let wanted = tracks.prefix(ahead).map(\.id).filter { !skip($0) && localURL($0) == nil }
        guard !wanted.isEmpty else { return }
        task?.cancel()
        task = Task {
            for id in wanted {
                guard !Task.isCancelled,
                      let (url, q) = try? await deezer.streamURL(id, quality: quality),
                      let (tmp, res) = try? await URLSession.shared.download(from: url),
                      (res as? HTTPURLResponse)?.statusCode ?? 200 < 300 else { continue }
                try? FileManager.default.createDirectory(at: Self.dir, withIntermediateDirectories: true)
                let dest = Self.dir.appendingPathComponent("\(id).\(q == .flac ? "flac" : "mp3")")
                try? FileManager.default.removeItem(at: dest)
                try? FileManager.default.moveItem(at: tmp, to: dest)
                trim()
            }
        }
    }

    private func files() -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: Self.dir, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? []
    }

    private func trim() {
        let sorted = files().sorted {
            let a = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let b = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return a > b
        }
        var total: Int64 = 0
        for f in sorted {
            total += Int64((try? f.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            if total > Self.maxBytes { try? FileManager.default.removeItem(at: f) }
        }
    }
}

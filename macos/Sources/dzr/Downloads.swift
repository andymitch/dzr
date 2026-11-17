import Foundation
import Observation

@MainActor @Observable
final class Downloads {
    struct Entry: Codable, Equatable {
        var track: Item
        var file: String
        var quality: Quality
    }

    struct Collection: Codable, Equatable {
        var item: Item
        var trackIds: [String]
    }

    struct Index: Codable, Equatable {
        var tracks: [String: Entry] = [:]
        var collections: [Collection] = []

        func orphans(after removing: String) -> [String] {
            let kept = Set(collections.filter { $0.item.id != removing }.flatMap(\.trackIds))
            return (collections.first { $0.item.id == removing }?.trackIds ?? []).filter { !kept.contains($0) && tracks[$0] != nil }
        }

        mutating func upsert(_ item: Item, trackIds: [String]) -> [String] {
            let previous = collections.first { $0.item.id == item.id }?.trackIds ?? []
            if let i = collections.firstIndex(where: { $0.item.id == item.id }) {
                collections[i] = Collection(item: item, trackIds: trackIds)
            } else {
                collections.append(Collection(item: item, trackIds: trackIds))
            }
            let kept = Set(collections.flatMap(\.trackIds))
            return previous.filter { !kept.contains($0) && tracks[$0] != nil }
        }

        var orderedTracks: [Item] {
            var seen = Set<String>()
            return collections.flatMap(\.trackIds).compactMap { id in
                guard seen.insert(id).inserted else { return nil }
                return tracks[id]?.track
            }
        }
    }

    private(set) var index = Index()
    private(set) var progress: [String: (done: Int, total: Int)] = [:]
    var quality: Quality {
        didSet { UserDefaults.standard.set(quality.rawValue, forKey: "downloadQuality") }
    }
    private let deezer: Deezer

    static var dir: URL { Session.url.deletingLastPathComponent().appendingPathComponent("Downloads", isDirectory: true) }
    private static var indexURL: URL { dir.appendingPathComponent("index.json") }

    init(deezer: Deezer) {
        self.deezer = deezer
        quality = Quality(rawValue: UserDefaults.standard.string(forKey: "downloadQuality") ?? "") ?? .high
        if let data = try? Data(contentsOf: Self.indexURL), let saved = try? JSONDecoder().decode(Index.self, from: data) {
            index = saved
        }
    }

    var tracks: [Item] { index.orderedTracks }

    func entry(_ trackId: String) -> Entry? {
        guard let e = index.tracks[trackId], FileManager.default.fileExists(atPath: Self.dir.appendingPathComponent(e.file).path) else { return nil }
        return e
    }

    func localURL(_ trackId: String) -> (URL, Quality)? {
        entry(trackId).map { (Self.dir.appendingPathComponent($0.file), $0.quality) }
    }

    func isDownloaded(_ collectionId: String) -> Bool {
        index.collections.contains { $0.item.id == collectionId }
    }

    var bytesUsed: Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(at: Self.dir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    func download(_ item: Item, tracks: [Item]) {
        let playable = tracks.filter { $0.kind == .track && $0.readable }
        deleteFiles(index.upsert(item, trackIds: playable.map(\.id)))
        save()
        let missing = playable.filter { entry($0.id) == nil }
        guard !missing.isEmpty, progress[item.id] == nil else { return }
        progress[item.id] = (0, missing.count)
        Task {
            await withTaskGroup(of: Void.self) { group in
                var pending = missing[...]
                for _ in 0..<min(3, pending.count) {
                    let t = pending.removeFirst()
                    group.addTask { await self.fetch(t, for: item.id) }
                }
                for await _ in group where !pending.isEmpty {
                    let t = pending.removeFirst()
                    group.addTask { await self.fetch(t, for: item.id) }
                }
            }
            progress[item.id] = nil
            save()
        }
    }

    func remove(_ collectionId: String) {
        let orphans = index.orphans(after: collectionId)
        index.collections.removeAll { $0.item.id == collectionId }
        deleteFiles(orphans)
        save()
    }

    func removeAll() {
        try? FileManager.default.removeItem(at: Self.dir)
        index = Index()
        progress = [:]
    }

    private func fetch(_ track: Item, for collectionId: String) async {
        defer { if let p = progress[collectionId] { progress[collectionId] = (p.done + 1, p.total) } }
        guard index.collections.contains(where: { $0.trackIds.contains(track.id) }),
              let (url, q) = try? await deezer.streamURL(track.id, quality: quality),
              let (tmp, res) = try? await URLSession.shared.download(from: url),
              (res as? HTTPURLResponse)?.statusCode ?? 200 < 300 else { return }
        let file = "\(track.id).\(q == .flac ? "flac" : "mp3")"
        let dest = Self.dir.appendingPathComponent(file)
        try? FileManager.default.createDirectory(at: Self.dir, withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: dest)
        guard (try? FileManager.default.moveItem(at: tmp, to: dest)) != nil else { return }
        index.tracks[track.id] = Entry(track: track, file: file, quality: q)
    }

    private func deleteFiles(_ ids: [String]) {
        for id in ids {
            if let e = index.tracks.removeValue(forKey: id) {
                try? FileManager.default.removeItem(at: Self.dir.appendingPathComponent(e.file))
            }
        }
    }

    private func save() {
        try? FileManager.default.createDirectory(at: Self.dir, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(index) { try? data.write(to: Self.indexURL, options: .atomic) }
    }
}

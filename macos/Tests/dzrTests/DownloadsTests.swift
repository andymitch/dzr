import Foundation
import Testing
@testable import dzr

@MainActor @Suite struct DownloadsIndexTests {
    private func t(_ id: String) -> Item { Item(kind: .track, id: id, title: id, subtitle: "") }
    private func entry(_ id: String) -> Downloads.Entry { .init(track: t(id), file: "\(id).mp3", quality: .high) }
    private let liked = Item(kind: .playlist, id: "liked", title: "Liked Songs", subtitle: "")
    private let album = Item(kind: .album, id: "9", title: "Album", subtitle: "")

    @Test func sharedSongsSurviveRemovingOneCollection() {
        var index = Downloads.Index()
        _ = index.upsert(liked, trackIds: ["a", "b"])
        _ = index.upsert(album, trackIds: ["b", "c"])
        for id in ["a", "b", "c"] { index.tracks[id] = entry(id) }
        #expect(index.orphans(after: "liked") == ["a"])
        #expect(index.orphans(after: "9") == ["c"])
        #expect(index.orderedTracks.map(\.id) == ["a", "b", "c"])
    }

    @Test func updatingACollectionReportsSongsThatLeftIt() {
        var index = Downloads.Index()
        _ = index.upsert(liked, trackIds: ["a", "b"])
        _ = index.upsert(album, trackIds: ["b"])
        for id in ["a", "b"] { index.tracks[id] = entry(id) }
        #expect(index.upsert(liked, trackIds: ["c"]) == ["a"])
        #expect(index.collections.count == 2)
    }
}

import Foundation
import Testing
@testable import dzr

private func track(_ id: String, readable: Bool = true) -> Item {
    Item(kind: .track, id: id, title: id, subtitle: "", readable: readable)
}

private func ids(_ items: some Sequence<Item>) -> [String] { items.map(\.id) }

@Suite struct PlayQueueTests {
    @Test func playSkipsUnplayableAndStartsAtTheTappedTrack() {
        var q = PlayQueue()
        let list = [track("a"), track("b", readable: false), track("c"), Item(kind: .album, id: "x", title: "", subtitle: "")]
        let started = q.play(list, at: 2, from: nil)
        #expect(started)
        #expect(ids(q.tracks) == ["a", "c"])
        #expect(q.current?.id == "c")
        let startedUnplayable = q.play(list, at: 1, from: nil)
        #expect(!startedUnplayable)
    }

    @Test func nextIndexFollowsRepeatMode() {
        var q = PlayQueue()
        q.play([track("a"), track("b")], at: 1, from: nil)
        #expect(q.nextIndex(auto: true) == nil)
        q.repeatMode = .all
        #expect(q.nextIndex(auto: true) == 0)
        #expect(q.previousIndex() == 0)
        q.repeatMode = .one
        #expect(q.nextIndex(auto: true) == 1)
        #expect(q.nextIndex(auto: false) == nil)
    }

    @Test func shuffleKeepsCurrentAndUnshuffleRestoresOrder() {
        var q = PlayQueue()
        let list = (0..<30).map { track(String($0)) }
        q.play(list, at: 5, from: nil)
        q.toggleShuffle()
        #expect(q.current?.id == "5")
        #expect(q.index == 5)
        #expect(Set(ids(q.tracks)) == Set(ids(list)))
        #expect(ids(q.tracks.prefix(5)) == ids(list.prefix(5)))
        q.toggleShuffle()
        #expect(ids(q.tracks) == ids(list))
        #expect(q.current?.id == "5")
    }

    @Test func upNextEditsAreRelativeToTheCurrentTrack() {
        var q = PlayQueue()
        q.play([track("a"), track("b"), track("c"), track("d")], at: 1, from: nil)
        q.playNext(track("n"))
        #expect(ids(q.upNext) == ["n", "c", "d"])
        q.moveUpNext(from: [2], to: 0)
        #expect(ids(q.upNext) == ["d", "n", "c"])
        q.removeUpNext(at: [1])
        #expect(ids(q.upNext) == ["d", "c"])
        q.enqueue(track("e"))
        #expect(ids(q.upNext) == ["d", "c", "e"])
    }

    @Test func flowNeedsMoreNearTheEndAndAppendSkipsDuplicates() {
        var q = PlayQueue()
        let flow = Item(kind: .flow, id: "default", title: "Flow", subtitle: "")
        q.play((0..<10).map { track(String($0)) }, at: 0, from: flow)
        #expect(!q.needsMore)
        q.index = 7
        #expect(q.needsMore)
        let added = q.append([track("9"), track("10"), track("11", readable: false)])
        #expect(added == 1)
        #expect(q.tracks.count == 11)
        q.source = nil
        #expect(!q.needsMore)
    }

    @Test func removingABannedSongKeepsTheCurrentTrack() {
        var q = PlayQueue()
        q.play([track("a"), track("b"), track("c"), track("b")], at: 2, from: nil)
        q.removeOthers("b")
        #expect(ids(q.tracks) == ["a", "c"])
        #expect(q.current?.id == "c")
        q.removeOthers("c")
        #expect(q.current?.id == "c")
    }

    @Test func survivesASaveAndRestore() throws {
        var q = PlayQueue()
        q.play([track("a"), track("b")], at: 1, from: Item(kind: .playlist, id: "p", title: "Mine", subtitle: ""))
        q.repeatMode = .all
        let restored = try JSONDecoder().decode(PlayQueue.self, from: JSONEncoder().encode(q))
        #expect(restored == q)
    }
}

@Suite struct VersionTests {
    @Test func ordersReleasesBetasAndHotfixes() throws {
        let order = ["0.9", "0.10", "1-beta.1", "1-beta.2", "1", "1.1", "2-beta.1", "2"].map { Version($0)! }
        #expect(order == order.sorted())
        #expect(Version("v1") == Version("1.0.0"))
        #expect(Version("android-v1") == nil)
    }
}

@Suite struct DeezerParsingTests {
    @Test func mapsGenericApiResources() throws {
        let json = """
        {"data": {"type": "playlist", "id": "1", "attributes": {"title": "Mix"}, "included": [
          {"type": "track", "id": "10", "attributes": {"title": "Song", "artistName": "Band", "albumName": "Record", "artistId": 5, "albumId": 6, "duration": 200, "image": {"large": "https://cdn-images.dzcdn.net/a.jpg"}}},
          {"type": "albums", "id": "20", "attributes": {"name": "Record", "artist": {"id": 5, "name": "Band"}, "release_date": "2024-05-01"}},
          {"type": "artist", "id": "30", "attributes": {"name": "Band", "nbFans": 1200}},
          {"type": "playlist", "id": "40", "attributes": {"title": "List", "nb_tracks": 3, "creator": {"id": 9, "name": "Me"}}},
          {"type": "flow", "id": "chill", "attributes": {"title": "Chill"}}
        ]}}
        """
        let items = try JSONDecoder().decode(One.self, from: Data(json.utf8)).data.included!.map(\.item)
        #expect(items.map(\.kind) == [.track, .album, .artist, .playlist, .flow])
        #expect(items[0].subtitle == "Band · Record")
        #expect(items[0].duration == 200)
        #expect(items[0].artwork?.absoluteString == "https://cdn-images.dzcdn.net/a.jpg")
        #expect(items[1].subtitle == "Band · 2024")
        #expect(items[2].subtitle == "1,200 fans")
        #expect(items[3].subtitle == "Me · 3 tracks")
        #expect(items[3].creatorId == 9)
    }

    @Test func readsArtistIdsSentAsStrings() throws {
        let json = """
        {"data": {"type": "artist", "id": "27", "included": [
          {"type": "album", "id": "1", "attributes": {"name": "Homework", "artist": {"id": "27", "name": "Daft Punk"}, "release_date": "1997-01-20T00:00:00+00:00"}}
        ]}}
        """
        let items = try JSONDecoder().decode(One.self, from: Data(json.utf8)).data.included!.map(\.item)
        #expect(items.first?.title == "Homework")
        #expect(items.first?.subtitle == "Daft Punk · 1997")
    }
}

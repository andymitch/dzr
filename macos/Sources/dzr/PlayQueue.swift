import Foundation

enum Repeat: String, CaseIterable, Codable { case off, all, one }

struct PlayQueue: Codable, Equatable {
    var tracks: [Item] = []
    var original: [Item] = []
    var index = 0
    var shuffle = false
    var repeatMode = Repeat.off
    var source: Item?

    var current: Item? { tracks.indices.contains(index) ? tracks[index] : nil }
    var upNext: ArraySlice<Item> { tracks.indices.contains(index + 1) ? tracks[(index + 1)...] : [] }
    var needsMore: Bool { source?.kind == .flow && index >= tracks.count - 3 }

    @discardableResult
    mutating func play(_ list: [Item], at i: Int, from source: Item?) -> Bool {
        let playable = list.filter { $0.kind == .track && $0.readable }
        guard list.indices.contains(i), let start = playable.firstIndex(where: { $0.id == list[i].id }) else { return false }
        original = playable
        tracks = playable
        index = start
        self.source = source
        if shuffle { shuffleRest() }
        return true
    }

    mutating func playNext(_ t: Item) {
        tracks.insert(t, at: min(index + 1, tracks.count))
        original.append(t)
    }

    mutating func enqueue(_ t: Item) {
        tracks.append(t)
        original.append(t)
    }

    @discardableResult
    mutating func append(_ more: [Item]) -> Int {
        let known = Set(tracks.map(\.id))
        let fresh = more.filter { $0.kind == .track && $0.readable && !known.contains($0.id) }
        tracks += fresh
        original += fresh
        return fresh.count
    }

    mutating func moveUpNext(from: IndexSet, to: Int) {
        let base = index + 1
        tracks.move(fromOffsets: IndexSet(from.map { $0 + base }), toOffset: to + base)
    }

    mutating func removeUpNext(at offsets: IndexSet) {
        tracks.remove(atOffsets: IndexSet(offsets.map { $0 + index + 1 }))
    }

    mutating func toggleShuffle() {
        shuffle.toggle()
        guard let cur = current else { return }
        if shuffle {
            shuffleRest()
        } else {
            tracks = original
            index = tracks.firstIndex(of: cur) ?? 0
        }
    }

    mutating func cycleRepeat() {
        let all = Repeat.allCases
        repeatMode = all[(all.firstIndex(of: repeatMode)! + 1) % all.count]
    }

    func nextIndex(auto: Bool) -> Int? {
        guard !tracks.isEmpty else { return nil }
        if auto && repeatMode == .one { return index }
        if tracks.indices.contains(index + 1) { return index + 1 }
        return repeatMode == .all ? 0 : nil
    }

    func previousIndex() -> Int? {
        guard !tracks.isEmpty else { return nil }
        if tracks.indices.contains(index - 1) { return index - 1 }
        return repeatMode == .all ? tracks.count - 1 : nil
    }

    private mutating func shuffleRest() {
        guard index + 1 < tracks.count else { return }
        tracks[(index + 1)...].shuffle()
    }
}

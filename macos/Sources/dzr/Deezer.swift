import Foundation

struct Session: Codable {
    var accessToken: String
    var userId: Int64

    static var url: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("dzr/session.json")
    }

    static func load() -> Session? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Session.self, from: data)
    }

    func save() throws {
        try FileManager.default.createDirectory(at: Self.url.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try JSONEncoder().encode(self).write(to: Self.url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: Self.url.path)
    }

    static func clear() { try? FileManager.default.removeItem(at: url) }
}

enum DeezerError: LocalizedError {
    case unauthorized, http(Int), unavailable

    var errorDescription: String? {
        switch self {
        case .unauthorized: "Your Deezer session expired."
        case .http(let code): "Deezer returned HTTP \(code)."
        case .unavailable: "This track isn't available to stream."
        }
    }
}

enum Kind: String, Codable { case track, album, playlist, artist, flow, mix }

struct Item: Identifiable, Hashable, Codable {
    var kind: Kind
    var id: String
    var title: String
    var subtitle: String
    var duration: TimeInterval = 0
    var artistId: Int64 = 0
    var albumId: Int64 = 0
    var artwork: URL?
    var readable = true
    var creatorId: Int64 = 0
    var covers: [URL]?
}

struct Shelf: Identifiable {
    var title: String
    var items: [Item]
    var id: String { title }
}

struct Images: Decodable {
    var small: String?
    var medium: String?
    var large: String?
}

struct Attributes: Decodable {
    var title: String?
    var name: String?
    var artistName: String?
    var artistId: Int64?
    var albumName: String?
    var albumId: Int64?
    var duration: Int?
    var nb_tracks: Int?
    var nbFans: Int?
    var release_date: String?
    var readable: Bool?
    var image: Images?
    var pictures: Images?
    var images: [String: Images]?
    var artist: Named?
    var creator: Named?

    struct Named: Decodable {
        var id: Int64?
        var name: String?

        private enum CodingKeys: String, CodingKey { case id, name }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            name = try c.decodeIfPresent(String.self, forKey: .name)
            id = (try? c.decodeIfPresent(Int64.self, forKey: .id)) ?? (try? c.decodeIfPresent(String.self, forKey: .id)).flatMap { Int64($0) }
        }
    }
}

struct Resource: Decodable {
    var type: String
    var id: String
    var attributes: Attributes?
    var included: [Resource]?

    var item: Item {
        let a = attributes
        let img = a?.image ?? a?.pictures ?? a?.images?["square"]
        var it = Item(kind: .track, id: id, title: a?.title ?? a?.name ?? "", subtitle: "", artwork: (img?.large ?? img?.medium).flatMap(URL.init(string:)), readable: a?.readable ?? true)
        switch type.hasSuffix("s") ? String(type.dropLast()) : type {
        case "track":
            it.subtitle = "\(a?.artistName ?? "") · \(a?.albumName ?? "")"
            it.duration = TimeInterval(a?.duration ?? 0)
            it.artistId = a?.artistId ?? 0
            it.albumId = a?.albumId ?? 0
        case "album":
            it.kind = .album
            if it.title.isEmpty { it.title = a?.albumName ?? "" }
            it.subtitle = [a?.artist?.name ?? a?.artistName, a?.release_date.map { String($0.prefix(4)) }].compactMap { $0 }.joined(separator: " · ")
        case "playlist":
            it.kind = .playlist
            it.creatorId = a?.creator?.id ?? 0
            it.subtitle = [a?.creator?.name, a?.nb_tracks.map { "\($0) tracks" }].compactMap { $0 }.joined(separator: " · ")
        case "artist":
            it.kind = .artist
            if it.title.isEmpty { it.title = a?.artistName ?? "" }
            it.subtitle = a?.nbFans.map { "\($0.formatted()) fans" } ?? "Artist"
        default:
            it.kind = .flow
            it.subtitle = "Flow"
        }
        return it
    }
}

struct One: Decodable { var data: Resource }
private struct Many: Decodable { var data: [Resource] }

func items(_ rs: [Resource]?) -> [Item] { (rs ?? []).map(\.item) }

enum Quality: String, CaseIterable, Identifiable, Codable {
    case flac = "FLAC", high = "320 kbps", standard = "128 kbps"
    var id: String { rawValue }
}

struct LoginCode {
    var code: String
    var url: URL
}

final class Deezer {
    private static let appId = "447462"
    private static let api = URL(string: "https://api.deezer.com/")!
    var session: Session?

    init(session: Session?) { self.session = session }

    static func startLogin() async throws -> LoginCode {
        var req = URLRequest(url: URL(string: "https://connect.deezer.com/2.0/smartlogin?app_id=\(appId)")!)
        req.httpMethod = "POST"
        struct R: Decodable { var data: D; struct D: Decodable { var smartLoginCode: String; var url: String } }
        let r: R = try await fetch(req)
        return LoginCode(code: r.data.smartLoginCode, url: URL(string: r.data.url)!)
    }

    static func pollLogin(_ code: String) async throws -> Session {
        struct R: Decodable { var data: D; struct D: Decodable { var accessToken: String?; var userId: Int64? } }
        let req = URLRequest(url: URL(string: "https://connect.deezer.com/2.0/smartlogin/\(code)?app_id=\(appId)")!)
        while true {
            try Task.checkCancellation()
            if let r: R = try? await fetch(req), let token = r.data.accessToken, let user = r.data.userId {
                let s = Session(accessToken: token, userId: user)
                try s.save()
                return s
            }
            try await Task.sleep(for: .seconds(2))
        }
    }

    private static func fetch<T: Decodable>(_ req: URLRequest) async throws -> T {
        let (data, res) = try await URLSession.shared.data(for: req)
        let code = (res as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 { throw DeezerError.unauthorized }
        if code >= 300 { throw DeezerError.http(code) }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func request(_ path: String, method: String = "GET") -> URLRequest {
        var req = URLRequest(url: URL(string: path, relativeTo: Self.api)!)
        req.httpMethod = method
        req.setValue("Bearer \(session?.accessToken ?? "")", forHTTPHeaderField: "Authorization")
        return req
    }

    private func one(_ path: String) async throws -> Resource { try await (Self.fetch(request(path)) as One).data }
    private func many(_ path: String) async throws -> [Resource] { try await (Self.fetch(request(path)) as Many).data }

    private var user: String { "platform/generic/user/\(session?.userId ?? 0)" }
    private var castUser: String { "platform/gcast/user/\(session?.userId ?? 0)" }

    func home() async throws -> [Shelf] {
        async let flows = one("\(user)/multiflow")
        async let recs = one("\(castUser)/recommended-playlists")
        async let recents = try? one("\(castUser)/recently-played")
        async let releases = (try? newReleases()) ?? []
        async let discovery = try? one("platform/generic/smarttracklist/discovery")
        async let top = (try? classic("user/me/charts/tracks?limit=50")) ?? []
        let mixes = await withTaskGroup(of: (Int, Item?).self) { group in
            for i in 1...8 {
                group.addTask {
                    guard let r = try? await self.one("platform/generic/smarttracklist/inspired-by-\(i)"), !(r.included ?? []).isEmpty else { return (i, nil) }
                    let featuring = r.attributes?.title ?? ""
                    let lead = featuring.replacingOccurrences(of: "Featuring ", with: "").components(separatedBy: ",").first ?? ""
                    return (i, Item(kind: .mix, id: "inspired-by-\(i)", title: "Inspired by \(lead)", subtitle: featuring, artwork: r.item.artwork))
                }
            }
            return await group.reduce(into: []) { $0.append($1) }.sorted { $0.0 < $1.0 }.compactMap(\.1)
        }
        let releaseList = await releases
        let topTracks = await top
        var picks: [Item] = []
        if let d = await discovery, !(d.included ?? []).isEmpty {
            picks.append(Item(kind: .mix, id: "discovery", title: "Discovery", subtitle: d.attributes?.title ?? "", artwork: d.item.artwork))
        }
        if !topTracks.isEmpty {
            let month = Date().formatted(.dateTime.month(.wide))
            picks.append(Item(kind: .mix, id: Self.myTopId, title: "My Top \(month)", subtitle: Self.featuring(topTracks), artwork: topTracks.first?.artwork, covers: Self.covers(topTracks)))
        }
        var releaseShelf = releaseList
        if !releaseList.isEmpty {
            let artists = releaseList.prefix(4).map { $0.subtitle.components(separatedBy: " · ").first ?? "" }
            releaseShelf.insert(Item(kind: .mix, id: Self.newReleasesId, title: "New releases for you", subtitle: "Featuring " + artists.joined(separator: ", "), artwork: releaseList.first?.artwork, covers: Self.covers(releaseList)), at: 0)
        }
        return try await [
            Shelf(title: "Recently played", items: await renamingLoved(items(recents?.included))),
            Shelf(title: "Flow", items: items(flows.included)),
            Shelf(title: "New releases for you", items: releaseShelf),
            Shelf(title: "Mixes inspired by…", items: mixes),
            Shelf(title: "Recommended playlists", items: picks + items(recs.included)),
        ]
    }

    static let newReleasesId = "new-releases"
    static let myTopId = "my-top"

    private static func covers(_ list: [Item]) -> [URL] {
        var out: [URL] = []
        for url in list.compactMap(\.artwork) where !out.contains(url) {
            out.append(url)
            if out.count == 4 { break }
        }
        return out
    }

    private static func featuring(_ tracks: [Item]) -> String {
        var seen: [String] = []
        for t in tracks {
            let a = t.subtitle.components(separatedBy: " · ").first ?? ""
            if !a.isEmpty, !seen.contains(a) { seen.append(a) }
            if seen.count == 4 { break }
        }
        return "Featuring " + seen.joined(separator: ", ")
    }

    private struct Releases: Decodable {
        var data: [R]
        struct R: Decodable { var id: Int64; var title: String; var cover_xl: String?; var artist: A?; var release_date: String? }
        struct A: Decodable { var name: String }
    }

    func newReleases() async throws -> [Item] {
        let url = URL(string: Self.api.absoluteString + "user/me/recommendations/releases?access_token=\(session?.accessToken ?? "")")!
        let r: Releases = try await Self.fetch(URLRequest(url: url))
        return r.data.map {
            Item(kind: .album, id: String($0.id), title: $0.title, subtitle: [$0.artist?.name, $0.release_date.map { String($0.prefix(4)) }].compactMap { $0 }.joined(separator: " · "), artwork: $0.cover_xl.flatMap(URL.init(string:)))
        }
    }

    private func releaseMix() async throws -> [Item] {
        let releases = try await newReleases().prefix(20)
        return await withTaskGroup(of: (Int, Item?).self) { group in
            for (i, r) in releases.enumerated() {
                group.addTask { (i, try? await items(self.one("platform/generic/album/\(r.id)").included).first) }
            }
            return await group.reduce(into: []) { $0.append($1) }.sorted { $0.0 < $1.0 }.compactMap(\.1)
        }
    }

    private var lovedId: String?

    func lovedPlaylistId() async -> String? {
        if lovedId == nil { lovedId = try? await one("\(castUser)/loved-tracks").id }
        return lovedId
    }

    func playlists() async throws -> [Item] {
        let loved = await lovedPlaylistId()
        return try await items(one("\(castUser)/playlists").included).filter { $0.id != loved }
    }
    func albums() async throws -> [Item] { try await items(one("\(castUser)/loved-albums?start=0&limit=500").included) }
    func artists() async throws -> [Item] { try await items(one("\(castUser)/loved-artists").included) }
    func likedIds() async throws -> Set<String> { try await Set((one("\(castUser)/loved-tracks").included ?? []).map(\.id)) }

    private struct Classic: Decodable {
        var data: [T]
        struct T: Decodable {
            var id: Int64
            var title: String
            var duration: Int
            var readable: Bool?
            var artist: A
            var album: B
            struct A: Decodable { var id: Int64; var name: String }
            struct B: Decodable { var id: Int64; var title: String; var cover_xl: String? }
        }
    }

    @discardableResult
    private func write(_ method: String, _ path: String, _ query: [String: String] = [:]) async throws -> Data {
        var c = URLComponents(url: Self.api.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "access_token", value: session?.accessToken)] + query.map { URLQueryItem(name: $0.key, value: $0.value) }
        var req = URLRequest(url: c.url!)
        req.httpMethod = method
        req.httpBody = Data()
        let (data, res) = try await URLSession.shared.data(for: req)
        let code = (res as? HTTPURLResponse)?.statusCode ?? 0
        if code >= 300 || String(data: data, encoding: .utf8)?.contains("\"error\"") == true { throw DeezerError.http(code) }
        return data
    }

    func createPlaylist(_ title: String, tracks: [String] = []) async throws -> String {
        struct R: Decodable { var id: Int64 }
        let id = String(try JSONDecoder().decode(R.self, from: await write("POST", "user/me/playlists", ["title": title])).id)
        if !tracks.isEmpty { try await addToPlaylist(id, tracks) }
        return id
    }
    func addToPlaylist(_ id: String, _ tracks: [String]) async throws { try await write("POST", "playlist/\(id)/tracks", ["songs": tracks.joined(separator: ",")]) }
    func removeFromPlaylist(_ id: String, _ tracks: [String]) async throws { try await write("DELETE", "playlist/\(id)/tracks", ["songs": tracks.joined(separator: ",")]) }
    func renamePlaylist(_ id: String, _ title: String) async throws { try await write("POST", "playlist/\(id)", ["title": title]) }
    func deletePlaylist(_ id: String) async throws { try await write("DELETE", "playlist/\(id)") }

    private func classic(_ path: String) async throws -> [Item] {
        let sep = path.contains("?") ? "&" : "?"
        let url = Self.api.appendingPathComponent("").absoluteString + path + sep + "access_token=\(session?.accessToken ?? "")"
        let c: Classic = try await Self.fetch(URLRequest(url: URL(string: url)!))
        return c.data.map {
            Item(kind: .track, id: String($0.id), title: $0.title, subtitle: "\($0.artist.name) · \($0.album.title)", duration: TimeInterval($0.duration),
                 artistId: $0.artist.id, albumId: $0.album.id, artwork: $0.album.cover_xl.flatMap(URL.init(string:)), readable: $0.readable ?? true)
        }
    }

    func history() async throws -> [Item] { try await classic("user/me/history?limit=100") }
    func artistRadio(_ id: String) async throws -> [Item] { try await classic("artist/\(id)/radio?limit=100") }
    func trackMix(_ id: String) async throws -> [Item] { try await items(one("platform/generic/track/\(id)/mix").included) }
    func flowTracks(_ id: String) async throws -> [Item] { try await items(one("\(user)/multiflow/\(id)").included) }
    func lovedTracks() async throws -> [Item] { try await items(one("\(castUser)/loved-tracks").included) }

    struct LyricLine: Decodable {
        var milliseconds: String?
        var line: String
        var time: TimeInterval? { milliseconds.flatMap(Double.init).map { $0 / 1000 } }
    }

    func lyrics(_ trackId: String) async throws -> [LyricLine] {
        struct R: Decodable { var data: D; struct D: Decodable { var attributes: A }; struct A: Decodable { var items: [LyricLine]? } }
        return try await ((Self.fetch(request("platform/generic/lyrics/\(trackId)")) as R).data.attributes.items ?? []).filter { !$0.line.isEmpty }
    }

    private func renamingLoved(_ list: [Item]) async -> [Item] {
        let loved = await lovedPlaylistId()
        return list.map { var i = $0; if i.id == loved { i.title = "Liked Songs" }; return i }
    }

    func search(_ q: String) async throws -> [Shelf] {
        let q = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? q
        async let tracks = many("platform/generic/search/track?q=\(q)")
        async let artists = try? many("platform/generic/search/artist?q=\(q)")
        async let albums = try? one("platform/generic/search/album?q=\(q)")
        async let playlists = try? many("platform/generic/search/playlist?q=\(q)")
        return try await [
            Shelf(title: "Tracks", items: items(tracks)),
            Shelf(title: "Artists", items: items(artists)),
            Shelf(title: "Albums", items: items(albums?.included)),
            Shelf(title: "Playlists", items: items(playlists)),
        ]
    }

    func open(_ it: Item) async throws -> [Shelf] {
        switch it.kind {
        case .track: return []
        case .playlist:
            if it.id == (await lovedPlaylistId()) { return try await [Shelf(title: "Tracks", items: lovedTracks())] }
            return try await [Shelf(title: "Tracks", items: items(one("platform/generic/playlist/\(it.id)").included))]
        case .album: return try await [Shelf(title: "Tracks", items: items(one("platform/generic/album/\(it.id)").included))]
        case .flow: return try await [Shelf(title: "Tracks", items: flowTracks(it.id))]
        case .mix:
            switch it.id {
            case Self.newReleasesId: return try await [Shelf(title: "Tracks", items: releaseMix())]
            case Self.myTopId: return try await [Shelf(title: "Tracks", items: classic("user/me/charts/tracks?limit=50"))]
            default: return try await [Shelf(title: "Tracks", items: items(one("platform/generic/smarttracklist/\(it.id)").included))]
            }
        case .artist:
            async let top = one("platform/generic/artist/\(it.id)/top-tracks")
            async let albums = try? one("platform/generic/artist/\(it.id)/albums")
            async let similar = try? one("platform/generic/artist/\(it.id)/similar-artists")
            return try await [
                Shelf(title: "Top tracks", items: items(top.included)),
                Shelf(title: "Albums", items: items(albums?.included)),
                Shelf(title: "Similar artists", items: Array(items(similar?.included).prefix(20))),
            ]
        }
    }

    func streamURL(_ trackId: String, quality: Quality) async throws -> (URL, Quality) {
        struct R: Decodable { var data: D; struct D: Decodable { var attributes: A }; struct A: Decodable { var url: String?; var url_320: String?; var url_flac: String? } }
        let a = try await (Self.fetch(request("platform/gcast/track/\(trackId)/streamUrls")) as R).data.attributes
        let all: [(Quality, String?)] = [(.flac, a.url_flac), (.high, a.url_320), (.standard, a.url)]
        let start = all.firstIndex { $0.0 == quality } ?? 0
        for (q, u) in all[start...] {
            if let u, let url = URL(string: u) { return (url, q) }
        }
        throw DeezerError.unavailable
    }

    func ban(_ trackId: String) async throws {
        let (_, res) = try await URLSession.shared.data(for: request("platform/generic/track/\(trackId)/ban", method: "PUT"))
        if (res as? HTTPURLResponse)?.statusCode ?? 0 >= 300 { throw DeezerError.http((res as? HTTPURLResponse)?.statusCode ?? 0) }
    }

    func setLiked(_ trackId: String, _ liked: Bool) async throws {
        let (_, res) = try await URLSession.shared.data(for: request("platform/generic/track/\(trackId)/like", method: liked ? "POST" : "DELETE"))
        if (res as? HTTPURLResponse)?.statusCode ?? 0 >= 300 { throw DeezerError.http((res as? HTTPURLResponse)?.statusCode ?? 0) }
    }
}

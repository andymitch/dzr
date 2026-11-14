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

enum Kind: String { case track, album, playlist, artist, flow, mix }

struct Item: Identifiable, Hashable {
    var kind: Kind
    var id: String
    var title: String
    var subtitle: String
    var duration: TimeInterval = 0
    var artistId: Int64 = 0
    var albumId: Int64 = 0
    var artwork: URL?
    var readable = true
}

struct Shelf: Identifiable {
    var title: String
    var items: [Item]
    var id: String { title }
}

private struct Images: Decodable {
    var small: String?
    var medium: String?
    var large: String?
}

private struct Attributes: Decodable {
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

    struct Named: Decodable { var name: String? }
}

private struct Resource: Decodable {
    var type: String
    var id: String
    var attributes: Attributes?
    var included: [Resource]?

    var item: Item {
        let a = attributes
        let img = a?.image ?? a?.pictures ?? a?.images?["square"]
        var it = Item(kind: .track, id: id, title: a?.title ?? a?.name ?? "", subtitle: "", artwork: (img?.medium ?? img?.large).flatMap(URL.init(string:)), readable: a?.readable ?? true)
        switch type.hasSuffix("s") ? String(type.dropLast()) : type {
        case "track":
            it.subtitle = "\(a?.artistName ?? "") · \(a?.albumName ?? "")"
            it.duration = TimeInterval(a?.duration ?? 0)
            it.artistId = a?.artistId ?? 0
            it.albumId = a?.albumId ?? 0
        case "album":
            it.kind = .album
            it.subtitle = [a?.artist?.name, a?.release_date.map { String($0.prefix(4)) }].compactMap { $0 }.joined(separator: " · ")
        case "playlist":
            it.kind = .playlist
            it.subtitle = "\(a?.creator?.name ?? "") · \(a?.nb_tracks ?? 0) tracks"
        case "artist":
            it.kind = .artist
            it.subtitle = "\((a?.nbFans ?? 0).formatted()) fans"
        default:
            it.kind = .flow
            it.subtitle = "Flow"
        }
        return it
    }
}

private struct One: Decodable { var data: Resource }
private struct Many: Decodable { var data: [Resource] }

private func items(_ rs: [Resource]?) -> [Item] { (rs ?? []).map(\.item) }

enum Quality: String, CaseIterable, Identifiable {
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
        let mixes = [Item(kind: .mix, id: "discovery", title: "Discovery", subtitle: "Mix")]
            + (0..<3).map { Item(kind: .mix, id: "inspired-by-\($0)", title: "Inspired by #\($0 + 1)", subtitle: "Mix") }
        return try await [
            Shelf(title: "Flow", items: items(flows.included)),
            Shelf(title: "Mixes", items: mixes),
            Shelf(title: "Recommended playlists", items: items(recs.included)),
        ]
    }

    func playlists() async throws -> [Item] { try await items(one("\(castUser)/playlists").included) }
    func albums() async throws -> [Item] { try await items(one("\(castUser)/loved-albums?start=0&limit=500").included) }
    func artists() async throws -> [Item] { try await items(one("\(castUser)/loved-artists").included) }
    func likedIds() async throws -> Set<String> { try await Set((one("\(castUser)/loved-tracks").included ?? []).map(\.id)) }

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
        case .playlist: return try await [Shelf(title: "Tracks", items: items(one("platform/generic/playlist/\(it.id)").included))]
        case .album: return try await [Shelf(title: "Tracks", items: items(one("platform/generic/album/\(it.id)").included))]
        case .flow: return try await [Shelf(title: "Tracks", items: items(one("\(user)/multiflow/\(it.id)").included))]
        case .mix: return try await [Shelf(title: "Tracks", items: items(one("platform/generic/smarttracklist/\(it.id)").included))]
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

    func setLiked(_ trackId: String, _ liked: Bool) async throws {
        let (_, res) = try await URLSession.shared.data(for: request("platform/generic/track/\(trackId)/like", method: liked ? "POST" : "DELETE"))
        if (res as? HTTPURLResponse)?.statusCode ?? 0 >= 300 { throw DeezerError.http((res as? HTTPURLResponse)?.statusCode ?? 0) }
    }
}

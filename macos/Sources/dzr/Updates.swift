import AppKit

struct Version: Comparable {
    let parts: [Int]
    let beta: Int?

    init?(_ text: String) {
        let t = text.hasPrefix("v") ? String(text.dropFirst()) : text
        let split = t.split(separator: "-", maxSplits: 1)
        guard let head = split.first else { return nil }
        let nums = head.split(separator: ".").compactMap { Int($0) }
        guard !nums.isEmpty else { return nil }
        parts = nums + Array(repeating: 0, count: max(0, 3 - nums.count))
        beta = split.count > 1 ? Int(split[1].split(separator: ".").last ?? "") ?? 0 : nil
    }

    static func < (a: Version, b: Version) -> Bool {
        if a.parts != b.parts { return a.parts.lexicographicallyPrecedes(b.parts) }
        switch (a.beta, b.beta) {
        case let (x?, y?): return x < y
        case (_?, nil): return true
        default: return false
        }
    }
}

@MainActor
enum Updates {
    private struct Release: Decodable {
        let tag_name: String
        let html_url: URL
        let body: String?
        let prerelease: Bool
        let draft: Bool
    }

    static func check(manual: Bool) async {
        guard let text = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              !text.hasSuffix("-dev"), let current = Version(text) else { return }
        let alert = NSAlert()
        do {
            let url = URL(string: "https://api.github.com/repos/andymitch/dzr/releases?per_page=20")!
            let (data, _) = try await URLSession.shared.data(from: url)
            let newest = try JSONDecoder().decode([Release].self, from: data)
                .filter { !$0.draft && (current.beta != nil || !$0.prerelease) }
                .compactMap { r in Version(r.tag_name).map { (r, $0) } }
                .max { $0.1 < $1.1 }
            guard let (release, latest) = newest, current < latest else {
                guard manual else { return }
                alert.messageText = "You're up to date"
                alert.informativeText = "dzr v\(text) is the latest version."
                alert.runModal()
                return
            }
            alert.messageText = "dzr \(release.tag_name) is available"
            alert.informativeText = "You have v\(text).\n\n\(release.body ?? "")"
            alert.addButton(withTitle: "Download")
            alert.addButton(withTitle: "Later")
            NSApp.activate()
            if alert.runModal() == .alertFirstButtonReturn { NSWorkspace.shared.open(release.html_url) }
        } catch {
            guard manual else { return }
            alert.messageText = "Couldn't check for updates"
            alert.informativeText = error.localizedDescription
            alert.runModal()
        }
    }
}

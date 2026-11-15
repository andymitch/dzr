import AppKit

@MainActor
enum Updates {
    private struct Release: Decodable {
        let tag_name: String
        let html_url: URL
        let body: String?
    }

    static func check(manual: Bool) async {
        guard let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String else { return }
        let alert = NSAlert()
        do {
            let url = URL(string: "https://api.github.com/repos/andymitch/dzr/releases/latest")!
            let (data, _) = try await URLSession.shared.data(from: url)
            let release = try JSONDecoder().decode(Release.self, from: data)
            let latest = release.tag_name.trimmingCharacters(in: CharacterSet(charactersIn: "v"))
            guard latest.compare(current, options: .numeric) == .orderedDescending else {
                guard manual else { return }
                alert.messageText = "You're up to date"
                alert.informativeText = "dzr \(current) is the latest version."
                alert.runModal()
                return
            }
            alert.messageText = "dzr \(latest) is available"
            alert.informativeText = "You have \(current).\n\n\(release.body ?? "")"
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

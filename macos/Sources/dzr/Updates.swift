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
        let assets: [Asset]

        struct Asset: Decodable {
            let name: String
            let browser_download_url: URL
        }
    }

    private static var checking = false
    private static var lastCheck = Date.distantPast
    private static var wakeObserver: NSObjectProtocol?

    private static func name(_ release: Release) -> String {
        release.tag_name.hasPrefix("macos-") ? String(release.tag_name.dropFirst(6)) : release.tag_name
    }

    static func startDailyChecks() {
        Task { await check(manual: false) }
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                if Date().timeIntervalSince(lastCheck) > 24 * 3600 { Task { await check(manual: false) } }
            }
        }
        Task {
            while true {
                try? await Task.sleep(for: .seconds(24 * 3600))
                await check(manual: false)
            }
        }
    }

    static func check(manual: Bool) async {
        guard !checking, let text = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              !text.hasSuffix("-dev"), let current = Version(text) else { return }
        checking = true
        defer { checking = false }
        lastCheck = Date()
        let alert = NSAlert()
        do {
            let url = URL(string: "https://api.github.com/repos/andymitch/dzr/releases?per_page=30")!
            let (data, _) = try await URLSession.shared.data(from: url)
            let newest = try JSONDecoder().decode([Release].self, from: data)
                .filter { !$0.draft && (current.beta != nil || !$0.prerelease) }
                .compactMap { r in Version(name(r)).map { (r, $0) } }
                .max { $0.1 < $1.1 }
            guard let (release, latest) = newest, current < latest else {
                guard manual else { return }
                alert.messageText = "You're up to date"
                alert.informativeText = "dzr v\(text) is the latest version."
                alert.runModal()
                return
            }
            alert.messageText = "dzr \(name(release)) is available"
            alert.informativeText = "You have v\(text).\n\n\(release.body ?? "")"
            alert.addButton(withTitle: "Install and Relaunch")
            alert.addButton(withTitle: "Later")
            NSApp.activate()
            guard alert.runModal() == .alertFirstButtonReturn else { return }
            do {
                try await install(release)
            } catch {
                let failed = NSAlert()
                failed.messageText = "Couldn't install the update"
                failed.informativeText = "\(error.localizedDescription)\n\nYou can download it from the release page instead."
                failed.addButton(withTitle: "Open Release Page")
                failed.addButton(withTitle: "Cancel")
                if failed.runModal() == .alertFirstButtonReturn { NSWorkspace.shared.open(release.html_url) }
            }
        } catch {
            guard manual else { return }
            alert.messageText = "Couldn't check for updates"
            alert.informativeText = error.localizedDescription
            alert.runModal()
        }
    }

    private static func install(_ release: Release) async throws {
        guard let asset = release.assets.first(where: { $0.name.hasPrefix("dzr-macos-") && $0.name.hasSuffix(".zip") }) else {
            throw UpdateError("The release has no Mac download.")
        }
        let app = Bundle.main.bundleURL
        guard FileManager.default.isWritableFile(atPath: app.deletingLastPathComponent().path) else {
            throw UpdateError("dzr can't replace itself in \(app.deletingLastPathComponent().path).")
        }
        let (zip, _) = try await URLSession.shared.download(from: asset.browser_download_url)
        let staging = try stage(zip: zip)
        let script = """
        while kill -0 \(ProcessInfo.processInfo.processIdentifier) 2>/dev/null; do sleep 0.2; done
        rm -rf "$1" && mv "$2" "$1" && rm -rf "$3"
        open "$1"
        """
        let relaunch = Process()
        relaunch.executableURL = URL(fileURLWithPath: "/bin/sh")
        relaunch.arguments = ["-c", script, "sh", app.path, staging.appendingPathComponent("dzr.app").path, staging.path]
        try relaunch.run()
        NSApp.terminate(nil)
    }

    static func stage(zip: URL) throws -> URL {
        let staging = FileManager.default.temporaryDirectory.appendingPathComponent("dzr-update-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let unzip = Process()
        unzip.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        unzip.arguments = ["-x", "-k", zip.path, staging.path]
        try unzip.run()
        unzip.waitUntilExit()
        let app = staging.appendingPathComponent("dzr.app")
        guard unzip.terminationStatus == 0, Bundle(url: app)?.bundleIdentifier == "com.andymitch.dzr" else {
            try? FileManager.default.removeItem(at: staging)
            throw UpdateError("The download isn't a dzr app.")
        }
        return staging
    }

    private struct UpdateError: LocalizedError {
        let errorDescription: String?
        init(_ message: String) { errorDescription = message }
    }
}

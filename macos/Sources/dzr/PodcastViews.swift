import SwiftUI

extension AppModel {
    func playEpisode(_ episode: Item) {
        player.shuffle = false
        player.play([episode], at: 0, from: Item(kind: .podcast, id: episode.subtitle, title: episode.subtitle, subtitle: ""))
    }

    func podcastsShelf(_ term: String) async -> [Shelf] {
        [Shelf(title: "Podcasts", items: await podcasts.search(term))]
    }

    func newEpisodesShelf() async -> [Shelf] {
        await podcasts.refreshIfStale()
        return [Shelf(title: "New episodes", items: podcasts.newEpisodes)]
    }
}

struct PodcastsPage: View {
    @Environment(AppModel.self) private var model
    @State private var link: String?
    @State private var adding = false

    var body: some View {
        let shows = model.podcasts.shows.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        Group {
            if shows.isEmpty {
                ContentUnavailableView("No Podcasts", systemImage: "antenna.radiowaves.left.and.right", description: Text("Search for a podcast to subscribe, or add one by its RSS link."))
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 160, maximum: 200), spacing: 20)], alignment: .leading, spacing: 24) {
                        ForEach(shows, id: \.feed) { Card(item: $0.item) }
                    }
                    .padding(24)
                }
            }
        }
        .navigationTitle("Podcasts")
        .toolbar {
            Button { link = "" } label: { Label("Add by RSS Link", systemImage: "plus") }
                .help("Add a podcast by its RSS link")
                .disabled(adding)
        }
        .alert("Add a Podcast", isPresented: Binding(get: { link != nil }, set: { if !$0 { link = nil } })) {
            TextField("RSS link", text: Binding(get: { link ?? "" }, set: { link = $0 }))
            Button("Add") { add(link ?? "") }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func add(_ text: String) {
        guard let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), url.scheme?.hasPrefix("http") == true else {
            model.notice = "That isn't a web link."
            return
        }
        adding = true
        Task {
            if let podcast = await model.podcasts.fetch(url) { model.podcasts.subscribe(podcast) } else { model.notice = "Couldn't read a podcast from that link." }
            adding = false
        }
    }
}

struct PodcastPage: View {
    @Environment(AppModel.self) private var model
    let item: Item
    @State private var podcast: Podcast?
    @State private var failed = false

    private var feed: URL? { URL(string: item.id) }

    var body: some View {
        Group {
            if let podcast {
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        header(podcast)
                        EpisodeList(episodes: podcast.episodes)
                    }
                    .padding(.vertical, 24)
                }
            } else if failed {
                ContentUnavailableView("Couldn't Load", systemImage: "exclamationmark.triangle", description: Text("This podcast's feed didn't load."))
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(item.title)
        .task {
            guard let feed else { return failed = true }
            podcast = model.podcasts.show(feed)
            podcast = await model.podcasts.load(feed) ?? podcast
            failed = podcast == nil
        }
    }

    private func header(_ podcast: Podcast) -> some View {
        let subscribed = model.podcasts.show(podcast.feed) != nil
        return HStack(alignment: .bottom, spacing: 24) {
            Artwork(url: podcast.artwork, radius: 18)
                .frame(width: 200, height: 200)
                .shadow(color: .black.opacity(0.35), radius: 20, y: 10)
            VStack(alignment: .leading, spacing: 8) {
                Text("PODCAST").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Marquee(text: podcast.title).font(.system(size: 34, weight: .bold))
                if !podcast.author.isEmpty { Text(podcast.author).foregroundStyle(.secondary) }
                Group {
                    if subscribed {
                        Button { model.podcasts.unsubscribe(podcast.feed) } label: { Label("Subscribed", systemImage: "checkmark").padding(.horizontal, 6) }
                            .buttonStyle(.glass)
                    } else {
                        Button { model.podcasts.subscribe(podcast) } label: { Label("Subscribe", systemImage: "plus").padding(.horizontal, 6) }
                            .buttonStyle(.glassProminent)
                    }
                }
                .controlSize(.large)
                .padding(.top, 4)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 28)
    }
}

struct EpisodeList: View {
    var title: String?
    let episodes: [Item]
    var showName = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title { Text(title).font(.title2.bold()).padding(.horizontal, 24) }
            LazyVStack(spacing: 0) {
                ForEach(episodes) { EpisodeRow(episode: $0, showName: showName) }
            }
            .padding(.horizontal, 12)
        }
    }
}

struct EpisodeRow: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    let episode: Item
    var showName = false
    @State private var hover = false

    var body: some View {
        let progress = model.podcasts.progress[episode.id]
        let played = progress == Podcasts.played
        let position = max(progress ?? 0, 0)
        let isCurrent = player.current?.id == episode.id
        HStack(spacing: 12) {
            Image(systemName: isCurrent && player.isPlaying ? "pause.fill" : "play.fill")
                .foregroundStyle(isCurrent ? Color.accent : .secondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 3) {
                if showName { Text(episode.subtitle).font(.caption.weight(.semibold)).foregroundStyle(Color.accent).lineLimit(1) }
                Marquee(text: episode.title)
                    .fontWeight(isCurrent ? .semibold : .regular)
                    .foregroundStyle(played && !isCurrent ? .secondary : .primary)
                Text(details(position: position, played: played)).font(.caption).foregroundStyle(.secondary)
                if position > 0, episode.duration > 0 {
                    ProgressView(value: min(position / episode.duration, 1)).frame(maxWidth: 220)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(hover ? AnyShapeStyle(.quaternary) : AnyShapeStyle(.clear), in: .rect(cornerRadius: 10))
        .contentShape(.rect)
        .onHover { hover = $0 }
        .onTapGesture { isCurrent ? player.toggle() : model.playEpisode(episode) }
        .contextMenu {
            Button("Play") { model.playEpisode(episode) }
            if !played { Button("Mark as Played") { model.podcasts.markPlayed(episode.id) } }
        }
    }

    private func details(position: Double, played: Bool) -> String {
        let length: String? = if played {
            "Played"
        } else if position > 0, episode.duration > position {
            "\(minutes(episode.duration - position)) left"
        } else if episode.duration > 0 {
            minutes(episode.duration)
        } else {
            nil
        }
        return [episode.published?.formatted(date: .abbreviated, time: .omitted), length].compactMap { $0 }.joined(separator: " · ")
    }

    private func minutes(_ seconds: Double) -> String {
        let m = max(Int(seconds / 60), 1)
        return m >= 60 ? "\(m / 60) hr \(m % 60) min" : "\(m) min"
    }
}

struct PodcastControls: View {
    @Environment(Player.self) private var player
    var large = false

    var body: some View {
        HStack(spacing: large ? 22 : 16) {
            Menu {
                ForEach([0.8, 1, 1.2, 1.5, 1.75, 2], id: \.self) { speed in
                    Button { player.setSpeed(speed) } label: {
                        if player.podcasts.speed == speed { Label(speedLabel(speed), systemImage: "checkmark") } else { Text(speedLabel(speed)) }
                    }
                }
            } label: {
                Text(speedLabel(player.podcasts.speed)).monospacedDigit()
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .fixedSize()
            .foregroundStyle(player.podcasts.speed == 1 ? .secondary : Color.accent)
            .help("Playback speed")
            Button(action: player.previous) { Image(systemName: "gobackward.15") }
                .help("Back 15 seconds")
            PlayPauseButton(large: large)
            Button(action: player.next) { Image(systemName: "goforward.30") }
                .help("Forward 30 seconds")
            Button(action: player.toggleCutSilence) { Image(systemName: "waveform.badge.minus") }
                .foregroundStyle(player.podcasts.cutSilence ? Color.accent : .secondary)
                .help(player.podcasts.cutSilence ? "Cut silence: on" : "Cut silence: off")
        }
    }

    private func speedLabel(_ speed: Double) -> String {
        speed.formatted(.number.precision(.fractionLength(0...2))) + "×"
    }
}

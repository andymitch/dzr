# dzr

Custom Deezer client. Browse your Deezer library, play via YouTube. Two frontends share one Rust backend:

- **`dzr-tui`** — ratatui terminal app with FFT visualizer + album art (kitty/sixel/iterm2 graphics protocols)
- **`dzr` (Tauri)** — SvelteKit + WebKit desktop app

```
┌──── dzr ─────────────────────────┬──── Heavy Shoegaze · 50 ────────────┐
│  Flow                            │ ▶  Cold Room   Hand In Pants  4:17  │
│  Charts                          │    Wherever I Am   Ethan Tasch 3:28 │
│  Liked (42)                      │    Bandaid Heart   Fog Lake   4:21  │
│                                  │    …                                │
│  Playlists                       │                                     │
│  Heavy Shoegaze · 02/04/2026     │                                     │
│  Soft Sundays · 3                │                                     │
└──────────────────────────────────┴─────────────────────────────────────┘
┌──── Now Playing ───────────────────────────────────────────────────────┐
│  ████   Cold Room                                              ▆ █ ▆   │
│  ████   Hand In Pants · Cold Room                              ▄ █ █ ▄ │
│  ████   playing   vol  70%   shuffle off                       ▁ ▆ █ ▆ │
└────────────────────────────────────────────────────────────────────────┘
 q quit · ? help · / search · Tab switch pane · Space pause · n/p next/prev · s shuffle
```

## Why

Deezer stopped accepting new app registrations in 2025. No OAuth = no third-party clients via the official API. dzr works around it:

- **Metadata + library**: Deezer's public REST. Set your profile to public, paste your user ID, the app fetches `/user/{id}/playlists`, `/user/{id}/tracks`, `/user/{id}/flow`.
- **Playback**: search YouTube for the Deezer track (artist + title), download audio via yt-dlp, decode and play locally.

No ARL cookies, no API keys, no Deezer dev portal.

## Architecture

```
crates/
├── core/                       # shared library
│   ├── deezer.rs               # public REST client (search, user, playlists)
│   ├── ytsearch.rs             # native YouTube InnerTube search (~150 lines, no rusty_ytdl)
│   └── resolver.rs             # Deezer track → YouTube video_id; LRU + persistent disk cache
│
└── tui/                        # ratatui binary
    ├── main.rs                 # event loop, key bindings
    ├── app.rs                  # state, queue, prefetch
    ├── ui.rs                   # widgets, rounded borders, FFT bars
    ├── audio.rs                # rodio Sink, OutputStream, TappedSource
    ├── decode.rs               # symphonia (AAC/MP4 + MKV/WebM) + opus FFI for Opus packets
    ├── visualizer.rs           # SampleRing + FftBars (rustfft, log-spaced bands, smoothing)
    └── ytdlp.rs                # embeds yt-dlp via include_bytes!

src-tauri/                      # Tauri shell, consumes dzr-core
├── src/lib.rs                  # plugin wiring
├── src/commands.rs             # invoke handlers
└── tauri.conf.json             # sidecar yt-dlp, CSP

src/                            # SvelteKit frontend
├── lib/deezer.ts               # invokes Rust commands
├── lib/player.ts               # <audio> wrapper, queue store
└── routes/+page.svelte         # UI
```

## Resolution flow

```
 Deezer track ─────► dzr-core::ytsearch ─────► youtubei/v1/search
                                                     │
                                                     ▼
                                          [filter by artist+title+channel match]
                                          [pick closest duration]
                                                     │
                                                     ▼
                                              video_id (cached to disk)
                                                     │
       ┌──────────────────────────┬──────────────────┘
       ▼                          ▼
  TUI: yt-dlp -o file       Tauri: yt-dlp -g url
       │                          │
       ▼                          ▼
  symphonia + opus FFI       <audio src=url> in WebKit
       │
       ▼
  rodio Sink ──► CoreAudio
       │
       ▼
  TappedSource ──► SampleRing ──► FFT ──► viz bars
```

## Match quality

Heuristics in `crates/core/src/resolver.rs::match_track`:

1. Token both artist and title (lowercase alphanumeric, ≥3 chars, drop stopwords like `feat`, `remix`)
2. Require **all** artist tokens AND **all** title tokens in `video_title + channel`
3. Channel must look official: ends with ` - Topic`, contains `vevo`, or contains normalized artist name
4. Sort by duration delta vs Deezer's `duration` field; reject if best delta > max(5s, 5%)

False positives are surfaced as `✕` (red) in the TUI; tracks with no valid YouTube source are greyed out instead of pretending to be playable.

## Install (macOS, Apple Silicon)

```sh
git clone https://github.com/andymitch/dzr.git
cd dzr

# Toolchain
xcode-select --install                  # if missing
brew install cmake bun                  # cmake for libopus static build
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# yt-dlp binary embedded in TUI; downloaded once into the workspace
./scripts/fetch-ytdlp.sh                # writes src-tauri/binaries/yt-dlp-<triple>

# TUI
CMAKE_POLICY_VERSION_MINIMUM=3.5 cargo build -p dzr-tui --release --bin dzr-tui

# Tauri GUI
bun install
CMAKE_POLICY_VERSION_MINIMUM=3.5 bun run tauri build --bundles app
```

Build artifacts:

- `target/release/dzr-tui` — 50 MB single binary (yt-dlp embedded via `include_bytes!`)
- `target/release/bundle/macos/dzr.app` — 50 MB .app (yt-dlp shipped as sidecar)

Other platforms compile but no embedded yt-dlp is provided yet; install yt-dlp on PATH (`brew install yt-dlp` / `pipx install yt-dlp`).

## Run

```sh
./target/release/dzr-tui
# or
open ./target/release/bundle/macos/dzr.app
```

On first launch you're prompted for a Deezer user ID. Find yours at <https://www.deezer.com/profile/<ID>> while logged in. Profile must be set to **public** in Deezer account settings for playlists and likes to load. Stored at `~/Library/Application Support/com.klear.dzr/user_id`.

## TUI keys

| Key | Action |
|---|---|
| `j/k` `↑/↓` | move within pane |
| `h/l` `←/→` `Tab` | switch pane |
| `1` / `2` | focus sidebar / tracks |
| `g` / `G` | top / bottom |
| `Enter` | activate / play |
| `Space` | pause / resume |
| `n` / `p` | next / prev |
| `s` | toggle shuffle |
| `S` | shuffle play active list |
| `+` / `-` | volume |
| `/` | search Deezer |
| `?` | help |
| `q` `Ctrl-C` | quit |

Mouse: left-click row plays; scroll wheel moves the pane under the cursor.

## Caches

- `~/Library/Caches/com.klear.dzr/yt_match.json` — Deezer track id → YouTube video id (persistent)
- `~/Library/Caches/dzr/audio/<video_id>.{m4a,webm}` — downloaded audio files (no eviction yet)

Delete either to re-resolve from scratch.

## Known limitations

- **macOS arm64 first-class**, other platforms have to install yt-dlp manually.
- **OS media keys** (F7/F8/F9, Bluetooth play buttons) not wired. souvlaki integration was attempted but a TUI binary isn't classified as a media app by macOS without a code-signed `.app` bundle. Keyboard shortcuts above always work in the TUI.
- **yt-dlp rotations**: when YouTube changes their signature cipher, `brew upgrade yt-dlp` (or re-run `scripts/fetch-ytdlp.sh`) to refresh the embedded binary.
- **Match accuracy**: tied to YouTube's auto-generated `Artist - Topic` channels. Indie/Deezer-exclusive tracks correctly show as unresolvable (`✕`).
- **Audio quality**: capped at YouTube non-premium tiers (≈ 128 kbps AAC or 160 kbps Opus webm).
- **No playback position tracking yet**: scrubber in Tauri uses Deezer's duration; TUI shows track state but not elapsed time.

## License

MIT

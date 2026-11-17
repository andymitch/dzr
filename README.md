# dzr

<p align="center"><img src="assets/icon.svg" width="120" alt="dzr pixel heart"></p>

Unofficial Deezer clients for Android and macOS. Personal project, not affiliated with Deezer.

| App | Path | Stack |
| --- | --- | --- |
| Android | `android/` | Kotlin, Jetpack Compose, Material 3 Expressive, Media3 |
| macOS | `macos/` | Swift, SwiftUI (macOS 26 Liquid Glass), AVFoundation |

Downloads are on the [releases page](https://github.com/andymitch/dzr/releases): a zipped `dzr.app` and an arm64 Android APK.

## Features

Both apps:

- Sign in with Deezer's TV code flow (approve a short code at dzr.fm/tv)
- Home with Recently played, Flow and its moods, Discovery and Inspired by mixes, recommended playlists
- Search for tracks, artists, albums and playlists
- Library: liked songs, recently played, playlists, albums, artists
- Streaming in FLAC, 320 kbps or 128 kbps
- Offline downloads: Liked Songs, playlists and albums, with their own quality setting
- Artist Mix and Track Mix, synced lyrics, likes
- Create, rename and delete playlists and add or remove tracks

macOS:

- Menu bar mini player, optional menu-bar-only mode
- Up Next with Play Next, drag to reorder and remove
- Appearance and accent colour settings, including an accent that follows the album artwork

Android:

- Accent colours over black and white, Wallpaper colours, and a pure black (OLED) mode
- Three navigation styles, including one without a bottom bar
- Save a Track Mix as a playlist
- Android Auto: Home (Flow, song mixes, recommended playlists), Library and search on the car's screen

## Android Auto

Android Auto only lists apps installed from the Play Store until you allow unknown sources. One-time setup on the phone:

1. Open Android Auto's settings (Settings > Connected devices > Connection preferences > Android Auto).
2. Scroll to the bottom and tap **Version** 10 times, then confirm to turn on developer settings.
3. Open the menu (⋮) > **Developer settings** and turn on **Unknown sources**.
4. Reconnect to the car. dzr shows up in the app launcher.

Sign in to dzr on the phone first; the car shows "Sign in to dzr on your phone" until you do.

## Known gaps

dzr signs in with Deezer's TV login, which only reaches the API Deezer's TV and Cast apps use. A few things the official app does need its full account login, so dzr can't do them:

- **Don't play this artist.** Songs can be banned ("Don't play this song"), but there's no artist ban on that API.
- **Deezer's own song mixes on Home.** dzr builds song mixes from your recently played songs instead, using the same Track Mix Deezer plays.

## How it talks to Deezer

Both apps sign in with Deezer's TV device login and use the endpoints Deezer's TV and Cast apps use:

- `connect.deezer.com/2.0/smartlogin` issues and polls the login code
- `api.deezer.com/platform/generic/...` and `platform/gcast/...` serve Flow, mixes, library, search, lyrics and likes
- `platform/gcast/track/{id}/streamUrls` returns plain MP3 and FLAC URLs
- The public `api.deezer.com` REST API handles history, artist radio, liked dates and playlist editing

Streaming needs a paid Deezer plan, and any of this can stop working if Deezer changes those endpoints. The macOS app stores its session at `~/Library/Application Support/dzr/session.json` (readable only by you); Android keeps its own.

## Build

```sh
# macOS 26+. Command Line Tools are enough.
cd macos && ./bundle.sh && open build/dzr.app

# Android (JDK 21, Android SDK)
cd android && ./gradlew :app:assembleDebug     # debug build
cd android && ./gradlew :app:assembleRelease   # release build, signed if keystore.properties exists
```

Release signing reads `android/keystore.properties` (git-ignored):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it the release build is unsigned.

## Versions

Android and macOS are released separately, each with its own version and tags prefixed by the app: `android-vX` and `macos-vX`. A big feature or a batch of small ones is the next `vX`, a fix or small change is `vX.Y`, and a beta of the next big release is `vX-beta.N`. Before v1, releases were `v0.X` (shared up to `v0.6`, then per app).

Releases are cut by the Release Android and Release macOS workflows (Actions > Run workflow, then pick major, hotfix or beta). Each tags the next version for its app, builds it, and publishes notes grouped by the label of each issue closed by commits that touched that app's folder. Commits name the issues they close (`Closes #25`). Betas are GitHub pre-releases, so only people already on a beta are offered them. CI builds only the app whose folder changed.

Each app checks GitHub for its own newer release at launch. Local builds are `<latest tag>-dev` and skip the check. To build a specific version locally:

```sh
cd macos && VERSION=1 ./bundle.sh
cd android && ./gradlew :app:assembleRelease -PAPP_VERSION_NAME=1 -PAPP_VERSION_CODE=100
```

Android's version code is `X*100 + Y` for `vX.Y` and `(X-1)*100 + 50 + N` for `vX-beta.N`, so each install is newer than the last.

## Credits

The Android app started as a fork of [Pixer](https://github.com/Minuga-RC/Pixer), which is based on [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer). Both are MIT licensed; their copyright notices are kept in `android/LICENSE` and the bundled third-party notices.

## License

MIT

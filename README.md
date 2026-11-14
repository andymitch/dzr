# dzr

Personal Deezer clients. Private, unofficial, for my own use.

| App | Path | Stack |
| --- | --- | --- |
| Android | `android/` | Kotlin, Jetpack Compose, Media3 (fork of [Pixer](https://github.com/Minuga-RC/Pixer), itself a fork of [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer)) |
| macOS | `macos/` | Swift, SwiftUI, AVFoundation |
| Terminal | `tui/` | Go, Bubble Tea, Lip Gloss, Bubbles, beep |

## How it talks to Deezer

All three sign in with Deezer's TV device login (the app shows a short code, you approve it at
dzr.fm/tv) and use the same endpoints Deezer's TV and Cast apps use:

- `connect.deezer.com/2.0/smartlogin` issues and polls the login code.
- `api.deezer.com/platform/generic/...` and `platform/gcast/...` serve Flow, mixes, library, search
  and likes with `Authorization: Bearer <token>`.
- `platform/gcast/track/{id}/streamUrls` returns plain MP3 128, MP3 320 and FLAC URLs.

This needs a paid Deezer plan for streaming and can break whenever Deezer changes those endpoints.

The macOS and terminal apps share one session at `~/Library/Application Support/dzr/session.json`
(mode 600), so signing in to one signs in to both.

## Build

```sh
# Terminal
cd tui && go build -o dzr . && ./dzr            # -quality flac|320|128, -logout

# macOS (Command Line Tools are enough, no Xcode needed)
cd macos && ./bundle.sh && open build/dzr.app

# Android (JDK 21, Android SDK)
cd android && ./gradlew :app:assembleDebug
```

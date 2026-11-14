# dzr

Personal Deezer clients. Private, unofficial, for my own use.

| App | Path | Stack |
| --- | --- | --- |
| Android | `android/` | Kotlin, Jetpack Compose, Media3 (fork of [Pixer](https://github.com/Minuga-RC/Pixer), itself a fork of [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer)) |
| macOS | `macos/` | Swift, SwiftUI, AVFoundation |

## How it talks to Deezer

Both sign in with Deezer's TV device login (the app shows a short code, you approve it at
dzr.fm/tv) and use the same endpoints Deezer's TV and Cast apps use:

- `connect.deezer.com/2.0/smartlogin` issues and polls the login code.
- `api.deezer.com/platform/generic/...` and `platform/gcast/...` serve Flow, mixes, library, search
  and likes with `Authorization: Bearer <token>`.
- `platform/gcast/track/{id}/streamUrls` returns plain MP3 128, MP3 320 and FLAC URLs.

This needs a paid Deezer plan for streaming and can break whenever Deezer changes those endpoints.

The macOS app keeps its session at `~/Library/Application Support/dzr/session.json`
(mode 600).

## Build

```sh
# macOS 26+, needs Xcode with the Metal toolchain (for the header blur shader). Lives in the menu bar too;
# toggle "Show in Dock" from the app menu or the menu bar player to run menu-bar only.
cd macos && ./bundle.sh && open build/dzr.app

# Android (JDK 21, Android SDK)
cd android && ./gradlew :app:assembleDebug
```

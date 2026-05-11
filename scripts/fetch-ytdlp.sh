#!/usr/bin/env bash
set -euo pipefail

# Download yt-dlp binary matching the host rust target triple.
# Run after fresh clone or to bump version.

DIR="$(cd "$(dirname "$0")/.." && pwd)"
TRIPLE="$(rustc -vV | awk '/host:/ {print $2}')"
OUT="$DIR/src-tauri/binaries/yt-dlp-$TRIPLE"

case "$TRIPLE" in
  aarch64-apple-darwin|x86_64-apple-darwin)
    ASSET="yt-dlp_macos"
    ;;
  x86_64-pc-windows-msvc|aarch64-pc-windows-msvc)
    ASSET="yt-dlp.exe"
    OUT="$OUT.exe"
    ;;
  x86_64-unknown-linux-gnu)
    ASSET="yt-dlp_linux"
    ;;
  aarch64-unknown-linux-gnu)
    ASSET="yt-dlp_linux_aarch64"
    ;;
  *)
    echo "Unsupported triple: $TRIPLE" >&2
    exit 1
    ;;
esac

mkdir -p "$DIR/src-tauri/binaries"
echo "Fetching $ASSET → $OUT"
curl -fsSL -o "$OUT" "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$ASSET"
chmod +x "$OUT"
"$OUT" --version

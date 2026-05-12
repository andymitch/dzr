#!/usr/bin/env bash
# Build Tauri release and install to /Applications, avoiding the Spotlight
# duplicate that appears when both target/release and /Applications hold a copy.
set -euo pipefail
cd "$(dirname "$0")/.."

. "$HOME/.cargo/env"
CMAKE_POLICY_VERSION_MINIMUM=3.5 bun run tauri build --bundles app

SRC="target/release/bundle/macos/dzr.app"
DST="/Applications/dzr.app"

rm -rf "$DST"
cp -R "$SRC" "$DST"
rm -rf "$SRC"   # avoid Spotlight dupe

echo
echo "Installed: $DST"

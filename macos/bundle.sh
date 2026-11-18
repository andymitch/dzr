#!/bin/sh
set -eu
cd "$(dirname "$0")"
swift build -c release
if [ -z "${VERSION:-}" ]; then
  TAG=$(git describe --tags --match 'macos-v*' --match 'v*' --abbrev=0 2>/dev/null || echo v0)
  TAG=${TAG#macos-}
  VERSION="${TAG#v}-dev"
fi
APP=build/dzr.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/dzr "$APP/Contents/MacOS/dzr"
xcrun actool AppIcon.icon --compile "$APP/Contents/Resources" --app-icon AppIcon --platform macosx \
  --minimum-deployment-target 26.0 --target-device mac --output-partial-info-plist build/icon.plist >/dev/null

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>dzr</string>
  <key>CFBundleDisplayName</key><string>dzr</string>
  <key>CFBundleIdentifier</key><string>com.andymitch.dzr</string>
  <key>CFBundleExecutable</key><string>dzr</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIconName</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$VERSION</string>
  <key>LSMinimumSystemVersion</key><string>26.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.music</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST
codesign --force --sign - "$APP"
echo "$APP"

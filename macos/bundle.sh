#!/bin/sh
set -eu
cd "$(dirname "$0")"
swift build -c release
APP=build/dzr.app
rm -rf "$APP" build/icon.iconset
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" build/icon.iconset
cp .build/release/dzr "$APP/Contents/MacOS/dzr"
xcrun metal -c Shaders/HeaderBlur.metal -o build/HeaderBlur.air
xcrun metallib build/HeaderBlur.air -o "$APP/Contents/Resources/default.metallib"

qlmanage -t -s 1024 -o build ../assets/icon.svg >/dev/null
for s in 16 32 128 256 512; do
  sips -z $s $s build/icon.svg.png --out build/icon.iconset/icon_${s}x${s}.png >/dev/null
  sips -z $((s * 2)) $((s * 2)) build/icon.svg.png --out build/icon.iconset/icon_${s}x${s}@2x.png >/dev/null
done
iconutil -c icns build/icon.iconset -o "$APP/Contents/Resources/AppIcon.icns"

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
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>26.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.music</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST
codesign --force --sign - "$APP"
echo "$APP"

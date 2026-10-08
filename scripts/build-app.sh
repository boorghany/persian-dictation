#!/bin/zsh
# Builds build/Persian Dictation.app from the Swift package.
# With --install, also replaces /Applications/Persian Dictation.app and launches it.
set -euo pipefail

ROOT="${0:A:h:h}"
# ".noindex" keeps Spotlight from registering this copy with LaunchServices. Several
# registered copies with one bundle id confuse macOS's Accessibility permission.
APP="$ROOT/build.noindex/Persian Dictation.app"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister

cd "$ROOT"
swift build -c release

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$(swift build -c release --show-bin-path)/PersianDictation" "$APP/Contents/MacOS/PersianDictation"
cp Resources/Info.plist "$APP/Contents/Info.plist"

# App icon, drawn by the app itself (AppIcon.swift) so it matches the menu-bar icon.
ICONSET="$(mktemp -d)/AppIcon.iconset"
"$APP/Contents/MacOS/PersianDictation" --write-iconset "$ICONSET"
iconutil -c icns -o "$APP/Contents/Resources/AppIcon.icns" "$ICONSET"
rm -rf "${ICONSET:h}"

# Every build gets the next build number, shown in the app's menu as "<version> (build N)"; the version itself is set by hand in Resources/Info.plist.
BUILD_FILE="$ROOT/build-number.txt"
BUILD=$(( $(cat "$BUILD_FILE" 2>/dev/null || echo 0) + 1 ))
echo "$BUILD" > "$BUILD_FILE"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD" "$APP/Contents/Info.plist"
VERSION="$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP/Contents/Info.plist") (build $BUILD)"

# macOS ties the Accessibility permission to the signature. Signing with the same local
# certificate every time (see setup-signing.sh) keeps the permission across rebuilds;
# an ad-hoc signature would lose it on every build.
IDENTITY="Persian Dictation Local Signing"
if security find-certificate -c "$IDENTITY" >/dev/null 2>&1; then
  codesign --force --sign "$IDENTITY" "$APP"
else
  echo "warning: no \"$IDENTITY\" certificate; run scripts/setup-signing.sh. Signing ad-hoc." >&2
  codesign --force --sign - "$APP"
fi

"$LSREGISTER" -u "$APP" 2>/dev/null || true
echo "Built: $APP — version $VERSION"

if [[ "${1:-}" == "--install" ]]; then
  INSTALLED="/Applications/Persian Dictation.app"
  pkill -x PersianDictation || true
  sleep 1
  rm -rf "$INSTALLED"
  ditto "$APP" "$INSTALLED"
  "$LSREGISTER" -f "$INSTALLED"
  # The first launch after replacing the app reports the Accessibility permission as missing
  # (macOS re-validates the new copy); from the second launch on it is fine. So launch twice.
  sleep 2
  open "$INSTALLED"
  sleep 3
  pkill -x PersianDictation || true
  sleep 2
  open "$INSTALLED"
  echo "Installed and launched: $INSTALLED — version $VERSION"
fi

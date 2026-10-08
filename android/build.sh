#!/bin/zsh
# Builds the Android voice keyboard APK into dist/, and installs it if a phone is connected over adb.
set -euo pipefail

ROOT="${0:A:h}"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

# Every build gets the next build number, shown in the app as "<versionName> (build N)".
# The version itself is set by hand in app/build.gradle.kts.
BUILD_FILE="$ROOT/build-number.txt"
BUILD=$(( $(cat "$BUILD_FILE" 2>/dev/null || echo 0) + 1 ))
echo "$BUILD" > "$BUILD_FILE"

./gradlew :core:test :app:assembleDebug -PbuildNumber="$BUILD" --console=plain -q

VERSION_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
VERSION="$VERSION_NAME (build $BUILD)"
mkdir -p dist
APK="dist/PersianDictation-$VERSION_NAME-build$BUILD.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$APK"
echo "Built: $ROOT/$APK — version $VERSION"

if adb get-state >/dev/null 2>&1; then
  adb install -r "$APK" >/dev/null
  echo "Installed on $(adb shell getprop ro.product.model | tr -d '\r') — version $VERSION"
else
  echo "No phone connected over adb; not installed."
fi

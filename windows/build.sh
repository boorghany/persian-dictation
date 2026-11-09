#!/bin/zsh
# Builds the Windows app. Compiles on the Mac (Gradle, shares android/core), then packages it with
# jpackage inside the Parallels VM "Windows 11" (jpackage only makes Windows apps on Windows).
# Output in windows/dist/: PersianDictation-<version>-build<N>-Setup.exe (the installer, x64 + ARM64) and
# PersianDictation-<version>-build<N>-windows-{x64,arm64}.zip (portable: unzip, run "Persian Dictation.exe").
# With --install, also runs that Setup silently in the VM and launches the app.
set -euo pipefail

ROOT="${0:A:h:h}"
WIN="$ROOT/windows"
VM="Windows 11"
# The VM sees only the Mac's Downloads folder (Z:\Downloads), so staging goes there.
STAGE="$HOME/Downloads/PersianDictation-Windows-build"
STAGE_WIN='Z:\Downloads\PersianDictation-Windows-build'
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

if [[ "$(prlctl list -o status "$VM" 2>/dev/null | tail -1 | tr -d ' ')" != running ]]; then
  echo "Start the Parallels VM \"$VM\" first." >&2
  exit 1
fi

BUILD_FILE="$WIN/build-number.txt"
BUILD=$(( $(cat "$BUILD_FILE" 2>/dev/null || echo 0) + 1 ))
echo "$BUILD" > "$BUILD_FILE"
VERSION=$(sed -n 's/^val appVersion = "\(.*\)"/\1/p' "$WIN/build.gradle.kts")

(cd "$ROOT/android" && ./gradlew -q :core:test :windows:installDist)

mkdir -p "$STAGE/input" "$STAGE/dist"
rm -f "$STAGE/input/"*(N)
cp "$WIN/build/install/PersianDictation/lib/"*.jar "$STAGE/input/"
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -cp "$WIN/build/install/PersianDictation/lib/*" \
  local.voice2text.windows.MainKt --write-ico "$STAGE/PersianDictation.ico"
cp "$WIN/package.ps1" "$WIN/installer.iss" "$STAGE/"

# Microsoft Build of OpenJDK 21 for both architectures (jpackage + the bundled runtime). Downloaded once.
for arch in x64 aarch64; do
  [[ -s "$STAGE/jdk-$arch.zip" ]] || curl -fsSL -o "$STAGE/jdk-$arch.zip" "https://aka.ms/download-jdk/microsoft-jdk-21-windows-$arch.zip"
done

# Inno Setup (builds the installer); installed in the VM by package.ps1 on first use.
[[ -s "$STAGE/innosetup.exe" ]] || curl -fsSL -o "$STAGE/innosetup.exe" "https://github.com/jrsoftware/issrc/releases/download/is-6_7_4/innosetup-6.7.4.exe"

ARGS=(-Version "$VERSION" -Build "$BUILD")
[[ "${1:-}" == --install ]] && ARGS+=(-Install)
prlctl exec "$VM" --current-user powershell -NoProfile -ExecutionPolicy Bypass -File "$STAGE_WIN\\package.ps1" "${ARGS[@]}"

mkdir -p "$WIN/dist"
cp "$STAGE/dist/PersianDictation-$VERSION-build$BUILD-"*.{zip,exe} "$WIN/dist/"
ls -lh "$WIN/dist/"*"build$BUILD-"*
echo "Persian Dictation for Windows $VERSION (build $BUILD)"

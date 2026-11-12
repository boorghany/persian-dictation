# Persian Dictation

Persian (fa-IR) voice typing for macOS, Windows and Android, using Google's speech recognizer.

- **macOS** — menu-bar app: press ⌃⌥Space, speak, and the text is pasted into any app.
- **Windows** — notification-area app: press Ctrl+Alt+Space, speak, and the text is pasted into any app.
- **Android** — a voice-only keyboard to use next to Gboard (also on [Cafe Bazaar](https://cafebazaar.ir/app/ir.boorghany.persiandictation)).

Say «نقطه», «ویرگول», «علامت سوال», «خط بعد», «پاراگراف بعد» or «اینتر» for punctuation, new lines and Enter.

## Download

Get the latest builds from [Releases](../../releases). The Windows installer and the Mac app are not signed by a
known publisher; the release notes explain how to open them.

## How it works

The apps stream audio to the same Google endpoint that Chrome uses for the Web Speech API, with Chromium's public
API key. This endpoint is **unofficial and undocumented**: Google may change or block it at any time. Audio is sent
only while you are dictating. This project is not affiliated with or endorsed by Google.

## Building

- macOS (Swift package, macOS 13+): `./scripts/build-app.sh --install`
- Android (Gradle, JDK 17): `android/build.sh`
- Windows (Kotlin/JVM, packaged in a Windows VM): `windows/build.sh`

`CLAUDE.md` holds detailed development notes (design, workarounds for Google bugs, testing).

## License

[MIT](LICENSE)

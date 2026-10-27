# Persian Dictation — macOS app + Android voice keyboard

Persian (fa-IR) dictation that uses Google's speech recognizer — the user's quality bar is Gboard's
voice typing, and other engines (Whisper, Cloud STT, Claude's dictation) were rejected. Two products
share one design:

- **macOS** (Swift, repo root): menu-bar app; press a hotkey, speak, text is pasted into any app.
- **Android** (Kotlin, `android/`): a voice-only keyboard (IME) used *next to* Gboard, so Gboard's swipe
  typing and clipboard history stay available. The user's phone: Xiaomi Poco X6 Pro, latest HyperOS.

Personal use at first; since 2026-10-19 the Android app is also being published on **Cafe Bazaar** (see below).

## Working agreements

- After every code change, **build, install and launch it yourself** — don't ask the user to reinstall —
  then tell them the **build number** (shown in the Mac menu-bar menu / Android keyboard panel).
- **Android builds only when the user asks** (2026-10-11): don't run `android/build.sh` for every change.
  Still port shared-engine changes to `android/core` and check them with the core tests / replay tool. When
  asked for a build, set the Android `versionName` to the current Mac version, build, and reveal the APK.
- **Bump the version, not just the build number**, for every change you ship: minor (0.x → 0.x+1) for
  fixes and features, major for big changes. Mac: `CFBundleShortVersionString` in `Resources/Info.plist`;
  Android: `versionName` in `android/app/build.gradle*`. The build scripts only bump the build number.
- **Commit every change** to the local git repo (branch `main`, created 2026-10-08) after it builds and its
  tests/replays pass: one commit per change, message says what and why, version in the subject when bumped.
  Build output, APKs (`android/dist/`), `local.properties` and caches are git-ignored.
- **The user plans to publish this on GitHub later.** Keep secrets and personal data out of commits. Before
  the first push, review with the user: `testdata/recordings/*.wav` are the user's own voice and dictated
  text; this file mentions their phone, location/VPN and headset; the Chromium key is public but unofficial
  use of Google's endpoint should be explained in a README; add a LICENSE.
- **Development mode** (verbose log + a WAV recording of every Google session) is **off by default** since
  0.17.0 (the user's request, for other people installing it); turning it on first warns that private speech
  and text are saved. Without it, the log must not contain dictated text. To debug, ask the user to turn it on.
- To debug a recognition problem: find the session's recording, **replay it** (see Testing), reproduce,
  fix, then rerun the regression set. Measure before guessing — every workaround below came from logs.

## The engine (shared by both apps)

Google's **full-duplex streaming endpoint** used by Chrome's Web Speech API (unofficial; the user tested
its Persian quality in Chrome and approved it). Gboard's own backend (`speechs3proto2-pa`, S3 protocol)
was ruled out: private, needs app impersonation, fragile.

- Base: `https://www.google.com/speech-api/full-duplex/v1`, public Chromium key `AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw`.
- One session = random `pair` id shared by two requests:
  - **down**: `GET /down?key&pair&output=pb` → stream of 4-byte big-endian length-prefixed protobufs.
  - **up**: chunked `POST /up?key&pair&output=pb&lang=fa-IR&pFilter=0&app=chromium&continuous&interim`
    (bare flags), `Content-Type: audio/l16; rate=16000`, raw 16 kHz / 16-bit LE / mono PCM in 100 ms chunks.
- Protobuf: `SpeechRecognitionEvent {1: status (0 = ok), 2: repeated Result}`,
  `Result {1: repeated Alternative, 2: final bool, 3: stability float}`, `Alternative {1: transcript, 2: confidence}`.
  Interim text = concatenation of the non-final results' first alternatives.
- **The down request must reach Google before the up request**, or the session silently returns nothing.
  Google sends no down response headers until the up arrives (so you can't wait for them).
- Google accepts audio **faster than real time** (12 s of audio sent in a burst → final in ~2 s).
- The user's network goes through a **VPN** (Iran). Google's Android SDK/Maven servers returned 404 on one
  VPN exit; switching location fixed it. Through the VPN, new TLS handshakes sometimes stall 4–10 s.

## Workarounds for Google bugs (both apps, `ContinuousRecognizer` + helpers)

All found from real recordings; the Android `core` module is a line-by-line port of the Swift version.

1. **One Google session per utterance + pre-roll.** In continuous mode, after Google finalizes an
   utterance it clips the start of the next one if the user resumes quickly ("می‌خوام" → "ی‌خوام",
   "نمونه" → "مونه", "رو" → "و"), and sometimes drops whole phrases. A fresh session recognizes the
   same audio correctly. So on every final we hand over to a new session and send it a **pre-roll**:
   - keep the last 8 s of audio; walk back over current speech to the last silence gap ≥ 800 ms;
   - start 600 ms into that gap (fallback: last 1 s).
   - Why 800 ms: across 78 finals Google only ever finalized after ≥ 1.24 s of silence, so shorter
     pauses can't be the utterance boundary. (With a 200 ms gap, words spoken during a recovery were lost.)
   - Silence threshold per 20 ms frame: `max(3 × 20th-percentile level, 150)` RMS.
   - **Hard limit after a normal session** (pre-roll ≤ 1 s): the pre-roll never starts before the old
     session's last interim text change + 250 ms. Clicks/breaths after an utterance broke up the 800 ms gap,
     the walk-back crossed the finalized utterance, the next session re-finalized it from the pre-roll, and
     "من راستش اینجا" was typed 4 times (2026-10-08). Not after a recovery session: its audio went out in a
     burst, so text-change times say nothing about speech times (limiting there lost the next utterance).
   - **Duplicate-final backstop:** a final equal to the previous final that arrives within
     pre-roll + 1 s of its session's start is dropped (`dropped duplicate final` in the log).
2. **Freeze watchdog.** Google sometimes stops sending results mid-utterance while the user keeps talking,
   then sends an **empty final**, losing the sentence. Detected as: ≥ 3 s without new interim text while
   ≥ 0.8 s of speech was heard, or an empty final after non-empty interim text. Recovery: open a fresh
   session and **replay all audio of the current utterance** (up to 55 s) in one burst. Max 2 recoveries
   per utterance; after that, use the last interim text if it has ≥ 2 words (else drop), or start clean.
   The clock starts when the session is actually connected (Android: OkHttp `requestHeadersEnd`; Mac: first
   `didSendBodyData` of the up request, or any interim); not connected within 4 s → retry with the audio.
   Before the Mac had this (≤ 0.2.0), a 3–7 s VPN handshake was mistaken for a freeze (2026-10-07 afternoon:
   57 "freezes" in 49 dictations, TLS connects > 2 s for 68 of 316 connections).
3. **Prefix-drop repair** (`PrefixDropRepair`): Google sometimes erodes the first 1–2 letters of a word
   it had right (علی → لی → ی, لاجیک → اجیک). Every interim of the utterance is remembered; a word that
   equals an earlier word minus 1–2 leading letters *at the same position* (same neighbouring word) is
   restored to the longest earlier version. ZWNJ is ignored when comparing (می‌تونی = میتونی).
   Not changed: suffix drops, 3+ letters, different positions. Backstop only — fix 1 removed most cases.
4. **Reconnect** when Google ends a session (long silence / ~60 s), reusing the pre-roll; 3 consecutive
   errors → stop and show "can't reach Google… check VPN".
5. **Corrections list** (`Corrections`, Persian only, separate from punctuation and applied before it, to
   preview and final): fixed whole-word replacements for words Google writes differently from how the user
   wants them — currently عرشیا → ارشیا, بورغانی → بورقانی (names), ارز → عرض, ارزی → عرضی (the user means width, never currency). Add new entries to both `Corrections.swift` and `.kt`.
6. **Spoken punctuation** (`SpokenPunctuation`, Persian only), longest phrase first, whole words only
   ("نقطه‌ی" untouched): علامت سوال / علامت سؤال → `؟`, علامت تعجب → `!`, پاراگراف بعد / پاراگراف بد → two newlines,
   خط بعد / خط بد (Google mishears بعد) → newline,
   دو نقطه / دونقطه → `:`, نقطه → `.`, ویرگول → `،`, پرانتز باز → `(`, پرانتز بسته → `)`. No space before
   symbols (incl. `)`), one after if a word follows (decimals and times like 10:30 untouched); `(` gets a space
   before and none after; no spaces around newlines. `attachesToNext` (text ending in `(` or a newline): the
   next final gets no leading space (Mac `lastInsertAttachesToNext`, Android `needsSpaceBefore`).
   **Mac: trailing line breaks are held back** (0.21.0, `DictationController.pendingBreaks`) and pasted at the
   start of the next final, or alone when dictation ends. Many editors (the browser, Quill-based ones) drop
   or hide a line break at the very end of pasted text, so a "پاراگراف بعد" pasted on its own (`\n\n`)
   showed as one line break and the user had to say "خط بعد" again (log 2026-10-22 00:33, 00:37).
   Known gaps: literal "خط بد" becomes a newline; "۲ نقطه" written with a digit isn't converted.

## macOS app (repo root)

Swift package `PersianDictation`, macOS 13+, built with Command Line Tools (no Xcode).

| File | Role |
|---|---|
| `SpeechStream.swift` | Google endpoint client. **One shared URLSession** (`SessionRouter` forwards task callbacks to each stream) = one HTTP/2 connection for all sessions. Until 0.2.0 every session had its own URLSession → a new TLS handshake through the VPN per handover/recovery (see the Android networking lesson) |
| `ContinuousRecognizer.swift` | sessions per utterance, pre-roll, watchdog, fault simulation |
| `PrefixDropRepair.swift`, `SpokenPunctuation.swift` | workarounds 3 and 5 |
| `DictationController.swift` | mic → recognizer → paste; overlay; `Settings` (language, dev mode, API key) |
| `AudioCapture.swift` | **AVCaptureSession** (not AVAudioEngine) → 16 kHz mono Int16 via `audioSettings`, re-chunked to 100 ms. Menu **Microphone**: "System Default (name)" follows the macOS input (CoreAudio default-input listener, switches mid-dictation) or a fixed device (`Settings.microphoneID`, falls back to default while disconnected). Switches on disconnect, and to the built-in mic if the device sends no/all-zero audio for 3 s (overlay shows "🎤 name — reason"). Why: with AVAudioEngine the soundcore Liberty 5 Bluetooth headset gave 0-byte recordings even after restarting on `AVAudioEngineConfigurationChange` (0.3.0–0.8.0). Log: `microphone: X (reason)`, `capture format: …` |
| `TextInserter.swift` | paste via clipboard + synthetic ⌘V, item marked transient. The text is a **lazy data provider**, so we know when the target app reads it; the user's clipboard is restored ≥ 1.5 s after ⌘V and 1 s after the last read (5 s if never read; skipped if the user copied something meanwhile). The saved clipboard survives back-to-back pastes. Why: with a fixed 0.4 s, Chrome on Google Calendar often read the clipboard after it was restored — Edit menu flashed, nothing pasted. Dev log: `paste read after N ms` / `paste never read` |
| `HotKey.swift` | Carbon global hotkeys (no extra permission) |
| `OverlayPanel.swift` | non-activating floating RTL preview at the top of the **main display** (`NSScreen.screens.first`, the menu-bar display — the user tried one box per display and preferred only the main one), on every Desktop (`.canJoinAllSpaces`). macOS sometimes drops that membership — seen after unplugging the external monitor: the window stayed on one Desktop (`CGSCopySpacesForWindows` → one space) and ordering it front didn't help — so the window is **recreated** on every display change and whenever it isn't on the current Desktop at show time or after a Desktop switch. Dev mode logs `overlay show:` / `overlay space changed` / `overlay recreated (reason)` |
| `AppDelegate.swift` | menu bar, hotkeys, version (`AppVersion`), dev-mode toggle, "Open Logs Folder" |
| `SessionRecorder.swift`, `DiagnosticsLog.swift` | dev-mode WAVs and log |
| `DiagnosticsExport.swift` | menu **Share Diagnostics…**: zips info.txt (version, macOS, mics, displays, permissions) + logs + last 48 h of recordings into `~/Downloads/PersianDictation-diagnostics-<UTC>.zip` (~100 MB/day of recordings), opens the share picker. CLI: `PersianDictation --export-diagnostics` prints the zip path (run the `/Applications` binary so Settings match; permissions then show the Terminal's, not the app's) |
| `main.swift` | app entry; also the `--transcribe` replay CLI |

Features: ⌃⌥Space toggles dictation (⌃Space is left for input-source switching). While listening only,
**Esc** and **Enter** (main or keypad) stop, finalize and paste; **Shift+Enter** is not intercepted
(Carbon hotkeys match exact modifiers). Enter does *not* send in chat apps — offered as an option, not built.
**Enter, Enter = paste the rest, then send** (0.22.0): after the first Enter the app waits (0.1–6 s) for
Google's last final. Before, Enter was only caught while listening, so a quick second Enter reached the chat
app and sent the message without the last words, which were then pasted into the empty box. Now Enter stays
registered while finishing; pressed again, it is pressed in the target app (`TextInserter.pressReturnAfterPaste`)
200 ms after the app read the last paste — not if the paste was never read. Log: `dictation stop (Enter|Esc|…)`,
`Enter while finishing…`, `pressing Enter after the last paste`.
The overlay goes back to the placeholder as soon as a final is pasted (0.22.0); before, the pasted text stayed
there up to ~1 s, until the next session's first interim, and looked like the removed early-paste feature.
**Tried and removed:** pasting the interim's stable words after a 0.6 s pause, before Google's final
(0.16.0, commit 82b358a). It didn't work well in practice, so 0.20.0 reverted it; only finals are pasted.
Language menu (fa-IR / en-US). Version shown as the first menu line, e.g. "Persian Dictation 0.2.0 (build 8)".

Build & install: `./scripts/build-app.sh --install` → bumps `build-number.txt`, builds into
`build.noindex/`, signs, replaces `/Applications/Persian Dictation.app`, launches it **twice** (the first
launch after replacing the app often reports Accessibility as missing; the second is fine). Then check
`~/Library/Logs/PersianDictation.log` for `launch version="…"` and `accessibilityTrusted=true`.

Signing / Accessibility (painful history — don't regress):
- Ad-hoc signatures change every build and macOS forgets the Accessibility grant. `scripts/setup-signing.sh`
  created the self-signed identity **"Persian Dictation Local Signing"** in the login keychain and trusted
  it for code signing; the build signs with it. The keychain asks "codesign wants to access key 'cert'" —
  answer **Always Allow**.
- Several registered copies of the app (old build folder, a copy in the Trash) made the grant attach to the
  wrong copy. Builds now go to `build.noindex/` and are unregistered from LaunchServices; only
  `/Applications` stays registered. If the grant is lost: remove the entry with "−" in System Settings →
  Privacy & Security → Accessibility (toggling off is not enough), then re-grant.

Dev mode (menu toggle, default off, confirmation alert before turning on): log `~/Library/Logs/PersianDictation.log` (session open/handover/end,
every interim/final with session-relative ms, repairs, recoveries, mic name), recordings
`~/Library/Logs/PersianDictation/recordings/<UTC>-s<N>.wav` = exact audio sent to that session
(pre-roll included), pruned after 7 days.

## Android app (`android/`)

Gradle (AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1 wrapper, JDK 17 at `/opt/homebrew/opt/openjdk@17`,
SDK at `/opt/homebrew/share/android-commandlinetools`, compileSdk/targetSdk 35, minSdk 28, no AndroidX).

- `core/` — plain Kotlin/JVM engine shared by the app and the Mac replay tool: `SpeechStream.kt`
  (OkHttp 4.12), `ContinuousRecognizer.kt` (own single thread; all public methods are thread-safe;
  `AudioBuffer` ring), `PrefixDropRepair.kt`, `SpokenPunctuation.kt`, `WavRecorder.kt`. Unit tests in
  `core/src/test` (punctuation, repair, buffer).
- `replay/` — Mac CLI that streams a recording through `core` in real time.
- `app/` — `VoiceInputService` (the IME), `SetupActivity` (launcher), `AppEnvironment.kt`
  (`Settings`, `AppLog`, `MicCapture`, `versionDisplay`), `DiagnosticsExport` + `DiagnosticsProvider`
  (setup screen **Share diagnostics**: zips info.txt + everything in the files dir except `test-input.wav`,
  recordings only from the last 48 h, into `cache/diagnostics/` and opens the share sheet; the provider is a
  minimal private FileProvider stand-in since there's no AndroidX. `Settings.Secure.ENABLED_INPUT_METHODS`
  throws for targetSdk > 33 — use `InputMethodManager`).

**Networking lesson:** `SpeechStream` must use **one shared OkHttp client over HTTP/2**. With separate
connections (HttpURLConnection, raw sockets) the up request could overtake the down request (empty
sessions, false "freezes") and every session paid a VPN TLS handshake (sometimes 4–10 s). The up request
starts only after OkHttp's `requestHeadersEnd` for the down request; `onConnected` feeds the watchdog.

IME behaviour (0.17.0): opens **paused** — the user taps 🎤 to start (before: listened as soon as shown).
Interim text is shown in the panel and as underlined composing text in the field; finals are committed with a
space if the text before the cursor needs one. Keys: ⌨ back to Gboard immediately (`switchToPreviousInputMethod`,
falls back to the picker), ⌫, ◀, ▶ (all hold to repeat, 150 ms). **⌫ deletes a whole word**: the word before the
cursor, or the whole word the cursor is in, plus the spaces before it, keeping one space when words remain on
both sides; a line break before the cursor alone; a selection as a whole. **◀ ▶ move by words** (Persian is RTL:
▶ = back to the start of the previous word, ◀ = forward to the end of the next). While listening, these keys
first pause (type the last interim as is, cancel the recognizer). Removed in 0.17.0: ⏎ (finish + Enter) and
✓ (same as ⌨). Hiding the keyboard keeps the composing text and stops. Mic: `AudioRecord` VOICE_RECOGNITION.
Half-open panel bug (keys cut off at the bottom, seen on the phone): the bottom padding for the navigation bar
was set only when insets arrived, after the first layout, changing the keyboard's height while it slid in. Now
it's set before the first layout (decor insets or `navigation_bar_height`) and changed only when the inset really
changes (log `navigation bar inset A → B`). Unconfirmed fix — ask for a screenshot if it happens again.
`method.xml` declares no subtypes so it is one entry in the keyboard picker.

Dev mode (switch in the setup screen, default off, confirmation dialog before turning on): `Android/data/ir.boorghany.persiandictation/files/`
holds `PersianDictation.log` (+ connection traces) and `recordings/`. Test hook: if `test-input.wav`
exists there, it is played instead of the microphone (used for emulator tests; delete it afterwards).

**Cafe Bazaar** (2026-10-19, the user asked for it): developer account boorghany@gmail.com, developer title
احمد بورقانی, draft app 225251 at pishkhan.cafebazaar.ir. Listing: name تایپ صوتی فارسی / Persian Dictation, category
ابزارها, free, support email boorghany@gmail.com; texts say audio goes to Google only while listening and that it is not a
Google product. `applicationId` is **`ir.boorghany.persiandictation`** (permanent once published; the Kotlin package /
namespace stays `local.voice2text…`, so component names are `ir.boorghany.persiandictation/local.voice2text.persiandictation.X`).
`android/build.sh --release` builds `dist/…-release.apk` signed with `android/signing/release.jks`
(+ `keystore.properties` with the passwords; both git-ignored — **the user must back them up**, without them Bazaar
won't take updates). Store assets in `android/store/` (512 px icon rendered from the Mac `AppIcon` code, emulator screenshots).
Every Bazaar update needs a higher `versionCode` (= build number).

Build & install: `android/build.sh` → bumps `android/build-number.txt`, runs core tests, builds
`android/dist/PersianDictation-<versionName>-build<N>.apk`, and `adb install -r`s it if a device is connected.
Emulator for smoke tests: AVD `pd35` (Android 15, Google APIs, has Gboard):
`$ANDROID_HOME/emulator/emulator -avd pd35 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect`,
then `adb shell ime enable/set ir.boorghany.persiandictation/local.voice2text.persiandictation.VoiceInputService`.

Status (2026-10-07): build 3 passes all replays and an emulator end-to-end test (recording played in,
text landed in the field with correct spacing/ZWNJ, ✓ returned to Gboard). 2026-10-08: the user has been
using it on the phone (an older build) and reported bad bugs; get their logs via Share diagnostics (0.3.0+)
or wireless adb. Before that: **not yet on the user's phone** — they took the APK to install via Telegram Saved Messages. For future updates, wireless adb
is recommended: HyperOS Developer options → USB debugging + **Install via USB** (needs Mi account) +
Wireless debugging → pair with code (`adb pair IP:PORT CODE`, then `adb connect`). Real-mic and HyperOS
behaviour are untested.

## Testing

- `test/index.html`: the Chrome Web Speech page from the feasibility stage (`python3 -m http.server` in `test/`, open in Google Chrome); the user's quality check of the engine.

- Mac replay: `.build/release/PersianDictation --transcribe <wav> fa-IR [--simulate-freeze silence:<session>:<seconds> | empty-final:<session>]`
- Android engine on the Mac: `cd android && ./gradlew :replay:installDist` then
  `replay/build/install/replay/bin/replay <wav> fa-IR [--verbose] [--simulate-freeze …]`
  (needs `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`).
- Don't run many replays in parallel: CPU contention once delayed the watchdog and lost a word.

Regression set in `testdata/recordings/` — expected finals (both engines must match):

| Recording | Expected finals |
|---|---|
| `2026-10-07T05-55-36Z-s1.wav` | غیر از متن پیامک · می‌خوام · یه شماره یا · برند فرستنده و یه شمارنده · گیرنده که · متناسب با یک مخاطب انگلیسی باشه · برام بدی |
| `2026-10-07T05-52-37Z-s1.wav` | گت یور پسکیز · رو نشون نده علامت سوال من علاقه دارم رمزهام با گوگل · سینک بشه ولی پس کیها نه · نقطه |
| `2026-10-08T14-58-31Z-repeated-final.wav` (stitched from 8 session recordings) | گیج شدم · چون اون بالا نوشتی فرایند به این صورت است · من راستش اینجا (once; ≤ 0.9.0 typed it 4 times) |
| `2026-10-07T05-55-36Z-s1-first12s.wav` | with each of `silence:1:2.0`, `empty-final:1`, `silence:2:0.5`: غیر از متن پیامک · می‌خوام · یه شماره یا |

Recordings 05-55-36, 05-54-38 and 05-52-37 reproduce the clipped-start bug and 06-00-38 the freeze when
replayed in a single continuous session; the old buggy outputs were ی‌خوام, مونه, و, and a lost sentence.

## Open ideas

- Test on the phone (real mic, HyperOS) and iterate from its log/recordings.
- Mac: optionally press Enter after the final paste (send in chat apps).
- Handle "۲ نقطه" (digit) and a way to say "خط بد" literally, if they come up.

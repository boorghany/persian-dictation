# Persian Dictation — macOS app + Android voice keyboard + Windows app

Persian (fa-IR) dictation that uses Google's speech recognizer — the user's quality bar is Gboard's
voice typing, and other engines (Whisper, Cloud STT, Claude's dictation) were rejected. Three products
share one design:

- **macOS** (Swift, repo root): menu-bar app; press a hotkey, speak, text is pasted into any app.
- **Android** (Kotlin, `android/`): a voice-only keyboard (IME) used *next to* Gboard, so Gboard's swipe
  typing and clipboard history stay available. The user's phone: Xiaomi Poco X6 Pro, latest HyperOS.
- **Windows** (Kotlin/JVM, `windows/`, since 2026-11-08): notification-area app like the Mac one; it runs the
  Android `core` engine unchanged, so there is no third port of the workarounds.

Personal use at first; since 2026-10-19 the Android app is also being published on **Cafe Bazaar** (see below).

## Working agreements

- After every code change, **build, install and launch it yourself** — don't ask the user to reinstall —
  then tell them the **build number** (shown in the Mac menu-bar menu / Android keyboard panel).
- **Android builds only when the user asks** (2026-10-11): don't run `android/build.sh` for every change.
  Still port shared-engine changes to `android/core` and check them with the core tests / replay tool. When
  asked for a build, set the Android `versionName` to the current Mac version, build, and reveal the APK.
- **Bump the version, not just the build number**, for every change you ship. Since 2026-10-27 the version is
  **1.0.1** on both apps (the user's choice) and counts up from there: the last number (1.0.1 → 1.0.2) for each
  fix or feature, the middle one for big changes. Mac and Android share the sequence: a change to one app takes the
  next free number (Android 1.0.2 while the Mac stays 1.0.1 until its next change, which is then 1.0.3). Mac: `CFBundleShortVersionString` in `Resources/Info.plist`;
  Android: `versionName` in `android/app/build.gradle*`; Windows: `appVersion` in `windows/build.gradle.kts` (all three
  share the sequence). The build scripts only bump the build number.
- **Commit every change** to the local git repo (branch `main`, created 2026-10-08) after it builds and its
  tests/replays pass: one commit per change, message says what and why, version in the subject when bumped.
  Build output, APKs (`android/dist/`), `local.properties` and caches are git-ignored.
- **Push after every commit** to GitHub (`origin` = private repo `boorghany/persian-dictation`; the user asked
  on 2026-10-27).
- **GitHub:** private repo `boorghany/persian-dictation` (`origin`, over SSH; `gh` is signed in as boorghany),
  `main` first pushed 2026-10-27 at the user's request. Push `main` after merging. Keep secrets and personal data out
  of commits. The user may make it public later; before that, review with the user: `testdata/recordings/*.wav` are the user's own voice and dictated
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
   per utterance; after that, use the shown text (see 7) if it has ≥ 2 words (else drop), or start clean.
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
   wants them — currently عرشیا → ارشیا, بورغانی → بورقانی (names), ارز → عرض, ارزی → عرضی (the user means width, never currency), ناد → نآد. Add new entries to both `Corrections.swift` and `.kt`.
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
   **اینتر → Enter** (0.25.0): the word becomes the marker `\r` (`SpokenPunctuation.ENTER` / `.enter`, no spaces
   around it); the text before it is inserted, then Enter is pressed, then the rest. Mac: Return is pressed in the
   target app after the paste is read (`TextInserter.pressReturnAfterPaste`, the same path as "Enter, Enter"), held-back
   line breaks are dropped, the next final gets no leading space; our own Enter hotkey is unregistered around the
   synthetic Return (`DictationController.onReturnPassThrough`, re-registered 0.25 s after posting) or it would stop
   dictation, and pastes that come while a Return is pending wait for it (order paste → Return → paste is kept).
   Dictation keeps listening. Android: the keyboard's submit action (see Android IME behaviour).
   Known gaps: literal "خط بد" becomes a newline; the word "اینتر" can't be typed; "۲ نقطه" written with a digit isn't converted.
7. **Keep what the user saw** (`ShownText`, 0.26.0, both apps). Google sometimes drops the last words of the
   interim (noise, an unsure word, a long sentence) and then leaves them out of the final too, although it had
   shown them — 2026-10-27 06:03: "…پرشین دیکتیشن علامت سوال الان یه چیز بی‌ربط" → final "…پرشین دیکتیشن",
   and the user said it again. Replaying the audio does **not** help: real-time and burst replays into fresh
   sessions dropped the same 6 words (4 of 4). On Android it looked worse because the interim is the field's
   composing text, and a freeze recovery's new session starts its text from nothing (Android log 2026-10-23..26:
   22 freezes in 80 dictations; the Mac has the same rate, 137 in 553, but only the overlay shrank).
   - Interim: text 2+ words shorter than what was shown isn't displayed; the shown text stays until Google
     catches up (log `keeping the shown text`). Smaller revisions (شدن → شدند) go through.
   - Final: if everything but its last word matches the shown text and 2+ shown words are missing after it,
     they're put back (log `final left out N shown words; keeping them`). One missing word may be noise: not restored.
   - Echo: Google sometimes sends those words in the next utterance after all (Mac log: 15 of 34 shorter finals
     were splits), so while the next session's text is, or starts with, the restored words they're removed from it
     (once; forgotten as soon as the text is something else). The same after a freeze that replays didn't fix:
     the shown text is delivered (`using the shown text for the frozen utterance`).
   - Kotlin unit tests: `ShownTextTest.kt`. Regression recordings: `2026-10-27T06-03-36Z-dropped-tail*.wav`.

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
Interim text is shown in the panel and as underlined composing text in the field; finals are committed.
**Spacing** (0.25.0, `gapAtCursor` / `Gap`): before composing/committing, the characters around the cursor are read:
a space goes before the text only if a word is right before the cursor (not whitespace, `(` or the field's start), and
one goes **after** it if a word follows (not whitespace or `. ، ؟ : ) !`); text that attaches to the previous word
(punctuation, line break, Enter) also removes spaces right before the cursor. Before, only the leading space was
decided, so inserting in the middle of text (after moving the cursor or deleting) left words stuck together or two or
three spaces apart (the user's report).
**App removed the composing text** (0.25.0): the user said something still interim, then tapped the app's own Send,
which sent and cleared the field; the next interim/final composed the whole utterance again, so part of the sent
message reappeared. Now, before each interim and final, the keyboard checks its composing text (`composingShown`) is
still right before the cursor, or anywhere in the field (the user may have tapped elsewhere); if not, the utterance's
first N words (N = words in the last interim) are skipped in its later results (`sentWords`, log
`composing text removed by the app`). On `onStartInputView(restarting)` the composing region is re-attached if our text
is still before the cursor, else treated the same way. Unconfirmed on the phone.
**Switching from Gboard** (1.0.2): the user's phone has 3-button navigation (HyperOS shows no keyboard-switcher button
in the navigation bar; the emulator's gesture bar does, and it opens the picker: 2 taps) and Gboard's 🌐 language
switch key on (emoji key off). They had to long-press space and pick us. `method.xml` now sets
`supportsSwitchingToNextInputMethod="true"` (it was false): Android's next-keyboard rotation (`switchToNextInputMethod`,
what 🌐 uses) only includes keyboards that declare it. Unverified: the emulator's Gboard has one language and shows no 🌐;
check on the phone whether a 🌐 tap (after Gboard's own languages) reaches us. ⌨ in our panel goes back to Gboard.
**Keyboard sometimes gone after switching** (reported 2026-10-27 on the phone, not reproduced on the emulator): the log now
records `start input …`, `keyboard shown` / `hidden`, `ime destroyed` and uncaught crashes (`AppLog.init`, stack only,
no text). Safety net: within 5 s of the service being created (= the user just switched to it), if the keyboard isn't on
screen 700 ms after input starts, `requestShowSelf` (log `not shown … asking to show it`). The clipboard preview can't
crash the panel any more (`coerceToText` of a content-URI clip is guarded).
**Keys don't stop dictation** (1.0.1, the user's request: only the first opening, which is paused, and their own
pause stop it). ◀ ▶ ⌫, paste and typing an emoji (opening the picker no longer pauses) first `detachComposing`: the
utterance in progress is typed in as shown and its shown words are skipped in that utterance's later results (`sentWords`,
the mechanism also used when an app's Send button takes the composing text), so a deleted word doesn't come back and
nothing is typed twice; the rest of the utterance is composed where the cursor is then. While finishing after the
user's pause these keys still don't wait for the last result (`pauseNow`). The submit key while listening waits up
to 3 s for the final of the utterance on screen, types it, submits and keeps listening (`submitWhenFinal`).
Emulator test (recording via `test-input.wav`): ⌫ at 17 s, emoji at 26 s, ◀ at 31 s — listening went on, the deleted
word stayed deleted, no duplicates.
**Panel** (0.27.0, the user's layout feedback on 0.26.0: one row too sparse, one too cramped, colourful icons broke the
theme): Gboard-style **toolbar** on top — a flat emoji icon and a **paste chip** showing the clipboard's first 24 characters
(whitespace collapsed; `••••••` for clips marked `EXTRA_IS_SENSITIVE`; just the icon when the clipboard has no text;
refreshed on `onStartInputView` and `OnPrimaryClipChangedListener`) — then the status line and mic, then **one key row**:
⌨ ◀ ▶ ⌫ and the wider **submit key**. The space key was removed (0.27.0). All key icons are monochrome Material vector
drawables (`res/drawable/ic_*.xml`, Apache 2.0) tinted for light/dark; the delete icon is **mirrored to point right**
(in Persian deleting eats towards the right). The submit key is filled with Gboard's accent (Material You
`system_accent1_600` / `_200` on Android 12+, else Google blue) and follows the field's `imeOptions`: search, send, go,
next, previous, done icons, a custom `actionLabel` as text, else ↵ (newline in multi-line fields — chat apps mark their
box `IME_FLAG_NO_ENTER_ACTION`, so there it is a newline, like Gboard — else a KEYCODE_ENTER key event). It runs
`performEditorAction`; while listening it first waits for the last result (like the Mac's Enter). Spoken **اینتر** does
the same action between the text before and after it, and keeps listening.
The paste chip types the clipboard's text (an IME in use may read it), or asks the app to paste (`performContextMenuAction`)
if it can't. The emoji icon swaps the status line and mic for a scrollable emoji grid (80 common emoji + a row of the 8
most recent, kept in prefs `emoji`); the mic icon in its place goes back. Gboard's emoji panel can't be opened from another
keyboard and there is no AndroidX (no EmojiCompat), so emoji rely on the system font. Paste, emoji and opening the picker
pause first. Checked on the emulator (light + dark, Messages and Settings search): layout, chip text, paste, delete-word.
**Number, phone, date/time and password fields** (0.25.0, `voiceSuitsField`): the keyboard switches straight back to
the previous one (Gboard) in `onStartInputView` — chosen over a built-in numeric keypad because the user runs this next
to Gboard anyway, Gboard's number pad is better than anything we'd draw, and a password must never be sent to Google.
If there is no previous keyboard the system picker opens. OTP fields declared as plain text aren't detected.
⌨ goes back to Gboard immediately (`switchToPreviousInputMethod`, falls back to the picker); ⌫, ◀, ▶ hold to repeat
(150 ms). **⌫ deletes a whole word**: the word before the
cursor, or the whole word the cursor is in, plus the spaces before it, keeping one space when words remain on
both sides; a line break before the cursor alone; a selection as a whole. **◀ ▶ move by words** (Persian is RTL:
▶ = back to the start of the previous word, ◀ = forward to the end of the next). While listening, these keys
first pause (type the last interim as is, cancel the recognizer) — until 1.0.1, see below. Removed in 0.17.0: ⏎ (finish + Enter) and
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
Open the Bazaar console in the **built-in browser** pane, not Chrome (the user's request, 2026-10-27); the user signs in there.
**Publishing an update (how 1.0.2 build 24 was published, 2026-10-27):**
1. `android/build.sh --release` → `android/dist/PersianDictation-<version>-build<N>-release.apk`; commit + push the build number.
2. Console: Apps → تایپ صوتی فارسی → side menu (☰ at this pane width) → نسخه‌ها(رهانش‌ها) → **نسخه جدید**.
3. **Upload: the user drags the APK into the dotted drop box** (`open -R <apk>` shows it in Finder). The built-in browser
   has no file-upload tool, and every workaround failed: page `fetch` from a localhost server (blocked, even with CORS and
   private-network headers), a localhost iframe (blocked), `window.open` to a localhost page (it replaces the tab, no
   opener), a file on the clipboard + synthetic ⌘V (no paste event), and typing a path into the macOS open dialog
   (`osascript` has no Accessibility access). Check the package row: versionCode (versionName), target/min SDK.
4. Release notes (تغییرات جدید فارسی / انگلیسی, ≤ 800 chars each, shown publicly — get the user's OK on the text): the
   editors are plain `contenteditable` divs; typing "- " doesn't make bullets and the list button misbehaves, so set
   `innerHTML` to `<ul><li>…</li></ul>` and dispatch an `input` event (the `N / ۸۰۰` counters show it registered).
5. «انتشار خودکار نسخه» on (publishes when approved) → **درخواست انتشار** → confirm «درخواست انتشار برنامه» (reviewer note
   optional). Review: up to 3 working days, result by email. Status then «در صف انتظار».
Releases: 0.19.0 (build 20) published 1405/07/27; **1.0.2 (build 24) submitted 2026-10-27** (notes: toolbar with emoji and
paste, simpler keys and themed icons, keys don't stop dictation, shown words no longer vanish, «علامت تعجب» → !, mid-text
spacing, no retyping after send, number/password fields open the previous keyboard).

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

## Windows app (`windows/`)

Kotlin/JVM, a Gradle module of the `android/` build (`settings.gradle.kts` maps `:windows` to `../windows`) so it uses
`:core` directly. Win32 through JNA 5.15 (`jna-platform`), UI with AWT/Swing. Packaged by `jpackage` (Microsoft Build of
OpenJDK 21, runtime bundled, ~50 MB) as a folder with `Persian Dictation.exe`, zipped for x64 and ARM64.

| File | Role |
|---|---|
| `Main.kt` | entry; single instance (lock file; a second launch posts `WM_APP_ALREADY_RUNNING` → notification); `--toggle` (start/stop the running instance), `--transcribe <wav> [lang]`, `--export-diagnostics`, `--write-ico <file>`; `DiagnosticsExport` (info.txt + logs + 48 h of recordings → `%USERPROFILE%\Downloads\PersianDictation-diagnostics-<UTC>.zip`, shown in Explorer) |
| `App.kt` | tray icon + menu (version, status, Start/Stop, Microphone, Language, Development Mode with a warning dialog, Open Logs Folder, Share Diagnostics…, Start with Windows = HKCU `Run` key, only in the packaged app, Quit); hotkeys; double-click on the icon toggles |
| `DictationController.kt` | line-by-line port of the Mac controller (held-back line breaks, spoken Enter, Enter-while-finishing); pasted line breaks become CRLF |
| `WinThread` (`Win32.kt`) | one thread with a hidden message-only window (class `PersianDictationMessageWindow`) and message loop: global hotkeys (`RegisterHotKey`), clipboard rendering, tasks from other threads, `SendInput`, foreground tracking (`SetWinEventHook`) |
| `Paster.kt` | port of the Mac `TextInserter`. The text goes on the clipboard with **delayed rendering** (`SetClipboardData(CF_UNICODETEXT, NULL)`, rendered on `WM_RENDERFORMAT`) = the Mac's lazy provider, so we know when the app read it (`paste read after N ms`); same hold times, Return queueing and never-read rules. Marked `ExcludeClipboardContentFromMonitorProcessing` / `CanIncludeInClipboardHistory=0` / `CanUploadToCloudClipboard=0` (stays out of Win+V history). The user's clipboard (all memory formats) is restored unless someone else took the clipboard (`WM_DESTROYCLIPBOARD` / owner check). Ctrl+V waits up to 1.5 s for Shift/Ctrl/Alt/Win to be released |
| `MicCapture.kt` | Java Sound `TargetDataLine` 16 kHz mono (Windows converts); "System Default" = Java's default line; switches to the default if the chosen device stops or sends only zeros for 3 s (zeros on the default → overlay hint about Settings → Privacy & security → Microphone). Test hook: `%LOCALAPPDATA%\Persian Dictation\test-input.wav` is played instead of the mic |
| `Overlay.kt` | non-activating click-through `JWindow` (WS_EX_NOACTIVATE/TOOLWINDOW/TRANSPARENT) at the top of the primary display, RTL for Persian, last 3 lines; **a new window for every dictation** (a window belongs to one virtual desktop) |
| `AppIcon.kt` | the Mac icon geometry in Java2D: tray icon (white/black per `SystemUsesLightTheme`, red while listening) and the `.ico` |
| `Environment.kt` | paths, `Settings` (`%APPDATA%\Persian Dictation\settings.properties`), `Log` (`%LOCALAPPDATA%\Persian Dictation\PersianDictation.log`, rotated at 5 MB; dictated text only in dev mode), version |

Hotkeys: **Ctrl+Alt+Space** toggles (same keys as the Mac's ⌃⌥Space); while listening **Esc / Enter** stop; Enter again
while finishing = press Enter after the last paste. Our Enter hotkey is unregistered around a spoken "اینتر" Return.
Starting from the tray menu first gives the focus back to the last app window (the tray click took it).
Known limits: pastes into apps running as administrator fail (UIPI) — run elevated only if needed; "System Default"
doesn't follow a change of the Windows default mid-dictation; Java Sound may garble non-ASCII device names.

Build: `windows/build.sh [--install]` (Mac) → bumps `windows/build-number.txt`, runs core tests, `:windows:installDist`,
writes the `.ico`, stages everything in `~/Downloads/PersianDictation-Windows-build/` (the only Mac folder the VM
sees, as `Z:\Downloads`; it also caches the two JDK zips, ~200 MB each), then runs `windows/package.ps1` in the
Parallels VM **"Windows 11"** (ARM64, Windows 11) via `prlctl exec --current-user`: JDKs unpacked once to
`%LOCALAPPDATA%\PersianDictationBuild`, `jpackage --type app-image` per arch, zips → `windows/dist/` (git-ignored).
`--install` copies the ARM64 build to `%LOCALAPPDATA%\Programs\Persian Dictation`, adds a Start-menu shortcut and
launches it. Screenshots of the VM: `prlctl capture "Windows 11" --file x.png`. The VM is the user's (they use Word
in it): keep tests short and close what you open. Run PowerShell there with
`prlctl exec "Windows 11" --current-user powershell -NoProfile -EncodedCommand <base64 UTF-16LE>` (quoting through
prlctl breaks paths with spaces; console output of Persian text comes back as `?`, so return it base64-encoded).

Testing in the VM: `windows/test-paste.ps1` (copied to the staging folder with `target.ps1`, a WinForms text box used as
the paste target — Windows 11 Notepad restores unsaved tabs, which mixed runs; if Notepad is ever used, delete only
your own files in its `LocalState\TabState`). `-Finals <utf8 file>` sends each line through the hidden test hook
`--paste-test @file` (the running app handles it like a Google final, spoken punctuation included, and shows it in the
overlay for 3 s) — no network needed; `-Wav <file>` dictates a recording via `test-input.wav` + `--toggle`.
2026-11-08 (1.0.3 build 6): the paste test passed on ARM64 (text, spacing, ، ؟ . and CRLF exact; clipboard restored;
reads after 7–530 ms), overlay checked in screenshots (Persian RTL, 3 lines), the x64 build starts under emulation.
**Not yet tested with Google on Windows**: the VM had no network (adapter on 169.254.x, no DHCP from Parallels shared
networking — not changed by us); the engine itself passed `--transcribe` of the dropped-tail recording on the Mac.
No real-mic test (the VM lists no capture device) and the tray icon wasn't visible (VM in Coherence mode).
Parallels' clipboard sharing (`prl_cc`) reads every clipboard change despite the exclusion formats, so inside the VM a
"paste read" can come from Parallels, not the target app.

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
| `2026-10-27T06-03-36Z-dropped-tail.wav` | اسم پوشه پروژه رو هم روی این سیستم می‌تونی تغییر بدی به همین پرشین دیکتیشن علامت سوال الان یه چیز بی‌ربط (≤ 0.25.0: without the last 6 words) |
| `2026-10-27T06-03-36Z-dropped-tail-repeated.wav` (+ the next session, where the user said the tail again) | the same, then nothing (the repeat is removed as an echo) |

Recordings 05-55-36, 05-54-38 and 05-52-37 reproduce the clipped-start bug and 06-00-38 the freeze when
replayed in a single continuous session; the old buggy outputs were ی‌خوام, مونه, و, and a lost sentence.

## Open ideas

- Test on the phone (real mic, HyperOS) and iterate from its log/recordings.
- Handle "۲ نقطه" (digit) and a way to say "خط بد" literally, if they come up.

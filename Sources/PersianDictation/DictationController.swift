import AppKit
import AVFoundation

/// Owns one dictation run: microphone → streaming recognizer → paste into the focused app.
/// All state lives on the main thread.
final class DictationController {
    enum State { case idle, listening, finishing }

    var onStateChange: ((State) -> Void)?
    /// true right before a spoken "اینتر" presses Return in the target app, false once it is through:
    /// the Enter hotkey (stop dictation) must let that Return pass.
    var onReturnPassThrough: ((Bool) -> Void)?

    private(set) var state = State.idle {
        didSet { onStateChange?(state) }
    }

    private let overlay = OverlayPanel()
    private var audio: AudioCapture?
    private var recognizer: ContinuousRecognizer?
    private var insertedAny = false
    private var lastInsertAttachesToNext = false
    /// Line breaks that ended the last final, not pasted yet: many editors (web text boxes, Quill)
    /// drop or hide a line break at the very end of pasted text, so "پاراگراف بعد" became one line
    /// break. They are pasted at the start of the next final instead, or when dictation ends.
    private var pendingBreaks = ""
    /// Enter was pressed again while finishing ("paste the rest, then send"): press it in the target
    /// app once the last words are pasted. Before, that Enter reached the app at once and sent the
    /// message without the words Google hadn't finalized yet.
    private var pressEnterWhenDone = false
    private var finishTimeout: DispatchWorkItem?
    private var dictationStart = Date()
    private var gotFirstAudio = false

    private var language: String { Settings.language }

    /// The Microphone menu changed: switch now if dictating.
    func microphonePreferenceChanged() {
        audio?.preferenceChanged()
    }

    func toggle() {
        switch state {
        case .idle: start()
        case .listening: stop(by: "hotkey or menu")
        case .finishing: break
        }
    }

    /// Esc or Enter while dictating.
    func stopKeyPressed(enter: Bool) {
        switch state {
        case .listening:
            stop(by: enter ? "Enter" : "Esc")
        case .finishing where enter && !pressEnterWhenDone:
            DiagnosticsLog.write("Enter while finishing: pressing it after the last paste")
            pressEnterWhenDone = true
        default:
            break
        }
    }

    // MARK: Start / stop

    private func start() {
        let trusted = TextInserter.isTrusted(prompt: true)
        DiagnosticsLog.write("start accessibilityTrusted=\(trusted)")
        guard trusted else {
            flash("Allow Persian Dictation under System Settings → Privacy & Security → Accessibility, then try again.")
            watchForAccessibilityGrant()
            return
        }
        AudioCapture.requestPermission { [weak self] granted in
            guard let self else { return }
            guard granted else {
                self.flash("Microphone access is off. Enable it in System Settings → Privacy & Security → Microphone.")
                return
            }
            self.begin()
        }
    }

    private func begin() {
        insertedAny = false
        lastInsertAttachesToNext = false
        pendingBreaks = ""
        pressEnterWhenDone = false
        dictationStart = Date()
        gotFirstAudio = false
        let mics = AudioCapture.microphones().map(\.localizedName).joined(separator: ", ")
        DiagnosticsLog.write("dictation start mic=\"\(AudioCapture.preferred()?.localizedName ?? "?")\" "
            + "choice=\(Settings.microphoneID == nil ? "system default" : "fixed") "
            + "default=\"\(AudioCapture.systemDefault()?.localizedName ?? "?")\" available=[\(mics)]")
        startRecognizer()

        let capture = AudioCapture()
        capture.onAudio = { [weak self] pcm, level in
            DispatchQueue.main.async {
                if let self, !self.gotFirstAudio {
                    self.gotFirstAudio = true
                    DiagnosticsLog.write(String(format: "first audio after %.0f ms", Date().timeIntervalSince(self.dictationStart) * 1000))
                }
                self?.recognizer?.send(pcm)
                self?.overlay.setLevel(level)
            }
        }
        capture.onDeviceChange = { [weak self] name, reason in
            guard let self, self.state == .listening else { return }
            // Shown until the next interim result replaces it.
            self.overlay.show("🎤 \(name) — \(reason)")
        }
        do {
            try capture.start()
        } catch {
            recognizer?.cancel()
            recognizer = nil
            flash("Could not start the microphone: \(error.localizedDescription)")
            return
        }
        audio = capture
        overlay.setBusy(false)
        overlay.show(placeholder)
        state = .listening
    }

    private func stop(by key: String) {
        DiagnosticsLog.write("dictation stop (\(key))")
        audio?.stop()
        audio = nil
        state = .finishing
        overlay.setBusy(true)
        recognizer?.finish()

        // If the server never closes the stream, give up after a few seconds.
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, self.state == .finishing else { return }
            self.recognizer?.cancel()
            self.completeFinish()
        }
        finishTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: timeout)
    }

    private func completeFinish() {
        pastePendingBreaks()
        finishTimeout?.cancel()
        finishTimeout = nil
        recognizer = nil
        overlay.hide()
        state = .idle // unregisters our Enter hotkey before we press Enter below
        if pressEnterWhenDone {
            pressEnterWhenDone = false
            TextInserter.pressReturnAfterPaste()
        }
    }

    // MARK: Streaming

    private func startRecognizer() {
        let r = ContinuousRecognizer(apiKey: Settings.apiKey, language: language)
        r.verbose = Settings.developmentMode
        r.recordSessions = Settings.developmentMode
        r.onInterim = { [weak self] text in
            guard let self else { return }
            let preview = SpokenPunctuation.apply(Corrections.apply(text, language: self.language), language: self.language)
            self.overlay.show(preview.isEmpty ? self.placeholder
                : (self.pendingBreaks + preview).replacingOccurrences(of: "\n", with: " ↵ ")
                    .replacingOccurrences(of: SpokenPunctuation.enter, with: " ⏎ "))
        }
        r.onFinal = { [weak self] text in
            guard let self else { return }
            self.insert(text)
            // It's pasted now: don't keep showing it until the next session's first interim (up to ~1 s).
            if self.state == .listening { self.overlay.show(self.placeholder) }
        }
        r.onEnd = { [weak self] error in
            guard let self else { return }
            switch self.state {
            case .listening:
                // The connection kept failing.
                self.pastePendingBreaks()
                self.audio?.stop()
                self.audio = nil
                self.recognizer = nil
                self.state = .idle
                if let error { self.flash(self.describe(error)) }
            case .finishing:
                self.completeFinish()
                if let error { self.flash(self.describe(error)) }
            case .idle:
                break
            }
        }
        recognizer = r
        r.start()
    }

    private func insert(_ raw: String) {
        let text = SpokenPunctuation.apply(Corrections.apply(raw, language: language), language: language)
        // "اینتر": paste what came before it, press Return (send in chat apps), then go on.
        for (i, part) in text.components(separatedBy: SpokenPunctuation.enter).enumerated() {
            if i > 0 { pressReturn() }
            insertPart(part)
        }
    }

    private func insertPart(_ text: String) {
        guard !text.isEmpty else { return }
        var body = text
        var breaks = ""
        while body.hasSuffix("\n") {
            body.removeLast()
            breaks += "\n"
        }
        guard !body.isEmpty else {
            pendingBreaks += breaks
            return
        }
        let paste = pendingBreaks + body
        let needsSpace = insertedAny && !lastInsertAttachesToNext && !SpokenPunctuation.attachesToPrevious(paste)
        TextInserter.insert(needsSpace ? " " + paste : paste)
        insertedAny = true
        lastInsertAttachesToNext = SpokenPunctuation.attachesToNext(body)
        pendingBreaks = breaks
    }

    /// A spoken Enter. Line breaks held back are dropped (Return makes its own), and the next text
    /// starts without a space: the message was sent or a new line begun.
    private func pressReturn() {
        DiagnosticsLog.write("spoken Enter")
        pendingBreaks = ""
        insertedAny = false
        lastInsertAttachesToNext = false
        TextInserter.pressReturnAfterPaste(willPress: { [weak self] in self?.onReturnPassThrough?(true) },
                                           didPress: { [weak self] in self?.onReturnPassThrough?(false) })
    }

    /// Dictation ended right after a line break command: paste it as is.
    private func pastePendingBreaks() {
        guard !pendingBreaks.isEmpty else { return }
        TextInserter.insert(pendingBreaks)
        insertedAny = true
        lastInsertAttachesToNext = true
        pendingBreaks = ""
    }

    // MARK: Accessibility

    private var accessibilityWatch: Timer?

    /// Polls until macOS reports the Accessibility grant, then tells the user it's ready.
    private func watchForAccessibilityGrant() {
        accessibilityWatch?.invalidate()
        let deadline = Date().addingTimeInterval(180)
        accessibilityWatch = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] timer in
            if TextInserter.isTrusted(prompt: false) {
                timer.invalidate()
                DiagnosticsLog.write("accessibility grant detected while running")
                self?.flash("Accessibility permission received. Press ⌃⌥Space to dictate.")
            } else if Date() > deadline {
                timer.invalidate()
                DiagnosticsLog.write("accessibility still not granted after 3 minutes")
            }
        }
    }

    // MARK: Messages

    private var placeholder: String {
        language.hasPrefix("fa") ? "در حال گوش دادن…" : "Listening…"
    }

    private func describe(_ error: Error) -> String {
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain {
            return "Can't reach Google's speech server (\(ns.localizedDescription)). Check your connection or VPN."
        }
        return error.localizedDescription
    }

    /// Show a message in the overlay for a few seconds.
    private func flash(_ message: String) {
        overlay.setBusy(true)
        overlay.show(message)
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self, self.state == .idle else { return }
            self.overlay.hide()
        }
    }
}

enum Settings {
    /// The public Chromium key that Chrome builds use for Web Speech.
    static let defaultAPIKey = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw"

    static var apiKey: String {
        UserDefaults.standard.string(forKey: "apiKey") ?? defaultAPIKey
    }

    /// Development mode: verbose log (with the dictated text) plus a WAV recording of every session.
    /// Off by default; turning it on asks first, since it keeps private speech on disk.
    static var developmentMode: Bool {
        get { UserDefaults.standard.object(forKey: "developmentMode") as? Bool ?? false }
        set { UserDefaults.standard.set(newValue, forKey: "developmentMode") }
    }

    /// The chosen microphone's uniqueID, or nil to follow the macOS default input.
    static var microphoneID: String? {
        get { UserDefaults.standard.string(forKey: "microphoneID") }
        set { UserDefaults.standard.set(newValue, forKey: "microphoneID") }
    }

    static var language: String {
        get { UserDefaults.standard.string(forKey: "language") ?? "fa-IR" }
        set { UserDefaults.standard.set(newValue, forKey: "language") }
    }
}

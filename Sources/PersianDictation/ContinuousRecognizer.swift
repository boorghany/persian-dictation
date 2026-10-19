import Foundation

/// Long-running dictation on top of Google's streaming endpoint, using one session per utterance.
///
/// Why one session per utterance: in Google's continuous mode, once an utterance is finalized the
/// beginning of the next one is often clipped (e.g. "می‌خوام" comes back as "ی‌خوام"), while a fresh
/// session recognizes the same audio correctly. So as soon as a session delivers a final result we
/// hand over to a new session and replay a short pre-roll of recent audio into it, going back to
/// the silence before the next utterance so nothing is lost while the new session connects.
///
/// Google also sometimes freezes in the middle of an utterance: it stops sending results while the
/// user keeps talking, then returns an empty final, losing everything. A watchdog detects this
/// (speech but no new results for a few seconds, or an empty final after real interim text) and
/// replays all of the utterance's audio into a fresh session, which recognizes it correctly.
///
/// Main-thread only. All callbacks are delivered on the main thread.
final class ContinuousRecognizer {
    /// Current hypothesis of the utterance in progress (prefix-drop repaired).
    var onInterim: ((String) -> Void)?
    /// A finalized utterance (prefix-drop repaired). With `earlyCommit`, also each stable piece of one,
    /// and at the end only the part not delivered yet.
    var onFinal: ((String) -> Void)?
    /// Deliver the stable words of the utterance in progress during short pauses instead of waiting
    /// for Google's final, which only comes after about 1.2 s of silence.
    var earlyCommit = false
    /// Called once: after `finish()` completes, or when the connection keeps failing.
    var onEnd: ((Error?) -> Void)?
    /// Diagnostics output. `verbose` adds every interim and final.
    var log: (String) -> Void = DiagnosticsLog.write
    var verbose = false
    /// Save the audio of every session as a WAV file (development mode).
    var recordSessions = false
    /// Testing only (`--transcribe --simulate-freeze`): make one session behave like a frozen one.
    enum SimulatedFault { case silence(session: Int, after: TimeInterval), emptyFinal(session: Int) }
    var simulatedFault: SimulatedFault?

    private enum Phase { case idle, running, finishing, done }

    private let apiKey: String
    private let language: String
    private var phase = Phase.idle
    private var stream: SpeechStream?
    private var repair = PrefixDropRepair()
    private var sessionNumber = 0
    private var sessionStart = Date()
    private var consecutiveFailures = 0

    /// The most recent audio, for pre-roll when switching sessions.
    private var recent = Data()
    private static let recentBytes = 8 * SpeechStream.sampleRate * 2 // 8 s of 16-bit mono

    // Watchdog state.
    /// All audio sent to the current session (one utterance), for replay if Google freezes.
    private var sessionAudio = Data()
    private static let sessionAudioBytes = 55 * SpeechStream.sampleRate * 2
    /// When Google last showed progress (new interim text), or the session opened.
    private var lastProgress = Date()
    /// Seconds of speech heard since `lastProgress`.
    private var speechSinceProgress: TimeInterval = 0
    private var lastInterim = ""
    /// When the current session's interim text last changed: the finalized speech had been heard by then.
    private var lastTextChange: Date?
    /// Pre-roll sent to the current session, in seconds.
    private var sessionPreroll: TimeInterval = 0
    /// The previous final's text, to catch a pre-roll that made Google finalize the same speech again.
    private var previousFinal = ""
    /// Recoveries for the utterance in progress; reset once an utterance is finalized.
    private var recoveries = 0
    private var recentLevels: [Float] = []

    // Early commit state.
    /// The part of the utterance in progress that was already delivered early (repaired interim text).
    private var committed = ""
    /// The current session's latest interim text, prefix-drop repaired.
    private var repairedInterim = ""
    /// When the microphone last heard speech.
    private var lastSpeech = Date.distantPast
    /// Deliver early after this much silence, once the text hasn't changed for `earlyTextSeconds`.
    private static let earlyPauseSeconds: TimeInterval = 0.6
    private static let earlyTextSeconds: TimeInterval = 0.5
    private var watchdog: Timer?
    /// Whether the current session's audio is actually reaching Google. The freeze clock starts then.
    private var connected = false

    /// Speech but no progress for this long counts as a freeze.
    private static let stallSeconds: TimeInterval = 3
    private static let stallSpeechSeconds: TimeInterval = 0.8
    private static let maxRecoveries = 2
    /// A session not connected after this long is retried (a new connection through a VPN sometimes
    /// stalls for many seconds; a fresh attempt is usually quick).
    private static let connectStallSeconds: TimeInterval = 4
    /// The finalized speech ends at most this long after its last interim text change.
    private static let finalizedSpeechMargin: TimeInterval = 0.25

    init(apiKey: String, language: String) {
        self.apiKey = apiKey
        self.language = language
    }

    func start() {
        guard phase == .idle else { return }
        phase = .running
        openSession(preroll: Data())
        watchdog = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.checkForStall()
        }
    }

    /// 16 kHz / 16-bit little-endian / mono PCM.
    func send(_ pcm: Data) {
        guard phase == .running else { return }
        recent.append(pcm)
        if recent.count > Self.recentBytes { recent.removeFirst(recent.count - Self.recentBytes) }
        sessionAudio.append(pcm)
        if sessionAudio.count > Self.sessionAudioBytes { sessionAudio.removeFirst(sessionAudio.count - Self.sessionAudioBytes) }
        if isSpeech(pcm) {
            speechSinceProgress += Double(pcm.count) / Double(SpeechStream.sampleRate * 2)
            lastSpeech = Date()
        }
        stream?.send(pcm)
        if earlyCommit { commitEarlyIfPaused() }
    }

    /// No more audio: let the current session deliver its last result, then call `onEnd`.
    func finish() {
        guard phase == .running else { return }
        phase = .finishing
        stopWatchdog()
        stream?.finish()
    }

    func cancel() {
        phase = .done
        stopWatchdog()
        stream?.cancel()
        stream = nil
    }

    // MARK: Sessions

    private func openSession(preroll: Data) {
        let s = SpeechStream(apiKey: apiKey, language: language)
        sessionNumber += 1
        sessionStart = Date()
        repair = PrefixDropRepair()
        sessionAudio = preroll
        lastProgress = Date()
        speechSinceProgress = 0
        lastInterim = ""
        repairedInterim = ""
        lastTextChange = nil
        sessionPreroll = Double(preroll.count) / Double(SpeechStream.sampleRate * 2)
        connected = false
        if recordSessions {
            let stamp = ISO8601DateFormatter().string(from: sessionStart).replacingOccurrences(of: ":", with: "-")
            s.recorder = SessionRecorder(name: "\(stamp)-s\(sessionNumber)")
        }
        log(String(format: "session %d open preroll=%d ms", sessionNumber, preroll.count * 1000 / (SpeechStream.sampleRate * 2))
            + (s.recorder.map { " recording=\($0.url.lastPathComponent)" } ?? ""))

        s.onConnected = { [weak self, weak s] in
            DispatchQueue.main.async {
                guard let self, let s, self.stream === s else { return }
                // Audio queued while connecting is now on its way; start the freeze clock here.
                self.connected = true
                self.lastProgress = Date()
                self.speechSinceProgress = 0
                self.log("session \(self.sessionNumber) connected after \(self.sessionMillis) ms")
            }
        }
        s.onInterim = { [weak self, weak s] text in
            DispatchQueue.main.async {
                guard let self, let s, self.stream === s, !self.faultSilences() else { return }
                self.consecutiveFailures = 0
                self.connected = true
                if self.verbose { self.log("  s\(self.sessionNumber) +\(self.sessionMillis) interim: \(text)") }
                let trimmed = text.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty, trimmed != self.lastInterim {
                    self.lastInterim = trimmed
                    self.lastTextChange = Date()
                    self.lastProgress = Date()
                    self.speechSinceProgress = 0
                }
                self.repairedInterim = self.repair.interim(text)
                self.onInterim?(self.repairedInterim)
            }
        }
        s.onFinal = { [weak self, weak s] text in
            DispatchQueue.main.async {
                guard let self, let s, self.stream === s, !self.faultSilences() else { return }
                self.consecutiveFailures = 0
                var text = text
                if case .emptyFinal(let n)? = self.simulatedFault, n == self.sessionNumber { text = "" }
                if self.verbose { self.log("  s\(self.sessionNumber) +\(self.sessionMillis) FINAL: \(text)") }
                if text.trimmingCharacters(in: .whitespaces).isEmpty, !self.lastInterim.isEmpty {
                    // Google dropped an utterance it had been transcribing.
                    if self.phase == .running, self.recoveries < Self.maxRecoveries {
                        self.recover(reason: "empty final after \"\(self.lastInterim)\"")
                        return
                    }
                    // Out of retries: keep what Google showed rather than lose it, unless it's a
                    // single word, which is more likely noise.
                    if self.lastInterim.split(separator: " ").count >= 2 {
                        self.log("using last interim instead of empty final")
                        text = self.lastInterim
                    }
                }
                self.recoveries = 0
                let trimmed = text.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty, trimmed == self.previousFinal,
                   Date().timeIntervalSince(self.sessionStart) < self.sessionPreroll + 1 {
                    // Too soon to come from new speech: Google re-finalized the pre-roll. (Backstop for
                    // the pre-roll limit in handOver; seen as "من راستش اینجا" typed 4 times.)
                    self.log("dropped duplicate final \"\(trimmed)\" (came from the pre-roll)")
                } else {
                    if !trimmed.isEmpty { self.previousFinal = trimmed }
                    self.deliverFinal(self.repair.final(text))
                }
                self.committed = ""
                if self.phase == .running { self.handOver() }
            }
        }
        s.onEnd = { [weak self, weak s] error in
            DispatchQueue.main.async {
                guard let self, let s, self.stream === s else { return }
                self.sessionEnded(error: error)
            }
        }
        stream = s
        s.start()
        if !preroll.isEmpty { s.send(preroll) }
    }

    /// Replace the current session with a fresh one for the next utterance.
    private func handOver() {
        let old = stream
        // Never reach back into the speech that was just finalized (see prerollAudio). Only after a
        // normal session: in a recovery session the replayed audio went out in a burst, so the timing of
        // its text changes says nothing about when the words were spoken (limiting there lost the next
        // utterance's start); the duplicate-final check covers that case.
        let limit = sessionPreroll <= 1 ? lastTextChange?.addingTimeInterval(Self.finalizedSpeechMargin) : nil
        let preroll = prerollAudio(notBefore: limit)
        log(String(format: "session %d handover after %.1fs", sessionNumber, Date().timeIntervalSince(sessionStart)))
        openSession(preroll: preroll)
        // Results the old session might still send belong to audio the new one now covers.
        old?.finish()
    }

    private func sessionEnded(error: Error?) {
        log(String(format: "session %d end after %.1fs error=%@", sessionNumber,
                   Date().timeIntervalSince(sessionStart), error.map { "\($0)" } ?? "none"))
        switch phase {
        case .running:
            // Google closes sessions after long silence or about a minute of audio: reconnect.
            if let error {
                consecutiveFailures += 1
                if consecutiveFailures >= 3 {
                    phase = .done
                    stream = nil
                    onEnd?(error)
                    return
                }
            }
            openSession(preroll: prerollAudio())
        case .finishing:
            phase = .done
            stream = nil
            onEnd?(error)
        case .idle, .done:
            break
        }
    }

    private var sessionMillis: Int { Int(Date().timeIntervalSince(sessionStart) * 1000) }

    // MARK: Early commit

    /// During a short pause, deliver the words Google is unlikely to change: all but the last one
    /// (it is sometimes still revised, شدن → شدند), and never the first word of a spoken punctuation
    /// phrase whose second word may still come ("علامت" + "سوال").
    private func commitEarlyIfPaused() {
        guard phase == .running, let changed = lastTextChange,
              Date().timeIntervalSince(changed) >= Self.earlyTextSeconds,
              Date().timeIntervalSince(lastSpeech) >= Self.earlyPauseSeconds else { return }
        var words = repairedInterim.split(separator: " ").map(String.init)
        guard !words.isEmpty else { return }
        words.removeLast()
        while let last = words.last, SpokenPunctuation.startsPhrase(last, language: language) { words.removeLast() }
        let candidate = words.joined(separator: " ")
        let squeezedCandidate = Self.squeezed(candidate)
        guard squeezedCandidate.count > Self.squeezed(committed).count else { return }
        // A new session re-recognizing the previous utterance from its pre-roll (see the duplicate-final check).
        if Date().timeIntervalSince(sessionStart) < sessionPreroll + 1,
           Self.squeezed(previousFinal).starts(with: squeezedCandidate) { return }
        // Wait for the final if Google changed what was already delivered.
        guard Self.commonPrefix(candidate, committed) == Self.squeezed(committed).count else { return }
        let piece = Self.words(of: candidate, after: Self.squeezed(committed).count)
        guard !piece.isEmpty else { return }
        committed = candidate
        log("early commit" + (verbose ? ": \(piece)" : String(format: " (%d words)", piece.split(separator: " ").count)))
        onFinal?(piece)
    }

    /// A final, minus the part of it that was delivered early.
    private func deliverFinal(_ final: String) {
        guard !committed.isEmpty else { onFinal?(final); return }
        let length = Self.squeezed(committed).count
        let common = Self.commonPrefix(final, committed)
        if common == length {
            let rest = Self.words(of: final, after: length)
            if !rest.isEmpty { onFinal?(rest) }
        } else if Double(common) >= 0.6 * Double(length) {
            // Google changed a word that was already typed; keep it and type what follows it.
            log("final revised early text" + (verbose ? " \"\(committed)\" → \"\(final)\"" : ""))
            let rest = Self.words(of: final, after: length)
            if !rest.isEmpty { onFinal?(rest) }
        } else {
            // Mostly different (e.g. after a reconnect the final covers new speech only): type all of it.
            log("final differs from early text" + (verbose ? " \"\(committed)\" → \"\(final)\"" : "") + "; typing all of it")
            onFinal?(final)
        }
    }

    /// Text without spaces and ZWNJ, so "دو تا" = "دوتا" and "می‌خوام" = "میخوام". (By scalars: Swift
    /// keeps a ZWNJ in the same Character as the letter before it.)
    private static func squeezed(_ s: String) -> [Unicode.Scalar] {
        s.unicodeScalars.filter { $0 != " " && $0 != "\u{200C}" }
    }

    private static func commonPrefix(_ a: String, _ b: String) -> Int {
        zip(squeezed(a), squeezed(b)).prefix { $0 == $1 }.count
    }

    /// The words of `text` after its first `length` squeezed scalars. A word that the boundary falls
    /// inside of counts as already delivered.
    private static func words(of text: String, after length: Int) -> String {
        let words = text.split(separator: " ")
        var seen = 0
        for (i, word) in words.enumerated() {
            if seen >= length { return words[i...].joined(separator: " ") }
            seen += squeezed(String(word)).count
        }
        return ""
    }

    // MARK: Watchdog

    private func faultSilences() -> Bool {
        guard case .silence(let n, let after)? = simulatedFault, n == sessionNumber else { return false }
        return Date().timeIntervalSince(sessionStart) >= after
    }

    private func stopWatchdog() {
        watchdog?.invalidate()
        watchdog = nil
    }

    private func checkForStall() {
        guard phase == .running else { return }
        if !connected {
            let waited = Date().timeIntervalSince(sessionStart)
            guard waited >= Self.connectStallSeconds else { return }
            // The queued audio is replayed into the new session, so nothing is lost.
            if recoveries >= Self.maxRecoveries { recoveries = 0 }
            recover(reason: String(format: "not connected after %.1fs", waited))
            return
        }
        guard Date().timeIntervalSince(lastProgress) >= Self.stallSeconds,
              speechSinceProgress >= Self.stallSpeechSeconds else { return }
        if recoveries < Self.maxRecoveries {
            recover(reason: String(format: "no results for %.1fs during %.1fs of speech",
                                   Date().timeIntervalSince(lastProgress), speechSinceProgress))
        } else {
            // Replaying didn't help: give up on this utterance's audio and start clean.
            log("still frozen after \(recoveries) replays; starting a fresh session")
            recoveries = 0
            committed = ""
            let old = stream
            openSession(preroll: prerollAudio())
            old?.cancel()
        }
    }

    /// Replace a frozen session with a fresh one and replay the whole utterance into it.
    private func recover(reason: String) {
        recoveries += 1
        let audio = sessionAudio
        log(String(format: "session %d frozen (%@): replaying %.1fs into a new session (attempt %d)",
                   sessionNumber, reason, Double(audio.count) / Double(SpeechStream.sampleRate * 2), recoveries))
        let old = stream
        openSession(preroll: audio)
        old?.cancel()
    }

    /// Rough voice activity check for one chunk, relative to the recent noise floor.
    private func isSpeech(_ pcm: Data) -> Bool {
        let level: Float = pcm.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            guard !samples.isEmpty else { return 0 }
            var sum: Float = 0
            for v in samples { let f = Float(Int16(littleEndian: v)); sum += f * f }
            return (sum / Float(samples.count)).squareRoot()
        }
        recentLevels.append(level)
        if recentLevels.count > 30 { recentLevels.removeFirst() }
        let noiseFloor = recentLevels.min() ?? 0
        return level > max(noiseFloor * 3, 150)
    }

    // MARK: Pre-roll

    /// Recent audio starting a little before the speech that is in progress now (if any), so the
    /// next session hears the whole next utterance but nothing of the one just finalized.
    ///
    /// `notBefore`: hard limit, used after a final. Clicks or breaths after the finalized utterance can
    /// break up the silence the walk-back looks for, and it then crossed the whole utterance to the
    /// pause before it: the new session finalized the same text again, its pre-roll did the same, and
    /// the phrase was typed 4 times (2026-10-08).
    private func prerollAudio(notBefore: Date? = nil) -> Data {
        let audio = prerollFromSilence()
        guard let notBefore else { return audio }
        let frameBytes = SpeechStream.sampleRate / 50 * 2
        let maxBytes = max(0, Int(Date().timeIntervalSince(notBefore) * 50)) * frameBytes
        guard audio.count > maxBytes else { return audio }
        log(String(format: "pre-roll limited from %d to %d ms (keeps out the finalized speech)",
                   audio.count * 1000 / (SpeechStream.sampleRate * 2), maxBytes * 1000 / (SpeechStream.sampleRate * 2)))
        return audio.suffix(maxBytes)
    }

    private func prerollFromSilence() -> Data {
        let frameBytes = SpeechStream.sampleRate / 50 * 2 // 20 ms
        let frameCount = recent.count / frameBytes
        guard frameCount > 0 else { return Data() }

        let levels: [Float] = recent.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            return (0..<frameCount).map { f in
                var sum: Float = 0
                for k in f * frameBytes / 2 ..< (f + 1) * frameBytes / 2 {
                    let v = Float(Int16(littleEndian: samples[k]))
                    sum += v * v
                }
                return (sum / Float(frameBytes / 2)).squareRoot()
            }
        }
        let noiseFloor = levels.sorted()[levels.count / 5]
        let threshold = max(noiseFloor * 3, 150)

        // Walk back over any speech after the finalized utterance (pauses inside it included) until
        // we reach the gap that ended that utterance. Google only finalizes after more than a second
        // of silence (measured: at least 1.24 s across 78 finals), so shorter pauses are not it.
        let gapFrames = 40 // 800 ms
        var silentRun = 0
        var gapEnd: Int?           // first frame after the gap, where the current speech starts
        for f in stride(from: frameCount - 1, through: 0, by: -1) {
            if levels[f] <= threshold {
                silentRun += 1
                if silentRun == gapFrames { gapEnd = f + gapFrames; break }
            } else {
                silentRun = 0
            }
        }
        guard let gapEnd else {
            // No gap in the buffer: fall back to the last second.
            return recent.suffix(50 * frameBytes)
        }
        // Start 600 ms into the gap: some silence before the speech, none of the previous speech.
        let start = gapEnd - 30
        return recent.suffix((frameCount - start) * frameBytes)
    }
}

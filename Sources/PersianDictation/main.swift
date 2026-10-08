import AppKit

// Debug mode: `PersianDictation --transcribe <file.wav|file.raw> [lang]` streams 16 kHz / 16-bit /
// mono PCM (e.g. a development-mode recording) through the recognizer and prints the results.
if CommandLine.arguments.count >= 3, CommandLine.arguments[1] == "--transcribe" {
    var pcm = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
    if pcm.prefix(4) == Data("RIFF".utf8) { pcm = pcm.dropFirst(44) } // our recordings use a 44-byte header
    pcm = Data(pcm)
    setvbuf(stdout, nil, _IOLBF, 0) // line-buffered even when piped
    let language = CommandLine.arguments.count >= 4 && !CommandLine.arguments[3].hasPrefix("--")
        ? CommandLine.arguments[3] : Settings.language
    let recognizer = ContinuousRecognizer(apiKey: Settings.apiKey, language: language)
    let start = Date()
    func stamp() -> String { String(format: "[%.2fs]", Date().timeIntervalSince(start)) }
    recognizer.log = { print(stamp(), $0) }
    // Testing: --simulate-freeze silence:<session>:<seconds> | empty-final:<session>
    if let i = CommandLine.arguments.firstIndex(of: "--simulate-freeze"), i + 1 < CommandLine.arguments.count {
        let parts = CommandLine.arguments[i + 1].split(separator: ":")
        if parts.first == "silence", parts.count == 3, let n = Int(parts[1]), let t = Double(parts[2]) {
            recognizer.simulatedFault = .silence(session: n, after: t)
        } else if parts.first == "empty-final", parts.count == 2, let n = Int(parts[1]) {
            recognizer.simulatedFault = .emptyFinal(session: n)
        }
    }
    recognizer.onInterim = { if !$0.isEmpty { print(stamp(), "interim:", $0) } }
    recognizer.onFinal = { print(stamp(), "FINAL:", $0) }
    recognizer.onEnd = { error in
        print("end", error.map { "error: \($0.localizedDescription)" } ?? "ok")
        exit(error == nil ? 0 : 1)
    }
    recognizer.start()
    // Feed the file in real time (100 ms chunks), like a microphone.
    var offset = 0
    Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { timer in
        guard offset < pcm.count else {
            timer.invalidate()
            recognizer.finish()
            return
        }
        recognizer.send(pcm.subdata(in: offset..<min(offset + 3200, pcm.count)))
        offset += 3200
    }
    RunLoop.main.run() // the feed timer needs a run loop
}

// `PersianDictation --export-diagnostics [hours]`: same zip as the menu's "Share Diagnostics…"
// (recordings of the last `hours`, default 2); prints its path.
if CommandLine.arguments.count >= 2, CommandLine.arguments[1] == "--export-diagnostics" {
    let hours = CommandLine.arguments.count >= 3 ? Int(CommandLine.arguments[2]) ?? 2 : 2
    let export = DiagnosticsExport(hours: hours)
    export.start(progress: { _ in }) { result in
        switch result {
        case .success(let zip): print(zip.path); exit(0)
        case .failure(let error): print("error: \(error.localizedDescription)"); exit(1)
        }
    }
    RunLoop.main.run()
}

// `PersianDictation --write-iconset <dir.iconset>`: used by scripts/build-app.sh to make the .icns.
if CommandLine.arguments.count >= 3, CommandLine.arguments[1] == "--write-iconset" {
    try AppIcon.writeIconset(to: URL(fileURLWithPath: CommandLine.arguments[2]))
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory) // menu-bar only, no Dock icon
app.run()

import AppKit
import AVFoundation

/// Builds the "Share Diagnostics" zip: info.txt (version, macOS, mics, displays, permissions), the logs
/// and the session recordings of the last few hours (the user picks how many: ~50–100 MB per day),
/// into ~/Downloads. Reports progress; can be cancelled.
final class DiagnosticsExport {
    static let hourChoices = [2, 6, 24, 48]

    let hours: Int
    private var process: Process?
    private var cancelled = false

    init(hours: Int) {
        self.hours = hours
    }

    /// Log files and the recordings of the last `hours`, with their sizes.
    static func inputs(hours: Int) -> [(url: URL, bytes: Int64)] {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        let logsDir = DiagnosticsLog.url.deletingLastPathComponent()
        let logs = ((try? fm.contentsOfDirectory(at: logsDir, includingPropertiesForKeys: keys)) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("PersianDictation") && $0.pathExtension == "log" }
        let cutoff = Date().addingTimeInterval(-Double(hours) * 3600)
        let recordings = ((try? fm.contentsOfDirectory(at: SessionRecorder.directory, includingPropertiesForKeys: keys)) ?? [])
            .filter { url in
                guard url.pathExtension == "wav",
                      let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
                else { return false }
                return modified >= cutoff
            }
        return (logs + recordings).map { url in
            (url, Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0))
        }
    }

    /// `progress` (0...1) and `completion` are called on the main thread.
    func start(progress: @escaping (Double) -> Void, completion: @escaping (Result<URL, Error>) -> Void) {
        let info = Self.summary(hours: hours) // AppKit / Settings: read on the main thread
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Result { try self.makeZip(info: info, progress: progress) }
            DispatchQueue.main.async { completion(result) }
        }
    }

    func cancel() {
        cancelled = true
        process?.terminate()
    }

    private func makeZip(info: String, progress: @escaping (Double) -> Void) throws -> URL {
        let fm = FileManager.default
        let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
        let name = "PersianDictation-diagnostics-\(stamp)"
        let staging = fm.temporaryDirectory.appendingPathComponent(name)
        try? fm.removeItem(at: staging)
        try fm.createDirectory(at: staging.appendingPathComponent("recordings"), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }

        try info.write(to: staging.appendingPathComponent("info.txt"), atomically: true, encoding: .utf8)
        let inputs = Self.inputs(hours: hours)
        for (url, _) in inputs {
            let dir = url.pathExtension == "wav" ? staging.appendingPathComponent("recordings") : staging
            try Self.place(url, in: dir)
        }
        let total = max(1, inputs.reduce(0) { $0 + $1.bytes })

        let downloads = fm.urls(for: .downloadsDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory
        let zip = downloads.appendingPathComponent(name + ".zip")
        let ditto = Process()
        ditto.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        // Level 1: WAV barely compresses better at higher levels, and it is ~3x faster.
        ditto.arguments = ["-V", "-c", "-k", "--norsrc", "--noextattr", "--noacl", "--zlibCompressionLevel", "1",
                           "--keepParent", staging.path, zip.path]
        // -V prints "<n> bytes for <file>" after each file: sum them for progress.
        let pipe = Pipe()
        ditto.standardError = pipe
        var done: Int64 = 0
        var pending = ""
        pipe.fileHandleForReading.readabilityHandler = { handle in
            pending += String(decoding: handle.availableData, as: UTF8.self)
            while let newline = pending.firstIndex(of: "\n") {
                let line = pending[..<newline]
                pending = String(pending[pending.index(after: newline)...])
                if let n = line.split(separator: " ").first.flatMap({ Int64($0) }), line.contains(" bytes for ") {
                    done += n
                    let fraction = min(1, Double(done) / Double(total))
                    DispatchQueue.main.async { progress(fraction) }
                }
            }
        }
        process = ditto
        try ditto.run()
        ditto.waitUntilExit()
        pipe.fileHandleForReading.readabilityHandler = nil
        if cancelled {
            try? fm.removeItem(at: zip)
            throw CancellationError()
        }
        guard ditto.terminationStatus == 0 else {
            try? fm.removeItem(at: zip)
            throw NSError(domain: "DiagnosticsExport", code: Int(ditto.terminationStatus),
                          userInfo: [NSLocalizedDescriptionKey: "Could not create the zip file."])
        }
        return zip
    }

    /// Hard link when possible (instant, no extra space), else copy.
    private static func place(_ file: URL, in dir: URL) throws {
        let target = dir.appendingPathComponent(file.lastPathComponent)
        do { try FileManager.default.linkItem(at: file, to: target) } catch {
            try FileManager.default.copyItem(at: file, to: target)
        }
    }

    private static func summary(hours: Int) -> String {
        var model = [CChar](repeating: 0, count: 256)
        var size = model.count
        sysctlbyname("hw.model", &model, &size, nil, 0)
        let inputs = AudioCapture.microphones()
        let screens = NSScreen.screens.map { "\($0.localizedName) \(Int($0.frame.width))x\(Int($0.frame.height))" }
        return """
        Persian Dictation \(AppVersion.display)
        Created: \(ISO8601DateFormatter().string(from: Date())) (time zone \(TimeZone.current.identifier))
        macOS: \(ProcessInfo.processInfo.operatingSystemVersionString)
        Mac model: \(String(cString: model))
        Language: \(Settings.language)
        Development mode: \(Settings.developmentMode)
        Accessibility trusted: \(TextInserter.isTrusted(prompt: false))
        Microphone permission: \(AVCaptureDevice.authorizationStatus(for: .audio).rawValue) (3 = authorized)
        Default microphone: \(AudioCapture.systemDefault()?.localizedName ?? "none")
        Microphone choice: \(Settings.microphoneID == nil ? "System Default" : AudioCapture.preferred()?.localizedName ?? "not connected")
        Microphones: \(inputs.map(\.localizedName).joined(separator: ", "))
        Displays: \(screens.joined(separator: ", "))
        Recordings included: last \(hours) hours

        """
    }
}

/// The window behind "Share Diagnostics…": pick how many hours of recordings, watch the progress,
/// then share the zip or show it in Finder.
final class DiagnosticsWindow: NSObject, NSWindowDelegate {
    private let window: NSWindow
    private let hoursPopup = NSPopUpButton()
    private let progressBar = NSProgressIndicator()
    private let status = NSTextField(labelWithString: "")
    private let primaryButton = NSButton(title: "Create Zip", target: nil, action: nil)
    private let secondaryButton = NSButton(title: "Cancel", target: nil, action: nil)
    private let finderButton = NSButton(title: "Show in Finder", target: nil, action: nil)
    private let consent = NSButton(checkboxWithTitle:
        "I understand and want to share my voice and dictated text.",
        target: nil, action: nil)
    private var export: DiagnosticsExport?
    private var zip: URL?
    var onClose: (() -> Void)?

    override init() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 440, height: 320),
                          styleMask: [.titled, .closable], backing: .buffered, defer: false)
        super.init()
        window.title = "Share Diagnostics"
        window.isReleasedWhenClosed = false
        window.delegate = self

        let intro = NSTextField(wrappingLabelWithString:
            "Zips the log and the session recordings into your Downloads folder, then opens the share menu.")
        for hours in DiagnosticsExport.hourChoices {
            let bytes = DiagnosticsExport.inputs(hours: hours).reduce(Int64(0)) { $0 + $1.bytes }
            hoursPopup.addItem(withTitle: "Last \(hours) hours (about \(Self.megabytes(bytes)) before zipping)")
        }
        let hoursRow = NSStackView(views: [NSTextField(labelWithString: "Recordings from:"), hoursPopup])
        progressBar.isIndeterminate = false
        progressBar.minValue = 0
        progressBar.maxValue = 1
        progressBar.isHidden = true
        status.textColor = .secondaryLabelColor

        consent.target = self
        consent.action = #selector(consentChanged)
        consent.contentTintColor = .systemRed
        primaryButton.isEnabled = false // until the user consents
        primaryButton.target = self
        primaryButton.action = #selector(primaryClicked)
        primaryButton.keyEquivalent = "\r"
        secondaryButton.target = self
        secondaryButton.action = #selector(secondaryClicked)
        secondaryButton.keyEquivalent = "\u{1b}"
        finderButton.target = self
        finderButton.action = #selector(showInFinder)
        finderButton.isHidden = true
        let spacer = NSView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let buttons = NSStackView(views: [finderButton, spacer, secondaryButton, primaryButton])

        let stack = NSStackView(views: [intro, hoursRow, privacyWarning(), consent, progressBar, status, buttons])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 12
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)
        stack.translatesAutoresizingMaskIntoConstraints = false
        window.contentView = stack
        NSLayoutConstraint.activate([
            stack.widthAnchor.constraint(equalToConstant: 440),
            intro.widthAnchor.constraint(equalToConstant: 400),
            progressBar.widthAnchor.constraint(equalToConstant: 400),
            buttons.widthAnchor.constraint(equalToConstant: 400),
        ])
    }

    /// The zip holds the user's voice (recordings) and what they dictated (the log): make sure they know.
    private func privacyWarning() -> NSView {
        let title = NSTextField(labelWithString: "⚠️  Private data")
        title.font = .boldSystemFont(ofSize: 14)
        title.textColor = .systemRed
        let body = NSTextField(wrappingLabelWithString:
            "The zip contains recordings of your voice and the full text of everything you dictated in this "
            + "period. It may include personal or confidential information (names, messages, passwords you "
            + "spoke, work details). Only send it to someone you trust, and check the time range first.")
        body.textColor = .systemRed
        let column = NSStackView(views: [title, body])
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = 4
        column.translatesAutoresizingMaskIntoConstraints = false
        let box = NSBox()
        box.boxType = .custom
        box.borderColor = .systemRed
        box.borderWidth = 1.5
        box.cornerRadius = 8
        box.fillColor = NSColor.systemRed.withAlphaComponent(0.08)
        box.contentViewMargins = NSSize(width: 12, height: 10)
        box.contentView = column
        NSLayoutConstraint.activate([
            box.widthAnchor.constraint(equalToConstant: 400),
            body.widthAnchor.constraint(equalToConstant: 370),
        ])
        return box
    }

    @objc private func consentChanged() {
        if zip == nil, export == nil { primaryButton.isEnabled = consent.state == .on }
    }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        window.center()
        window.makeKeyAndOrderFront(nil)
    }

    @objc private func primaryClicked() {
        if let zip { share(zip) } else if consent.state == .on { startExport() }
    }

    @objc private func secondaryClicked() {
        if let export {
            export.cancel()
        } else {
            window.close()
        }
    }

    @objc private func showInFinder() {
        if let zip { NSWorkspace.shared.activateFileViewerSelecting([zip]) }
    }

    private func startExport() {
        let hours = DiagnosticsExport.hourChoices[hoursPopup.indexOfSelectedItem]
        let export = DiagnosticsExport(hours: hours)
        self.export = export
        hoursPopup.isEnabled = false
        consent.isEnabled = false
        primaryButton.isEnabled = false
        progressBar.doubleValue = 0
        progressBar.isHidden = false
        status.stringValue = "Zipping… 0%"
        let started = Date()
        export.start(progress: { [weak self] fraction in
            self?.progressBar.doubleValue = fraction
            self?.status.stringValue = "Zipping… \(Int(fraction * 100))%"
        }, completion: { [weak self] result in
            guard let self else { return }
            self.export = nil
            switch result {
            case .success(let zip):
                let bytes = (try? zip.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
                DiagnosticsLog.write(String(format: "diagnostics zip created: %@ (%@, %d h, %.1fs)", zip.path,
                                            Self.megabytes(Int64(bytes)), hours, Date().timeIntervalSince(started)))
                self.zip = zip
                self.progressBar.doubleValue = 1
                self.status.stringValue = "Ready: \(zip.lastPathComponent) (\(Self.megabytes(Int64(bytes)))) in Downloads."
                self.primaryButton.title = "Share…"
                self.primaryButton.isEnabled = true
                self.secondaryButton.title = "Close"
                self.finderButton.isHidden = false
                self.share(zip)
            case .failure(let error):
                let cancelled = error is CancellationError
                if !cancelled { DiagnosticsLog.write("diagnostics zip failed: \(error.localizedDescription)") }
                self.progressBar.isHidden = true
                self.status.stringValue = cancelled ? "Cancelled." : "Could not create the zip: \(error.localizedDescription)"
                self.hoursPopup.isEnabled = true
                self.consent.isEnabled = true
                self.primaryButton.isEnabled = self.consent.state == .on
            }
        })
    }

    private func share(_ zip: URL) {
        NSSharingServicePicker(items: [zip]).show(relativeTo: primaryButton.bounds, of: primaryButton,
                                                  preferredEdge: .minY)
    }

    func windowWillClose(_ notification: Notification) {
        export?.cancel()
        onClose?()
    }

    private static func megabytes(_ bytes: Int64) -> String {
        let mb = Double(bytes) / 1_000_000
        return mb < 10 ? String(format: "%.1f MB", mb) : "\(Int(mb.rounded())) MB"
    }
}

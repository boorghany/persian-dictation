import AppKit
import Carbon

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let controller = DictationController()
    private var statusItem: NSStatusItem!
    private var diagnosticsWindow: DiagnosticsWindow?
    private let microphoneItem = NSMenuItem(title: "Microphone", action: nil, keyEquivalent: "")
    private let microphoneMenu = NSMenu()
    private let shareDiagnosticsItem = NSMenuItem(title: "Share Diagnostics…", action: #selector(shareDiagnostics),
                                                  keyEquivalent: "")
    private var hotKey: HotKey?
    /// Escape and Enter stop dictation (pasting what was said). Registered only while listening,
    /// so these keys behave normally otherwise. Enter stays registered while finishing: pressed again
    /// then, it is sent to the app after the last words are pasted.
    private var escapeHotKey: HotKey?
    private var enterHotKeys: [HotKey] = []

    private let statusLine = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let toggleItem = NSMenuItem(title: "", action: #selector(toggle), keyEquivalent: " ")
    private let accessibilityItem = NSMenuItem(title: "Grant Accessibility Permission…",
                                               action: #selector(openAccessibilitySettings), keyEquivalent: "")
    private let languages: [(title: String, code: String)] = [
        ("Persian (fa-IR)", "fa-IR"),
        ("English (en-US)", "en-US"),
    ]
    private var languageItems: [NSMenuItem] = []
    private let developmentItem = NSMenuItem(title: "Development Mode (log + record audio)",
                                             action: #selector(toggleDevelopmentMode), keyEquivalent: "")

    private func registerEnterHotKeys() {
        enterHotKeys = [kVK_Return, kVK_ANSI_KeypadEnter].compactMap { key in
            HotKey(keyCode: key, modifiers: 0) { [weak self] in
                self?.controller.stopKeyPressed(enter: true)
            }
        }
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        statusItem.menu = buildMenu()

        controller.onStateChange = { [weak self] state in
            guard let self else { return }
            switch state {
            case .listening:
                self.escapeHotKey = HotKey(keyCode: kVK_Escape, modifiers: 0) { [weak self] in
                    self?.controller.stopKeyPressed(enter: false)
                }
                self.registerEnterHotKeys()
            case .finishing:
                self.escapeHotKey = nil
            case .idle:
                self.escapeHotKey = nil
                self.enterHotKeys = []
            }
            self.refresh()
        }
        // A spoken "اینتر" presses Return in the target app: our own Enter hotkey must not catch it.
        controller.onReturnPassThrough = { [weak self] passThrough in
            guard let self else { return }
            if passThrough {
                self.enterHotKeys = []
            } else if self.controller.state != .idle && self.enterHotKeys.isEmpty {
                self.registerEnterHotKeys()
            }
        }

        // ⌃⌥Space. ⌃Space is left alone because it switches keyboard input sources.
        hotKey = HotKey(keyCode: kVK_Space, modifiers: controlKey | optionKey) { [weak self] in
            self?.controller.toggle()
        }
        if hotKey == nil {
            NSLog("PersianDictation: could not register ⌃⌥Space; it may be used by another app")
        }

        refresh()
        SessionRecorder.pruneOld()
        DiagnosticsLog.write("launch version=\"\(AppVersion.display)\" developmentMode=\(Settings.developmentMode) path=\(Bundle.main.bundlePath) \(DiagnosticsLog.signingSummary())")
        // Ask for Accessibility up front so the first dictation just works.
        let trusted = TextInserter.isTrusted(prompt: true)
        DiagnosticsLog.write("launch accessibilityTrusted=\(trusted) postEventAccess=\(CGPreflightPostEventAccess()) parent=\(getppid())")
    }

    private func buildMenu() -> NSMenu {
        let menu = NSMenu()

        let versionItem = NSMenuItem(title: "Persian Dictation \(AppVersion.display)", action: nil, keyEquivalent: "")
        versionItem.isEnabled = false
        menu.addItem(versionItem)
        menu.delegate = self

        statusLine.isEnabled = false
        menu.addItem(statusLine)

        toggleItem.keyEquivalentModifierMask = [.control, .option]
        toggleItem.target = self
        menu.addItem(toggleItem)
        menu.addItem(.separator())

        let languageMenu = NSMenu()
        for language in languages {
            let item = NSMenuItem(title: language.title, action: #selector(selectLanguage(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = language.code
            languageMenu.addItem(item)
            languageItems.append(item)
        }
        microphoneItem.submenu = microphoneMenu
        microphoneMenu.delegate = self
        menu.addItem(microphoneItem)

        let languageItem = NSMenuItem(title: "Language", action: nil, keyEquivalent: "")
        languageItem.submenu = languageMenu
        menu.addItem(languageItem)

        accessibilityItem.target = self
        menu.addItem(accessibilityItem)
        menu.addItem(.separator())

        developmentItem.target = self
        menu.addItem(developmentItem)
        let logsItem = NSMenuItem(title: "Open Logs Folder", action: #selector(openLogsFolder), keyEquivalent: "")
        logsItem.target = self
        menu.addItem(logsItem)
        shareDiagnosticsItem.target = self
        menu.addItem(shareDiagnosticsItem)
        menu.addItem(.separator())

        menu.addItem(NSMenuItem(title: "Quit Persian Dictation",
                                action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        return menu
    }

    func menuWillOpen(_ menu: NSMenu) {
        if menu === microphoneMenu { buildMicrophoneMenu() } else { refresh() }
    }

    /// "System Default (name)" follows the macOS input setting (and switches when it changes);
    /// picking a device sticks to it while it is connected.
    private func buildMicrophoneMenu() {
        microphoneMenu.removeAllItems()
        let choice = Settings.microphoneID
        let defaultName = AudioCapture.systemDefault()?.localizedName ?? "none"
        let follow = NSMenuItem(title: "System Default (\(defaultName))", action: #selector(selectMicrophone(_:)),
                                keyEquivalent: "")
        follow.target = self
        follow.state = choice == nil ? .on : .off
        microphoneMenu.addItem(follow)
        microphoneMenu.addItem(.separator())
        let devices = AudioCapture.microphones()
        for device in devices {
            let item = NSMenuItem(title: device.localizedName, action: #selector(selectMicrophone(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = device.uniqueID
            item.state = device.uniqueID == choice ? .on : .off
            microphoneMenu.addItem(item)
        }
        if let choice, !devices.contains(where: { $0.uniqueID == choice }) {
            let missing = NSMenuItem(title: "Chosen microphone not connected — using the default", action: nil,
                                     keyEquivalent: "")
            missing.isEnabled = false
            microphoneMenu.addItem(missing)
        }
    }

    @objc private func selectMicrophone(_ sender: NSMenuItem) {
        Settings.microphoneID = sender.representedObject as? String
        DiagnosticsLog.write("microphone choice: \(sender.title)")
        controller.microphonePreferenceChanged()
    }

    private func refresh() {
        let listening = controller.state != .idle
        statusItem.button?.image = AppIcon.menuBarImage(listening: listening)

        switch controller.state {
        case .idle: statusLine.title = "Ready"
        case .listening: statusLine.title = "Listening…"
        case .finishing: statusLine.title = "Finishing…"
        }
        toggleItem.title = listening ? "Stop Dictation (or press Esc / Enter)" : "Start Dictation"
        microphoneItem.title = "Microphone: \(Settings.microphoneID == nil ? "System Default" : AudioCapture.preferred()?.localizedName ?? "?")"
        accessibilityItem.isHidden = TextInserter.isTrusted(prompt: false)
        developmentItem.state = Settings.developmentMode ? .on : .off
        for item in languageItems {
            item.state = (item.representedObject as? String) == Settings.language ? .on : .off
        }
    }

    @objc private func toggle() {
        controller.toggle()
    }

    @objc private func selectLanguage(_ sender: NSMenuItem) {
        guard let code = sender.representedObject as? String else { return }
        Settings.language = code
        refresh()
    }

    @objc private func toggleDevelopmentMode() {
        if !Settings.developmentMode {
            let alert = NSAlert()
            alert.messageText = "Turn on Development Mode?"
            alert.informativeText = "Development Mode saves a recording of everything you dictate and the recognized "
                + "text in ~/Library/Logs (kept for 7 days). This may include private or confidential information. "
                + "Use it only for troubleshooting."
            alert.addButton(withTitle: "Turn On")
            alert.addButton(withTitle: "Cancel")
            NSApp.activate(ignoringOtherApps: true)
            guard alert.runModal() == .alertFirstButtonReturn else { return }
        }
        Settings.developmentMode.toggle()
        DiagnosticsLog.write("developmentMode=\(Settings.developmentMode)")
        refresh()
    }

    @objc private func openLogsFolder() {
        try? FileManager.default.createDirectory(at: SessionRecorder.directory, withIntermediateDirectories: true)
        NSWorkspace.shared.open(DiagnosticsLog.url.deletingLastPathComponent().appendingPathComponent("PersianDictation"))
    }

    @objc private func shareDiagnostics() {
        if diagnosticsWindow == nil {
            let window = DiagnosticsWindow()
            window.onClose = { [weak self] in self?.diagnosticsWindow = nil }
            diagnosticsWindow = window
        }
        diagnosticsWindow?.show()
    }

    @objc private func openAccessibilitySettings() {
        _ = TextInserter.isTrusted(prompt: true)
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }
}

enum AppVersion {
    /// e.g. "0.1.0 (build 12)". The build number is bumped by scripts/build-app.sh on every build.
    static var display: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? "?"
        return "\(version) (build \(build))"
    }
}

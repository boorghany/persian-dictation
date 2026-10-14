import AppKit
import ApplicationServices

/// Inserts text into whatever app currently has keyboard focus by pasting it
/// (clipboard + synthetic ⌘V), then restoring the user's clipboard.
///
/// Pasting is used instead of synthesized typing because it is reliable for
/// Persian text (ZWNJ, combining marks) in every kind of app, including Electron ones.
enum TextInserter {
    /// Synthetic key events require the Accessibility permission.
    static func isTrusted(prompt: Bool) -> Bool {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: prompt] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    /// The user's clipboard, saved before our first paste and not yet restored. Kept across
    /// back-to-back pastes so a quick second paste doesn't save our own text as "the user's".
    private static var saved: [NSPasteboardItem]?
    private static var restore: DispatchWorkItem?
    private static var provider: PasteProvider?

    /// Restore no earlier than this after ⌘V, and this long after the target app last read the text.
    private static let minimumHold: TimeInterval = 1.5
    private static let afterRead: TimeInterval = 1.0
    /// Nobody read it (the paste didn't happen): restore anyway.
    private static let maximumHold: TimeInterval = 5

    /// Main thread only.
    static func insert(_ text: String) {
        guard !text.isEmpty else { return }
        let pasteboard = NSPasteboard.general
        restore?.cancel()
        if saved == nil { saved = snapshot(pasteboard) }

        // The text is provided lazily, so we learn when the target app actually reads it. Browsers
        // read asynchronously: in Google Calendar Chrome often read it after the old fixed 0.4 s, when
        // the user's clipboard was already back, and nothing was pasted (the Edit menu still flashed).
        let sent = Date()
        var changeCount = 0 // set after writing; captured by reference
        let provider = PasteProvider(text: text) { requestedAt in
            DiagnosticsLog.verbose(String(format: "paste read after %.0f ms", requestedAt.timeIntervalSince(sent) * 1000))
            scheduleRestore(at: max(sent.addingTimeInterval(minimumHold), requestedAt.addingTimeInterval(afterRead)),
                            changeCount: changeCount)
        }
        self.provider = provider
        pasteboard.clearContents()
        let item = NSPasteboardItem()
        item.setDataProvider(provider, forTypes: [.string])
        // Ask clipboard managers not to record this temporary entry.
        item.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.TransientType"))
        pasteboard.writeObjects([item])
        changeCount = pasteboard.changeCount

        sendCommandV()
        scheduleRestore(at: sent.addingTimeInterval(maximumHold), changeCount: changeCount, unread: true)
    }

    private static func scheduleRestore(at time: Date, changeCount: Int, unread: Bool = false) {
        restore?.cancel()
        let work = DispatchWorkItem {
            restore = nil
            provider = nil
            let pasteboard = NSPasteboard.general
            defer { saved = nil }
            if unread { DiagnosticsLog.verbose("paste never read; restoring the clipboard") }
            // Don't clobber something the user copied in the meantime.
            guard pasteboard.changeCount == changeCount, let saved else { return }
            pasteboard.clearContents()
            if !saved.isEmpty { pasteboard.writeObjects(saved) }
        }
        restore = work
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, time.timeIntervalSinceNow), execute: work)
    }

    private static func snapshot(_ pasteboard: NSPasteboard) -> [NSPasteboardItem] {
        (pasteboard.pasteboardItems ?? []).map { original in
            let copy = NSPasteboardItem()
            for type in original.types {
                if let data = original.data(forType: type) {
                    copy.setData(data, forType: type)
                }
            }
            return copy
        }
    }

    private static func sendCommandV() {
        let source = CGEventSource(stateID: .combinedSessionState)
        let vKey: CGKeyCode = 9 // kVK_ANSI_V
        let down = CGEvent(keyboardEventSource: source, virtualKey: vKey, keyDown: true)
        let up = CGEvent(keyboardEventSource: source, virtualKey: vKey, keyDown: false)
        down?.flags = .maskCommand
        up?.flags = .maskCommand
        down?.post(tap: .cghidEventTap)
        up?.post(tap: .cghidEventTap)
    }
}

/// Supplies the pasted text when an app asks for it, and reports when that happened.
private final class PasteProvider: NSObject, NSPasteboardItemDataProvider {
    private let text: String
    private let onRead: (Date) -> Void

    init(text: String, onRead: @escaping (Date) -> Void) {
        self.text = text
        self.onRead = onRead
    }

    func pasteboard(_ pasteboard: NSPasteboard?, item: NSPasteboardItem, provideDataForType type: NSPasteboard.PasteboardType) {
        item.setString(text, forType: type)
        let now = Date()
        DispatchQueue.main.async { self.onRead(now) }
    }
}

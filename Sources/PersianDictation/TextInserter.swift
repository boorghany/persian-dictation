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

    /// Whether the target app hasn't read the latest paste yet, and what to do once it has.
    private static var unread = false
    private static var readWaiters: [() -> Void] = []
    /// After the app read the paste, give it this long to insert the text (browsers do it asynchronously).
    private static let insertDelay: TimeInterval = 0.2

    /// A Return press is waiting for the last paste to be read: later pastes wait for the Return,
    /// or they would land before it (e.g. in the chat box before the message is sent).
    private static var returnPending = false
    /// Pastes and Return presses that came while a Return was pending, in order.
    private static var afterReturn: [() -> Void] = []
    /// How long after posting Return before [pressReturnAfterPaste]'s `didPress` runs (the Enter
    /// hotkey is registered again then, and must not catch our own Return).
    private static let returnDelivery: TimeInterval = 0.25

    /// Main thread only.
    static func insert(_ text: String) {
        guard !text.isEmpty else { return }
        if returnPending {
            afterReturn.append { insert(text) }
            return
        }
        let pasteboard = NSPasteboard.general
        restore?.cancel()
        if saved == nil { saved = snapshot(pasteboard) }

        // The text is provided lazily, so we learn when the target app actually reads it. Browsers
        // read asynchronously: in Google Calendar Chrome often read it after the old fixed 0.4 s, when
        // the user's clipboard was already back, and nothing was pasted (the Edit menu still flashed).
        let sent = Date()
        var changeCount = 0 // set after writing; captured by reference
        unread = true
        let provider = PasteProvider(text: text) { requestedAt in
            DiagnosticsLog.verbose(String(format: "paste read after %.0f ms", requestedAt.timeIntervalSince(sent) * 1000))
            unread = false
            let waiters = readWaiters
            readWaiters = []
            if !waiters.isEmpty {
                DispatchQueue.main.asyncAfter(deadline: .now() + insertDelay) { waiters.forEach { $0() } }
            }
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
            if unread {
                DiagnosticsLog.verbose("paste never read; restoring the clipboard")
                // The paste didn't happen, so don't press Enter: it would send the message without it.
                if !readWaiters.isEmpty { DiagnosticsLog.write("paste never read; not pressing Enter") }
                readWaiters = []
                Self.unread = false
                if returnPending { DispatchQueue.main.async { returnDone() } }
            }
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

    /// Press Return in the focused app once it has inserted the last paste. Main thread only.
    /// `willPress` runs right before the key is posted, `didPress` a moment after it was delivered
    /// (also when it isn't pressed because the paste was never read).
    static func pressReturnAfterPaste(willPress: (() -> Void)? = nil, didPress: (() -> Void)? = nil) {
        if returnPending {
            afterReturn.append { pressReturnAfterPaste(willPress: willPress, didPress: didPress) }
            return
        }
        returnPending = true
        returnDidPress = didPress
        let press = {
            willPress?()
            DiagnosticsLog.write("pressing Enter after the last paste")
            sendKey(36) // kVK_Return
            DispatchQueue.main.asyncAfter(deadline: .now() + returnDelivery) { returnDone() }
        }
        if unread {
            readWaiters.append(press)
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + insertDelay, execute: press)
        }
    }

    private static var returnDidPress: (() -> Void)?

    /// The Return was delivered (or dropped): paste what waited for it.
    private static func returnDone() {
        guard returnPending else { return }
        returnPending = false
        let done = returnDidPress
        returnDidPress = nil
        done?()
        // Stops at the next Return, which queues the rest again until it is through.
        while !returnPending && !afterReturn.isEmpty { afterReturn.removeFirst()() }
    }

    private static func sendCommandV() {
        sendKey(9, flags: .maskCommand) // kVK_ANSI_V
    }

    private static func sendKey(_ key: CGKeyCode, flags: CGEventFlags = []) {
        let source = CGEventSource(stateID: .combinedSessionState)
        let down = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: true)
        let up = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: false)
        down?.flags = flags
        up?.flags = flags
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

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

    static func insert(_ text: String) {
        guard !text.isEmpty else { return }
        let pasteboard = NSPasteboard.general
        let saved = snapshot(pasteboard)

        pasteboard.clearContents()
        let item = NSPasteboardItem()
        item.setString(text, forType: .string)
        // Ask clipboard managers not to record this temporary entry.
        item.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.TransientType"))
        pasteboard.writeObjects([item])
        let changeCount = pasteboard.changeCount

        sendCommandV()

        // Give the target app time to read the clipboard before restoring it.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            // Don't clobber something the user copied in the meantime.
            guard pasteboard.changeCount == changeCount else { return }
            pasteboard.clearContents()
            if !saved.isEmpty { pasteboard.writeObjects(saved) }
        }
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

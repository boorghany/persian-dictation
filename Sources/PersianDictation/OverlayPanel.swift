import AppKit

/// Live (interim) text near the top of the main display (the one with the menu bar), on every
/// Desktop (Space). The user asked for one box on the main display rather than one per display.
/// The box never takes focus, so the app the user is dictating into stays active.
final class OverlayPanel {
    private let window = OverlayWindow()
    private var isShown = false
    private var text = ""
    private var observers: [NSObjectProtocol] = []

    init() {
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.activeSpaceDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.spaceChanged() })
        observers.append(NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.screensChanged() })
    }

    func show(_ text: String) {
        self.text = text
        let first = !isShown
        isShown = true
        if let screen = NSScreen.screens.first { window.show(text, on: screen) }
        if first { logPlacement("overlay show") }
    }

    func hide() {
        isShown = false
        window.orderOut(nil)
    }

    /// Scale the recording dot with the microphone level (0...1).
    func setLevel(_ level: Float) {
        window.setLevel(level)
    }

    func setBusy(_ busy: Bool) {
        window.setBusy(busy)
    }

    private func screensChanged() {
        if isShown, let screen = NSScreen.screens.first { window.show(text, on: screen) }
    }

    /// `.canJoinAllSpaces` should already keep the box on every Desktop; if it still isn't on the
    /// Desktop the user switched to, show it again there.
    private func spaceChanged() {
        guard isShown else { return }
        let missing = !window.isOnActiveSpace
        if missing { window.orderFrontRegardless() }
        logPlacement("overlay space changed, reshown=\(missing)")
    }

    private func logPlacement(_ event: String) {
        DiagnosticsLog.verbose("\(event): \(window.screen?.localizedName ?? "?") onActiveSpace=\(window.isOnActiveSpace)")
    }
}

private final class OverlayWindow: NSPanel {
    private let label = NSTextField(wrappingLabelWithString: "")
    private let dot = NSView()
    private let width: CGFloat = 560

    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 560, height: 64),
                   styleMask: [.borderless, .nonactivatingPanel],
                   backing: .buffered, defer: true)
        isFloatingPanel = true
        level = .statusBar  // after isFloatingPanel, which resets the level to .floating
        hidesOnDeactivate = false
        ignoresMouseEvents = true
        backgroundColor = .clear
        isOpaque = false
        hasShadow = true
        isReleasedWhenClosed = false
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]

        let background = NSVisualEffectView()
        background.material = .hudWindow
        background.state = .active
        background.wantsLayer = true
        background.layer?.cornerRadius = 14
        background.layer?.masksToBounds = true
        contentView = background

        dot.wantsLayer = true
        dot.layer?.backgroundColor = NSColor.systemRed.cgColor
        dot.layer?.cornerRadius = 6
        dot.translatesAutoresizingMaskIntoConstraints = false

        label.font = .systemFont(ofSize: 18)
        label.textColor = .labelColor
        label.alignment = .right
        label.baseWritingDirection = .rightToLeft
        label.maximumNumberOfLines = 3
        label.lineBreakMode = .byTruncatingHead
        label.translatesAutoresizingMaskIntoConstraints = false

        background.addSubview(dot)
        background.addSubview(label)
        NSLayoutConstraint.activate([
            dot.widthAnchor.constraint(equalToConstant: 12),
            dot.heightAnchor.constraint(equalToConstant: 12),
            dot.trailingAnchor.constraint(equalTo: background.trailingAnchor, constant: -16),
            dot.centerYAnchor.constraint(equalTo: background.centerYAnchor),
            label.leadingAnchor.constraint(equalTo: background.leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(equalTo: dot.leadingAnchor, constant: -12),
            label.topAnchor.constraint(equalTo: background.topAnchor, constant: 12),
            label.bottomAnchor.constraint(equalTo: background.bottomAnchor, constant: -12),
            label.widthAnchor.constraint(equalToConstant: width - 56),
        ])
    }

    func show(_ text: String, on screen: NSScreen) {
        label.stringValue = text
        let height = max(48, contentView!.fittingSize.height)
        let frame = screen.visibleFrame
        setFrame(NSRect(x: frame.midX - width / 2, y: frame.maxY - height - 24,
                        width: width, height: height), display: true)
        orderFrontRegardless()
    }

    func setLevel(_ level: Float) {
        let alpha = 0.35 + min(1, CGFloat(level) * 12) * 0.65
        dot.layer?.opacity = Float(alpha)
    }

    func setBusy(_ busy: Bool) {
        dot.layer?.backgroundColor = (busy ? NSColor.systemOrange : NSColor.systemRed).cgColor
    }
}

import AppKit

/// The app's mark: a microphone with right-to-left lines of text beside it (voice → Persian text).
/// Drawn in code so the Dock/Finder icon (`--write-iconset`, run by scripts/build-app.sh) and the
/// menu-bar icon come from one source. Android's `ic_launcher_foreground.xml` uses the same geometry.
enum AppIcon {
    /// Draws the glyph into the square `r`; geometry is in fractions of the side, y measured from the top.
    static func drawGlyph(in r: CGRect, color: NSColor) {
        color.setFill()
        color.setStroke()
        let s = r.width
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: r.minX + x * s, y: r.maxY - y * s) }
        let micX: CGFloat = 0.70
        let line = 0.055 * s

        // Capsule
        let capsule = CGRect(x: p(micX - 0.10, 0).x, y: p(0, 0.56).y, width: 0.20 * s, height: 0.40 * s)
        NSBezierPath(roundedRect: capsule, xRadius: 0.10 * s, yRadius: 0.10 * s).fill()
        // Holder, stem and base
        let holder = NSBezierPath()
        holder.lineWidth = line
        holder.lineCapStyle = .round
        holder.appendArc(withCenter: p(micX, 0.44), radius: 0.175 * s, startAngle: 180, endAngle: 360, clockwise: false)
        holder.move(to: p(micX, 0.615))
        holder.line(to: p(micX, 0.76))
        holder.move(to: p(micX - 0.10, 0.76))
        holder.line(to: p(micX + 0.10, 0.76))
        holder.stroke()
        // Text lines: right-aligned, ragged on the left, like Persian text
        let height: CGFloat = 0.07
        for (y, length) in [(0.30, 0.30), (0.45, 0.22), (0.60, 0.27)] as [(CGFloat, CGFloat)] {
            let rect = CGRect(x: p(0.40 - length, 0).x, y: p(0, y + height / 2).y, width: length * s, height: height * s)
            NSBezierPath(roundedRect: rect, xRadius: height * s / 2, yRadius: height * s / 2).fill()
        }
    }

    /// Menu-bar icon: a template (adapts to light/dark menu bars), or red while listening.
    static func menuBarImage(listening: Bool) -> NSImage {
        let image = NSImage(size: NSSize(width: 22, height: 18), flipped: false) { rect in
            drawGlyph(in: CGRect(x: 1, y: -1, width: 20, height: 20), color: listening ? .systemRed : .black)
            return true
        }
        image.isTemplate = !listening
        image.accessibilityDescription = "Persian Dictation"
        return image
    }

    /// Full app icon (macOS shape: 824/1024 rounded square with a soft shadow) at `pixels` × `pixels`.
    static func render(pixels: Int) -> NSBitmapImageRep {
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8,
                                   samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                   bytesPerRow: 0, bitsPerPixel: 0)!
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        let k = CGFloat(pixels) / 1024
        let body = CGRect(x: 100 * k, y: 100 * k, width: 824 * k, height: 824 * k)
        let shape = NSBezierPath(roundedRect: body, xRadius: 185 * k, yRadius: 185 * k)
        NSGraphicsContext.saveGraphicsState()
        let shadow = NSShadow()
        shadow.shadowColor = NSColor.black.withAlphaComponent(0.3)
        shadow.shadowBlurRadius = 20 * k
        shadow.shadowOffset = NSSize(width: 0, height: -8 * k)
        shadow.set()
        NSColor.black.setFill()
        shape.fill()
        NSGraphicsContext.restoreGraphicsState()
        // Persian turquoise to blue.
        NSGradient(starting: NSColor(srgbRed: 0.10, green: 0.74, blue: 0.72, alpha: 1),
                   ending: NSColor(srgbRed: 0.12, green: 0.31, blue: 0.80, alpha: 1))!.draw(in: shape, angle: -60)
        drawGlyph(in: body.insetBy(dx: 120 * k, dy: 120 * k), color: .white)
        NSGraphicsContext.restoreGraphicsState()
        return rep
    }

    /// Writes an .iconset folder for `iconutil -c icns`.
    static func writeIconset(to dir: URL) throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for points in [16, 32, 128, 256, 512] {
            for scale in [1, 2] {
                let name = scale == 1 ? "icon_\(points)x\(points).png" : "icon_\(points)x\(points)@2x.png"
                let png = render(pixels: points * scale).representation(using: .png, properties: [:])!
                try png.write(to: dir.appendingPathComponent(name))
            }
        }
    }
}

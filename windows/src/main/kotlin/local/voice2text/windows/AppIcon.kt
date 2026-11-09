package local.voice2text.windows

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * The app's mark: a microphone with right-to-left lines of text beside it. Same geometry as the Mac
 * `AppIcon.swift` (fractions of the side, y from the top), drawn with Java2D.
 */
object AppIcon {
    fun drawGlyph(g: Graphics2D, x: Double, y: Double, s: Double, color: Color) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = color
        fun px(f: Double) = x + f * s
        fun py(f: Double) = y + f * s
        val micX = 0.70
        // Capsule
        g.fill(RoundRectangle2D.Double(px(micX - 0.10), py(0.16), 0.20 * s, 0.40 * s, 0.20 * s, 0.20 * s))
        // Holder (lower half circle), stem and base
        g.stroke = BasicStroke((0.055 * s).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val r = 0.175 * s
        g.draw(Arc2D.Double(px(micX) - r, py(0.44) - r, 2 * r, 2 * r, 180.0, 180.0, Arc2D.OPEN))
        g.draw(Line2D.Double(px(micX), py(0.615), px(micX), py(0.76)))
        g.draw(Line2D.Double(px(micX - 0.10), py(0.76), px(micX + 0.10), py(0.76)))
        // Text lines: right-aligned, ragged on the left, like Persian text
        val h = 0.07
        for ((cy, length) in listOf(0.30 to 0.30, 0.45 to 0.22, 0.60 to 0.27)) {
            g.fill(RoundRectangle2D.Double(px(0.40 - length), py(cy - h / 2), length * s, h * s, h * s, h * s))
        }
    }

    /** Notification-area icon at [size] px: white or black to suit the taskbar, red while listening. */
    fun trayBitmap(size: Int, listening: Boolean, lightTaskbar: Boolean): BufferedImage {
        val color = when {
            listening -> Color(232, 64, 52)
            lightTaskbar -> Color(28, 28, 28)
            else -> Color.WHITE
        }
        return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { img ->
            val g = img.createGraphics()
            drawGlyph(g, 0.0, size * 0.04, size.toDouble(), color)
            g.dispose()
        }
    }

    /** Full app icon: a rounded square in Persian turquoise to blue with the white glyph. */
    fun render(pixels: Int): BufferedImage {
        val img = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val k = pixels / 1024.0
        val margin = 40 * k
        val side = pixels - 2 * margin
        g.paint = GradientPaint(
            (margin).toFloat(), (margin).toFloat(), Color(26, 189, 184),
            (margin + side * 0.7).toFloat(), (margin + side).toFloat(), Color(31, 79, 204),
        )
        g.fill(RoundRectangle2D.Double(margin, margin, side, side, 200 * k * 2, 200 * k * 2))
        val inset = side * 120 / 824
        drawGlyph(g, margin + inset, margin + inset, side - 2 * inset, Color.WHITE)
        g.dispose()
        return img
    }

    /** Writes a Windows .ico with PNG entries (supported since Vista). */
    fun writeIco(file: File) {
        val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
        val pngs = sizes.map { size ->
            ByteArrayOutputStream().also { ImageIO.write(render(size), "png", it) }.toByteArray()
        }
        val header = ByteBuffer.allocate(6 + 16 * sizes.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(sizes.size.toShort())
        var offset = 6 + 16 * sizes.size
        sizes.forEachIndexed { i, size ->
            val dim = if (size >= 256) 0 else size
            header.put(dim.toByte()).put(dim.toByte()).put(0).put(0)
            header.putShort(1).putShort(32).putInt(pngs[i].size).putInt(offset)
            offset += pngs[i].size
        }
        file.outputStream().use { out ->
            out.write(header.array())
            pngs.forEach { out.write(it) }
        }
    }
}

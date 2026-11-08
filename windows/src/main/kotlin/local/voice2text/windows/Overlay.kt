package local.voice2text.windows

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.BorderLayout
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.font.TextAttribute
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.JWindow
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Live (interim) text near the top of the primary display. Never takes focus or clicks, so the app
 * the user dictates into stays active. EDT only.
 *
 * A new window for every dictation: a Windows window belongs to one virtual desktop, and the Mac
 * app learned that a window reused after a desktop/display change can end up on the wrong one.
 */
class Overlay {
    private var window: OverlayWindow? = null
    private var busy = false
    private var level = 0f
    var rightToLeft = true

    fun show(text: String) {
        val w = window ?: OverlayWindow().also {
            window = it
            it.setBusy(busy)
            it.setLevel(level)
        }
        w.show(text, rightToLeft)
    }

    fun hide() {
        window?.dispose()
        window = null
    }

    fun setLevel(level: Float) {
        this.level = level
        window?.setLevel(level)
    }

    fun setBusy(busy: Boolean) {
        this.busy = busy
        window?.setBusy(busy)
    }
}

private class OverlayWindow : JWindow() {
    private val boxWidth = 560
    private val maxLines = 3
    private var busy = false
    private var level = 0f
    private val text = JTextPane()
    private val textFont = pickFont()

    private val panel = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(28, 28, 30, 238)
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 28.0, 28.0))
            val alpha = (0.35f + minOf(1f, level * 12) * 0.65f).coerceIn(0f, 1f)
            val base = if (busy) Color(255, 159, 10) else Color(255, 69, 58)
            g2.color = Color(base.red, base.green, base.blue, (alpha * 255).toInt())
            val dot = 12.0
            g2.fill(Ellipse2D.Double(width - 16 - dot, (height - dot) / 2, dot, dot))
            g2.dispose()
        }
    }

    init {
        focusableWindowState = false
        isAlwaysOnTop = true
        type = Type.UTILITY
        background = Color(0, 0, 0, 0)
        panel.isOpaque = false
        panel.border = BorderFactory.createEmptyBorder(12, 16, 12, 40)
        text.isEditable = false
        text.isFocusable = false
        text.isOpaque = false
        text.font = textFont
        text.foreground = Color(245, 245, 247)
        text.border = null
        panel.add(text, BorderLayout.CENTER)
        contentPane = panel
    }

    fun show(value: String, rightToLeft: Boolean) {
        setDirection(rightToLeft)
        val innerWidth = boxWidth - 56
        val lineHeight = text.getFontMetrics(textFont).height
        var shown = value
        text.text = shown
        // Keep the end (the newest words) when it's longer than three lines.
        var words = value.split(' ')
        while (textHeight(innerWidth) > lineHeight * maxLines + 4 && words.size > 1) {
            words = words.drop(1)
            shown = "… " + words.joinToString(" ")
            text.text = shown
        }
        val height = maxOf(48, textHeight(innerWidth) + 30)
        val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        setBounds(bounds.x + (bounds.width - boxWidth) / 2, bounds.y + insets.top + 24, boxWidth, height)
        if (!isDisplayable) {
            addNotify()
            makeClickThrough()
        }
        if (!isVisible) isVisible = true
        repaint()
    }

    private fun textHeight(width: Int): Int {
        text.size = Dimension(width, Short.MAX_VALUE.toInt())
        return text.preferredSize.height
    }

    private fun setDirection(rightToLeft: Boolean) {
        val orientation = if (rightToLeft) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
        if (text.componentOrientation == orientation && text.document.getProperty(TextAttribute.RUN_DIRECTION) != null) return
        text.componentOrientation = orientation
        text.document.putProperty(
            TextAttribute.RUN_DIRECTION,
            if (rightToLeft) TextAttribute.RUN_DIRECTION_RTL else TextAttribute.RUN_DIRECTION_LTR,
        )
        val attrs = SimpleAttributeSet()
        StyleConstants.setAlignment(attrs, if (rightToLeft) StyleConstants.ALIGN_RIGHT else StyleConstants.ALIGN_LEFT)
        text.styledDocument.setParagraphAttributes(0, Int.MAX_VALUE, attrs, false)
    }

    /** No activation, no taskbar button, clicks pass through. */
    private fun makeClickThrough() {
        runCatching {
            val hwnd = HWND(Native.getWindowPointer(this))
            val user32 = WinThread.user32
            val gwlExStyle = -20
            val style = user32.GetWindowLong(hwnd, gwlExStyle)
            // WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TRANSPARENT | WS_EX_TOPMOST
            user32.SetWindowLong(hwnd, gwlExStyle, style or 0x08000000 or 0x80 or 0x20 or 0x8)
        }.onFailure { Log.write("overlay style: $it") }
    }

    fun setLevel(level: Float) {
        this.level = level
        panel.repaint()
    }

    fun setBusy(busy: Boolean) {
        this.busy = busy
        panel.repaint()
    }

    companion object {
        private fun pickFont(): Font {
            for (name in listOf("Segoe UI", "Tahoma", "Arial")) {
                val f = Font(name, Font.PLAIN, 18)
                if (f.family == name && f.canDisplayUpTo("گچپژی‌ ").let { it == -1 }) return f
            }
            return Font(Font.DIALOG, Font.PLAIN, 18)
        }
    }
}

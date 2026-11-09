package local.voice2text.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.HBITMAP
import com.sun.jna.platform.win32.WinDef.HICON
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.image.BufferedImage

/** Win32 calls for the native tray icon and menu that jna-platform doesn't declare (or declares awkwardly). */
interface Win32Extra : StdCallLibrary {
    fun CreatePopupMenu(): Pointer?
    fun AppendMenuW(menu: Pointer, flags: Int, idOrSubmenu: Pointer?, text: WString?): Boolean
    fun SetMenuDefaultItem(menu: Pointer, item: Int, byPosition: Int): Boolean
    fun TrackPopupMenuEx(menu: Pointer, flags: Int, x: Int, y: Int, hwnd: HWND, params: Pointer?): Int
    fun DestroyMenu(menu: Pointer): Boolean
    fun MessageBoxW(hwnd: HWND?, text: WString, caption: WString, type: Int): Int
    fun RegisterWindowMessageW(name: WString): Int
    fun CreateIconIndirect(info: WinGDI.ICONINFO): HICON?
    fun DestroyIcon(icon: HICON): Boolean
    fun GetDpiForSystem(): Int
    fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int

    companion object {
        val INSTANCE: Win32Extra = Native.load("user32", Win32Extra::class.java)
    }
}

/** Gdi32's CreateBitmap lives in gdi32, not user32. */
interface Gdi32Extra : StdCallLibrary {
    fun CreateBitmap(width: Int, height: Int, planes: Int, bitsPerPixel: Int, bits: Pointer?): HBITMAP?

    companion object {
        val INSTANCE: Gdi32Extra = Native.load("gdi32", Gdi32Extra::class.java)
    }
}

interface Shell32Extra : StdCallLibrary {
    fun Shell_NotifyIconW(message: Int, data: NotifyIconData): Boolean

    companion object {
        val INSTANCE: Shell32Extra = Native.load("shell32", Shell32Extra::class.java)
    }
}

@Structure.FieldOrder(
    "cbSize", "hWnd", "uID", "uFlags", "uCallbackMessage", "hIcon", "szTip", "dwState", "dwStateMask",
    "szInfo", "uVersion", "szInfoTitle", "dwInfoFlags", "guidItem", "hBalloonIcon",
)
class NotifyIconData : Structure() {
    @JvmField var cbSize = 0
    @JvmField var hWnd: HWND? = null
    @JvmField var uID = 0
    @JvmField var uFlags = 0
    @JvmField var uCallbackMessage = 0
    @JvmField var hIcon: HICON? = null
    @JvmField var szTip = CharArray(128)
    @JvmField var dwState = 0
    @JvmField var dwStateMask = 0
    @JvmField var szInfo = CharArray(256)
    @JvmField var uVersion = 0
    @JvmField var szInfoTitle = CharArray(64)
    @JvmField var dwInfoFlags = 0
    @JvmField var guidItem = Guid.GUID()
    @JvmField var hBalloonIcon: HICON? = null

    init {
        cbSize = size()
    }

    fun setText(field: CharArray, text: String) {
        field.fill('\u0000')
        text.take(field.size - 1).toCharArray().copyInto(field)
    }
}

/** One entry of the tray menu, built on the EDT and shown on [WinThread]. */
sealed class MenuEntry {
    data class Item(
        val id: Int, val text: String, val checked: Boolean = false,
        val enabled: Boolean = true, val isDefault: Boolean = false,
    ) : MenuEntry()
    data class Submenu(val text: String, val entries: List<MenuEntry>) : MenuEntry()
    data object Separator : MenuEntry()
}

/**
 * The notification-area icon and its menu, with the native Win32 calls (Shell_NotifyIcon,
 * TrackPopupMenuEx), so the menu looks and behaves like other Windows 11 tray menus: system font and
 * scaling and the Windows 11 menu style. (Java's AWT tray menu was a classic,
 * small, unthemed menu.) All Win32 calls happen on [WinThread].
 */
object Tray {
    const val EVENT_CONTEXTMENU = 0x007B
    const val EVENT_SELECT = 0x0400 // NIN_SELECT: left click
    const val EVENT_KEYSELECT = 0x0401
    private const val NIM_ADD = 0
    private const val NIM_MODIFY = 1
    private const val NIM_DELETE = 2
    private const val NIM_SETVERSION = 4
    private const val NIF_MESSAGE = 0x1
    private const val NIF_ICON = 0x2
    private const val NIF_TIP = 0x4
    private const val NIF_INFO = 0x10
    private const val NIF_SHOWTIP = 0x80
    private const val NOTIFYICON_VERSION_4 = 4
    const val INFO = 1
    const val WARNING = 2
    const val ERROR = 3

    private val extra = Win32Extra.INSTANCE
    private val shell = Shell32Extra.INSTANCE
    private var icon: HICON? = null
    private var tip = "Persian Dictation"
    private var added = false

    private fun data(flags: Int) = NotifyIconData().apply {
        hWnd = WinThread.hwnd
        uID = 1
        uFlags = flags
    }

    /** Adds the icon (again, after Explorer restarted). */
    fun add(image: BufferedImage, tooltip: String) = WinThread.post {
        replaceIcon(image)
        tip = tooltip
        val d = data(NIF_MESSAGE or NIF_ICON or NIF_TIP or NIF_SHOWTIP)
        d.uCallbackMessage = WinThread.WM_APP_TRAY
        d.hIcon = icon
        d.setText(d.szTip, tip)
        if (added) shell.Shell_NotifyIconW(NIM_DELETE, data(0))
        added = shell.Shell_NotifyIconW(NIM_ADD, d)
        val v = data(0)
        v.uVersion = NOTIFYICON_VERSION_4
        val versionSet = shell.Shell_NotifyIconW(NIM_SETVERSION, v)
        Log.write("tray icon added=$added version4=$versionSet icon=${icon != null} size=${iconSize()} cbSize=${d.cbSize}")
    }

    fun update(image: BufferedImage?, tooltip: String) = WinThread.post {
        if (!added) return@post
        if (image != null) replaceIcon(image)
        tip = tooltip
        val d = data(NIF_ICON or NIF_TIP or NIF_SHOWTIP)
        d.hIcon = icon
        d.setText(d.szTip, tip)
        shell.Shell_NotifyIconW(NIM_MODIFY, d)
    }

    /** A Windows notification from the tray icon. */
    fun notify(title: String, text: String, kind: Int = INFO) = WinThread.post {
        if (!added) return@post
        val d = data(NIF_INFO)
        d.setText(d.szInfoTitle, title)
        d.setText(d.szInfo, text)
        d.dwInfoFlags = kind
        shell.Shell_NotifyIconW(NIM_MODIFY, d)
    }

    fun remove() = WinThread.sync {
        if (added) shell.Shell_NotifyIconW(NIM_DELETE, data(0))
        added = false
    }

    /** Small-icon size for the system DPI (16 px at 100 %, 24 at 150 %, 32 at 200 %). */
    fun iconSize(): Int = runCatching { extra.GetSystemMetricsForDpi(49 /* SM_CXSMICON */, extra.GetDpiForSystem()) }
        .getOrDefault(16).coerceIn(16, 64)

    private fun replaceIcon(image: BufferedImage) {
        val new = createIcon(image) ?: run { Log.write("tray icon: CreateIconIndirect failed (${Kernel32.INSTANCE.GetLastError()})"); return }
        icon?.let { extra.DestroyIcon(it) }
        icon = new
    }

    /** HICON from a 32-bit ARGB image (alpha channel kept). */
    private fun createIcon(image: BufferedImage): HICON? {
        val w = image.width
        val h = image.height
        val bmi = WinGDI.BITMAPINFO()
        bmi.bmiHeader.biWidth = w
        bmi.bmiHeader.biHeight = -h // top-down
        bmi.bmiHeader.biPlanes = 1
        bmi.bmiHeader.biBitCount = 32
        bmi.bmiHeader.biCompression = WinGDI.BI_RGB
        val bits = PointerByReference()
        val color = GDI32.INSTANCE.CreateDIBSection(null, bmi, WinGDI.DIB_RGB_COLORS, bits, null, 0) ?: return null
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)
        bits.value.write(0, pixels, 0, pixels.size) // ARGB ints are BGRA bytes in memory
        val mask = Gdi32Extra.INSTANCE.CreateBitmap(w, h, 1, 1, null)
        val info = WinGDI.ICONINFO()
        info.fIcon = true
        info.hbmColor = color
        info.hbmMask = mask
        val result = extra.CreateIconIndirect(info)
        GDI32.INSTANCE.DeleteObject(color)
        if (mask != null) GDI32.INSTANCE.DeleteObject(mask)
        return result
    }

    /**
     * Shows the menu at (x, y) and returns the chosen item's id, or 0. Must run on [WinThread].
     * The window has to be in front while the menu is open, or the menu doesn't close when the user
     * clicks elsewhere (documented TrackPopupMenu behaviour).
     */
    fun showMenu(entries: List<MenuEntry>, x: Int, y: Int): Int {
        val menu = build(entries) ?: return 0
        try {
            val front = WinThread.user32.SetForegroundWindow(WinThread.hwnd)
            // TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON | TPM_BOTTOMALIGN, right-aligned like other tray menus
            val flags = 0x0100 or 0x0080 or 0x0002 or 0x0020 or 0x0008
            val opened = System.currentTimeMillis()
            val command = extra.TrackPopupMenuEx(menu, flags, x, y, WinThread.hwnd, null)
            val error = if (command == 0) Kernel32.INSTANCE.GetLastError() else 0
            Log.write("tray menu at $x,$y foreground=$front open ${System.currentTimeMillis() - opened} ms command=$command error=$error")
            WinThread.user32.PostMessage(WinThread.hwnd, 0 /* WM_NULL */, WPARAM(0), LPARAM(0))
            // Closed with Esc (or a command that opens no window): our invisible window would keep the
            // keyboard focus, and the next dictation would paste into nothing. Give it back.
            if (WinThread.user32.GetForegroundWindow() == WinThread.hwnd) WinThread.restoreForeground()
            return command
        } finally {
            extra.DestroyMenu(menu) // also destroys the submenus
        }
    }

    private fun build(entries: List<MenuEntry>): Pointer? {
        val menu = extra.CreatePopupMenu() ?: return null
        for (e in entries) {
            when (e) {
                MenuEntry.Separator -> extra.AppendMenuW(menu, 0x800 /* MF_SEPARATOR */, null, null)
                is MenuEntry.Item -> {
                    var flags = 0 // MF_STRING
                    if (e.checked) flags = flags or 0x8 // MF_CHECKED
                    if (!e.enabled) flags = flags or 0x1 // MF_GRAYED
                    extra.AppendMenuW(menu, flags, Pointer.createConstant(e.id.toLong()), WString(e.text))
                    if (e.isDefault) extra.SetMenuDefaultItem(menu, e.id, 0)
                }
                is MenuEntry.Submenu -> {
                    val sub = build(e.entries) ?: continue
                    extra.AppendMenuW(menu, 0x10 /* MF_POPUP */, sub, WString(e.text))
                }
            }
        }
        return menu
    }

    /** A native message box (in front of other windows); true for OK. Blocks the calling thread. */
    fun confirm(title: String, text: String): Boolean =
        extra.MessageBoxW(null, WString(text), WString(title), 0x1 or 0x30 or 0x10000 or 0x40000 /* OKCANCEL|WARNING|SETFOREGROUND|TOPMOST */) == 1
}

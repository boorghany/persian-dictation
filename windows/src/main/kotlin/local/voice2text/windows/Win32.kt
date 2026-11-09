package local.voice2text.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Clipboard calls that jna-platform's User32 doesn't declare. */
interface ClipboardApi : StdCallLibrary {
    fun OpenClipboard(hWnd: HWND?): Boolean
    fun CloseClipboard(): Boolean
    fun EmptyClipboard(): Boolean
    fun SetClipboardData(format: Int, hMem: Pointer?): Pointer?
    fun GetClipboardData(format: Int): Pointer?
    fun EnumClipboardFormats(format: Int): Int
    fun GetClipboardOwner(): HWND?
    fun RegisterClipboardFormatW(name: WString): Int

    companion object {
        val INSTANCE: ClipboardApi = Native.load("user32", ClipboardApi::class.java)
    }
}

interface GlobalMemory : StdCallLibrary {
    fun GlobalAlloc(flags: Int, bytes: BaseTSD.SIZE_T): Pointer?
    fun GlobalLock(hMem: Pointer): Pointer?
    fun GlobalUnlock(hMem: Pointer): Boolean
    fun GlobalSize(hMem: Pointer): BaseTSD.SIZE_T
    fun GlobalFree(hMem: Pointer): Pointer?

    companion object {
        val INSTANCE: GlobalMemory = Native.load("kernel32", GlobalMemory::class.java)
    }
}

/** A delayed action on [WinThread] that can be called off before it runs. */
class Delayed {
    @Volatile var cancelled = false
    fun cancel() { cancelled = true }
}

/**
 * One thread with a hidden message-only window and a message loop. Everything that talks to
 * Win32 state with thread affinity happens here: global hotkeys (RegisterHotKey delivers WM_HOTKEY
 * to the registering thread), the clipboard (we own it while a paste is pending and render the
 * text when the target app asks for it), and the foreground-window tracking.
 */
object WinThread {
    const val CLASS_NAME = "PersianDictationHiddenWindow"
    private const val WM_APP_TASKS = 0x8000
    /** Sent by a second launch with `--toggle`. */
    const val WM_APP_TOGGLE = 0x8001
    /** Sent by a second launch without arguments: the app is already running. */
    const val WM_APP_ALREADY_RUNNING = 0x8002
    /** Sent by `--quit` (the installer and uninstaller close a running copy this way). */
    const val WM_APP_QUIT = 0x8003
    private const val WM_HOTKEY = 0x0312
    private const val WM_COPYDATA = 0x004A
    /** WM_COPYDATA tag for `--paste-test <text>`. */
    const val COPYDATA_PASTE_TEST = 0x5044
    private const val WM_RENDERFORMAT = 0x0305
    private const val WM_RENDERALLFORMATS = 0x0306
    private const val WM_DESTROYCLIPBOARD = 0x0307
    private const val WM_SETTINGCHANGE = 0x001A
    /** Notification-area icon events (see [Tray]). */
    const val WM_APP_TRAY = 0x800A
    private var wmTaskbarCreated = 0
    const val MOD_ALT = 0x1
    const val MOD_CONTROL = 0x2
    private const val MOD_NOREPEAT = 0x4000
    private const val EVENT_SYSTEM_FOREGROUND = 0x0003
    private const val WINEVENT_SKIPOWNPROCESS = 0x0002

    val user32: User32 = User32.INSTANCE
    lateinit var hwnd: HWND
        private set

    /** Called on the EDT. */
    var onToggleRequest: (() -> Unit)? = null
    var onSecondLaunch: (() -> Unit)? = null
    var onQuitRequest: (() -> Unit)? = null
    /** Test hook: text from `--paste-test`, handled like a final from Google. */
    var onPasteTest: ((String) -> Unit)? = null
    /** A click on the tray icon (event, x, y in screen pixels). */
    var onTrayEvent: ((Int, Int, Int) -> Unit)? = null
    /** Explorer restarted (re-add the tray icon) or the light/dark theme changed. */
    var onTaskbarCreated: (() -> Unit)? = null
    var onThemeChanged: (() -> Unit)? = null

    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "win32-timer").apply { isDaemon = true } }
    private val hotkeys = HashMap<Int, () -> Unit>()
    private lateinit var thread: Thread
    // Kept in fields so the JNA callbacks aren't garbage collected.
    private lateinit var windowProc: WinUser.WindowProc
    private lateinit var winEventProc: WinUser.WinEventProc

    /** The last foreground window of another app, to give focus back after our tray menu took it. */
    @Volatile var lastAppWindow: HWND? = null
        private set

    fun start() {
        val ready = CountDownLatch(1)
        // Not a daemon: this thread's message loop is what keeps the app running. (AWT shuts down when it has
        // no windows, and the tray icon is native, so without it the JVM quietly exited after startup.)
        thread = Thread({ run(ready) }, "win32").apply { isDaemon = false; start() }
        ready.await()
    }

    private fun run(ready: CountDownLatch) {
        val instance = Kernel32.INSTANCE.GetModuleHandle(null)
        windowProc = WinUser.WindowProc { h, msg, w, l -> handle(h, msg, w, l) }
        val wc = WinUser.WNDCLASSEX()
        wc.hInstance = instance
        wc.lpfnWndProc = windowProc
        wc.lpszClassName = CLASS_NAME
        user32.RegisterClassEx(wc)
        // A hidden top-level window, never shown. Not message-only: the tray menu needs a window that can be
        // brought to the front, and only top-level windows get the TaskbarCreated and theme broadcasts.
        hwnd = user32.CreateWindowEx(0x80 /* WS_EX_TOOLWINDOW */, CLASS_NAME, "Persian Dictation",
            0x80000000.toInt() /* WS_POPUP */, 0, 0, 0, 0, null, null, instance, null)
        wmTaskbarCreated = Win32Extra.INSTANCE.RegisterWindowMessageW(WString("TaskbarCreated"))
        winEventProc = WinUser.WinEventProc { _, _, h, _, _, _, _ -> foregroundChanged(h) }
        user32.SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, null, winEventProc, 0, 0, WINEVENT_SKIPOWNPROCESS)
        lastAppWindow = user32.GetForegroundWindow()
        ready.countDown()
        val msg = WinUser.MSG()
        while (user32.GetMessage(msg, null, 0, 0) > 0) {
            user32.TranslateMessage(msg)
            user32.DispatchMessage(msg)
        }
    }

    private fun handle(h: HWND, msg: Int, w: WPARAM, l: LPARAM): LRESULT {
        try {
            when (msg) {
                WM_HOTKEY -> {
                    hotkeys[w.toInt()]?.let { action -> SwingUtilities.invokeLater { action() } }
                    return LRESULT(0)
                }
                WM_APP_TASKS -> {
                    while (true) (tasks.poll() ?: break).run()
                    return LRESULT(0)
                }
                WM_APP_TOGGLE -> {
                    SwingUtilities.invokeLater { onToggleRequest?.invoke() }
                    return LRESULT(0)
                }
                WM_APP_QUIT -> {
                    SwingUtilities.invokeLater { onQuitRequest?.invoke() }
                    return LRESULT(0)
                }
                WM_APP_ALREADY_RUNNING -> {
                    SwingUtilities.invokeLater { onSecondLaunch?.invoke() }
                    return LRESULT(0)
                }
                WM_COPYDATA -> {
                    val data = WinUser.COPYDATASTRUCT(Pointer(l.toLong()))
                    if (data.dwData.toLong() == COPYDATA_PASTE_TEST.toLong() && data.lpData != null) {
                        val text = String(data.lpData.getByteArray(0, data.cbData), Charsets.UTF_8)
                        SwingUtilities.invokeLater { onPasteTest?.invoke(text) }
                    }
                    return LRESULT(1)
                }
                WM_RENDERFORMAT -> {
                    Paster.renderFormat(w.toInt())
                    return LRESULT(0)
                }
                WM_RENDERALLFORMATS -> {
                    Paster.renderAll()
                    return LRESULT(0)
                }
                WM_DESTROYCLIPBOARD -> Paster.clipboardDestroyed()
                WM_APP_TRAY -> {
                    // NOTIFYICON_VERSION_4: event in LOWORD(lParam), anchor point in wParam.
                    val event = (l.toLong() and 0xFFFF).toInt()
                    val x = (w.toLong() and 0xFFFF).toShort().toInt()
                    val y = ((w.toLong() shr 16) and 0xFFFF).toShort().toInt()
                    SwingUtilities.invokeLater { onTrayEvent?.invoke(event, x, y) }
                    return LRESULT(0)
                }
                WM_SETTINGCHANGE -> {
                    val area = if (l.toLong() != 0L) runCatching { Pointer(l.toLong()).getWideString(0) }.getOrNull() else null
                    if (area == "ImmersiveColorSet") SwingUtilities.invokeLater { onThemeChanged?.invoke() }
                }
                else -> if (msg == wmTaskbarCreated && wmTaskbarCreated != 0) {
                    SwingUtilities.invokeLater { onTaskbarCreated?.invoke() }
                    return LRESULT(0)
                }
            }
        } catch (e: Throwable) {
            Log.write("win32 message $msg failed: $e")
        }
        return user32.DefWindowProc(h, msg, w, l)
    }

    val isCurrent: Boolean get() = Thread.currentThread() === thread

    fun post(block: () -> Unit) {
        tasks.add(Runnable {
            try { block() } catch (e: Throwable) { Log.write("win32 task failed: $e\n${e.stackTraceToString()}") }
        })
        user32.PostMessage(hwnd, WM_APP_TASKS, WPARAM(0), LPARAM(0))
    }

    fun after(millis: Long, block: () -> Unit): Delayed {
        val d = Delayed()
        timer.schedule({ post { if (!d.cancelled) block() } }, maxOf(0, millis), TimeUnit.MILLISECONDS)
        return d
    }

    fun <T> sync(block: () -> T): T {
        if (isCurrent) return block()
        val task = FutureTask(block)
        tasks.add(task)
        user32.PostMessage(hwnd, WM_APP_TASKS, WPARAM(0), LPARAM(0))
        return task.get()
    }

    /** Registers a global hotkey; [action] runs on the EDT. False if another app has it. */
    fun registerHotKey(id: Int, modifiers: Int, vk: Int, action: () -> Unit): Boolean = sync {
        val ok = user32.RegisterHotKey(hwnd, id, modifiers or MOD_NOREPEAT, vk)
        if (ok) hotkeys[id] = action
        ok
    }

    fun unregisterHotKey(id: Int) = sync {
        if (hotkeys.remove(id) != null) user32.UnregisterHotKey(hwnd.pointer, id)
    }

    // Foreground tracking

    private val ignoredClasses = setOf(
        "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "NotifyIconOverflowWindow",
        "TopLevelWindowForOverflowXamlIsland", "Windows.UI.Core.CoreWindow", "#32768", "XamlExplorerHostIslandWindow",
    )

    private fun foregroundChanged(h: HWND?) {
        if (h == null) return
        val buf = CharArray(256)
        val n = user32.GetClassName(h, buf, buf.size)
        if (String(buf, 0, maxOf(0, n)) in ignoredClasses) return
        lastAppWindow = h
    }

    /** Window class of the foreground window, for the dev-mode log ("paste into …"). */
    fun foregroundClass(): String {
        val h = user32.GetForegroundWindow() ?: return "none"
        val buf = CharArray(256)
        return String(buf, 0, maxOf(0, user32.GetClassName(h, buf, buf.size)))
    }

    /** After our tray menu: give the keyboard focus back to the app the user was in. */
    fun restoreForeground() = sync {
        val target = lastAppWindow ?: return@sync
        if (user32.IsWindow(target) && user32.GetForegroundWindow() != target) {
            user32.SetForegroundWindow(target)
        }
    }

    // Keyboard

    private const val VK_SHIFT = 0x10
    private const val VK_CONTROL = 0x11
    private const val VK_MENU = 0x12
    private const val VK_LWIN = 0x5B
    private const val VK_RWIN = 0x5C
    const val VK_RETURN = 0x0D
    const val VK_ESCAPE = 0x1B
    const val VK_SPACE = 0x20
    private const val VK_V = 0x56

    fun modifiersHeld(): Boolean =
        listOf(VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN).any { user32.GetAsyncKeyState(it).toInt() and 0x8000 != 0 }

    /** Synthetic key presses; [keys] are pressed in order and released in reverse. */
    fun sendKeys(vararg keys: Int) {
        val inputs = WinUser.INPUT().toArray(keys.size * 2).map { it as WinUser.INPUT }
        val order = keys.map { it to false } + keys.reversed().map { it to true }
        order.forEachIndexed { i, (vk, up) ->
            val input = inputs[i]
            input.type = com.sun.jna.platform.win32.WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
            input.input.setType("ki")
            input.input.ki.wVk = com.sun.jna.platform.win32.WinDef.WORD(vk.toLong())
            input.input.ki.wScan = com.sun.jna.platform.win32.WinDef.WORD(0)
            input.input.ki.dwFlags = com.sun.jna.platform.win32.WinDef.DWORD(if (up) 2L else 0L) // KEYEVENTF_KEYUP
            input.input.ki.time = com.sun.jna.platform.win32.WinDef.DWORD(0)
            input.write()
        }
        val sent = user32.SendInput(com.sun.jna.platform.win32.WinDef.DWORD(inputs.size.toLong()), inputs.toTypedArray(), inputs[0].size())
        if (sent.toInt() != inputs.size) Log.write("SendInput sent ${sent.toInt()} of ${inputs.size} events (error ${Kernel32.INSTANCE.GetLastError()})")
    }

    fun sendCtrlV() = sendKeys(VK_CONTROL, VK_V)
    fun sendReturn() = sendKeys(VK_RETURN)
}

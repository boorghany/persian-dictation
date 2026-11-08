package local.voice2text.windows

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.BaseTSD

/**
 * Inserts text into the focused app by pasting it (clipboard + synthetic Ctrl+V), then restores the
 * user's clipboard. Port of the Mac `TextInserter`; all state lives on [WinThread].
 *
 * Pasting rather than typing: reliable for Persian (ZWNJ, combining marks) in every kind of app.
 *
 * The text is put on the clipboard with **delayed rendering**: Windows asks us for it
 * (WM_RENDERFORMAT) when the target app reads it, so we know when the paste really happened, like
 * the lazy data provider on the Mac. That decides when the user's clipboard comes back and when a
 * pending Enter may be pressed. The entry is marked to stay out of clipboard history (Win+V) and the
 * cloud clipboard.
 */
object Paster {
    private const val CF_UNICODETEXT = 13
    private const val GMEM_MOVEABLE = 0x0002

    /** Restore no earlier than this after Ctrl+V, and this long after the target app read the text. */
    private const val MINIMUM_HOLD = 1500L
    private const val AFTER_READ = 1000L
    /** Nobody read it (the paste didn't happen): restore anyway. */
    private const val MAXIMUM_HOLD = 5000L
    /** After the app read the paste, give it this long to insert the text before pressing Enter. */
    private const val INSERT_DELAY = 200L
    /** How long after posting Return before `didPress` runs (our Enter hotkey is registered again then). */
    private const val RETURN_DELIVERY = 250L

    private val clip = ClipboardApi.INSTANCE
    private val mem = GlobalMemory.INSTANCE
    private val excludeFormats = listOf(
        "ExcludeClipboardContentFromMonitorProcessing",
        "CanIncludeInClipboardHistory",
        "CanUploadToCloudClipboard",
    ).map { clip.RegisterClipboardFormatW(WString(it)) }

    /** The user's clipboard, saved before our first paste and not yet restored (kept across back-to-back pastes). */
    private var saved: List<Pair<Int, ByteArray>>? = null
    /** The text we offered with delayed rendering, until it's rendered or replaced. */
    private var pendingText: String? = null
    private var sentAt = 0L
    private var unread = false
    private val readWaiters = mutableListOf<() -> Unit>()
    private var restore: Delayed? = null
    /** Our own EmptyClipboard calls also send us WM_DESTROYCLIPBOARD; those aren't the user copying. */
    private var emptyingOurselves = false

    /** A Return is waiting for the last paste to be read: later pastes and Returns wait for it, in order. */
    private var returnPending = false
    private val afterReturn = ArrayDeque<() -> Unit>()
    private var returnDidPress: (() -> Unit)? = null

    fun insert(text: String) {
        if (text.isEmpty()) return
        WinThread.post { insertNow(text) }
    }

    private fun insertNow(text: String) {
        if (returnPending) {
            afterReturn.addLast { insertNow(text) }
            return
        }
        restore?.cancel()
        if (saved == null) saved = snapshot()
        if (!open()) {
            Log.write("paste failed: the clipboard is busy")
            return
        }
        emptyingOurselves = true
        clip.EmptyClipboard()
        emptyingOurselves = false
        clip.SetClipboardData(CF_UNICODETEXT, null) // delayed: rendered when someone reads it
        for (format in excludeFormats) clip.SetClipboardData(format, global(ByteArray(4)))
        clip.CloseClipboard()
        pendingText = text
        unread = true

        waitForModifiers()
        sentAt = System.currentTimeMillis()
        Log.verbose { "paste into ${WinThread.foregroundClass()}" }
        WinThread.sendCtrlV()
        scheduleRestore(sentAt + MAXIMUM_HOLD, unread = true)
    }

    /** Ctrl+V while the user still holds Alt or Shift (e.g. from Ctrl+Alt+Space) would be another shortcut. */
    private fun waitForModifiers() {
        val deadline = System.currentTimeMillis() + 1500
        var waited = false
        while (WinThread.modifiersHeld() && System.currentTimeMillis() < deadline) {
            waited = true
            Thread.sleep(15)
        }
        if (waited) Log.verbose { "waited for modifier keys to be released" + if (WinThread.modifiersHeld()) " (still held)" else "" }
    }

    /** WM_RENDERFORMAT: the target app reads the paste. */
    fun renderFormat(format: Int) {
        val text = pendingText ?: return
        if (format != CF_UNICODETEXT) return
        clip.SetClipboardData(CF_UNICODETEXT, global((text + "\u0000").toByteArray(Charsets.UTF_16LE)))
        pendingText = null
        onRead(System.currentTimeMillis())
    }

    /** WM_RENDERALLFORMATS: we're quitting while still owning a delayed format. */
    fun renderAll() {
        if (pendingText == null || !clip.OpenClipboard(WinThread.hwnd)) return
        if (clip.GetClipboardOwner() == WinThread.hwnd) renderFormat(CF_UNICODETEXT)
        clip.CloseClipboard()
    }

    /** WM_DESTROYCLIPBOARD: another app emptied the clipboard (the user copied something): don't restore over it. */
    fun clipboardDestroyed() {
        if (emptyingOurselves) return
        pendingText = null
        if (saved != null) Log.verbose { "clipboard taken over by another app; not restoring" }
        saved = null
    }

    private fun onRead(at: Long) {
        Log.verbose { "paste read after ${at - sentAt} ms" }
        unread = false
        val waiters = readWaiters.toList()
        readWaiters.clear()
        if (waiters.isNotEmpty()) WinThread.after(INSERT_DELAY) { waiters.forEach { it() } }
        scheduleRestore(maxOf(sentAt + MINIMUM_HOLD, at + AFTER_READ), unread = false)
    }

    private fun scheduleRestore(at: Long, unread: Boolean) {
        restore?.cancel()
        restore = WinThread.after(at - System.currentTimeMillis()) { restoreNow(unread) }
    }

    private fun restoreNow(wasUnread: Boolean) {
        restore = null
        if (wasUnread) {
            Log.verbose { "paste never read; restoring the clipboard" }
            // The paste didn't happen, so don't press Enter: it would send the message without it.
            if (readWaiters.isNotEmpty()) Log.write("paste never read; not pressing Enter")
            readWaiters.clear()
            unread = false
            if (returnPending) WinThread.post { returnDone() }
        }
        val formats = saved
        saved = null
        pendingText = null
        // Don't clobber something the user copied in the meantime.
        if (formats == null || clip.GetClipboardOwner() != WinThread.hwnd) return
        if (!open()) return
        emptyingOurselves = true
        clip.EmptyClipboard()
        emptyingOurselves = false
        for ((format, data) in formats) {
            val h = global(data)
            if (clip.SetClipboardData(format, h) == null) mem.GlobalFree(h)
        }
        clip.CloseClipboard()
    }

    /**
     * Press Return in the focused app once it has inserted the last paste. `willPress` runs right
     * before the key is sent, `didPress` a moment after it was delivered (also when it isn't pressed
     * because the paste was never read). Callbacks run on [WinThread].
     */
    fun pressReturnAfterPaste(willPress: (() -> Unit)? = null, didPress: (() -> Unit)? = null) {
        WinThread.post { pressReturnNow(willPress, didPress) }
    }

    private fun pressReturnNow(willPress: (() -> Unit)?, didPress: (() -> Unit)?) {
        if (returnPending) {
            afterReturn.addLast { pressReturnNow(willPress, didPress) }
            return
        }
        returnPending = true
        returnDidPress = didPress
        val press = {
            willPress?.invoke()
            Log.write("pressing Enter after the last paste")
            waitForModifiers()
            WinThread.sendReturn()
            WinThread.after(RETURN_DELIVERY) { returnDone() }
            Unit
        }
        if (unread) readWaiters.add(press) else WinThread.after(INSERT_DELAY) { press() }
    }

    /** The Return was delivered (or dropped): paste what waited for it. */
    private fun returnDone() {
        if (!returnPending) return
        returnPending = false
        val done = returnDidPress
        returnDidPress = null
        done?.invoke()
        // Stops at the next Return, which queues the rest again until it is through.
        while (!returnPending && afterReturn.isNotEmpty()) afterReturn.removeFirst()()
    }

    // Clipboard helpers

    private fun open(): Boolean {
        repeat(10) {
            if (clip.OpenClipboard(WinThread.hwnd)) return true
            Thread.sleep(20)
        }
        return false
    }

    /** GDI handles and private formats aren't memory blocks; Windows synthesizes the common ones anyway. */
    private fun isMemoryFormat(format: Int) = when (format) {
        2, 3, 9, 14, 0x80, 0x82, 0x83, 0x8E -> false // BITMAP, METAFILEPICT, PALETTE, ENHMETAFILE, OWNERDISPLAY, DSP*
        in 0x200..0x3FF -> false // PRIVATEFIRST..GDIOBJLAST
        else -> true
    }

    private fun snapshot(): List<Pair<Int, ByteArray>> {
        if (!open()) return emptyList()
        val result = mutableListOf<Pair<Int, ByteArray>>()
        try {
            var format = clip.EnumClipboardFormats(0)
            while (format != 0) {
                if (isMemoryFormat(format)) {
                    val h = clip.GetClipboardData(format)
                    if (h != null) {
                        val size = mem.GlobalSize(h).toLong()
                        val p = mem.GlobalLock(h)
                        if (p != null && size in 1..200_000_000) {
                            result += format to p.getByteArray(0, size.toInt())
                        }
                        if (p != null) mem.GlobalUnlock(h)
                    }
                }
                format = clip.EnumClipboardFormats(format)
            }
        } finally {
            clip.CloseClipboard()
        }
        return result
    }

    private fun global(data: ByteArray): Pointer {
        val h = mem.GlobalAlloc(GMEM_MOVEABLE, BaseTSD.SIZE_T(maxOf(1, data.size).toLong()))
            ?: throw OutOfMemoryError("GlobalAlloc")
        val p = mem.GlobalLock(h)!!
        if (data.isNotEmpty()) p.write(0, data, 0, data.size)
        mem.GlobalUnlock(h)
        return h
    }
}

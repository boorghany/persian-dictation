package local.voice2text.windows

import java.io.IOException
import javax.swing.SwingUtilities
import javax.swing.Timer
import local.voice2text.core.ContinuousRecognizer
import local.voice2text.core.Corrections
import local.voice2text.core.SpokenPunctuation

/**
 * One dictation run: microphone → streaming recognizer → paste into the focused app.
 * Port of the Mac `DictationController`; all state lives on the EDT.
 */
class DictationController {
    enum class State { IDLE, LISTENING, FINISHING }

    var onStateChange: ((State) -> Unit)? = null
    /**
     * true right before a spoken "اینتر" presses Return in the target app, false once it is through:
     * the Enter hotkey (stop dictation) must let that Return pass. Called on [WinThread], right
     * before and after the key is sent, so it must not wait for the EDT.
     */
    var onReturnPassThrough: ((Boolean) -> Unit)? = null

    @Volatile var state = State.IDLE
        private set(value) {
            field = value
            onStateChange?.invoke(value)
        }

    private val overlay = Overlay()
    private var mic: MicCapture? = null
    private var recognizer: ContinuousRecognizer? = null
    private var insertedAny = false
    private var lastInsertAttachesToNext = false
    /**
     * Line breaks that ended the last final, not pasted yet: many editors (web text boxes, Quill)
     * drop or hide a line break at the very end of pasted text. Pasted at the start of the next
     * final instead, or when dictation ends.
     */
    private var pendingBreaks = ""
    /** Enter was pressed again while finishing: press it in the target app once the last words are pasted. */
    private var pressEnterWhenDone = false
    private var finishTimeout: Timer? = null
    private var dictationStart = 0L
    private var gotFirstAudio = false

    private val language get() = Settings.language

    fun microphonePreferenceChanged() {
        mic?.preferenceChanged()
    }

    fun toggle() {
        when (state) {
            State.IDLE -> start()
            State.LISTENING -> stop("hotkey or menu")
            State.FINISHING -> {}
        }
    }

    /** Esc or Enter while dictating. */
    fun stopKeyPressed(enter: Boolean) {
        when {
            state == State.LISTENING -> stop(if (enter) "Enter" else "Esc")
            state == State.FINISHING && enter && !pressEnterWhenDone -> {
                Log.write("Enter while finishing: pressing it after the last paste")
                pressEnterWhenDone = true
            }
        }
    }

    // Start / stop

    private fun start() {
        insertedAny = false
        lastInsertAttachesToNext = false
        pendingBreaks = ""
        pressEnterWhenDone = false
        dictationStart = System.nanoTime()
        gotFirstAudio = false
        overlay.rightToLeft = language.startsWith("fa")
        val mics = MicCapture.microphones().joinToString(", ") { it.name }
        Log.write("dictation start choice=${Settings.microphone ?: "system default"} available=[$mics]")
        startRecognizer()

        val capture = MicCapture()
        capture.onAudio = { pcm, level ->
            recognizer?.send(pcm) // thread-safe
            SwingUtilities.invokeLater {
                if (!gotFirstAudio && state == State.LISTENING) {
                    gotFirstAudio = true
                    Log.write("first audio after ${(System.nanoTime() - dictationStart) / 1_000_000} ms")
                }
                overlay.setLevel(level)
            }
        }
        capture.onDeviceChange = { name, reason ->
            SwingUtilities.invokeLater {
                // Shown until the next interim result replaces it.
                if (state == State.LISTENING) overlay.show("🎤 $name — $reason")
            }
        }
        try {
            capture.start()
        } catch (e: Exception) {
            Log.write("microphone failed: $e")
            recognizer?.cancel()
            recognizer = null
            flash("Could not start the microphone: ${e.message ?: e}")
            return
        }
        mic = capture
        overlay.setBusy(false)
        overlay.show(placeholder)
        state = State.LISTENING
    }

    private fun stop(key: String) {
        Log.write("dictation stop ($key)")
        mic?.stop()
        mic = null
        state = State.FINISHING
        overlay.setBusy(true)
        recognizer?.finish()
        // If the server never closes the stream, give up after a few seconds.
        finishTimeout = Timer(6000) {
            if (state == State.FINISHING) {
                recognizer?.cancel()
                completeFinish()
            }
        }.apply { isRepeats = false; start() }
    }

    private fun completeFinish() {
        pastePendingBreaks()
        finishTimeout?.stop()
        finishTimeout = null
        recognizer = null
        overlay.hide()
        state = State.IDLE // unregisters our Enter hotkey before we press Enter below
        if (pressEnterWhenDone) {
            pressEnterWhenDone = false
            Paster.pressReturnAfterPaste()
        }
    }

    /** Quitting: stop without waiting for Google. */
    fun shutdown() {
        mic?.stop()
        recognizer?.cancel()
        overlay.hide()
    }

    // Streaming

    private fun startRecognizer() {
        val r = ContinuousRecognizer(Settings.apiKey, language)
        r.verbose = Settings.developmentMode
        r.log = { Log.write(it) }
        if (Settings.developmentMode) r.recordingDir = AppPaths.recordings.apply { mkdirs() }
        r.onInterim = { text ->
            SwingUtilities.invokeLater {
                if (recognizer === r && state != State.IDLE) {
                    val preview = SpokenPunctuation.apply(Corrections.apply(text, language), language)
                    overlay.show(
                        if (preview.isEmpty()) placeholder
                        else (pendingBreaks + preview).replace("\n", " ↵ ").replace(SpokenPunctuation.ENTER.toString(), " ⏎ "),
                    )
                }
            }
        }
        r.onFinal = { text ->
            SwingUtilities.invokeLater {
                if (recognizer === r) {
                    insert(text)
                    // It's pasted now: don't keep showing it until the next session's first interim.
                    if (state == State.LISTENING) overlay.show(placeholder)
                }
            }
        }
        r.onEnd = { error ->
            SwingUtilities.invokeLater {
                if (recognizer === r) {
                    when (state) {
                        State.LISTENING -> {
                            // The connection kept failing.
                            pastePendingBreaks()
                            mic?.stop()
                            mic = null
                            recognizer = null
                            state = State.IDLE
                            if (error != null) flash(describe(error)) else overlay.hide()
                        }
                        State.FINISHING -> {
                            completeFinish()
                            if (error != null) flash(describe(error))
                        }
                        State.IDLE -> {}
                    }
                }
            }
        }
        recognizer = r
        r.start()
    }

    /** Test hook (`--paste-test`): handle [text] like a final from Google, also when not dictating. */
    fun testFinal(text: String) {
        Log.write("paste test (${text.length} chars)")
        if (state == State.IDLE) {
            // Also shown in the overlay, to check the right-to-left rendering without a network.
            overlay.rightToLeft = language.startsWith("fa")
            overlay.setBusy(false)
            overlay.show(SpokenPunctuation.apply(Corrections.apply(text, language), language).replace("\n", " ↵ "))
            Timer(3000) { if (state == State.IDLE) overlay.hide() }.apply { isRepeats = false; start() }
        }
        insert(text)
    }

    private fun insert(raw: String) {
        val text = SpokenPunctuation.apply(Corrections.apply(raw, language), language)
        // "اینتر": paste what came before it, press Return (send in chat apps), then go on.
        text.split(SpokenPunctuation.ENTER).forEachIndexed { i, part ->
            if (i > 0) pressReturn()
            insertPart(part)
        }
    }

    private fun insertPart(text: String) {
        if (text.isEmpty()) return
        var body = text
        var breaks = ""
        while (body.endsWith("\n")) {
            body = body.dropLast(1)
            breaks += "\n"
        }
        if (body.isEmpty()) {
            pendingBreaks += breaks
            return
        }
        val paste = pendingBreaks + body
        val needsSpace = insertedAny && !lastInsertAttachesToNext && !SpokenPunctuation.attachesToPrevious(paste)
        // Windows editors expect CRLF; most accept LF, but Notepad-style controls want CRLF.
        Paster.insert(toWindowsLines(if (needsSpace) " $paste" else paste))
        insertedAny = true
        lastInsertAttachesToNext = SpokenPunctuation.attachesToNext(body)
        pendingBreaks = breaks
    }

    /** A spoken Enter. Held-back line breaks are dropped (Return makes its own); the next text starts without a space. */
    private fun pressReturn() {
        Log.write("spoken Enter")
        pendingBreaks = ""
        insertedAny = false
        lastInsertAttachesToNext = false
        Paster.pressReturnAfterPaste(
            willPress = { onReturnPassThrough?.invoke(true) },
            didPress = { onReturnPassThrough?.invoke(false) },
        )
    }

    /** Dictation ended right after a line-break command: paste it as is. */
    private fun pastePendingBreaks() {
        if (pendingBreaks.isEmpty()) return
        Paster.insert(toWindowsLines(pendingBreaks))
        insertedAny = true
        lastInsertAttachesToNext = true
        pendingBreaks = ""
    }

    private fun toWindowsLines(s: String) = s.replace("\n", "\r\n")

    // Messages

    private val placeholder get() = if (language.startsWith("fa")) "در حال گوش دادن…" else "Listening…"

    private fun describe(error: Throwable): String =
        if (error is IOException) "Can't reach Google's speech server (${error.message ?: error.javaClass.simpleName}). Check your connection or VPN."
        else error.message ?: error.toString()

    /** Show a message in the overlay for a few seconds. */
    fun flash(message: String) {
        overlay.setBusy(true)
        overlay.rightToLeft = false // messages are in English
        overlay.show(message)
        Timer(4000) { if (state == State.IDLE) overlay.hide() }.apply { isRepeats = false; start() }
    }
}

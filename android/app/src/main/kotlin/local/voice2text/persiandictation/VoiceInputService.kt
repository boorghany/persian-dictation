package local.voice2text.persiandictation

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import local.voice2text.core.ContinuousRecognizer
import local.voice2text.core.Corrections
import local.voice2text.core.SpeechStream
import local.voice2text.core.SpokenPunctuation

/**
 * A voice-only keyboard used next to Gboard. It starts listening as soon as it is shown, writes the
 * hypothesis in progress into the text field as composing (underlined) text, commits each finalized
 * utterance, and switches back to the previous keyboard (Gboard) when the user is done.
 */
class VoiceInputService : InputMethodService() {
    private enum class State { IDLE, LISTENING, FINISHING }

    private val main = Handler(Looper.getMainLooper())
    /** The latest interim hypothesis (raw), kept so a pause can type it in without waiting. */
    private var lastInterim = ""
    private var state = State.IDLE
    private var recognizer: ContinuousRecognizer? = null
    private var mic: MicCapture? = null
    /** Runs once the last result is committed after stopping (e.g. press Enter, return to Gboard). */
    private var afterFinish: (() -> Unit)? = null
    private var finishTimeout: Runnable? = null
    /** Whether a space goes before the utterance being composed; decided when composing starts. */
    private var composingSpace: Boolean? = null

    private lateinit var status: TextView
    private lateinit var micButton: TextView
    private lateinit var micCircle: GradientDrawable
    private var dark = false

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.write("ime created version=\"${versionDisplay(this)}\"")
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val fg = if (dark) Color.rgb(232, 234, 237) else Color.rgb(32, 33, 36)
        val muted = if (dark) Color.rgb(154, 160, 166) else Color.rgb(95, 99, 104)
        val keyBg = if (dark) Color.rgb(60, 64, 67) else Color.WHITE

        status = TextView(this).apply {
            textSize = 17f
            setTextColor(fg)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(px(12), px(10), px(12), px(6))
        }
        micCircle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        micButton = TextView(this).apply {
            text = "🎤"
            textSize = 34f
            gravity = Gravity.CENTER
            background = micCircle
            setOnClickListener { tap(it); toggleListening() }
        }
        fun key(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = GradientDrawable().apply { setColor(keyBg); cornerRadius = px(8).toFloat() }
            setOnClickListener { tap(it); action() }
            layoutParams = LinearLayout.LayoutParams(0, px(48), 1f).apply { setMargins(px(4), 0, px(4), 0) }
        }
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), px(8), px(4), px(4))
            addView(key("⌨") { switchBack(waitForLastResult = false) })
            addView(key("⌫") { backspace() }.apply { repeatWhileHeld(this) { backspace() } })
            addView(key("⏎") { done(pressEnter = true) })
            addView(key("✓") { done(pressEnter = false) })
        }
        val version = TextView(this).apply {
            text = "Persian Dictation ${versionDisplay(this@VoiceInputService)}"
            textSize = 10f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) Color.rgb(32, 33, 36) else Color.rgb(232, 234, 237))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(64)))
            addView(micButton, LinearLayout.LayoutParams(px(88), px(88)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(keys, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(version)
            // Keep the keys above the navigation bar / gesture handle.
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                v.setPadding(0, 0, 0, bottom + px(4))
                insets
            }
        }.also { render() }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (state == State.IDLE) startListening()
    }

    /** The keyboard is hidden or the user moved to another field: keep what was shown and stop. */
    override fun onFinishInputView(finishingInput: Boolean) {
        if (state != State.IDLE) {
            AppLog.write("input view closed while ${state.name.lowercase()}; keeping composing text")
            currentInputConnection?.finishComposingText()
            stopEverything()
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // Listening

    private fun toggleListening() = when (state) {
        State.IDLE -> startListening()
        State.LISTENING -> stopListening(then = null)
        State.FINISHING -> {}
    }

    private fun startListening() {
        if (!hasMicPermission(this)) {
            showStatus("Microphone permission needed: tap here to open setup")
            if (::status.isInitialized) status.setOnClickListener {
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        if (::status.isInitialized) status.setOnClickListener(null)
        val dev = Settings.developmentMode(this)
        val r = ContinuousRecognizer(SpeechStream.DEFAULT_API_KEY, Settings.LANGUAGE).apply {
            log = AppLog::write
            verbose = dev
            recordingDir = if (dev) AppLog.recordingsDir(this@VoiceInputService).also { local.voice2text.core.WavRecorder.pruneOld(it) } else null
            onInterim = { text -> main.post { showInterim(text) } }
            onFinal = { text -> main.post { commitFinal(text) } }
            onEnd = { error -> main.post { recognizerEnded(error) } }
        }
        val testInput = if (dev) AppLog.testInputFile(this).takeIf { it.exists() } else null
        if (testInput != null) AppLog.write("using test input ${testInput.name} instead of the microphone")
        val capture = MicCapture(testInput) { pcm, level ->
            r.send(pcm)
            main.post { showLevel(level) }
        }
        try {
            capture.start()
        } catch (e: Exception) {
            AppLog.write("mic start failed: $e")
            showStatus("Could not start the microphone")
            return
        }
        AppLog.write("dictation start field=${currentInputEditorInfo?.packageName}")
        recognizer = r
        mic = capture
        composingSpace = null
        r.start()
        state = State.LISTENING
        render()
    }

    /** Stop the microphone and let the recognizer deliver the last result, then run [then]. */
    private fun stopListening(then: (() -> Unit)?) {
        if (state != State.LISTENING) { then?.invoke(); return }
        state = State.FINISHING
        afterFinish = then
        mic?.stop()
        mic = null
        recognizer?.finish()
        render()
        val timeout = Runnable {
            if (state == State.FINISHING) {
                AppLog.write("finish timed out")
                recognizer?.cancel()
                recognizerEnded(null)
            }
        }
        finishTimeout = timeout
        main.postDelayed(timeout, 6_000)
    }

    private fun recognizerEnded(error: Throwable?) {
        finishTimeout?.let { main.removeCallbacks(it) }
        finishTimeout = null
        mic?.stop()
        mic = null
        recognizer = null
        val wasListening = state == State.LISTENING
        state = State.IDLE
        currentInputConnection?.finishComposingText()
        composingSpace = null
        render()
        if (error != null) {
            showStatus(if (wasListening) "Connection problem: ${error.message}. Check internet/VPN, then tap the mic." else "Stopped: ${error.message}")
        }
        val next = afterFinish
        afterFinish = null
        next?.invoke()
    }

    /** Pause immediately: keep the text shown so far (no waiting for Google's final result). */
    private fun pauseNow() {
        val pending = lastInterim
        val space = composingSpace // decided before the composing text was shown; keep it
        stopEverything()
        composingSpace = space
        if (pending.isNotBlank()) commitFinal(pending) else { currentInputConnection?.finishComposingText(); composingSpace = null }
        lastInterim = ""
        render()
        AppLog.write("paused by ⌫" + if (pending.isNotBlank()) " (kept: $pending)" else "")
    }

    /** Hold to repeat, like a keyboard's delete key: once on touch, then every 50 ms after 400 ms. */
    private fun repeatWhileHeld(view: View, action: () -> Unit) {
        val repeat = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 50) }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { tap(v); action(); main.postDelayed(repeat, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(repeat)
            }
            true
        }
    }

    private fun stopEverything() {
        finishTimeout?.let { main.removeCallbacks(it) }
        finishTimeout = null
        afterFinish = null
        mic?.stop()
        mic = null
        recognizer?.cancel()
        recognizer = null
        composingSpace = null
        state = State.IDLE
    }

    // Text

    private fun showInterim(raw: String) {
        if (state == State.IDLE) return
        lastInterim = raw
        val text = SpokenPunctuation.apply(Corrections.apply(raw, Settings.LANGUAGE), Settings.LANGUAGE).replace('\n', ' ')
        showStatus(text.ifEmpty { "در حال گوش دادن…" })
        val ic = currentInputConnection ?: return
        if (text.isEmpty()) {
            if (composingSpace != null) ic.setComposingText("", 1)
            return
        }
        val space = composingSpace ?: needsSpaceBefore().also { composingSpace = it }
        ic.setComposingText(if (space && !SpokenPunctuation.attachesToPrevious(text)) " $text" else text, 1)
    }

    private fun commitFinal(raw: String) {
        lastInterim = "" // this utterance is done; a pause must not type it again
        val text = SpokenPunctuation.apply(Corrections.apply(raw, Settings.LANGUAGE), Settings.LANGUAGE)
        val ic = currentInputConnection ?: return
        if (text.isEmpty()) {
            ic.commitText("", 1) // removes any composing text
        } else {
            val space = composingSpace ?: needsSpaceBefore()
            ic.commitText(if (space && !SpokenPunctuation.attachesToPrevious(text)) " $text" else text, 1)
        }
        composingSpace = null
        if (state != State.IDLE) showStatus(if (state == State.LISTENING) "در حال گوش دادن…" else "…")
    }

    /** Whether the committed text right before the cursor needs a separating space. */
    private fun needsSpaceBefore(): Boolean {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)
        return !before.isNullOrEmpty() && !before.last().isWhitespace() && before.last() != '('
    }

    /**
     * ⌫ always deletes. While listening, or while waiting for the last result after a pause, it first
     * pauses: the text shown so far is typed in as it is and the recognizer is stopped, so the deletion
     * isn't undone by a later result. (Before, ⌫ was ignored while there was composing text, i.e. for up
     * to 6 s after pausing on a slow connection.)
     */
    private fun backspace() {
        if (afterFinish != null) return // ✓ / ⏎ / ⌨ is already finishing and leaving
        if (state != State.IDLE) pauseNow()
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    // Leaving

    private fun done(pressEnter: Boolean) = stopListening {
        if (pressEnter) pressEnterKey()
        switchBack(waitForLastResult = false)
    }

    private fun pressEnterKey() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun switchBack(waitForLastResult: Boolean) {
        if (waitForLastResult) { stopListening { switchBack(false) }; return }
        currentInputConnection?.finishComposingText()
        stopEverything()
        if (!switchToPreviousInputMethod()) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    // UI

    private fun render() {
        if (!::micCircle.isInitialized) return
        when (state) {
            State.LISTENING -> { micCircle.setColor(Color.rgb(217, 48, 37)); showStatus("در حال گوش دادن…") }
            State.FINISHING -> { micCircle.setColor(Color.rgb(242, 153, 0)); showStatus("…") }
            State.IDLE -> { micCircle.setColor(if (dark) Color.rgb(95, 99, 104) else Color.rgb(189, 193, 198)); micButton.alpha = 1f; showStatus("Paused — ⌫ to delete, 🎤 to resume") }
        }
    }

    private fun showStatus(text: String) {
        if (::status.isInitialized) status.text = text
    }

    private fun showLevel(level: Double) {
        if (state == State.LISTENING) micButton.alpha = (0.55 + minOf(1.0, level / 1500) * 0.45).toFloat()
    }

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

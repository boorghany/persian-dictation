package local.voice2text.persiandictation

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import local.voice2text.core.ContinuousRecognizer
import local.voice2text.core.Corrections
import local.voice2text.core.SpeechStream
import local.voice2text.core.SpokenPunctuation

/**
 * A voice-only keyboard used next to Gboard. It opens paused; 🎤 starts listening. The hypothesis in
 * progress goes into the text field as composing (underlined) text and each finalized utterance is
 * committed. ⌫ deletes and ◀ ▶ move by whole words; ⌨ switches back to the previous keyboard (Gboard);
 * the submit key does the field's action (send, search…) like Gboard's. A toolbar above, as in Gboard:
 * emoji picker and a paste chip showing the start of the clipboard text. Keys don't stop dictation: only the
 * user's own pause does (and it opens paused). Monochrome Material icons tinted
 * for the light/dark theme. Number, phone, date and password fields go straight back to the previous keyboard.
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
    /** The submit key was pressed while listening: submit once the utterance on screen is final (or after a few seconds). */
    private var submitWhenFinal: Runnable? = null
    /** The text around the cursor where the utterance is being composed; set when composing starts. */
    private var composingGap: Gap? = null
    /** Exactly what was last passed to setComposingText, to notice when the app removed it. */
    private var composingShown: String? = null
    /**
     * The app removed our composing text (e.g. its Send button sent and cleared the field) while
     * Google was still working on that utterance: this many leading words of the utterance's later
     * results are skipped, so they aren't typed into the field again.
     */
    private var sentWords = 0

    private lateinit var status: TextView
    private lateinit var micButton: TextView
    private lateinit var micCircle: GradientDrawable
    private lateinit var submitIcon: ImageView
    private lateinit var submitLabel: TextView
    private lateinit var emojiKey: ImageView
    private lateinit var clipText: TextView
    private val clipboard by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { main.post { showClipboard() } }
    private lateinit var emojiPanel: View
    private lateinit var emojiRecentRow: LinearLayout
    private var keyHeight = 0
    private var dark = false
    /** The field's selection, from onUpdateSelection: fallback when the app gives no extracted text. */
    private var selStart = -1
    private var selEnd = -1
    /** Bottom padding that keeps the keys above the navigation bar. */
    private var bottomInset = -1

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.write("ime created version=\"${versionDisplay(this)}\"")
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val fg = if (dark) Color.rgb(232, 234, 237) else Color.rgb(32, 33, 36)
        val muted = if (dark) Color.rgb(154, 160, 166) else Color.rgb(95, 99, 104)
        val keyBg = if (dark) Color.rgb(60, 64, 67) else Color.WHITE
        // Gboard's action key: the Material You accent where there is one (Android 12+), else Google blue.
        val accentBg: Int
        val accentFg: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            accentBg = getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            accentFg = getColor(if (dark) android.R.color.system_accent1_900 else android.R.color.white)
        } else {
            accentBg = if (dark) Color.rgb(138, 180, 248) else Color.rgb(26, 115, 232)
            accentFg = if (dark) Color.rgb(32, 33, 36) else Color.WHITE
        }
        val ripple = ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000)

        status = TextView(this).apply {
            textSize = 17f
            setTextColor(fg)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(px(12), px(6), px(12), px(6))
        }
        micCircle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        micButton = TextView(this).apply {
            text = "🎤"
            textSize = 34f
            gravity = Gravity.CENTER
            background = micCircle
            setOnClickListener { tap(it); toggleListening() }
        }
        fun icon(res: Int, tint: Int) = ImageView(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER
        }
        fun keyBackground(color: Int) = RippleDrawable(ripple, GradientDrawable().apply { setColor(color); cornerRadius = px(8).toFloat() }, null)
        fun key(res: Int, weight: Float = 1f) = icon(res, fg).apply {
            background = keyBackground(keyBg)
            layoutParams = LinearLayout.LayoutParams(0, px(48), weight).apply { setMargins(px(4), 0, px(4), 0) }
        }
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), px(8), px(4), px(4))
            addView(key(R.drawable.ic_keyboard).apply { setOnClickListener { tap(it); switchBack(waitForLastResult = false) } })
            // Persian runs right to left: ▶ goes back to the previous word, ◀ forward to the next.
            addView(key(R.drawable.ic_chevron_left).apply { repeatWhileHeld(this) { moveWord(back = false) } })
            addView(key(R.drawable.ic_chevron_right).apply { repeatWhileHeld(this) { moveWord(back = true) } })
            // Points right: in Persian, deleting eats the text towards the right.
            addView(key(R.drawable.ic_backspace_rtl).apply { repeatWhileHeld(this) { backspace() } })
            submitIcon = icon(R.drawable.ic_return, accentFg)
            submitLabel = TextView(this@VoiceInputService).apply {
                textSize = 15f
                setTextColor(accentFg)
                gravity = Gravity.CENTER
                maxLines = 1
                visibility = View.GONE
            }
            addView(FrameLayout(this@VoiceInputService).apply {
                background = keyBackground(accentBg)
                addView(submitIcon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(submitLabel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                setOnClickListener { tap(it); submitKeyPressed() }
            }, LinearLayout.LayoutParams(0, px(48), 1.6f).apply { setMargins(px(4), 0, px(4), 0) })
        }

        // Toolbar, as in Gboard: flat icons on the bar's colour, and the clipboard as a chip.
        emojiKey = icon(R.drawable.ic_emoji, muted).apply {
            background = RippleDrawable(ripple, null, null)
            setOnClickListener { tap(it); toggleEmoji() }
        }
        clipText = TextView(this).apply {
            textSize = 14f
            setTextColor(fg)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(px(6), 0, 0, 0)
        }
        val clipChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), 0, px(12), 0)
            background = RippleDrawable(ripple, GradientDrawable().apply { setColor(keyBg); cornerRadius = px(16).toFloat() }, null)
            addView(icon(R.drawable.ic_paste, muted), LinearLayout.LayoutParams(px(20), px(20)))
            addView(clipText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { tap(it); pasteClipboard() }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(4), px(8), 0)
            addView(emojiKey, LinearLayout.LayoutParams(px(44), px(40)))
            addView(clipChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(32)).apply { marginStart = px(8) })
        }
        keyHeight = px(44)
        emojiRecentRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emojiPanel = ScrollView(this).apply {
            visibility = View.GONE
            addView(LinearLayout(this@VoiceInputService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(4), px(4), px(4), 0)
                addView(emojiRecentRow)
                EMOJI.chunked(EMOJI_PER_ROW).forEach { addView(emojiRow(it)) }
            })
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
            addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56)))
            addView(micButton, LinearLayout.LayoutParams(px(88), px(88)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            // The emoji picker takes the place of the status line and the mic (same height).
            addView(emojiPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(144)))
            addView(keys, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(version)
            // Keep the keys above the navigation bar / gesture handle. The padding is set before the
            // first layout and changed only when the inset really changes: when the keyboard's height
            // changed while it was sliding in (the inset arrives after the first layout), it sometimes
            // stopped half-way up, with the keys cut off at the bottom.
            if (bottomInset < 0) bottomInset = navigationBarGuess()
            setPadding(0, 0, 0, bottomInset + px(4))
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                if (bottom != bottomInset) {
                    AppLog.write("navigation bar inset $bottomInset → $bottom")
                    bottomInset = bottom
                    v.setPadding(0, 0, 0, bottom + px(4))
                }
                insets
            }
        }.also { render(); updateSubmitKey(); showClipboard() }
    }

    /** The system's navigation bar height, the usual bottom inset, so the padding rarely changes later. */
    private fun navigationBarGuess(): Int {
        window?.window?.decorView?.rootWindowInsets?.let { return it.getInsets(WindowInsets.Type.navigationBars()).bottom }
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    /** Opens paused: the user taps 🎤 to start. */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        selStart = info?.initialSelStart ?: -1
        selEnd = info?.initialSelEnd ?: -1
        if (restarting && composingShown != null) composingAfterRestart(info)
        if (info != null && !voiceSuitsField(info)) {
            // Digits, PINs and passwords: voice is the wrong tool (and a password must not go to Google).
            // Back to the previous keyboard (Gboard) at once; posted so the input view finishes starting.
            AppLog.write("field type 0x${Integer.toHexString(info.inputType)}: switching to the previous keyboard")
            main.post { switchBack(waitForLastResult = false) }
            return
        }
        if (::emojiPanel.isInitialized) showEmoji(false)
        updateSubmitKey()
        showClipboard()
        render()
    }

    /** Number, phone, date/time and password fields are left to the previous keyboard. */
    private fun voiceSuitsField(info: EditorInfo): Boolean {
        val type = info.inputType
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> false
            InputType.TYPE_CLASS_TEXT -> when (type and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> false
                else -> true
            }
            else -> true
        }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
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
        clipboard.removePrimaryClipChangedListener(clipListener)
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
        endComposing()
        sentWords = 0
        lastInterim = ""
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
        submitWhenFinal?.let { main.removeCallbacks(it); submitWhenFinal = null; afterFinish = afterFinish ?: { submit() } }
        mic?.stop()
        mic = null
        recognizer = null
        val wasListening = state == State.LISTENING
        state = State.IDLE
        currentInputConnection?.finishComposingText()
        endComposing()
        sentWords = 0
        render()
        if (error != null) {
            showStatus(if (wasListening) "Connection problem: ${error.message}. Check internet/VPN, then tap the mic." else "Stopped: ${error.message}")
        }
        val next = afterFinish
        afterFinish = null
        next?.invoke()
    }

    /**
     * Before a key edits the text or moves the cursor (◀ ▶ ⌫, paste, emoji). While listening, dictation goes on
     * (the user's request, 2026-10-27: only the first opening and their own pause stop it): the utterance in
     * progress is typed in as shown, and the rest of it goes where the cursor is then ([detachComposing]).
     * While finishing after the user paused, the last result isn't waited for.
     */
    private fun beforeEdit() = when (state) {
        State.LISTENING -> detachComposing()
        State.FINISHING -> pauseNow()
        State.IDLE -> {}
    }

    /**
     * Leave the utterance in progress where it is: its composing text becomes plain text, and the words shown
     * so far are skipped in its later results (see [sentWords]), so a deleted word doesn't come back and
     * nothing is typed twice. What Google hears next is composed at the cursor.
     */
    private fun detachComposing() {
        if (composingShown == null) return
        currentInputConnection?.finishComposingText()
        sentWords = maxOf(sentWords, lastInterim.trim().split(WHITESPACE).count { it.isNotEmpty() })
        endComposing()
        AppLog.write("composing text left in place; still listening (skipping the utterance's first $sentWords words)")
    }

    /** Pause immediately: keep the text shown so far (no waiting for Google's final result). */
    private fun pauseNow() {
        val pending = lastInterim
        val gap = composingGap // measured before the composing text was shown; keep it
        val shown = composingShown
        val skip = sentWords
        stopEverything()
        composingGap = gap
        composingShown = shown
        sentWords = skip
        if (pending.isNotBlank()) commitFinal(pending) else { currentInputConnection?.finishComposingText(); endComposing() }
        sentWords = 0
        lastInterim = ""
        render()
        AppLog.write("paused by a key" + if (pending.isNotBlank() && Settings.developmentMode(this)) " (kept: $pending)" else "")
    }

    /** Hold to repeat, like a keyboard's delete key: once on touch, then every 150 ms (a word each) after 400 ms. */
    private fun repeatWhileHeld(view: View, action: () -> Unit) {
        val repeat = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 150) }
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
        submitWhenFinal?.let { main.removeCallbacks(it) }
        submitWhenFinal = null
        afterFinish = null
        mic?.stop()
        mic = null
        recognizer?.cancel()
        recognizer = null
        endComposing()
        sentWords = 0
        state = State.IDLE
    }

    // Text

    /** Spoken punctuation and corrections; the words the app already took (see [sentWords]) left out. */
    private fun format(raw: String): String {
        val words = if (sentWords > 0) raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }.drop(sentWords).joinToString(" ") else raw
        return SpokenPunctuation.apply(Corrections.apply(words, Settings.LANGUAGE), Settings.LANGUAGE)
    }

    private fun showInterim(raw: String) {
        if (state == State.IDLE) return
        if (composingShown != null && !composingStillThere()) composingRemovedByApp()
        lastInterim = raw
        val text = format(raw)
        showStatus(text.replace("\n", " ↵ ").replace(SpokenPunctuation.ENTER.toString(), " ⏎ ").trim().ifEmpty { "در حال گوش دادن…" })
        val ic = currentInputConnection ?: return
        // Line breaks and Enter happen when the final is committed; meanwhile they show as spaces.
        val preview = text.replace(Regex("[\\n\r]+"), " ").trim()
        if (preview.isEmpty()) {
            if (composingShown != null) { ic.setComposingText("", 1); composingShown = "" }
            return
        }
        val gap = composingGap ?: gapAtCursor(preview).also { composingGap = it }
        val shown = gap.fit(preview)
        ic.setComposingText(shown, 1)
        composingShown = shown
    }

    private fun commitFinal(raw: String) {
        if (composingShown != null && !composingStillThere()) composingRemovedByApp()
        lastInterim = "" // this utterance is done; a pause must not type it again
        val text = format(raw)
        sentWords = 0
        val ic = currentInputConnection ?: return
        // "اینتر": commit what came before it, do the field's action (send, search…), then go on.
        text.split(SpokenPunctuation.ENTER).forEachIndexed { i, part ->
            if (i > 0) {
                AppLog.write("spoken Enter")
                submit()
            }
            commitText(ic, part)
        }
        submitWhenFinal?.let { main.removeCallbacks(it); submitWhenFinal = null; submit() }
        if (state != State.IDLE) showStatus(if (state == State.LISTENING) "در حال گوش دادن…" else "…")
    }

    /** Commits [text] in place of the composing text, or at the cursor, spaced to fit its neighbours. */
    private fun commitText(ic: InputConnection, text: String) {
        if (text.isEmpty()) {
            if (composingShown != null) ic.commitText("", 1) // removes the composing text
        } else {
            ic.commitText((composingGap ?: gapAtCursor(text)).fit(text), 1)
        }
        endComposing()
    }

    private fun endComposing() {
        composingGap = null
        composingShown = null
    }

    /**
     * What is around the cursor, so inserted text gets exactly one space between it and the words on
     * either side, also in the middle of the text. Before, a space was only ever added in front, so
     * after moving the cursor or deleting, words ended up two or three spaces apart (or stuck together).
     * Text that attaches to the previous word (". ، ؟") also removes the spaces before the cursor.
     */
    private fun gapAtCursor(text: String): Gap {
        val ic = currentInputConnection ?: return Gap(false, false)
        var before = ic.getTextBeforeCursor(GAP_WINDOW, 0)?.toString().orEmpty()
        if (SpokenPunctuation.attachesToPrevious(text)) {
            val spaces = before.length - before.trimEnd { isSpace(it) }.length
            if (spaces in 1 until before.length) {
                ic.deleteSurroundingText(spaces, 0)
                before = before.dropLast(spaces)
            }
        }
        val prev = before.lastOrNull()
        val next = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        return Gap(
            wordBefore = prev != null && !prev.isWhitespace() && prev != '(',
            wordAfter = next != null && !next.isWhitespace() && next !in SpokenPunctuation.SYMBOLS,
        )
    }

    /** Whether there is a word right before / after the insertion point (not a space, not the field's edge). */
    private class Gap(val wordBefore: Boolean, val wordAfter: Boolean) {
        fun fit(text: String): String {
            val lead = wordBefore && !SpokenPunctuation.attachesToPrevious(text)
            val trail = wordAfter && !SpokenPunctuation.attachesToNext(text) && !text.last().isWhitespace()
            return (if (lead) " " else "") + text + (if (trail) " " else "")
        }
    }

    /**
     * Whether our composing text is still in the field. The app may take it away: its own Send button
     * sends and clears the field while Google is still on that utterance. Before, the next result was
     * composed again and part of the sent message reappeared in the empty field.
     */
    private fun composingStillThere(): Boolean {
        val shown = composingShown ?: return true
        if (shown.isEmpty()) return true
        val ic = currentInputConnection ?: return true
        val before = ic.getTextBeforeCursor(shown.length, 0) ?: return true // the app doesn't say
        if (before.toString() == shown) return true
        // The user may have tapped elsewhere in the field: the composing text is still somewhere.
        val all = ic.getExtractedText(ExtractedTextRequest(), 0)?.text
            ?: ((ic.getTextBeforeCursor(SEARCH_WINDOW, 0) ?: "").toString() + (ic.getTextAfterCursor(SEARCH_WINDOW, 0) ?: ""))
        return all.contains(shown)
    }

    /** The app took the composing text: skip what was shown of this utterance and start fresh. */
    private fun composingRemovedByApp() {
        val shownWords = lastInterim.trim().split(WHITESPACE).count { it.isNotEmpty() }
        AppLog.write("composing text removed by the app; skipping the utterance's first $shownWords words")
        sentWords = maxOf(sentWords, shownWords)
        endComposing()
    }

    /**
     * The app restarted the input (some do on send). The composing span is gone either way: if our
     * text is still right before the cursor, compose over it again, else treat it as removed.
     */
    private fun composingAfterRestart(info: EditorInfo?) {
        val shown = composingShown ?: return
        val ic = currentInputConnection ?: return
        val cursor = info?.initialSelEnd ?: -1
        if (shown.isNotEmpty() && cursor >= shown.length && ic.getTextBeforeCursor(shown.length, 0)?.toString() == shown) {
            ic.setComposingRegion(cursor - shown.length, cursor)
        } else if (shown.isNotEmpty() && state != State.IDLE) {
            composingRemovedByApp()
        } else {
            endComposing()
        }
    }

    /**
     * ⌫ deletes a whole word: the word before the cursor, or the word the cursor is in (both parts),
     * with the spaces before it, keeping one space if words remain on both sides. A line break before
     * the cursor is deleted on its own, a selection as a whole.
     *
     * While listening, the text shown so far is typed in as it is and listening goes on; its words are
     * skipped in later results, so the deletion isn't undone ([beforeEdit]).
     */
    private fun backspace() {
        if (afterFinish != null) return
        beforeEdit()
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(WORD_WINDOW, 0)?.toString()
        val after = ic.getTextAfterCursor(WORD_WINDOW, 0)?.toString()
        if (before.isNullOrEmpty() || after == null) { sendDelete(ic); return }
        if (before.last() == '\n') { ic.deleteSurroundingText(1, 0); return }
        var wordStart = before.length
        while (wordStart > 0 && isSpace(before[wordStart - 1])) wordStart--
        val cursorInWord = wordStart == before.length
        while (wordStart > 0 && !before[wordStart - 1].isWhitespace()) wordStart--
        val afterCount = if (cursorInWord) after.takeWhile { !it.isWhitespace() }.length else 0
        var start = wordStart
        while (start > 0 && isSpace(before[start - 1])) start--
        val wordOnLeft = start > 0 && !before[start - 1].isWhitespace()
        val wordOnRight = after.length > afterCount && !after[afterCount].isWhitespace()
        if (wordOnLeft && wordOnRight && start < wordStart) start++ // keep one space between them
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - start, afterCount)
        ic.endBatchEdit()
    }

    /** ◀ ▶: move the cursor to the start of the previous word or the end of the next one. */
    private fun moveWord(back: Boolean) {
        if (afterFinish != null) return
        beforeEdit()
        val ic = currentInputConnection ?: return
        val (start, end) = selection(ic) ?: return
        val offset = if (back) {
            val before = ic.getTextBeforeCursor(WORD_WINDOW, 0) ?: return
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
            i - before.length
        } else {
            val after = ic.getTextAfterCursor(WORD_WINDOW, 0) ?: return
            var i = 0
            while (i < after.length && after[i].isWhitespace()) i++
            while (i < after.length && !after[i].isWhitespace()) i++
            i
        }
        val target = (if (back) start else end) + offset
        ic.setSelection(target, target)
        selStart = target
        selEnd = target
    }

    /** The field's selection (absolute offsets): asked from the app, else the last one it reported. */
    private fun selection(ic: InputConnection): Pair<Int, Int>? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted != null && extracted.selectionStart >= 0) {
            val a = extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
            val b = extracted.startOffset + maxOf(extracted.selectionStart, extracted.selectionEnd)
            return a to b
        }
        return if (selStart >= 0) minOf(selStart, selEnd) to maxOf(selStart, selEnd) else null
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\u00A0'

    private fun sendDelete(ic: InputConnection) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    // Keys

    /** Like Gboard's: the field's action (send, search, go, next, done…), else Enter. */
    /** [label]: the app's own text for the key, shown instead of [icon]. */
    private class SubmitAction(val icon: Int, val label: String?, val actionId: Int?, val newline: Boolean)

    private fun submitAction(info: EditorInfo?): SubmitAction {
        if (info == null) return SubmitAction(R.drawable.ic_return, null, null, false)
        val multiLine = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return SubmitAction(R.drawable.ic_return, null, null, multiLine)
        if (info.actionLabel != null && info.actionId != 0) return SubmitAction(R.drawable.ic_return, info.actionLabel.toString(), info.actionId, false)
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val icon = when (action) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
            EditorInfo.IME_ACTION_GO -> R.drawable.ic_go
            EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_next
            EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_previous
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_done
            else -> return SubmitAction(R.drawable.ic_return, null, null, multiLine)
        }
        return SubmitAction(icon, null, action, false)
    }

    private fun updateSubmitKey() {
        if (!::submitIcon.isInitialized) return
        val action = submitAction(currentInputEditorInfo)
        submitIcon.setImageResource(action.icon)
        submitIcon.visibility = if (action.label == null) View.VISIBLE else View.GONE
        submitLabel.text = action.label
        submitLabel.visibility = if (action.label == null) View.GONE else View.VISIBLE
    }

    /** While listening it waits for the last result first (up to a few seconds), like Enter on the Mac. */
    private fun submitKeyPressed() {
        if (afterFinish != null) return
        when (state) {
            State.LISTENING -> submitAfterUtterance()
            State.FINISHING -> afterFinish = { submit() }
            State.IDLE -> submit()
        }
    }

    /**
     * Listening goes on (only the user's own pause stops it). If an utterance is on screen, wait for its final
     * (more accurate than the interim, usually within a second of the user stopping) and submit after typing it;
     * if it doesn't come within [SUBMIT_WAIT_MS], type what is shown and submit.
     */
    private fun submitAfterUtterance() {
        if (submitWhenFinal != null) return
        if (composingShown.isNullOrEmpty()) { submit(); return }
        val timeout = Runnable {
            submitWhenFinal = null
            AppLog.write("no final within ${SUBMIT_WAIT_MS} ms; submitting what was shown")
            detachComposing()
            submit()
        }
        submitWhenFinal = timeout
        main.postDelayed(timeout, SUBMIT_WAIT_MS)
    }

    private fun submit() {
        val ic = currentInputConnection ?: return
        val action = submitAction(currentInputEditorInfo)
        when {
            action.actionId != null -> ic.performEditorAction(action.actionId)
            action.newline -> ic.commitText("\n", 1)
            else -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    /** The paste chip: the start of the clipboard's text, like Gboard's; just the icon when there is none. */
    private fun showClipboard() {
        if (!::clipText.isInitialized) return
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.replace(WHITESPACE, " ")?.trim().orEmpty()
        clipText.text = when {
            text.isEmpty() -> ""
            sensitive -> "••••••"
            text.length > CLIP_PREVIEW -> text.take(CLIP_PREVIEW) + "…"
            else -> text
        }
        clipText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The paste chip types the clipboard's text as is; if we may not read it, the app pastes it itself. */
    private fun pasteClipboard() {
        if (afterFinish != null) return
        beforeEdit()
        val ic = currentInputConnection ?: return
        val clip = clipboard.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrEmpty()) ic.commitText(text, 1)
        else if (!ic.performContextMenuAction(android.R.id.paste)) showStatus("Clipboard is empty")
    }

    // Emoji

    private fun toggleEmoji() = showEmoji(emojiPanel.visibility != View.VISIBLE)

    /** The picker replaces the status line and the mic; opening it pauses dictation. */
    private fun showEmoji(show: Boolean) {
        if (show) fillRecentEmoji()
        emojiPanel.visibility = if (show) View.VISIBLE else View.GONE
        status.visibility = if (show) View.GONE else View.VISIBLE
        micButton.visibility = if (show) View.GONE else View.VISIBLE
        emojiKey.setImageResource(if (show) R.drawable.ic_mic else R.drawable.ic_emoji)
    }

    private fun emojiRow(emoji: List<String>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (e in emoji) addView(TextView(this@VoiceInputService).apply {
            text = e
            textSize = 24f
            gravity = Gravity.CENTER
            setOnClickListener { tap(it); typeEmoji(e) }
        }, LinearLayout.LayoutParams(0, keyHeight, 1f))
        // Keep a short last row in the same columns.
        repeat(EMOJI_PER_ROW - emoji.size) { addView(View(this@VoiceInputService), LinearLayout.LayoutParams(0, keyHeight, 1f)) }
    }

    private fun typeEmoji(emoji: String) {
        if (afterFinish != null) return
        beforeEdit()
        currentInputConnection?.commitText(emoji, 1)
        val recent = (listOf(emoji) + recentEmoji().filter { it != emoji }).take(EMOJI_PER_ROW)
        getSharedPreferences("emoji", MODE_PRIVATE).edit().putString("recent", recent.joinToString(" ")).apply()
    }

    private fun recentEmoji() =
        getSharedPreferences("emoji", MODE_PRIVATE).getString("recent", "")!!.split(' ').filter { it.isNotEmpty() }

    private fun fillRecentEmoji() {
        emojiRecentRow.removeAllViews()
        val recent = recentEmoji()
        if (recent.isNotEmpty()) emojiRecentRow.addView(emojiRow(recent))
    }

    // Leaving

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
            State.IDLE -> { micCircle.setColor(if (dark) Color.rgb(95, 99, 104) else Color.rgb(189, 193, 198)); micButton.alpha = 1f; showStatus("Paused — 🎤 to dictate") }
        }
    }

    private fun showStatus(text: String) {
        if (::status.isInitialized) status.text = text
    }

    private fun showLevel(level: Double) {
        if (state == State.LISTENING) micButton.alpha = (0.55 + minOf(1.0, level / 1500) * 0.45).toFloat()
    }

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private companion object {
        /** How much text around the cursor ⌫ and ◀ ▶ look at: longer than any word. */
        const val WORD_WINDOW = 200
        /** How far back [gapAtCursor] looks for spaces before the cursor. */
        const val GAP_WINDOW = 20
        /** How far around the cursor to look for moved composing text when the app gives no extracted text. */
        const val SEARCH_WINDOW = 2000
        val WHITESPACE = Regex("\\s+")
        /** How long the submit key waits for the final of the utterance on screen while listening. */
        const val SUBMIT_WAIT_MS = 3_000L
        /** Characters of the clipboard shown on the paste chip. */
        const val CLIP_PREVIEW = 24
        const val EMOJI_PER_ROW = 8
        val EMOJI = listOf(
            "😂", "❤️", "🌹", "🙏", "👍", "😍", "😊", "🤣",
            "😁", "😅", "😉", "😎", "😘", "🥰", "🤗", "🙂",
            "🤔", "😐", "🙄", "😏", "😬", "😔", "😢", "😭",
            "😡", "😱", "😴", "😷", "🤦", "🤷", "😇", "🥳",
            "👌", "👏", "💪", "✌️", "🙌", "👋", "🤝", "🤞",
            "👀", "👇", "👆", "👉", "👈", "💯", "🔥", "⭐",
            "✅", "❌", "❗", "❓", "⚠️", "💡", "📌", "🎉",
            "💙", "💚", "💛", "🧡", "💜", "🖤", "🤍", "💔",
            "☀️", "🌙", "🌸", "🍀", "☕", "🍰", "🎂", "🎁",
            "🏠", "🚗", "✈️", "📞", "💻", "📱", "⏰", "📷",
        )
    }
}

package local.voice2text.persiandictation

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Launcher screen: microphone permission, enabling the keyboard, how to use it, development mode. */
class SetupActivity : Activity() {
    private lateinit var micStatus: TextView
    private lateinit var micButton: Button
    private lateinit var keyboardStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide() // the screen has its own heading
        AppLog.init(this)
        val dp = resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(20), pad(20), pad(32))
        }
        fun heading(text: String) = TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad(24), 0, pad(6))
        }
        fun body(text: String = "") = TextView(this).apply {
            this.text = text
            textSize = 15f
            setLineSpacing(0f, 1.2f)
        }

        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(body("Version ${versionDisplay(this)}"))

        column.addView(heading("1. Microphone"))
        // Privacy notice before the permission request (Cafe Bazaar requires it for private data).
        column.addView(body(
            "While the keyboard is listening, your voice is sent to Google's speech recognition service " +
            "to turn it into text. The app doesn't store your voice or text and sends them nowhere else " +
            "(unless you turn on development mode below, which saves them only on this phone)."))
        column.addView(body(
            "وقتی صفحه‌کلید در حال گوش دادن است، صدای شما برای تبدیل به متن به سرویس تشخیص گفتار گوگل " +
            "فرستاده می‌شود. برنامه صدا یا متن شما را ذخیره نمی‌کند و به جای دیگری نمی‌فرستد " +
            "(مگر حالت توسعه را در پایین همین صفحه روشن کنید که آن‌ها را فقط روی همین گوشی ذخیره می‌کند).").apply {
            textDirection = android.view.View.TEXT_DIRECTION_RTL
            setPadding(0, pad(8), 0, 0)
        })
        micStatus = body()
        column.addView(micStatus)
        micButton = Button(this).apply {
            text = "Allow microphone"
            setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) }
        }
        column.addView(micButton)

        column.addView(heading("2. Turn on the keyboard"))
        keyboardStatus = body()
        column.addView(keyboardStatus)
        column.addView(Button(this).apply {
            text = "Open keyboard settings"
            setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) }
        })

        column.addView(heading("3. Use it"))
        column.addView(body(
            "Keep Gboard as your keyboard. To dictate, switch keyboards with one tap: the keyboard icon at " +
            "the bottom of the screen, or a long press on Gboard's space bar, then pick Persian Dictation. " +
            "Tap the mic to start listening, and again to pause.\n\n" +
            "⌨ goes back to Gboard. ⌫ deletes a whole word, ◀ ▶ move the cursor by words " +
            "(hold them to repeat).\n\n" +
            // Isolate each Persian word so it doesn't reorder the English sentence around it.
            "Say ${listOf("نقطه", "ویرگول", "علامت سوال", "دو نقطه", "پرانتز باز", "پرانتز بسته").joinToString(", ") { "\u2068«$it»\u2069" }}" +
            ", \u2068«خط بعد»\u2069 (new line) or \u2068«پاراگراف بعد»\u2069 (new paragraph) for punctuation."))
        column.addView(Button(this).apply {
            text = "Switch keyboard now"
            setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        })
        val tryField = EditText(this).apply {
            hint = "Try it here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        column.addView(tryField)

        column.addView(heading("Development mode"))
        column.addView(Switch(this).apply {
            text = "Log recognizer results and record audio"
            isChecked = Settings.developmentMode(this@SetupActivity)
            setOnCheckedChangeListener { button, on ->
                if (!on) {
                    Settings.setDevelopmentMode(this@SetupActivity, false)
                    AppLog.write("developmentMode=false")
                    return@setOnCheckedChangeListener
                }
                if (Settings.developmentMode(this@SetupActivity)) return@setOnCheckedChangeListener
                // It keeps private speech on the phone: ask first.
                button.isChecked = false
                android.app.AlertDialog.Builder(this@SetupActivity)
                    .setTitle("Turn on development mode?")
                    .setMessage("Development mode saves a recording of everything you dictate and the recognized text " +
                        "on this phone (kept for 7 days). This may include private or confidential information. " +
                        "Use it only for troubleshooting.")
                    .setPositiveButton("Turn on") { _, _ ->
                        Settings.setDevelopmentMode(this@SetupActivity, true)
                        AppLog.write("developmentMode=true")
                        button.isChecked = true
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        column.addView(body("Logs and recordings: Android/data/$packageName/files"))
        column.addView(Button(this).apply {
            text = "Share diagnostics"
            setOnClickListener { DiagnosticsDialog.show(this@SetupActivity) }
        })
        column.addView(body("Zips the log and recent recordings (you choose how many hours) and opens the share sheet."))

        val scroll = ScrollView(this).apply { addView(column) }
        // Edge-to-edge (Android 15): keep content clear of the status bar, navigation bar and keyboard.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            if (tryField.hasFocus()) v.post { scroll.smoothScrollTo(0, tryField.bottom) }
            WindowInsets.CONSUMED
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val mic = hasMicPermission(this)
        micStatus.text = if (mic) "✓ Microphone allowed" else "✗ Microphone not allowed yet"
        micButton.visibility = if (mic) View.GONE else View.VISIBLE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        keyboardStatus.text = if (enabled) "✓ Persian Dictation is turned on"
                              else "✗ Turn on “Persian Dictation” in the list (Android will show a standard warning for any new keyboard)."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onResume()
    }
}

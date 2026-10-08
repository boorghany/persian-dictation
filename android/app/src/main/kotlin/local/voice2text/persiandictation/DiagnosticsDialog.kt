package local.voice2text.persiandictation

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * "Share diagnostics": pick how many hours of recordings, confirm the privacy warning (the zip holds
 * the user's voice and dictated text), watch the progress, then the share sheet opens.
 */
object DiagnosticsDialog {
    private const val RED = 0xFFD32F2F.toInt()

    fun show(activity: Activity) {
        val dp = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(24), pad(8), pad(24), 0)
        }
        column.addView(TextView(activity).apply { text = "Recordings from:"; textSize = 15f })
        val choices = RadioGroup(activity)
        DiagnosticsExport.HOUR_CHOICES.forEachIndexed { i, hours ->
            val bytes = DiagnosticsExport.inputs(activity, hours).sumOf { it.length() }
            choices.addView(RadioButton(activity).apply {
                id = i + 1
                text = "Last $hours hours (about ${megabytes(bytes)})"
            })
        }
        choices.check(1)
        column.addView(choices)

        // The privacy warning: red, boxed, with a warning sign.
        val warning = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(12), pad(10), pad(12), pad(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(20, 211, 47, 47))
                setStroke(pad(2), RED)
                cornerRadius = 8 * dp
            }
            addView(TextView(activity).apply {
                text = "⚠️  Private data"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(RED)
            })
            addView(TextView(activity).apply {
                text = "The zip contains recordings of your voice and the full text of everything you dictated " +
                    "in this period. It may include personal or confidential information (names, messages, " +
                    "passwords you spoke, work details). Only send it to someone you trust, and check the time range first."
                textSize = 14f
                setTextColor(RED)
            })
        }
        column.addView(warning, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad(12) })
        val consent = CheckBox(activity).apply {
            text = "I understand and want to share my voice and dictated text."
            setTextColor(RED)
        }
        column.addView(consent)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Share diagnostics")
            .setView(column)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create zip") { _, _ ->
                zip(activity, DiagnosticsExport.HOUR_CHOICES[choices.checkedRadioButtonId - 1])
            }
            .create()
        dialog.show()
        val create = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        create.isEnabled = false // until the user consents
        consent.setOnCheckedChangeListener { _, checked -> create.isEnabled = checked }
    }

    private fun zip(activity: Activity, hours: Int) {
        val dp = activity.resources.displayMetrics.density
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val label = TextView(activity).apply { text = "Zipping… 0%"; textSize = 15f }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(label)
            addView(bar)
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Preparing diagnostics")
            .setView(column)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()
        var lastPercent = -1
        Thread {
            val result = runCatching {
                DiagnosticsExport.build(activity, hours, { cancelled.get() }) { fraction ->
                    val percent = (fraction * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        activity.runOnUiThread {
                            bar.progress = (fraction * 1000).toInt()
                            label.text = "Zipping… $percent%"
                        }
                    }
                }
            }
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                result.onSuccess {
                    AppLog.write("diagnostics zip created: ${it.name} (${megabytes(it.length())}, $hours h)")
                    DiagnosticsExport.share(activity, it)
                }.onFailure {
                    if (it is CancellationException) return@onFailure
                    AppLog.write("diagnostics zip failed: $it")
                    Toast.makeText(activity, "Could not prepare diagnostics: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun megabytes(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb < 10) String.format(Locale.US, "%.1f MB", mb) else "${Math.round(mb)} MB"
    }
}

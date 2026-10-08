package local.voice2text.persiandictation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Share diagnostics": zips the logs (and connection traces), the session recordings of the last few
 * hours (the user picks how many) and a summary of the setup, then opens the share sheet.
 */
object DiagnosticsExport {
    val HOUR_CHOICES = listOf(2, 6, 24, 48)

    /** Everything in the app's files dir except the test input; recordings only from the last [hours]. */
    fun inputs(context: Context, hours: Int): List<File> {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val recordings = AppLog.recordingsDir(context)
        val cutoff = System.currentTimeMillis() - hours * 3600_000L
        return root.walkTopDown()
            .filter { it.isFile && it != AppLog.testInputFile(context) }
            .filter { !it.startsWith(recordings) || it.lastModified() >= cutoff }
            .toList()
    }

    /** Slow (zips audio): call off the main thread. Throws [CancellationException] if [cancelled]. */
    fun build(context: Context, hours: Int, cancelled: () -> Boolean, progress: (Float) -> Unit): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val dir = DiagnosticsProvider.directory(context).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only keep the newest zip
        val zip = File(dir, "PersianDictation-diagnostics-$stamp.zip")
        try { writeZip(context, zip, hours, cancelled, progress) } catch (e: Throwable) { zip.delete(); throw e }
        return zip
    }

    private fun writeZip(context: Context, zip: File, hours: Int, cancelled: () -> Boolean, progress: (Float) -> Unit) {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val files = inputs(context, hours)
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        val buffer = ByteArray(256 * 1024)
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.setLevel(1) // WAV barely compresses better at higher levels, and it is much faster
            out.putNextEntry(ZipEntry("info.txt"))
            out.write(summary(context, hours).toByteArray())
            out.closeEntry()
            for (file in files) {
                out.putNextEntry(ZipEntry(file.relativeTo(root).path))
                file.inputStream().use { input ->
                    while (true) {
                        if (cancelled()) throw CancellationException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        progress(done.toFloat() / total)
                    }
                }
                out.closeEntry()
            }
        }
    }

    fun share(context: Context, zip: File) {
        val uri = DiagnosticsProvider.uriFor(context, zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            clipData = ClipData.newRawUri(zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
    }

    private fun summary(context: Context, hours: Int): String {
        // Settings.Secure.ENABLED_INPUT_METHODS is off limits for targetSdk > 33; ask the IMM instead.
        val enabledIme = runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.joinToString(", ") { it.id }
        }.getOrElse { "unknown ($it)" }
        val defaultIme = runCatching {
            AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrElse { "unknown ($it)" }
        return """
            Persian Dictation ${versionDisplay(context)}
            Created: ${Date()} (time zone ${TimeZone.getDefault().id})
            Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
            Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}
            Language: ${Settings.LANGUAGE}
            Development mode: ${Settings.developmentMode(context)}
            Microphone permission: ${hasMicPermission(context)}
            Current keyboard: $defaultIme
            Enabled keyboards: $enabledIme
            Recordings included: last $hours hours

        """.trimIndent()
    }
}

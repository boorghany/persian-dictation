package local.voice2text.persiandictation

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import local.voice2text.core.ContinuousRecognizer
import local.voice2text.core.SpeechStream

/** Settings stored in SharedPreferences. */
object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Development mode: verbose log plus a WAV recording of every session. On by default while testing. */
    fun developmentMode(context: Context) = prefs(context).getBoolean("developmentMode", true)
    fun setDevelopmentMode(context: Context, on: Boolean) = prefs(context).edit().putBoolean("developmentMode", on).apply()

    const val LANGUAGE = "fa-IR"
}

/**
 * Appends to PersianDictation.log in the app's external files folder
 * (Android/data/local.voice2text.persiandictation/files), readable with `adb pull`.
 */
object AppLog {
    private var file: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun init(context: Context) {
        if (file != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "PersianDictation.log")
    }

    /** Development-mode test hook: if this file exists, it is played instead of the microphone. */
    fun testInputFile(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "test-input.wav")

    fun recordingsDir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")

    @Synchronized
    fun write(message: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > 5_000_000) f.renameTo(File(f.parentFile, "PersianDictation.1.log"))
            f.appendText("${stamp.format(Date())} [${android.os.Process.myPid()}] $message\n")
        }
    }
}

fun versionDisplay(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return "${info.versionName} (build ${info.longVersionCode})"
}

fun hasMicPermission(context: Context) =
    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Captures the microphone as 16 kHz / 16-bit / mono PCM in 100 ms chunks on a background thread.
 *
 * Testing (development mode only): if [testInput] is set, that WAV file is played in real time
 * instead of the microphone, followed by silence, so the whole flow can be tested on an emulator.
 */
class MicCapture(private val testInput: File? = null, private val onAudio: (ByteArray, Double) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Throws if the microphone can't be opened. Requires the RECORD_AUDIO permission. */
    @Throws(IllegalStateException::class, SecurityException::class)
    fun start() {
        if (testInput != null) { startTestInput(testInput); return }
        val rate = SpeechStream.SAMPLE_RATE
        val chunk = rate / 10 * 2
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                                 AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunk * 4))
        check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        record.startRecording()
        running = true
        thread = Thread({
            try {
                while (running) {
                    val buffer = ByteArray(chunk)
                    var filled = 0
                    while (running && filled < chunk) {
                        val n = record.read(buffer, filled, chunk - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled > 0) {
                        val pcm = if (filled == chunk) buffer else buffer.copyOf(filled)
                        onAudio(pcm, ContinuousRecognizer.rms(pcm, 0, pcm.size))
                    }
                }
            } finally {
                record.stop()
                record.release()
            }
        }, "mic").apply { start() }
    }

    private fun startTestInput(file: File) {
        val pcm = file.readBytes().let { if (it.size > 44 && String(it, 0, 4) == "RIFF") it.copyOfRange(44, it.size) else it }
        val chunk = SpeechStream.SAMPLE_RATE / 10 * 2
        running = true
        thread = Thread({
            var offset = 0
            val start = System.nanoTime()
            while (running) {
                val piece = if (offset < pcm.size) pcm.copyOfRange(offset, minOf(offset + chunk, pcm.size)) else ByteArray(chunk)
                onAudio(piece, ContinuousRecognizer.rms(piece, 0, piece.size))
                offset += chunk
                val wait = start / 1_000_000 + offset * 1000L / (SpeechStream.SAMPLE_RATE * 2) - System.nanoTime() / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
        }, "test-input").apply { start() }
    }

    fun stop() {
        running = false
    }
}

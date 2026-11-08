package local.voice2text.windows

import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import local.voice2text.core.ContinuousRecognizer

/**
 * Microphone → 16 kHz / 16-bit / mono PCM in 100 ms chunks (Java Sound; Windows converts the rate).
 * "System Default" follows the Windows default input when dictation starts; a chosen device is used
 * while it is connected. Switches to the default if the device stops or sends only zeros for 3 s.
 */
class MicCapture {
    /** Called on the capture thread with each chunk and its level (RMS, 0...1). */
    var onAudio: ((ByteArray, Float) -> Unit)? = null
    /** The device changed or has a problem: (name, reason). Capture thread. */
    var onDeviceChange: ((String, String) -> Unit)? = null

    @Volatile private var running = false
    @Volatile private var switchRequested = false
    @Volatile private var line: TargetDataLine? = null
    private var thread: Thread? = null
    var deviceName = ""
        private set

    fun start() {
        val test = AppPaths.testInput.takeIf { it.exists() }
        running = true
        if (test != null) {
            deviceName = "test-input.wav"
            Log.write("microphone: test input ${test.path}")
            thread = Thread({ playTestInput(test) }, "mic").apply { isDaemon = true; start() }
            return
        }
        val (l, name) = open(Settings.microphone)
        line = l
        deviceName = name
        Log.write("microphone: $name (start) format=${l.format}")
        thread = Thread({ loop() }, "mic").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
    }

    /** The Microphone menu changed: switch now. */
    fun preferenceChanged() {
        switchRequested = true
    }

    private fun reopen(name: String?, reason: String) {
        line?.let { runCatching { it.stop(); it.close() } }
        val (l, n) = open(name)
        line = l
        deviceName = n
        Log.write("microphone: $n ($reason)")
        onDeviceChange?.invoke(n, reason)
    }

    private fun loop() {
        val buf = ByteArray(CHUNK)
        var silentChunks = 0
        var reportedSilence = false
        var failedReads = 0
        while (running) {
            try {
                if (switchRequested) {
                    switchRequested = false
                    reopen(Settings.microphone, "microphone changed")
                    silentChunks = 0
                    reportedSilence = false
                }
                val l = line ?: break
                val n = l.read(buf, 0, CHUNK)
                if (!running) break
                if (n <= 0) {
                    failedReads += 1
                    if (failedReads >= 5) {
                        failedReads = 0
                        reopen(null, "the microphone stopped")
                    } else {
                        Thread.sleep(100)
                    }
                    continue
                }
                failedReads = 0
                val chunk = buf.copyOf(n)
                if (chunk.all { it == 0.toByte() }) silentChunks += 1 else silentChunks = 0
                if (silentChunks == 30 && !reportedSilence) {
                    reportedSilence = true
                    if (Settings.microphone != null) {
                        reopen(null, "no sound from ${deviceName} for 3 s")
                        silentChunks = 0
                    } else {
                        Log.write("microphone: no sound from $deviceName for 3 s")
                        onDeviceChange?.invoke(deviceName, "no sound — check Settings → Privacy & security → Microphone")
                    }
                }
                onAudio?.invoke(chunk, level(chunk))
            } catch (e: Exception) {
                if (!running) break
                Log.write("microphone error: $e")
                Thread.sleep(300)
                runCatching { reopen(null, "error: ${e.message}") }
            }
        }
    }

    /** Emulator-style test: play the WAV in real time, then silence until stopped. */
    private fun playTestInput(file: File) {
        val pcm = RandomAccessFile(file, "r").use { f -> ByteArray(f.length().toInt()).also { f.readFully(it) } }
        val data = if (pcm.size > 44 && String(pcm, 0, 4) == "RIFF") pcm.copyOfRange(44, pcm.size) else pcm
        var offset = 0
        val t0 = System.nanoTime()
        while (running) {
            val chunk = if (offset < data.size) data.copyOfRange(offset, minOf(offset + CHUNK, data.size)) else ByteArray(CHUNK)
            offset += CHUNK
            onAudio?.invoke(chunk, level(chunk))
            val due = t0 + offset.toLong() * 1_000_000_000L / ContinuousRecognizer.BYTES_PER_SECOND
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000)
        }
    }

    companion object {
        const val CHUNK = 3200 // 100 ms
        val FORMAT = AudioFormat(16000f, 16, 1, true, false)
        private const val DEFAULT_MIXER = "Primary Sound Capture Driver"
        private val lineInfo = DataLine.Info(TargetDataLine::class.java, FORMAT)

        /** Capture devices (Java Sound's DirectSound mixers), without the "follow the default" one. */
        fun microphones(): List<Mixer.Info> = runCatching {
            AudioSystem.getMixerInfo().filter { info ->
                info.name != DEFAULT_MIXER && runCatching { AudioSystem.getMixer(info).isLineSupported(lineInfo) }.getOrDefault(false)
            }
        }.getOrDefault(emptyList())

        fun open(name: String?): Pair<TargetDataLine, String> {
            if (name != null) {
                val info = microphones().firstOrNull { it.name == name }
                if (info != null) {
                    val l = AudioSystem.getTargetDataLine(FORMAT, info)
                    l.open(FORMAT, CHUNK * 4)
                    l.start()
                    return l to info.name
                }
            }
            val l = AudioSystem.getTargetDataLine(FORMAT)
            l.open(FORMAT, CHUNK * 4)
            l.start()
            return l to "System Default"
        }

        fun level(pcm: ByteArray): Float =
            (ContinuousRecognizer.rms(pcm, 0, pcm.size) / 32768.0).toFloat()
    }
}

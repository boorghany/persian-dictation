package local.voice2text.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Long-running dictation on top of Google's streaming endpoint, using one session per utterance.
 * Port of the Mac app's ContinuousRecognizer.swift; see there for the full rationale.
 *
 * Why one session per utterance: in Google's continuous mode, once an utterance is finalized the
 * beginning of the next one is often clipped ("می‌خوام" → "ی‌خوام"), while a fresh session
 * recognizes the same audio correctly. So when a session delivers a final result we hand over to
 * a new session and replay a pre-roll of recent audio into it, going back to the silence before
 * the next utterance so nothing is lost while the new session connects.
 *
 * Google also sometimes freezes mid-utterance: no more results while the user keeps talking, then
 * an empty final. A watchdog detects this and replays the utterance's audio into a fresh session.
 *
 * Thread-safe: every public method hops onto the recognizer's own thread, and all callbacks are
 * delivered on that thread.
 */
class ContinuousRecognizer(private val apiKey: String, private val language: String) {
    /** Current hypothesis of the utterance in progress (prefix-drop repaired). */
    var onInterim: ((String) -> Unit)? = null
    /** A finalized utterance (prefix-drop repaired). */
    var onFinal: ((String) -> Unit)? = null
    /** Called once: after [finish] completes, or when the connection keeps failing. */
    var onEnd: ((Throwable?) -> Unit)? = null
    /** Diagnostics output. [verbose] adds every interim and final. */
    var log: (String) -> Unit = {}
    var verbose = false
    /** Development mode: save every session's audio as a WAV file in this directory. */
    var recordingDir: File? = null

    /** Testing only: make one session behave like a frozen one. */
    sealed class SimulatedFault {
        data class Silence(val session: Int, val afterSeconds: Double) : SimulatedFault()
        data class EmptyFinal(val session: Int) : SimulatedFault()
    }
    var simulatedFault: SimulatedFault? = null

    private enum class Phase { IDLE, RUNNING, FINISHING, DONE }

    private val thread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "recognizer").apply { isDaemon = true } }
    private var phase = Phase.IDLE
    private var stream: SpeechStream? = null
    private var repair = PrefixDropRepair()
    private var sessionNumber = 0
    private var sessionStart = 0L
    private var consecutiveFailures = 0

    /** The most recent audio, for pre-roll when switching sessions. */
    private val recent = AudioBuffer(8 * BYTES_PER_SECOND)

    // Watchdog state.
    /** All audio sent to the current session (one utterance), for replay if Google freezes. */
    private val sessionAudio = AudioBuffer(55 * BYTES_PER_SECOND)
    /** When Google last showed progress (new interim text), or the session opened. */
    private var lastProgress = 0L
    /** Seconds of speech heard since [lastProgress]. */
    private var speechSinceProgress = 0.0
    private var lastInterim = ""
    /** When the current session's interim text last changed (nanoTime): the finalized speech had been heard by then. */
    private var lastTextChange: Long? = null
    /** Pre-roll sent to the current session, in seconds. */
    private var sessionPreroll = 0.0
    /** The previous final's text, to catch a pre-roll that made Google finalize the same speech again. */
    private var previousFinal = ""
    /** Whether the current session's request has gone out; until then, silence from Google is expected. */
    private var connected = false
    /** Recoveries for the utterance in progress; reset once an utterance is finalized. */
    private var recoveries = 0
    private val recentLevels = ArrayDeque<Double>()
    private var watchdog: ScheduledFuture<*>? = null

    fun start() = thread.execute {
        if (phase != Phase.IDLE) return@execute
        phase = Phase.RUNNING
        openSession(ByteArray(0))
        watchdog = thread.scheduleAtFixedRate({ checkForStall() }, 500, 500, TimeUnit.MILLISECONDS)
    }

    /** 16 kHz / 16-bit little-endian / mono PCM. */
    fun send(pcm: ByteArray) = thread.execute {
        if (phase != Phase.RUNNING) return@execute
        recent.append(pcm)
        sessionAudio.append(pcm)
        if (isSpeech(pcm)) speechSinceProgress += pcm.size.toDouble() / BYTES_PER_SECOND
        stream?.send(pcm)
    }

    /** No more audio: let the current session deliver its last result, then call [onEnd]. */
    fun finish() = thread.execute {
        if (phase != Phase.RUNNING) return@execute
        phase = Phase.FINISHING
        stopWatchdog()
        stream?.finish()
    }

    fun cancel() = thread.execute {
        phase = Phase.DONE
        stopWatchdog()
        stream?.cancel()
        stream = null
    }

    // Sessions

    private fun openSession(preroll: ByteArray) {
        val s = SpeechStream(apiKey, language, thread)
        if (verbose) s.debug = log
        s.onConnected = connected@{
            if (stream !== s) return@connected
            // Audio queued while connecting is now on its way; start the freeze clock here.
            connected = true
            lastProgress = System.nanoTime()
            speechSinceProgress = 0.0
            log("session $sessionNumber connected after $sessionMillis ms")
        }
        sessionNumber += 1
        sessionStart = System.nanoTime()
        repair = PrefixDropRepair(log)
        sessionAudio.clear()
        sessionAudio.append(preroll)
        lastProgress = System.nanoTime()
        speechSinceProgress = 0.0
        lastInterim = ""
        connected = false
        recordingDir?.let { dir ->
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            s.recorder = runCatching { WavRecorder(File(dir, "$stamp-s$sessionNumber.wav")) }.getOrNull()
        }
        lastTextChange = null
        sessionPreroll = preroll.size.toDouble() / BYTES_PER_SECOND
        log("session $sessionNumber open preroll=${preroll.size * 1000 / BYTES_PER_SECOND} ms" +
            (s.recorder?.let { " recording=${it.file.name}" } ?: ""))

        s.onInterim = interim@{ text ->
            if (stream !== s || faultSilences()) return@interim
            consecutiveFailures = 0
            connected = true // results prove the session is connected
            if (verbose) log("  s$sessionNumber +$sessionMillis interim: $text")
            val trimmed = text.trim(' ')
            if (trimmed.isNotEmpty() && trimmed != lastInterim) {
                lastInterim = trimmed
                lastTextChange = System.nanoTime()
                lastProgress = System.nanoTime()
                speechSinceProgress = 0.0
            }
            onInterim?.invoke(repair.interim(text))
        }
        s.onFinal = final@{ raw ->
            if (stream !== s || faultSilences()) return@final
            consecutiveFailures = 0
            var text = raw
            val fault = simulatedFault
            if (fault is SimulatedFault.EmptyFinal && fault.session == sessionNumber) text = ""
            if (verbose) log("  s$sessionNumber +$sessionMillis FINAL: $text")
            if (text.isBlank() && lastInterim.isNotEmpty()) {
                // Google dropped an utterance it had been transcribing.
                if (phase == Phase.RUNNING && recoveries < MAX_RECOVERIES) {
                    recover("empty final after \"$lastInterim\"")
                    return@final
                }
                // Out of retries: keep what Google showed rather than lose it, unless it's a
                // single word, which is more likely noise.
                if (lastInterim.split(' ').size >= 2) {
                    log("using last interim instead of empty final")
                    text = lastInterim
                }
            }
            recoveries = 0
            val trimmed = text.trim(' ')
            if (trimmed.isNotEmpty() && trimmed == previousFinal && sessionSeconds < sessionPreroll + 1) {
                // Too soon to come from new speech: Google re-finalized the pre-roll. (Backstop for
                // the pre-roll limit in handOver; seen as "من راستش اینجا" typed 4 times.)
                log("dropped duplicate final \"$trimmed\" (came from the pre-roll)")
            } else {
                if (trimmed.isNotEmpty()) previousFinal = trimmed
                onFinal?.invoke(repair.final(text))
            }
            if (phase == Phase.RUNNING) handOver()
        }
        s.onEnd = end@{ error ->
            if (stream !== s) return@end
            sessionEnded(error)
        }
        stream = s
        s.start()
        if (preroll.isNotEmpty()) s.send(preroll)
    }

    /** Replace the current session with a fresh one for the next utterance. */
    private fun handOver() {
        val old = stream
        // Never reach back into the speech that was just finalized (see prerollAudio). Only after a
        // normal session: in a recovery session the replayed audio went out in a burst, so the timing of
        // its text changes says nothing about when the words were spoken (limiting there lost the next
        // utterance's start); the duplicate-final check covers that case.
        val limit = if (sessionPreroll <= 1.0) lastTextChange?.let { it + (FINALIZED_SPEECH_MARGIN * 1e9).toLong() } else null
        val preroll = prerollAudio(notBefore = limit)
        log("session $sessionNumber handover after %.1fs".format(Locale.US, sessionSeconds))
        openSession(preroll)
        // Results the old session might still send belong to audio the new one now covers.
        old?.finish()
    }

    private fun sessionEnded(error: Throwable?) {
        log("session $sessionNumber end after %.1fs error=%s".format(Locale.US, sessionSeconds, error ?: "none"))
        when (phase) {
            Phase.RUNNING -> {
                // Google closes sessions after long silence or about a minute of audio: reconnect.
                if (error != null) {
                    consecutiveFailures += 1
                    if (consecutiveFailures >= 3) {
                        phase = Phase.DONE
                        stopWatchdog()
                        stream = null
                        onEnd?.invoke(error)
                        return
                    }
                }
                openSession(prerollAudio())
            }
            Phase.FINISHING -> {
                phase = Phase.DONE
                stream = null
                onEnd?.invoke(error)
            }
            Phase.IDLE, Phase.DONE -> {}
        }
    }

    private val sessionSeconds get() = (System.nanoTime() - sessionStart) / 1e9
    private val sessionMillis get() = (System.nanoTime() - sessionStart) / 1_000_000

    // Watchdog

    private fun faultSilences(): Boolean {
        val fault = simulatedFault as? SimulatedFault.Silence ?: return false
        return fault.session == sessionNumber && sessionSeconds >= fault.afterSeconds
    }

    private fun stopWatchdog() {
        watchdog?.cancel(false)
        watchdog = null
    }

    private fun checkForStall() {
        if (phase != Phase.RUNNING) return
        if (!connected) {
            // A new connection through a VPN sometimes stalls for many seconds; a fresh attempt is
            // usually quick. The queued audio is replayed, so nothing is lost.
            if (sessionSeconds >= CONNECT_STALL_SECONDS) {
                if (recoveries < MAX_RECOVERIES) recover("not connected after %.1fs".format(Locale.US, sessionSeconds))
                else { recoveries = 0; recover("still not connected after %.1fs".format(Locale.US, sessionSeconds)) }
            }
            return
        }
        val sinceProgress = (System.nanoTime() - lastProgress) / 1e9
        if (phase != Phase.RUNNING || sinceProgress < STALL_SECONDS || speechSinceProgress < STALL_SPEECH_SECONDS) return
        if (recoveries < MAX_RECOVERIES) {
            recover("no results for %.1fs during %.1fs of speech".format(Locale.US, sinceProgress, speechSinceProgress))
        } else {
            // Replaying didn't help: give up on this utterance's audio and start clean.
            log("still frozen after $recoveries replays; starting a fresh session")
            recoveries = 0
            val old = stream
            openSession(prerollAudio())
            old?.cancel()
        }
    }

    /** Replace a frozen session with a fresh one and replay the whole utterance into it. */
    private fun recover(reason: String) {
        recoveries += 1
        val audio = sessionAudio.toByteArray()
        log("session $sessionNumber frozen (%s): replaying %.1fs into a new session (attempt %d)"
            .format(Locale.US, reason, audio.size.toDouble() / BYTES_PER_SECOND, recoveries))
        val old = stream
        openSession(audio)
        old?.cancel()
    }

    /** Rough voice activity check for one chunk, relative to the recent noise floor. */
    private fun isSpeech(pcm: ByteArray): Boolean {
        val level = rms(pcm, 0, pcm.size)
        recentLevels.addLast(level)
        if (recentLevels.size > 30) recentLevels.removeFirst()
        val noiseFloor = recentLevels.minOrNull() ?: 0.0
        return level > max(noiseFloor * 3, 150.0)
    }

    // Pre-roll

    /**
     * Recent audio starting a little before the speech that is in progress now (if any), so the
     * next session hears the whole next utterance but nothing of the one just finalized.
     */
    private fun prerollAudio(notBefore: Long? = null): ByteArray {
        val audio = prerollFromSilence()
        if (notBefore == null) return audio
        // Hard limit after a final: clicks or breaths after the finalized utterance can break up the
        // silence the walk-back looks for, and it then crossed the whole utterance (typed 4 times).
        val frameBytes = BYTES_PER_SECOND / 50
        val maxBytes = maxOf(0, ((System.nanoTime() - notBefore) / 20_000_000L).toInt()) * frameBytes
        if (audio.size <= maxBytes) return audio
        log("pre-roll limited from ${audio.size * 1000 / BYTES_PER_SECOND} to ${maxBytes * 1000 / BYTES_PER_SECOND} ms (keeps out the finalized speech)")
        return audio.copyOfRange(audio.size - maxBytes, audio.size)
    }

    private fun prerollFromSilence(): ByteArray {
        val audio = recent.toByteArray()
        val frameBytes = BYTES_PER_SECOND / 50 // 20 ms
        val frameCount = audio.size / frameBytes
        if (frameCount == 0) return ByteArray(0)
        val levels = DoubleArray(frameCount) { f -> rms(audio, f * frameBytes, frameBytes) }
        val noiseFloor = levels.sorted()[levels.size / 5]
        val threshold = max(noiseFloor * 3, 150.0)

        // Walk back over any speech after the finalized utterance (pauses inside it included) until
        // we reach the gap that ended that utterance. Google only finalizes after more than a second
        // of silence (measured on the Mac: at least 1.24 s across 78 finals), so shorter pauses are not it.
        val gapFrames = 40 // 800 ms
        var silentRun = 0
        var gapEnd = -1 // first frame after the gap, where the current speech starts
        for (f in frameCount - 1 downTo 0) {
            if (levels[f] <= threshold) {
                silentRun += 1
                if (silentRun == gapFrames) { gapEnd = f + gapFrames; break }
            } else {
                silentRun = 0
            }
        }
        // No gap in the buffer: fall back to the last second.
        if (gapEnd < 0) return audio.copyOfRange(max(0, audio.size - 50 * frameBytes), audio.size)
        // Start 600 ms into the gap: some silence before the speech, none of the previous speech.
        return audio.copyOfRange((gapEnd - 30) * frameBytes, audio.size)
    }

    companion object {
        const val BYTES_PER_SECOND = SpeechStream.SAMPLE_RATE * 2
        /** Speech but no progress for this long counts as a freeze. */
        private const val STALL_SECONDS = 3.0
        private const val STALL_SPEECH_SECONDS = 0.8
        private const val CONNECT_STALL_SECONDS = 4.0
        /** The finalized speech ends at most this long after its last interim text change. */
        private const val FINALIZED_SPEECH_MARGIN = 0.25
        private const val MAX_RECOVERIES = 2

        /** RMS of 16-bit little-endian samples in `pcm[offset, offset + length)`. */
        fun rms(pcm: ByteArray, offset: Int, length: Int): Double {
            val samples = length / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            var i = offset
            repeat(samples) {
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
                sum += v * v
                i += 2
            }
            return sqrt(sum / samples)
        }
    }
}

/** A byte buffer that keeps only the newest [capacity] bytes. */
class AudioBuffer(private val capacity: Int) {
    private var buf = ByteArray(capacity * 2)
    private var start = 0
    private var end = 0

    fun append(bytes: ByteArray) {
        if (end + bytes.size > buf.size) {
            // Shift the kept bytes to the front; grow only if a single append is huge.
            val length = end - start
            System.arraycopy(buf, start, buf, 0, length)
            start = 0
            end = length
            if (end + bytes.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, end + bytes.size))
        }
        System.arraycopy(bytes, 0, buf, end, bytes.size)
        end += bytes.size
        if (end - start > capacity) start = end - capacity
    }

    fun clear() {
        start = 0
        end = 0
    }

    fun toByteArray(): ByteArray = buf.copyOfRange(start, end)
}

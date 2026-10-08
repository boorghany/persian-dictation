package local.voice2text.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Client for Google's full-duplex streaming speech endpoint, the same one Chrome's
 * Web Speech API (`webkitSpeechRecognition`) talks to. Port of the Mac app's SpeechStream.swift.
 *
 * One session = one random `pair` id shared by two HTTPS requests:
 *  - upstream:   chunked POST of raw 16 kHz / 16-bit / mono PCM (`audio/l16`)
 *  - downstream: GET returning 4-byte big-endian length-prefixed `SpeechRecognitionEvent` protobufs.
 *
 * Both go over one shared HTTP/2 connection, like Apple's URLSession does on the Mac:
 *  - The downstream request must reach Google before the upstream one, or the session silently
 *    returns nothing. On a shared connection, starting the upstream only after the downstream's
 *    headers were written guarantees that order.
 *  - Setting up a new TLS connection through a VPN sometimes stalls for many seconds; reusing one
 *    connection for every session avoids paying that again and again.
 *
 * Callbacks are delivered on [callbacks].
 */
class SpeechStream(
    private val apiKey: String,
    private val language: String,
    private val callbacks: Executor,
) {
    /** The current (non-final) hypothesis whenever it changes. */
    var onInterim: ((String) -> Unit)? = null
    /** Once per finalized utterance. */
    var onFinal: ((String) -> Unit)? = null
    /** When the downstream request has gone out, i.e. the session is connected. */
    var onConnected: (() -> Unit)? = null
    /** When the session is over, with an error if it failed. */
    var onEnd: ((Throwable?) -> Unit)? = null
    /** Development mode: receives a copy of every byte uploaded in this session. */
    var recorder: WavRecorder? = null
    /** Connection-level diagnostics. */
    var debug: ((String) -> Unit)? = null

    private val pair = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val created = System.nanoTime()
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val downstreamSent = CountDownLatch(1)
    @Volatile private var closedForWrites = false
    @Volatile private var ended = false
    /** Guards `onEnd`: it fires at most once, and never after `cancel()`. */
    private var finished = false
    @Volatile private var downCall: Call? = null
    @Volatile private var upCall: Call? = null

    fun start() {
        Thread({ runDownstream() }, "speech-down-$pair").apply { isDaemon = true }.start()
        Thread({ runUpstream() }, "speech-up-$pair").apply { isDaemon = true }.start()
    }

    /** Queue PCM audio (16 kHz, Int16 little-endian, mono) for upload. */
    fun send(pcm: ByteArray) {
        if (closedForWrites || pcm.isEmpty()) return
        recorder?.append(pcm)
        queue.put(pcm)
    }

    /** Stop sending audio. The server then returns the last final result and closes the downstream. */
    fun finish() {
        if (closedForWrites) return
        closedForWrites = true
        queue.put(END_OF_AUDIO)
        recorder?.close()
    }

    fun cancel() {
        synchronized(this) { finished = true }
        ended = true
        finish()
        downstreamSent.countDown()
        downCall?.cancel()
        upCall?.cancel()
    }

    // Downstream: length-prefixed protobuf events.

    private fun runDownstream() {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/down?" + params("key" to apiKey, "pair" to pair, "output" to "pb"))
                .build()
            val client = HTTP.newBuilder().eventListener(object : EventListener() {
                override fun requestHeadersEnd(call: Call, request: Request) {
                    trace("down request sent")
                    downstreamSent.countDown()
                    callbacks.execute { onConnected?.invoke() }
                }
            }).build()
            val call = client.newCall(request)
            downCall = call
            if (ended) return
            call.execute().use { response ->
                trace("down response ${response.code} ${response.protocol}")
                if (response.code != 200) {
                    finishSession(SpeechException("Google speech server returned HTTP ${response.code}"))
                    return
                }
                DataInputStream(response.body!!.byteStream()).use { input ->
                    while (!ended) {
                        val length = try { input.readInt() } catch (e: EOFException) { break }
                        val message = ByteArray(length)
                        input.readFully(message)
                        handleEvent(message)
                    }
                }
            }
            finishSession(null)
        } catch (e: Exception) {
            downstreamSent.countDown()
            if (!ended) finishSession(e)
        }
    }

    // Upstream: streamed POST of the audio.

    private fun runUpstream() {
        try {
            if (!downstreamSent.await(30, TimeUnit.SECONDS) || ended) return
            val query = params("key" to apiKey, "pair" to pair, "output" to "pb", "lang" to language,
                               "pFilter" to "0", "app" to "chromium") + "&continuous&interim" // bare flags, as Chrome sends them
            var sent = 0
            val body = object : RequestBody() {
                override fun contentType() = AUDIO_TYPE
                override fun writeTo(sink: BufferedSink) {
                    while (true) {
                        val chunk = queue.take()
                        if (chunk === END_OF_AUDIO) break
                        sink.write(chunk)
                        sink.flush()
                        sent += chunk.size
                    }
                }
            }
            val call = HTTP.newCall(Request.Builder().url("$BASE_URL/up?$query").post(body).build())
            upCall = call
            if (ended) return
            call.execute().use { response ->
                trace("up response ${response.code} after $sent bytes")
                if (response.code != 200) finishSession(SpeechException("Google speech server returned HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            if (!ended) finishSession(e)
        }
    }

    @Synchronized
    private fun finishSession(error: Throwable?) {
        if (finished) return
        finished = true
        ended = true
        queue.put(END_OF_AUDIO)
        upCall?.cancel()
        downCall?.cancel()
        recorder?.close()
        callbacks.execute { onEnd?.invoke(error) }
    }

    // SpeechRecognitionEvent { 1: status (enum, 0 = success)  2: repeated SpeechRecognitionResult }
    // SpeechRecognitionResult { 1: repeated Alternative  2: final (bool)  3: stability (float) }
    // Alternative { 1: transcript (string)  2: confidence (float) }
    private fun handleEvent(message: ByteArray) {
        val interim = StringBuilder()
        for (field in ProtoReader.fields(message)) {
            if (field.number == 1 && field.varint != null && field.varint != 0L) {
                finishSession(SpeechException("Google speech server error (status ${field.varint})"))
                return
            }
            val result = field.bytes ?: continue
            if (field.number != 2) continue
            var transcript = ""
            var isFinal = false
            for (f in ProtoReader.fields(result)) {
                if (f.number == 1 && f.bytes != null && transcript.isEmpty()) {
                    for (a in ProtoReader.fields(f.bytes)) {
                        if (a.number == 1 && a.bytes != null) transcript = String(a.bytes, Charsets.UTF_8)
                    }
                } else if (f.number == 2 && f.varint != null) {
                    isFinal = f.varint != 0L
                }
            }
            if (isFinal) {
                val text = transcript.trim(' ')
                callbacks.execute { onFinal?.invoke(text) }
            } else {
                interim.append(transcript)
            }
        }
        val text = interim.toString()
        callbacks.execute { onInterim?.invoke(text) }
    }

    private fun trace(message: String) =
        debug?.invoke("[%s +%dms] %s".format(pair.take(4), (System.nanoTime() - created) / 1_000_000, message))

    private fun params(vararg items: Pair<String, String>) =
        items.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    companion object {
        const val BASE_URL = "https://www.google.com/speech-api/full-duplex/v1"
        const val SAMPLE_RATE = 16_000
        /** The public Chromium key that Chrome builds use for Web Speech. */
        const val DEFAULT_API_KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw"
        private val END_OF_AUDIO = ByteArray(0)
        private val AUDIO_TYPE = "audio/l16; rate=$SAMPLE_RATE".toMediaType()

        /** One client for the whole app, so every session shares the same HTTP/2 connection. */
        private val HTTP: OkHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS) // notice a dead connection instead of hanging on it
            .build()

    }
}

class SpeechException(message: String) : IOException(message)

/** Minimal protobuf wire-format reader: enough for the handful of fields we need. */
object ProtoReader {
    class Field(val number: Int, val varint: Long? = null, val bytes: ByteArray? = null)

    fun fields(data: ByteArray): List<Field> {
        val result = mutableListOf<Field>()
        var i = 0
        fun varint(): Long? {
            var shift = 0
            var value = 0L
            while (i < data.size) {
                val b = data[i++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return value
                shift += 7
                if (shift > 63) return null
            }
            return null
        }
        while (i < data.size) {
            val key = varint() ?: break
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> result += Field(number, varint = varint() ?: return result)
                1 -> { if (i + 8 > data.size) return result; i += 8 }
                2 -> {
                    val n = varint()?.toInt() ?: return result
                    if (n < 0 || i + n > data.size) return result
                    result += Field(number, bytes = data.copyOfRange(i, i + n))
                    i += n
                }
                5 -> { if (i + 4 > data.size) return result; i += 4 }
                else -> return result
            }
        }
        return result
    }
}

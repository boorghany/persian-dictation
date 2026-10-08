package local.voice2text.replay

import java.io.File
import java.util.Locale
import kotlin.system.exitProcess
import local.voice2text.core.ContinuousRecognizer
import local.voice2text.core.SpeechStream

/**
 * Streams a 16 kHz / 16-bit / mono recording (WAV or raw PCM) through the Android app's recognizer
 * in real time and prints the results, so the shared engine can be tested on the Mac.
 *
 * Usage: replay <file.wav|file.raw> [lang] [--simulate-freeze silence:<session>:<seconds> | empty-final:<session>]
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: replay <file.wav|file.raw> [lang] [--simulate-freeze silence:N:S|empty-final:N]")
        exitProcess(2)
    }
    var pcm = File(args[0]).readBytes()
    if (pcm.size >= 4 && String(pcm, 0, 4) == "RIFF") pcm = pcm.copyOfRange(44, pcm.size) // our recordings use a 44-byte header
    val language = args.getOrNull(1)?.takeUnless { it.startsWith("--") } ?: "fa-IR"

    val start = System.nanoTime()
    fun stamp() = "[%.2fs]".format(Locale.US, (System.nanoTime() - start) / 1e9)
    val done = java.util.concurrent.CountDownLatch(1)
    var exitCode = 0

    val recognizer = ContinuousRecognizer(SpeechStream.DEFAULT_API_KEY, language)
    recognizer.log = { println("${stamp()} $it") }
    recognizer.verbose = "--verbose" in args
    val faultIndex = args.indexOf("--simulate-freeze")
    if (faultIndex >= 0 && faultIndex + 1 < args.size) {
        val parts = args[faultIndex + 1].split(":")
        recognizer.simulatedFault = when {
            parts[0] == "silence" && parts.size == 3 -> ContinuousRecognizer.SimulatedFault.Silence(parts[1].toInt(), parts[2].toDouble())
            parts[0] == "empty-final" && parts.size == 2 -> ContinuousRecognizer.SimulatedFault.EmptyFinal(parts[1].toInt())
            else -> null
        }
    }
    recognizer.onInterim = { if (it.isNotEmpty()) println("${stamp()} interim: $it") }
    recognizer.onFinal = { println("${stamp()} FINAL: $it") }
    recognizer.onEnd = { error ->
        println("end " + (error?.let { "error: ${it.message}" } ?: "ok"))
        if (error != null) exitCode = 1
        done.countDown()
    }
    recognizer.start()

    // Feed the file in real time (100 ms chunks), like a microphone.
    val chunk = 3200
    var offset = 0
    val t0 = System.nanoTime()
    while (offset < pcm.size) {
        recognizer.send(pcm.copyOfRange(offset, minOf(offset + chunk, pcm.size)))
        offset += chunk
        val due = t0 + (offset.toLong() * 1_000_000_000L / ContinuousRecognizer.BYTES_PER_SECOND)
        val wait = due - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }
    recognizer.finish()
    done.await()
    exitProcess(exitCode)
}

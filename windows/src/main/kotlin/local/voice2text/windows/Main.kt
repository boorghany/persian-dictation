package local.voice2text.windows

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess
import local.voice2text.core.ContinuousRecognizer
import local.voice2text.core.SpeechStream

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--write-ico" -> { AppIcon.writeIco(File(args[1])); return }
        "--transcribe" -> transcribe(args.drop(1))
        "--toggle" -> { exitProcess(if (signalRunningInstance(WinThread.WM_APP_TOGGLE)) 0 else 1) }
        // `@file`: UTF-8 text from a file (Windows passes non-ASCII arguments through the ANSI code page).
        "--paste-test" -> {
            val text = args[1].let { if (it.startsWith("@")) File(it.drop(1)).readText().trimEnd('\r', '\n') else it }
            exitProcess(if (sendPasteTest(text)) 0 else 1)
        }
        "--export-diagnostics" -> { println(DiagnosticsExport.export().path); return }
    }

    // One instance: a second launch tells the first one (it shows a notification) and quits.
    AppPaths.data.mkdirs()
    val lock = RandomAccessFile(AppPaths.lock, "rw").channel.tryLock()
    if (lock == null) {
        signalRunningInstance(WinThread.WM_APP_ALREADY_RUNNING)
        exitProcess(0)
    }

    Thread.setDefaultUncaughtExceptionHandler { t, e -> Log.write("uncaught on ${t.name}: $e\n${e.stackTraceToString()}") }
    Log.write(
        "launch version=\"${AppVersion.display}\" developmentMode=${Settings.developmentMode} " +
            "os=\"${System.getProperty("os.name")} ${System.getProperty("os.version")}\" arch=${System.getProperty("os.arch")} " +
            "java=${System.getProperty("java.version")} exe=${System.getProperty("jpackage.app-path") ?: "-"}",
    )
    App.pruneRecordings()
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
        Log.write("no notification area; quitting")
        JOptionPane.showMessageDialog(null, "Persian Dictation needs the Windows notification area.")
        exitProcess(1)
    }
    WinThread.start()
    SwingUtilities.invokeLater {
        try {
            App().start()
        } catch (e: Throwable) {
            Log.write("start failed: $e\n${e.stackTraceToString()}")
            JOptionPane.showMessageDialog(null, "Persian Dictation could not start: $e")
            exitProcess(1)
        }
    }
}

/** Posts [message] to the running instance's hidden window. */
private fun signalRunningInstance(message: Int): Boolean {
    val user32 = User32.INSTANCE
    val messageOnly = HWND(com.sun.jna.Pointer.createConstant(-3L))
    val hwnd = user32.FindWindowEx(messageOnly, null, WinThread.CLASS_NAME, null) ?: return false
    return user32.PostMessage(hwnd, message, WPARAM(0), LPARAM(0)).let { true }
}

/** Test hook: the running instance pastes [text] as if it were a final from Google (spoken punctuation included). */
private fun sendPasteTest(text: String): Boolean {
    val user32 = User32.INSTANCE
    val hwnd = user32.FindWindowEx(HWND(com.sun.jna.Pointer.createConstant(-3L)), null, WinThread.CLASS_NAME, null) ?: return false
    val bytes = text.toByteArray(Charsets.UTF_8)
    val mem = com.sun.jna.Memory(maxOf(1, bytes.size).toLong()).apply { write(0, bytes, 0, bytes.size) }
    val data = com.sun.jna.platform.win32.WinUser.COPYDATASTRUCT()
    data.dwData = com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(WinThread.COPYDATA_PASTE_TEST.toLong())
    data.cbData = bytes.size
    data.lpData = mem
    data.write()
    user32.SendMessage(hwnd, 0x004A, WPARAM(0), LPARAM(com.sun.jna.Pointer.nativeValue(data.pointer)))
    return true
}

/**
 * `--transcribe <wav> [lang]`: streams a recording through the engine in real time and prints the
 * results (like the Mac app's `--transcribe` and the `replay` tool), to test the engine on Windows.
 */
private fun transcribe(args: List<String>) {
    var pcm = File(args[0]).readBytes()
    if (pcm.size >= 4 && String(pcm, 0, 4) == "RIFF") pcm = pcm.copyOfRange(44, pcm.size)
    val language = args.getOrNull(1) ?: "fa-IR"
    val start = System.nanoTime()
    fun stamp() = "[%.2fs]".format(Locale.US, (System.nanoTime() - start) / 1e9)
    val done = CountDownLatch(1)
    var exitCode = 0
    val r = ContinuousRecognizer(SpeechStream.DEFAULT_API_KEY, language)
    r.log = { println("${stamp()} $it") }
    r.onInterim = { if (it.isNotEmpty()) println("${stamp()} interim: $it") }
    r.onFinal = { println("${stamp()} FINAL: $it") }
    r.onEnd = { e ->
        println("end " + (e?.let { "error: ${it.message}" } ?: "ok"))
        if (e != null) exitCode = 1
        done.countDown()
    }
    r.start()
    var offset = 0
    val t0 = System.nanoTime()
    while (offset < pcm.size) {
        r.send(pcm.copyOfRange(offset, minOf(offset + MicCapture.CHUNK, pcm.size)))
        offset += MicCapture.CHUNK
        val wait = t0 + offset.toLong() * 1_000_000_000L / ContinuousRecognizer.BYTES_PER_SECOND - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000)
    }
    r.finish()
    done.await()
    exitProcess(exitCode)
}

/**
 * Menu "Share Diagnostics…": info.txt + logs + the last 48 h of recordings in one zip in Downloads.
 * Like the Mac and Android apps.
 */
object DiagnosticsExport {
    fun export(): File {
        val stamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().replace(":", "-")
        val downloads = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
        val zip = File(downloads, "PersianDictation-diagnostics-$stamp.zip")
        val cutoff = System.currentTimeMillis() - 48 * 3_600_000L
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("info.txt"))
            out.write(info().toByteArray())
            out.closeEntry()
            val files = (AppPaths.data.listFiles()?.filter { it.isFile && it.name.endsWith(".log") } ?: emptyList()) +
                (AppPaths.recordings.listFiles()?.filter { it.lastModified() >= cutoff } ?: emptyList())
            for (f in files) {
                out.putNextEntry(ZipEntry(f.relativeTo(AppPaths.data).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        Log.write("diagnostics exported: ${zip.name} (${zip.length() / 1024} KB)")
        return zip
    }

    private fun info(): String = buildString {
        appendLine("Persian Dictation ${AppVersion.display} (Windows)")
        appendLine("Created: ${Instant.now()}")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        appendLine("Java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
        appendLine("Executable: ${System.getProperty("jpackage.app-path") ?: "-"}")
        appendLine("Language: ${Settings.language}")
        appendLine("Development mode: ${Settings.developmentMode}")
        appendLine("Microphone choice: ${Settings.microphone ?: "System Default"}")
        appendLine("Microphones: ${MicCapture.microphones().joinToString(", ") { it.name }}")
        appendLine("Start with Windows: ${Startup.enabled}")
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEach {
                val b = it.defaultConfiguration.bounds
                appendLine("Display: ${it.iDstring} ${b.width}x${b.height} at ${b.x},${b.y} scale=${it.defaultConfiguration.defaultTransform.scaleX}")
            }
        }
    }
}

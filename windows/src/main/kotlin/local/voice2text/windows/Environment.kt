package local.voice2text.windows

import java.io.File
import java.time.Instant
import java.util.Properties
import local.voice2text.core.SpeechStream

/** Where the app keeps its files. Settings roam (%APPDATA%), logs and recordings don't (%LOCALAPPDATA%). */
object AppPaths {
    private val home = System.getProperty("user.home")
    val config = File(System.getenv("APPDATA") ?: home, "Persian Dictation")
    val data = File(System.getenv("LOCALAPPDATA") ?: home, "Persian Dictation")
    val log = File(data, "PersianDictation.log")
    val recordings = File(data, "recordings")
    val lock = File(data, "app.lock")

    /** Test hook (like the Android app): if this file exists, it is played instead of the microphone. */
    val testInput = File(data, "test-input.wav")
}

object AppVersion {
    private val props = Properties().apply {
        AppVersion::class.java.getResourceAsStream("/persiandictation-version.properties")?.use { load(it) }
    }
    val version: String = props.getProperty("version", "?")
    val build: String = props.getProperty("build", "?")
    /** e.g. "1.0.3 (build 4)", the same format as the Mac menu. */
    val display = "$version (build $build)"
}

/** Persisted in %APPDATA%\Persian Dictation\settings.properties. EDT only, apart from reads. */
object Settings {
    private val file = File(AppPaths.config, "settings.properties")
    private val props = Properties().apply {
        if (file.exists()) runCatching { file.inputStream().use { load(it) } }
    }

    private fun save() {
        runCatching {
            AppPaths.config.mkdirs()
            file.outputStream().use { props.store(it, "Persian Dictation") }
        }.onFailure { Log.write("could not save settings: $it") }
    }

    private fun set(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        save()
    }

    val apiKey: String get() = props.getProperty("apiKey") ?: SpeechStream.DEFAULT_API_KEY

    var language: String
        get() = props.getProperty("language") ?: "fa-IR"
        set(v) = set("language", v)

    /** Verbose log (with the dictated text) plus a WAV recording of every session. Off by default. */
    @Volatile var developmentMode: Boolean = props.getProperty("developmentMode") == "true"
        set(v) { field = v; set("developmentMode", v.toString()) }

    /** The chosen microphone's name, or null to follow the Windows default input. */
    var microphone: String?
        get() = props.getProperty("microphone")
        set(v) = set("microphone", v)

    var welcomeShown: Boolean
        get() = props.getProperty("welcomeShown") == "true"
        set(v) = set("welcomeShown", v.toString())
}

/** Appends to %LOCALAPPDATA%\Persian Dictation\PersianDictation.log. Dictated text only in development mode. */
object Log {
    private val pid = ProcessHandle.current().pid()

    fun verbose(message: () -> String) {
        if (Settings.developmentMode) write(message())
    }

    @Synchronized
    fun write(message: String) {
        runCatching {
            AppPaths.data.mkdirs()
            if (AppPaths.log.length() > 5_000_000) {
                val old = File(AppPaths.data, "PersianDictation.old.log")
                old.delete()
                AppPaths.log.renameTo(old)
            }
            AppPaths.log.appendText("${Instant.now()} [$pid] $message\n")
        }
    }
}

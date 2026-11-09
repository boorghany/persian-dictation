package local.voice2text.windows

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import local.voice2text.core.WavRecorder

/** Notification-area icon, its menu and the global hotkeys. EDT only. Port of the Mac `AppDelegate`. */
class App {
    private val controller = DictationController()
    private var toggleRegistered = false
    private var menuOpen = false
    private val languages = listOf("Persian (fa-IR)" to "fa-IR", "English (en-US)" to "en-US")

    fun start() {
        controller.onStateChange = { state ->
            // Escape and Enter stop dictation (pasting what was said). Registered only while
            // listening, so these keys behave normally otherwise. Enter stays registered while
            // finishing: pressed again then, it is sent to the app after the last words are pasted.
            when (state) {
                DictationController.State.LISTENING -> {
                    WinThread.registerHotKey(ID_ESCAPE, 0, WinThread.VK_ESCAPE) { controller.stopKeyPressed(enter = false) }
                    registerEnter()
                }
                DictationController.State.FINISHING -> WinThread.unregisterHotKey(ID_ESCAPE)
                DictationController.State.IDLE -> {
                    WinThread.unregisterHotKey(ID_ESCAPE)
                    WinThread.unregisterHotKey(ID_ENTER)
                }
            }
            updateIcon()
        }
        // A spoken "اینتر" presses Return in the target app: our own Enter hotkey must not catch it.
        // Called on the Win32 thread (WinThread.sync runs inline there).
        controller.onReturnPassThrough = { passThrough ->
            if (passThrough) WinThread.unregisterHotKey(ID_ENTER)
            else if (controller.state != DictationController.State.IDLE) registerEnter()
        }
        WinThread.onToggleRequest = { controller.toggle() }
        WinThread.onQuitRequest = { quit() }
        WinThread.onPasteTest = { controller.testFinal(it) }
        WinThread.onSecondLaunch = { Tray.notify("Persian Dictation is already running", "Press Ctrl+Alt+Space to dictate.") }
        WinThread.onTrayEvent = { event, x, y ->
            if (event == Tray.EVENT_CONTEXTMENU || event == Tray.EVENT_SELECT || event == Tray.EVENT_KEYSELECT) showMenu(x, y)
        }
        WinThread.onTaskbarCreated = { Tray.add(iconImage(), tooltip()) }
        WinThread.onThemeChanged = { updateIcon() }

        // Keep the startup entry pointing at this copy if the folder was moved.
        if (Startup.enabled) Startup.enabled = true
        Tray.add(iconImage(), tooltip())

        // Ctrl+Alt+Space, like the Mac's ⌃⌥Space.
        toggleRegistered = WinThread.registerHotKey(ID_TOGGLE, WinThread.MOD_CONTROL or WinThread.MOD_ALT, WinThread.VK_SPACE) {
            controller.toggle()
        }
        Log.write("hotkey Ctrl+Alt+Space registered=$toggleRegistered")
        if (!toggleRegistered) {
            Tray.notify("Ctrl+Alt+Space is taken",
                "Another app uses Ctrl+Alt+Space. Start dictation from the Persian Dictation icon instead.", Tray.WARNING)
        } else if (!Settings.welcomeShown) {
            Settings.welcomeShown = true
            Tray.notify("Persian Dictation is running",
                "Press Ctrl+Alt+Space to dictate into any app; Esc or Enter stops. Click the microphone icon for the menu.")
        }
    }

    private fun registerEnter() {
        WinThread.registerHotKey(ID_ENTER, 0, WinThread.VK_RETURN) { controller.stopKeyPressed(enter = true) }
    }

    // Icon

    private fun iconImage(): BufferedImage =
        AppIcon.trayBitmap(Tray.iconSize(), controller.state != DictationController.State.IDLE, lightTaskbar())

    private fun tooltip() = "Persian Dictation — " + when (controller.state) {
        DictationController.State.IDLE -> if (toggleRegistered) "Ctrl+Alt+Space to dictate" else "Ready"
        DictationController.State.LISTENING -> "Listening…"
        DictationController.State.FINISHING -> "Finishing…"
    }

    private fun updateIcon() = Tray.update(iconImage(), tooltip())

    // Menu

    private fun showMenu(x: Int, y: Int) {
        if (menuOpen) return
        menuOpen = true
        val entries = menuEntries()
        WinThread.post {
            val command = Tray.showMenu(entries, x, y)
            SwingUtilities.invokeLater {
                menuOpen = false
                if (command != 0) runCommand(command)
            }
        }
    }

    private fun menuEntries(): List<MenuEntry> {
        val listening = controller.state != DictationController.State.IDLE
        val toggle = when (controller.state) {
            DictationController.State.IDLE -> "Start Dictation\tCtrl+Alt+Space"
            DictationController.State.LISTENING -> "Stop Dictation\tEsc"
            DictationController.State.FINISHING -> "Finishing…"
        }
        val choice = Settings.microphone
        val devices = MicCapture.microphones().map { it.name }
        micNames = devices
        val microphones = buildList {
            add(MenuEntry.Item(CMD_MIC_DEFAULT, "System Default", checked = choice == null))
            if (devices.isNotEmpty()) add(MenuEntry.Separator)
            devices.forEachIndexed { i, name -> add(MenuEntry.Item(CMD_MIC_FIRST + i, name, checked = name == choice)) }
            if (devices.isEmpty()) add(MenuEntry.Item(0, "No microphone found", enabled = false))
            if (choice != null && choice !in devices) {
                add(MenuEntry.Separator)
                add(MenuEntry.Item(0, "$choice (not connected — using the default)", enabled = false))
            }
        }
        return buildList {
            add(MenuEntry.Item(0, "Persian Dictation ${AppVersion.display}", enabled = false))
            add(MenuEntry.Separator)
            add(MenuEntry.Item(CMD_TOGGLE, toggle, enabled = controller.state != DictationController.State.FINISHING, isDefault = true))
            add(MenuEntry.Separator)
            add(MenuEntry.Submenu("Language: ${languages.first { it.second == Settings.language }.first}",
                languages.mapIndexed { i, (title, code) -> MenuEntry.Item(CMD_LANGUAGE_FIRST + i, title, checked = code == Settings.language) }))
            add(MenuEntry.Submenu("Microphone: ${choice ?: "System Default"}", microphones))
            add(MenuEntry.Separator)
            if (Startup.available) add(MenuEntry.Item(CMD_STARTUP, "Start with Windows", checked = Startup.enabled))
            add(MenuEntry.Item(CMD_DEVELOPMENT, "Development Mode (log + record audio)", checked = Settings.developmentMode, enabled = !listening))
            add(MenuEntry.Item(CMD_LOGS, "Open Logs Folder"))
            add(MenuEntry.Item(CMD_DIAGNOSTICS, "Share Diagnostics…"))
            add(MenuEntry.Separator)
            add(MenuEntry.Item(CMD_QUIT, "Quit Persian Dictation"))
        }
    }

    private var micNames: List<String> = emptyList()

    private fun runCommand(command: Int) {
        when (command) {
            CMD_TOGGLE -> {
                // The menu took the focus; give it back to the user's app before dictating into it.
                if (controller.state == DictationController.State.IDLE) WinThread.restoreForeground()
                controller.toggle()
            }
            CMD_MIC_DEFAULT -> selectMicrophone(null)
            in CMD_MIC_FIRST until CMD_MIC_FIRST + 100 -> micNames.getOrNull(command - CMD_MIC_FIRST)?.let { selectMicrophone(it) }
            in CMD_LANGUAGE_FIRST until CMD_LANGUAGE_FIRST + languages.size -> {
                Settings.language = languages[command - CMD_LANGUAGE_FIRST].second
                Log.write("language=${Settings.language}")
            }
            CMD_STARTUP -> Startup.enabled = !Startup.enabled
            CMD_DEVELOPMENT -> toggleDevelopmentMode()
            CMD_LOGS -> {
                AppPaths.data.mkdirs()
                Runtime.getRuntime().exec(arrayOf("explorer.exe", AppPaths.data.path))
            }
            CMD_DIAGNOSTICS -> shareDiagnostics()
            CMD_QUIT -> quit()
        }
    }

    private fun selectMicrophone(name: String?) {
        Settings.microphone = name
        Log.write("microphone choice: ${name ?: "System Default"}")
        controller.microphonePreferenceChanged()
    }

    private fun toggleDevelopmentMode() {
        if (Settings.developmentMode) {
            Settings.developmentMode = false
            Log.write("developmentMode=false")
            return
        }
        // The message box has its own message loop; keep it off the EDT and the Win32 thread.
        Thread {
            val ok = Tray.confirm(
                "Turn on Development Mode?",
                "Development Mode saves a recording of everything you dictate and the recognized text in " +
                    "${AppPaths.data} (kept for 7 days). This may include private or confidential information.\n\n" +
                    "Use it only for troubleshooting.",
            )
            if (ok) SwingUtilities.invokeLater {
                Settings.developmentMode = true
                Log.write("developmentMode=true")
            }
        }.start()
    }

    private fun shareDiagnostics() {
        Thread {
            try {
                val zip = DiagnosticsExport.export()
                Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", zip.path))
            } catch (e: Exception) {
                Log.write("diagnostics export failed: $e")
                Tray.notify("Could not create the diagnostics file", e.message ?: e.toString(), Tray.ERROR)
            }
        }.start()
    }

    private fun quit() {
        Log.write("quit")
        controller.shutdown()
        Tray.remove()
        // Hand over any paste text we still own on the clipboard.
        WinThread.sync { Paster.renderAll() }
        exitProcess(0)
    }

    private fun lightTaskbar(): Boolean = runCatching {
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "SystemUsesLightTheme") == 1
    }.getOrDefault(false)

    companion object {
        private const val ID_TOGGLE = 1
        private const val ID_ESCAPE = 2
        private const val ID_ENTER = 3

        private const val CMD_TOGGLE = 1
        private const val CMD_STARTUP = 2
        private const val CMD_DEVELOPMENT = 3
        private const val CMD_LOGS = 4
        private const val CMD_DIAGNOSTICS = 5
        private const val CMD_QUIT = 6
        private const val CMD_MIC_DEFAULT = 99
        private const val CMD_MIC_FIRST = 100
        private const val CMD_LANGUAGE_FIRST = 300

        fun pruneRecordings() = WavRecorder.pruneOld(AppPaths.recordings)
    }
}

/** "Start with Windows": the HKCU Run key, pointing at the packaged launcher. */
object Startup {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Persian Dictation"
    /** Set by jpackage's launcher; absent when run with plain `java -jar`. */
    private val exe: String? = System.getProperty("jpackage.app-path")
    val available get() = exe != null

    var enabled: Boolean
        get() = exe != null && runCatching {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME)
        }.getOrDefault(false)
        set(value) {
            val path = exe ?: return
            runCatching {
                if (value) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"$path\"")
                else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME)) {
                    Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME)
                }
            }.onFailure { Log.write("start with Windows: $it") }
            Log.write("startWithWindows=$value")
        }
}

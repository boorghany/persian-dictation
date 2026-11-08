package local.voice2text.windows

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.CheckboxMenuItem
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ItemEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import local.voice2text.core.WavRecorder

/** Notification-area icon, menu and global hotkeys. EDT only. Port of the Mac `AppDelegate`. */
class App {
    private val controller = DictationController()
    private lateinit var tray: TrayIcon
    private val menu = PopupMenu()
    private val statusItem = MenuItem("Ready").apply { isEnabled = false }
    private val toggleItem = MenuItem("Start Dictation   Ctrl+Alt+Space")
    private val microphoneMenu = Menu("Microphone")
    private val languageMenu = Menu("Language")
    private val developmentItem = CheckboxMenuItem("Development Mode (log + record audio)")
    private val startupItem = CheckboxMenuItem("Start with Windows")
    private val languages = listOf("Persian (fa-IR)" to "fa-IR", "English (en-US)" to "en-US")
    private var toggleRegistered = false

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
            refresh()
        }
        // A spoken "اینتر" presses Return in the target app: our own Enter hotkey must not catch it.
        // Called on the Win32 thread (WinThread.sync runs inline there).
        controller.onReturnPassThrough = { passThrough ->
            if (passThrough) WinThread.unregisterHotKey(ID_ENTER)
            else if (controller.state != DictationController.State.IDLE) registerEnter()
        }
        WinThread.onToggleRequest = { controller.toggle() }
        WinThread.onPasteTest = { controller.testFinal(it) }
        WinThread.onSecondLaunch = {
            tray.displayMessage("Persian Dictation is already running", "Press Ctrl+Alt+Space to dictate.", TrayIcon.MessageType.INFO)
        }

        // Keep the startup entry pointing at this copy if the folder was moved.
        if (Startup.enabled) Startup.enabled = true
        buildMenu()
        tray = TrayIcon(AppIcon.trayImage(false, lightTaskbar()), "Persian Dictation", menu)
        tray.isImageAutoSize = true
        tray.addMouseListener(object : MouseAdapter() {
            // Rebuilt before the menu opens (it opens on release).
            override fun mousePressed(e: MouseEvent) = refresh()
        })
        // Double-click: start/stop dictating into the app the user was in.
        tray.addActionListener { toggleFromMenu() }
        SystemTray.getSystemTray().add(tray)

        // Ctrl+Alt+Space, like the Mac's ⌃⌥Space.
        toggleRegistered = WinThread.registerHotKey(ID_TOGGLE, WinThread.MOD_CONTROL or WinThread.MOD_ALT, WinThread.VK_SPACE) {
            controller.toggle()
        }
        Log.write("hotkey Ctrl+Alt+Space registered=$toggleRegistered")
        refresh()
        if (!toggleRegistered) {
            tray.displayMessage("Ctrl+Alt+Space is taken",
                "Another app uses Ctrl+Alt+Space. Start dictation from this icon's menu instead.", TrayIcon.MessageType.WARNING)
        } else if (!Settings.welcomeShown) {
            Settings.welcomeShown = true
            tray.displayMessage("Persian Dictation is running",
                "Press Ctrl+Alt+Space to dictate into any app. Esc or Enter stops. The menu is on this icon.",
                TrayIcon.MessageType.INFO)
        }
    }

    private fun registerEnter() {
        WinThread.registerHotKey(ID_ENTER, 0, WinThread.VK_RETURN) { controller.stopKeyPressed(enter = true) }
    }

    private fun buildMenu() {
        menu.add(MenuItem("Persian Dictation ${AppVersion.display}").apply { isEnabled = false })
        menu.add(statusItem)
        toggleItem.addActionListener { toggleFromMenu() }
        menu.add(toggleItem)
        menu.addSeparator()
        menu.add(microphoneMenu)
        for ((title, code) in languages) {
            languageMenu.add(CheckboxMenuItem(title).apply {
                addItemListener {
                    Settings.language = code
                    refresh()
                }
            })
        }
        menu.add(languageMenu)
        menu.addSeparator()
        developmentItem.addItemListener { e -> toggleDevelopmentMode(e.stateChange == ItemEvent.SELECTED) }
        menu.add(developmentItem)
        menu.add(MenuItem("Open Logs Folder").apply {
            addActionListener {
                AppPaths.data.mkdirs()
                Runtime.getRuntime().exec(arrayOf("explorer.exe", AppPaths.data.path))
            }
        })
        menu.add(MenuItem("Share Diagnostics…").apply { addActionListener { shareDiagnostics() } })
        if (Startup.available) {
            startupItem.addItemListener { e ->
                Startup.enabled = e.stateChange == ItemEvent.SELECTED
                refresh()
            }
            menu.add(startupItem)
        }
        menu.addSeparator()
        menu.add(MenuItem("Quit Persian Dictation").apply { addActionListener { quit() } })
    }

    /** The tray menu took the focus; give it back to the user's app before dictating into it. */
    private fun toggleFromMenu() {
        if (controller.state == DictationController.State.IDLE) WinThread.restoreForeground()
        controller.toggle()
    }

    /** "System Default" follows the Windows default input; a chosen device sticks while connected. */
    private fun buildMicrophoneMenu() {
        microphoneMenu.removeAll()
        val choice = Settings.microphone
        val devices = MicCapture.microphones().map { it.name }
        fun item(title: String, value: String?) = CheckboxMenuItem(title, value == choice).apply {
            addItemListener {
                Settings.microphone = value
                Log.write("microphone choice: $title")
                controller.microphonePreferenceChanged()
                refresh()
            }
        }
        microphoneMenu.add(item("System Default", null))
        microphoneMenu.addSeparator()
        devices.forEach { microphoneMenu.add(item(it, it)) }
        if (choice != null && choice !in devices) {
            microphoneMenu.add(MenuItem("Chosen microphone not connected — using the default").apply { isEnabled = false })
        }
        microphoneMenu.label = "Microphone: ${choice ?: "System Default"}"
    }

    private fun refresh() {
        val listening = controller.state != DictationController.State.IDLE
        tray.image = AppIcon.trayImage(listening, lightTaskbar())
        statusItem.label = when (controller.state) {
            DictationController.State.IDLE -> if (toggleRegistered) "Ready" else "Ready (Ctrl+Alt+Space is used by another app)"
            DictationController.State.LISTENING -> "Listening…"
            DictationController.State.FINISHING -> "Finishing…"
        }
        tray.toolTip = "Persian Dictation — ${statusItem.label}"
        toggleItem.label = if (listening) "Stop Dictation   Esc / Enter" else "Start Dictation   Ctrl+Alt+Space"
        buildMicrophoneMenu()
        developmentItem.state = Settings.developmentMode
        startupItem.state = Startup.enabled
        for (i in 0 until languageMenu.itemCount) {
            (languageMenu.getItem(i) as CheckboxMenuItem).state = languages[i].second == Settings.language
        }
    }

    private fun toggleDevelopmentMode(on: Boolean) {
        if (on && !Settings.developmentMode) {
            val owner = JFrame().apply { isUndecorated = true; isAlwaysOnTop = true; setLocationRelativeTo(null); isVisible = true }
            val answer = JOptionPane.showConfirmDialog(
                owner,
                "Development Mode saves a recording of everything you dictate and the recognized text in\n" +
                    "${AppPaths.data} (kept for 7 days). This may include private or confidential\n" +
                    "information. Use it only for troubleshooting.",
                "Turn on Development Mode?", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
            )
            owner.dispose()
            if (answer != JOptionPane.OK_OPTION) {
                refresh()
                return
            }
        }
        Settings.developmentMode = on
        Log.write("developmentMode=$on")
        refresh()
    }

    private fun shareDiagnostics() {
        Thread {
            try {
                val zip = DiagnosticsExport.export()
                Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", zip.path))
            } catch (e: Exception) {
                Log.write("diagnostics export failed: $e")
                tray.displayMessage("Could not create the diagnostics file", e.message ?: e.toString(), TrayIcon.MessageType.ERROR)
            }
        }.start()
    }

    private fun quit() {
        Log.write("quit")
        controller.shutdown()
        SystemTray.getSystemTray().remove(tray)
        // Give a pending paste's clipboard restore no chance to be lost: render anything we still own.
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

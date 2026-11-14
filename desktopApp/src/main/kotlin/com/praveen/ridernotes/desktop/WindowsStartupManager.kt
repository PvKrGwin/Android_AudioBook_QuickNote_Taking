package com.praveen.ridernotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit

object WindowsStartupManager {
    private val shortcut: File?
        get() = System.getenv("APPDATA")?.let {
            File(it, "Microsoft/Windows/Start Menu/Programs/Startup/RiderNotesPC.lnk")
        }

    fun isSupported(): Boolean = System.getProperty("os.name").contains("Windows", true)

    fun isEnabled(): Boolean = shortcut?.isFile == true

    fun setEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isSupported()) { "Windows startup is available only on Windows" }
        val target = shortcut ?: error("Windows Startup folder is unavailable")
        if (!enabled) {
            target.delete()
            return@runCatching
        }
        val command = ProcessHandle.current().info().command().orElse(null)
            ?: error("Installed Rider Notes executable was not found")
        require(command.endsWith(".exe", ignoreCase = true)) {
            "Install the Rider Notes MSI before enabling Windows startup"
        }
        require(!File(command).nameWithoutExtension.equals("java", true) &&
            !File(command).nameWithoutExtension.equals("javaw", true)
        ) { "Install the Rider Notes MSI before enabling Windows startup" }
        target.parentFile?.mkdirs()
        val escapedCommand = command.replace("'", "''")
        val escapedShortcut = target.absolutePath.replace("'", "''")
        val workingDirectory = (File(command).parent ?: error("Executable folder is unavailable"))
            .replace("'", "''")
        val script = "${'$'}s=(New-Object -COM WScript.Shell).CreateShortcut('$escapedShortcut');" +
            "${'$'}s.TargetPath='$escapedCommand';${'$'}s.WorkingDirectory='$workingDirectory';${'$'}s.Save()"
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script
        ).redirectErrorStream(true).start()
        require(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "Windows could not create the startup shortcut"
        }
    }
}

package com.praveen.ridernotes.desktop

import java.io.File

object WindowsStartupManager {
    private val startupFolder: File?
        get() = System.getenv("APPDATA")?.let {
            File(it, "Microsoft/Windows/Start Menu/Programs/Startup")
        }

    private val launcher: File?
        get() = startupFolder?.let { File(it, "RiderNotesPC.cmd") }

    private val legacyShortcut: File?
        get() = startupFolder?.let { File(it, "RiderNotesPC.lnk") }

    fun isSupported(): Boolean = System.getProperty("os.name").contains("Windows", true)

    fun isEnabled(): Boolean = launcher?.isFile == true || legacyShortcut?.isFile == true

    fun setEnabled(enabled: Boolean): Result<Unit> = runCatching {
        require(isSupported()) { "Windows startup is available only on Windows" }
        val target = launcher ?: error("Windows Startup folder is unavailable")
        if (!enabled) {
            target.delete()
            legacyShortcut?.delete()
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
        val parent = target.parentFile ?: error("Windows Startup folder is unavailable")
        require(parent.isDirectory || parent.mkdirs()) { "Windows Startup folder could not be created" }
        target.writeText(createBatchLauncher(command), Charsets.UTF_8)
        legacyShortcut?.delete()
        require(target.isFile && target.length() > 0L) { "Windows could not create the startup launcher" }
    }

    internal fun createBatchLauncher(command: String): String {
        // Percent signs have special meaning in batch files. Doubling them preserves
        // an executable path containing a literal percent sign.
        val escaped = command.replace("%", "%%")
        return "@echo off\r\nstart \"\" \"$escaped\"\r\n"
    }
}

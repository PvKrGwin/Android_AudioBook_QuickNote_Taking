package com.praveen.ridernotes.desktop

import org.json.JSONObject
import java.util.concurrent.TimeUnit

object WindowsMediaBridge {
    fun captureAndPause(): CapturedMedia? {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return null
        return runCatching {
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", SCRIPT
            ).redirectErrorStream(true).start()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            val jsonText = output.lineSequence().lastOrNull { it.trim().startsWith("{") }
                ?: return null
            val json = JSONObject(jsonText)
            CapturedMedia(
                sourceApp = friendlySource(json.optString("source")),
                sourcePackage = json.optString("source").takeIf(String::isNotBlank),
                title = json.optString("title", "Current media"),
                author = json.optString("artist").takeIf(String::isNotBlank),
                playbackPositionMs = json.optLong("positionMs", -1L).takeIf { it >= 0L }
            )
        }.getOrNull()
    }

    private fun friendlySource(value: String): String {
        val lower = value.lowercase()
        return when {
            "youtube" in lower -> "YouTube"
            "spotify" in lower -> "Spotify"
            "netflix" in lower -> "Netflix"
            "audible" in lower -> "Audible"
            "coursera" in lower -> "Coursera"
            "udemy" in lower -> "Udemy"
            "chrome" in lower -> "Chrome media"
            "edge" in lower -> "Edge media"
            value.isBlank() -> "Windows media"
            else -> value.substringAfterLast('!').substringAfterLast('\\')
        }
    }

    private val SCRIPT = """
        ${'$'}ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime] > ${'$'}null
        function Await(${ '$' }operation, ${ '$' }resultType) {
          ${'$'}method = [System.WindowsRuntimeSystemExtensions].GetMethods() |
            Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.IsGenericMethod -and ${'$'}_.GetParameters().Count -eq 1 } |
            Select-Object -First 1
          ${'$'}task = ${'$'}method.MakeGenericMethod(${ '$' }resultType).Invoke(${ '$' }null, @(${ '$' }operation))
          ${'$'}task.Wait()
          return ${'$'}task.Result
        }
        ${'$'}manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
        ${'$'}session = ${'$'}manager.GetCurrentSession()
        if (${ '$' }null -eq ${ '$' }session) { exit 2 }
        ${'$'}props = Await (${ '$' }session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
        ${'$'}timeline = ${'$'}session.GetTimelineProperties()
        ${'$'}null = ${'$'}session.TryPauseAsync()
        [ordered]@{
          source = ${'$'}session.SourceAppUserModelId
          title = ${'$'}props.Title
          artist = ${'$'}props.Artist
          positionMs = [long]${'$'}timeline.Position.TotalMilliseconds
        } | ConvertTo-Json -Compress
    """.trimIndent()
}

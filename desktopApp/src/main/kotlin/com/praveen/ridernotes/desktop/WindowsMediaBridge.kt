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
            val publishedTitle = json.optString("title", "Current media")
            val albumTitle = json.optString("albumTitle").takeIf(String::isNotBlank)
            val sourceUrl = json.optString("sourceUrl").takeIf(::isSupportedMediaUrl)
            CapturedMedia(
                sourceApp = friendlySource(json.optString("source"), sourceUrl),
                sourcePackage = json.optString("source").takeIf(String::isNotBlank),
                title = albumTitle ?: publishedTitle,
                author = json.optString("artist").takeIf(String::isNotBlank),
                playbackPositionMs = json.optLong("positionMs", -1L).takeIf { it >= 0L },
                sourceUrl = sourceUrl,
                chapter = publishedTitle.takeIf {
                    !albumTitle.isNullOrBlank() && !it.equals(albumTitle, ignoreCase = true)
                }
            )
        }.getOrNull()
    }

    private fun friendlySource(value: String, sourceUrl: String?): String {
        val url = sourceUrl.orEmpty().lowercase()
        if ("music.youtube.com" in url) return "YouTube Music"
        if ("youtube.com" in url || "youtu.be" in url) return "YouTube"
        if ("coursera.org" in url) return "Coursera"
        if ("udemy.com" in url) return "Udemy"
        if ("netflix.com" in url) return "Netflix"
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

    private fun isSupportedMediaUrl(value: String): Boolean = runCatching {
        val host = java.net.URI(value).host?.lowercase().orEmpty()
        host == "youtu.be" || host.endsWith(".youtu.be") ||
            host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "coursera.org" || host.endsWith(".coursera.org") ||
            host == "udemy.com" || host.endsWith(".udemy.com") ||
            host == "netflix.com" || host.endsWith(".netflix.com")
    }.getOrDefault(false)

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
        ${'$'}browserUrl = ${'$'}null
        try {
          Add-Type -AssemblyName UIAutomationClient
          ${'$'}sourceName = ${'$'}session.SourceAppUserModelId.ToLowerInvariant()
          ${'$'}processNames = if (${ '$' }sourceName.Contains('edge')) { @('msedge') } elseif (${ '$' }sourceName.Contains('firefox')) { @('firefox') } else { @('chrome','msedge','firefox') }
          ${'$'}windows = Get-Process -Name ${'$'}processNames -ErrorAction SilentlyContinue |
            Where-Object { ${'$'}_.MainWindowHandle -ne 0 -and ${'$'}_.MainWindowTitle.IndexOf(${ '$' }props.Title, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 }
          foreach (${ '$' }browser in ${ '$' }windows) {
            ${'$'}root = [System.Windows.Automation.AutomationElement]::FromHandle(${ '$' }browser.MainWindowHandle)
            ${'$'}condition = New-Object System.Windows.Automation.PropertyCondition(
              [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
              [System.Windows.Automation.ControlType]::Edit
            )
            ${'$'}edits = ${'$'}root.FindAll([System.Windows.Automation.TreeScope]::Descendants, ${'$'}condition)
            foreach (${ '$' }edit in ${ '$' }edits) {
              ${'$'}pattern = ${'$'}null
              if (${ '$' }edit.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]${'$'}pattern)) {
                ${'$'}candidate = ${'$'}pattern.Current.Value
                if (${ '$' }candidate -match '^https?://([^/]+\.)?(youtube\.com|youtu\.be|coursera\.org|udemy\.com|netflix\.com)(/|${'$'})') {
                  ${'$'}browserUrl = ${'$'}candidate
                  break
                }
              }
            }
            if (${ '$' }browserUrl) { break }
          }
        } catch { ${'$'}browserUrl = ${'$'}null }
        [ordered]@{
          source = ${'$'}session.SourceAppUserModelId
          title = ${'$'}props.Title
          artist = ${'$'}props.Artist
          albumTitle = ${'$'}props.AlbumTitle
          positionMs = [long]${'$'}timeline.Position.TotalMilliseconds
          sourceUrl = ${'$'}browserUrl
        } | ConvertTo-Json -Compress
    """.trimIndent()
}

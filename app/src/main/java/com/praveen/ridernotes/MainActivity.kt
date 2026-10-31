package com.praveen.ridernotes

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.praveen.ridernotes.input.BleButtonAccessibilityService
import com.praveen.ridernotes.input.ButtonEvent
import com.praveen.ridernotes.input.ButtonEventStore

class MainActivity : ComponentActivity() {
    private val detectorEnabled = mutableStateOf(false)
    private val microphoneEnabled = mutableStateOf(false)
    private lateinit var diagnosticMediaSession: MediaSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDiagnosticMediaSession()

        setContent {
            RiderNotesTheme {
                DetectorScreen(
                    detectorEnabled = detectorEnabled,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    microphoneEnabled = microphoneEnabled,
                    onRequestMicrophone = ::requestRecordingPermissions,
                    onClear = ButtonEventStore::clear,
                    onCopy = ::copyResults
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        detectorEnabled.value = isDetectorEnabled()
        microphoneEnabled.value = hasMicrophonePermission()
        diagnosticMediaSession.isActive = true
    }

    override fun onPause() {
        diagnosticMediaSession.isActive = false
        super.onPause()
    }

    override fun onDestroy() {
        diagnosticMediaSession.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        ButtonEventStore.record(ButtonEvent.from(event, route = "Foreground activity"))

        // Diagnostic safety: an external remote must not activate Clear or Copy
        // while we are trying to identify it. Phone buttons keep normal behavior.
        return if (event.device?.isExternal == true) true else super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.device?.isExternal == true) {
            ButtonEventStore.record(
                ButtonEvent.from(event, route = "Foreground generic motion")
            )
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.device?.isExternal == true) {
            ButtonEventStore.record(
                ButtonEvent.from(event, route = "Foreground external touch")
            )
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setupDiagnosticMediaSession() {
        diagnosticMediaSession = MediaSession(this, "RiderNotesSignalDetector").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonIntent
                        .getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

                    if (event != null) {
                        ButtonEventStore.record(
                            ButtonEvent.from(event, route = "Diagnostic media session")
                        )
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_REWIND or
                            PlaybackState.ACTION_FAST_FORWARD or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SKIP_TO_NEXT
                    )
                    .setState(
                        PlaybackState.STATE_PLAYING,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f
                    )
                    .build()
            )
            isActive = true
        }
    }

    private fun isDetectorEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this, BleButtonAccessibilityService::class.java)

        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val actual = ComponentName(
                    info.resolveInfo.serviceInfo.packageName,
                    info.resolveInfo.serviceInfo.name
                )
                actual == expected
            }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestRecordingPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            RECORDING_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORDING_PERMISSION_REQUEST) {
            microphoneEnabled.value = hasMicrophonePermission()
        }
    }

    private fun copyResults() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = ButtonEventStore.asShareableText()
            .ifBlank { "No Beauty-R1 signals captured yet." }
        clipboard.setPrimaryClip(ClipData.newPlainText("Beauty-R1 signal test", text))
    }

    companion object {
        private const val RECORDING_PERMISSION_REQUEST = 4102
    }
}

@Composable
private fun DetectorScreen(
    detectorEnabled: State<Boolean>,
    onOpenAccessibilitySettings: () -> Unit,
    microphoneEnabled: State<Boolean>,
    onRequestMicrophone: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    val events = ButtonEventStore.events.collectAsState()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Rider Notes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Milestone 1.1 · remote signal detector",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            StatusCard(
                enabled = detectorEnabled.value,
                onOpenSettings = onOpenAccessibilitySettings
            )

            Spacer(Modifier.height(10.dp))

            MicrophoneCard(
                enabled = microphoneEnabled.value,
                onRequestPermission = onRequestMicrophone
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Captured signals (${events.value.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onClear) { Text("Clear") }
                OutlinedButton(onClick = onCopy) { Text("Copy results") }
            }

            Spacer(Modifier.height(8.dp))

            if (events.value.isEmpty()) {
                EmptyInstructions()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = events.value,
                        key = { event ->
                            "${event.eventTimeMillis}-${event.action}-${event.keyCode}-${event.scanCode}"
                        }
                    ) { event ->
                        EventCard(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    val background = if (enabled) Color(0xFFE7F4EA) else Color(0xFFFFF3E0)
    Card(colors = CardDefaults.cardColors(containerColor = background)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (enabled) "Global key detector enabled" else "Global key detector not enabled",
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (enabled) {
                    "While this screen is open, Rider Notes also captures and blocks external " +
                        "motion/media signals so the remote cannot press Clear accidentally."
                } else {
                    "Enable ‘Rider Notes button detector’ for background hardware-key observation."
                }
            )
            if (!enabled) {
                Button(onClick = onOpenSettings) {
                    Text("Open Accessibility settings")
                }
            }
        }
    }
}

@Composable
private fun MicrophoneCard(enabled: Boolean, onRequestPermission: () -> Unit) {
    val background = if (enabled) Color(0xFFE7F4EA) else Color(0xFFFFF3E0)
    Card(colors = CardDefaults.cardColors(containerColor = background)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (enabled) "Microphone ready" else "Microphone permission required",
                fontWeight = FontWeight.Bold
            )
            Text(
                if (enabled) {
                    "Double press can record a voice-note test."
                } else {
                    "Allow microphone access before testing voice-note recording."
                }
            )
            if (!enabled) {
                Button(onClick = onRequestPermission) {
                    Text("Enable recording")
                }
            }
        }
    }
}

@Composable
private fun EmptyInstructions() {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("No signals yet", fontWeight = FontWeight.Bold)
            Text("1. Confirm Beauty-R1 is connected.")
            Text("2. Keep this screen open for the expanded diagnostic.")
            Text("3. Press one remote button once.")
            Text("4. Clear, repeat for each physical button, then copy the results.")
        }
    }
}

@Composable
private fun EventCard(event: ButtonEvent) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(event.keyName, fontWeight = FontWeight.Bold)
                Text(event.action)
            }
            if (event.keyCode >= 0) {
                Text("Key ${event.keyCode} · Scan ${event.scanCode} · Repeat ${event.repeatCount}")
            }
            Text("Likely: ${event.likelyMeaning}")
            Text("Device: ${event.deviceName} (${event.deviceId})")
            Text(
                text = "Captured via: ${event.captureRoutes.sorted().joinToString(" + ")}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun RiderNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF28536B),
            secondary = Color(0xFF5B7C8D),
            background = Color(0xFFF6F8FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFEAF0F3)
        ),
        content = { Surface(modifier = Modifier.fillMaxSize(), content = content) }
    )
}

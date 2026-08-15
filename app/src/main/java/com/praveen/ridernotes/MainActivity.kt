package com.praveen.ridernotes

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RiderNotesTheme {
                DetectorScreen(
                    detectorEnabled = detectorEnabled,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onClear = ButtonEventStore::clear,
                    onCopy = ::copyResults
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        detectorEnabled.value = isDetectorEnabled()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        ButtonEventStore.record(ButtonEvent.from(event, route = "Foreground activity"))
        return super.dispatchKeyEvent(event)
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

    private fun copyResults() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = ButtonEventStore.asShareableText().ifBlank { "No BLE-M3 events captured yet." }
        clipboard.setPrimaryClip(ClipData.newPlainText("BLE-M3 key test", text))
    }
}

@Composable
private fun DetectorScreen(
    detectorEnabled: State<Boolean>,
    onOpenAccessibilitySettings: () -> Unit,
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
                text = "Milestone 1 · BLE-M3 key detector",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            StatusCard(
                enabled = detectorEnabled.value,
                onOpenSettings = onOpenAccessibilitySettings
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Captured events (${events.value.size})",
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
                text = if (enabled) "Global detector enabled" else "Global detector not enabled",
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (enabled) {
                    "You may open YouTube, test BLE-M3, and return here. Buttons are observed but not blocked."
                } else {
                    "Enable ‘Rider Notes button detector’ to observe BLE-M3 while another app is open."
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
private fun EmptyInstructions() {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("No key events yet", fontWeight = FontWeight.Bold)
            Text("1. Confirm BLE-M3 is connected.")
            Text("2. Enable the global detector above.")
            Text("3. Press each remote button once while this screen is open.")
            Text("4. Then play YouTube, test the buttons, and return here.")
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
            Text("Key ${event.keyCode} · Scan ${event.scanCode} · Repeat ${event.repeatCount}")
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

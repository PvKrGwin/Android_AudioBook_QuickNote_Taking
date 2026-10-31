# Bluetooth microphone and Scrib integration status

## Microphone
The Beauty-R1 key events are remote input events, not evidence of microphone capability. An Android recorder must query its actual routed input *after* recording begins. `MicrophoneRouteDiagnostics.describe(context, audioRecord)` provides a diagnostic string for an AudioRecord-based recorder. If the recorder uses MediaRecorder, it needs its own route inspection instead. Do not show "Bluetooth microphone" as confirmed merely because a headset is connected or recording started.

On Android 12+, a recorder can request a compatible communication device using AudioManager.setCommunicationDevice(), subject to device support and audio mode. Verify the actual routed input afterward. A remote that has no microphone cannot supply audio.

## Scrib
Installing Scrib does not automatically expose a transcription API to Rider Notes. Verify the exact Scrib app package, accessibility/service status, supported import/share intent or documented API, and whether it can accept an M4A file. Do not claim that transcription is enabled until a successful transcription result is returned.

If Scrib has no documented external integration, offer an Android share-to-Scrib flow only if the installed app advertises a compatible ACTION_SEND handler for audio/*; otherwise use an explicitly supported transcription backend. Never silently upload recordings to third-party services.

## Integration remaining
This repository currently contains the Milestone 1 key detector rather than the newer recording/gesture implementation shown in the device logs. Wire the microphone diagnostic into that recorder and the transcription status into its UI when the corresponding source is available. Build and device tests are still required.

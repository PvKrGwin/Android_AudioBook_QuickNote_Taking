# Learning and development roadmap

## Milestone 1 — BLE-M3 characterization (this ZIP)

- Build and install a minimal Android app.
- Observe foreground and global hardware-key events.
- Compare single tap, double tap, short press, and long press.
- Confirm behaviour with YouTube, screen lock, earphones, and remote sleep.

## Milestone 2 — Safe trigger and media-session control

- Select the most reliable BLE-M3 event.
- Add debounce/double-tap protection.
- Read active media metadata and playback position where exposed.
- Pause and resume only the session Rider Notes controlled.

## Milestone 3 — Foreground audio recording

- Add microphone permission and recording foreground service.
- Show persistent notification and unlocked-screen recording state.
- Add distinct start/stop acknowledgement sounds.
- Save recordings progressively and recover interrupted files.

## Milestone 4 — Voice activity and limits

- Add voice-activity detection resilient to steady background noise.
- Stop after 20 seconds without accepted speech.
- Add configurable maximum duration, default six minutes.
- Add low-battery and shutdown-aware finalization.

## Milestone 5 — Transcription and note preparation

- Queue audio safely when offline.
- Transcribe through a backend-held API credential.
- Store original audio, verbatim transcript, cleaned note, and generated title.

## Milestone 6 — Speaker preference

- Enroll Praveen's voice locally.
- Implement Off, Balanced, and Strict filtering modes.
- Tune using stationary and vehicle/helmet samples.

## Milestone 7 — Cloud notes integration

- Authenticate without storing account secrets in Git.
- Create and sync entries in the selected `Rider` destination.
- Retry safely after connectivity returns.

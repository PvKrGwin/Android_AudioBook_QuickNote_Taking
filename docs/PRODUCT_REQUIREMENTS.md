# Rider Notes — agreed product direction

This is the long-term scope, not a claim about Milestone 1 functionality.

## Intended workflow

1. Detect a chosen BLE-M3 button globally.
2. Capture the active media title, source application, and playback position.
3. Pause the active compatible media session.
4. Play a start acknowledgement.
5. Record Praveen's voice in a foreground service with a visible indicator.
6. Stop on a second button press, 20 seconds of verified silence, the selected
   maximum duration (default six minutes), or a recoverable low-battery event.
7. Play a stop acknowledgement and resume only the media session Rider Notes
   paused.
8. Preserve the original recording, create a transcript and cleaned note, and
   generate a title when no media was active.
9. Queue offline work and sync the finished entry to a `Rider` destination,
   initially planned as Microsoft OneNote.

## Speaker filtering direction

- Off: accept all detected speakers.
- Balanced (planned default): prefer Praveen's enrolled voice and preserve the
  original recording for recovery.
- Strict: use only confidently matched speech for transcription.

Speaker recognition is a quality filter, not identity authentication.

## Safety and reliability boundaries

- The recording service must be visible through Android's foreground-service
  notification and microphone privacy indicator.
- Audio is written progressively so an abrupt shutdown loses as little as
  possible.
- Calls, alarms, and safety/navigation prompts must not be blindly overridden.
- Testing and operation must minimize interaction while riding.
- API keys must never be embedded in the Android application.

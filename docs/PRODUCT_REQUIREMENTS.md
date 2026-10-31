# Rider Notes — agreed product direction

This is the long-term scope, not a claim about the current milestone.

## Beauty-R1 controls

Beauty-R1 exposes one usable camera button. Android reports successive presses
as alternating Volume Down and Volume Up keys, so both key codes represent the
same physical control.

When no recording or call operation is active:

- single press: play or pause the active supported media session;
- double press within 800 ms: create a voice note;
- triple press within 800 ms: rewind by 10 seconds, or use the app's default rewind;
- four presses within 800 ms: forward by 10 seconds, or use the app's default forward.

During recording, any received press stops and saves the recording immediately.
The app must save audio before attempting transcription.

If supported media is active or paused, double press captures the source app,
available title/chapter and playback position, pauses media, acknowledges the
start with a short beep, records for at most 25 seconds, acknowledges the stop,
then resumes only the media Rider Notes paused after about 1.5 seconds.

If no supported media session is active or paused, double press records a
standalone thought. Its initial title is the local recording date and time.

All recordings retain the original audio and receive a transcript. Failed or
offline transcription remains retryable and must never delete the audio.

Future call-aware behavior has higher priority than media controls: single press
may answer an incoming call and double press may reject or end a call, subject
to Android permissions and device validation.

Future Recoil support will map dedicated buttons to the same internal actions
instead of reimplementing the workflows.

## Intended workflow

1. Detect a configured remote gesture globally.
2. Identify an active or paused supported media session.
3. Capture the available media title, source application and playback position.
4. Pause the active compatible media session when creating a linked voice note.
5. Play a start acknowledgement.
6. Record Praveen's voice in a foreground service with a visible indicator.
7. Stop on another remote press or after 25 seconds.
8. Save the original audio first, then play a stop acknowledgement.
9. Resume only the media session Rider Notes paused after about 1.5 seconds.
10. Create a transcript for every recording and retain failed work for retry.
11. Queue offline work and sync the finished entry to a Rider destination,
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
- Calls, alarms and safety/navigation prompts must not be blindly overridden.
- Call state takes priority over recording and media gestures.
- Testing and operation must minimize interaction while riding.
- API keys must never be embedded in the Android application.

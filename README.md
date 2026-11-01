# Rider Notes for Android

Rider Notes is a hands-free voice-note project for Praveen's Oppo phone and
the `Beauty-R1` six-button Bluetooth remote.

## Current milestone: 1.1 — Identify every remote signal

The original detector proved that Beauty-R1's Camera button is globally
available as alternating `KEYCODE_VOLUME_DOWN` and `KEYCODE_VOLUME_UP`
events, including while Audible is foregrounded and while the phone is locked.

Milestone 1.1 expands the foreground diagnostic to inspect three Android input
routes:

- ordinary hardware `KeyEvent` signals;
- external pointer/touch and scroll `MotionEvent` signals;
- media-button events routed through a temporary diagnostic `MediaSession`.

External remote signals are consumed only while the Rider Notes test screen is
open. This prevents a remote action from accidentally pressing **Clear** during
characterization. The Accessibility service still observes background key
events without blocking them.

This version **does not** record audio, control playback for production use,
transcribe speech, recognize a speaker, or sync to OneNote. Those features
remain postponed until the available physical buttons are characterized.

## Confirmed Beauty-R1 Camera-button behavior

- Device name: `Beauty-R1` (do not hard-code the temporary numeric device ID).
- Each press produces one `DOWN` and one `UP`.
- Successive presses alternate between key 25 / scan 114 and key 24 / scan 115.
- Detection works in the foreground, in the background, over Audible, and while
  the phone is locked.
- After the remote sleeps, the first press wakes it and the second press is the
  actionable event.

## Privacy of the test service

The Accessibility service is narrowly configured to request hardware-key
events. It cannot retrieve window content. The code does not read or store
typed text, screen contents, audio, notifications, contacts, or location.

## Toolchain

- Kotlin 2.3.21
- Jetpack Compose BOM 2026.06.00
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- `compileSdk` / `targetSdk` 37
- Minimum Android API 28

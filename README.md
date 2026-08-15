# Rider Notes for Android

Rider Notes is a hands-free voice-note project for Praveen's Oppo phone and
the `BLE-M3` six-button Bluetooth remote.

## Current milestone: 1 — Identify the remote

This version does exactly one job: capture and display Android hardware-key
events from the BLE-M3 remote.

It currently **does not** record audio, pause and resume media automatically,
transcribe speech, recognize a speaker, or sync to OneNote. Those features are
deliberately postponed until the physical button is characterized reliably.

Start with [GETTING_STARTED.md](GETTING_STARTED.md), then read
[docs/LESSON_01_ANDROID_KEY_EVENTS.md](docs/LESSON_01_ANDROID_KEY_EVENTS.md).

## Privacy of the test service

The Accessibility service is narrowly configured to request hardware-key
events. It cannot retrieve window content. The code does not read or store
typed text, screen contents, audio, notifications, contacts, or location.
It returns `false` from `onKeyEvent`, which means the observed key continues to
the currently active app.

## Toolchain

- Kotlin 2.3.21
- Jetpack Compose BOM 2026.06.00
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- `compileSdk` / `targetSdk` 37
- Minimum Android API 28

# Lesson 1 — How Android sees the BLE-M3 buttons

## The idea

BLE-M3 behaves like a Bluetooth Human Interface Device (HID). Android converts
many HID commands into `KeyEvent` objects—the same general object used for a
physical keyboard, media remote, volume button, or camera shutter.

One event contains several useful values:

- **Action**: `DOWN`, `UP`, or a repeated action.
- **Key code**: Android's logical meaning, such as media play/pause.
- **Scan code**: a lower-level number reported for the physical control.
- **Repeat count**: often increases while a button is held.
- **Device**: which connected input device produced the event.
- **Source**: the input class, represented as a hexadecimal bit mask.

## Why we capture through two routes

`MainActivity.dispatchKeyEvent()` observes events delivered while Rider Notes
is the visible application. It is the simplest place to learn Android's event
dispatch model.

`BleButtonAccessibilityService.onKeyEvent()` can observe key events before the
currently visible application. That lets us test while YouTube is open. The
service returns `false`, so it observes without consuming the event.

When both routes see the same event, `ButtonEventStore` merges their route names
instead of displaying a duplicate.

## Why Accessibility is restricted

The XML configuration sets:

```xml
android:canRequestFilterKeyEvents="true"
android:canRetrieveWindowContent="false"
```

The first line permits hardware-key observation. The second prevents the
service from retrieving the current application's view hierarchy. Milestone 1
does not inspect text, buttons, or screen contents.

## Why double-tap needs measurement

The BLE-M3's middle double-tap might be represented as:

1. Two rapid instances of the same play/pause key.
2. A separate Android key code.
3. A mouse/touch-style command that is not a `KeyEvent`.

These possibilities require different app logic. Capturing real device data is
therefore safer than guessing from the product description.

## What happens after this lesson

Once the middle button is characterized, Milestone 2 will define a safe trigger
policy. For example, it may use one confirmed key plus a debounce window so a
double-tap cannot start and immediately stop a voice note.

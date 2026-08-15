# Getting started — beginner path

## 1. Install Android Studio

Download and install the current stable Android Studio. During installation,
allow it to install the Android SDK and platform tools.

## 2. Open this project

1. Extract the ZIP to a short Windows path, for example:
   `C:\SW\RiderNotesAndroid_Milestone1`
2. Open Android Studio.
3. Choose **Open** and select the extracted project folder.
4. Wait for Gradle sync and accept installation of Android SDK 37 if prompted.

This project includes `gradle-wrapper.properties`. If Android Studio asks to
use or download Gradle 9.4.1, approve it. Wrapper command scripts are not
included in this starter ZIP, so use Android Studio for the first build.

## 3. Prepare the Oppo phone

1. On the phone, open **Settings → About device**.
2. Tap **Version number / Build number** seven times until Developer options
   are enabled. ColorOS wording can differ slightly.
3. Open **Developer options** and enable **USB debugging**.
4. Connect the phone to the computer with a data-capable USB cable.
5. Accept the phone's **Allow USB debugging** prompt.

Keep BLE-M3 paired and connected.

## 4. Build and install

1. In Android Studio's device selector, choose the Oppo phone.
2. Press the green **Run ▶** button.
3. Select the `app` configuration if Android Studio asks.
4. Wait for **Rider Notes Key Detector** to open on the phone.

## 5. Enable global button detection

1. In the app, press **Open Accessibility settings**.
2. Find **Rider Notes button detector** under downloaded/installed services.
3. Enable it and accept Android's warning.
4. Return to Rider Notes. It should say **Global detector enabled**.

The service is used only for this key test. You can disable it immediately
after copying the results.

## 6. Run the BLE-M3 test

1. Press **Clear**.
2. Single-tap the middle button once.
3. Double-tap it once.
4. Short-press Up and Down once each.
5. Long-press Up and Down once each.
6. Press the remaining buttons once, one at a time. Test Left/Right long press
   only while stationary: BLE-M3 currently uses them for Lock and Home.
7. Open YouTube and play a video.
8. Repeat the middle single tap, middle double tap, and Up/Down tests.
9. Return to Rider Notes.
10. Press **Copy results** and send the pasted text to ChatGPT.

Do not test while riding. Complete the initial validation while seated and
stationary.

## 7. What a result means

Android normally produces two rows per physical press:

- `DOWN`: the button was pressed.
- `UP`: the button was released.

A long press may create repeated `DOWN` events with an increasing repeat count.
Do not decide our final mapping from the visible name alone; share the complete
key and scan-code output.

## 8. Add the project to Git

Open Android Studio's Terminal at the project root and run:

```bash
git init -b main
git add .
git status
git commit -m "Add BLE-M3 key detector milestone"
```

Inspect `git status` before committing. `.gitignore` prevents local Android
settings, recordings, signing files, and secret files from entering Git.

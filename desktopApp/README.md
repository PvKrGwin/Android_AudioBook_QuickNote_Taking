# Rider Notes PC

The Windows companion opens `RiderNotes_Full_Backup.zip` from a Google Drive for
Desktop folder. It provides Notes and editable Topics views, typed notes for the
currently playing Windows media, Topic-only microphone recordings, and safe full-ZIP
publishing with a revision, device ID, and timestamp.

## Run during development

Open the root project in Android Studio or IntelliJ IDEA, select the `desktopApp`
run configuration, and run `com.praveen.ridernotes.desktop.MainKt`.

## Build for Windows 10+

Run the following on a Windows computer with JDK 17:

```powershell
.\gradlew.bat :desktopApp:packageMsi
```

The installer is created below `desktopApp\build\compose\binaries\main\msi`.

## Connect the cloud backup

1. Install and sign in to Google Drive for Desktop.
2. In the Android app, tap **Create fresh full backup**, then **Sync now**.
3. Wait for `RiderNotes_Full_Backup.zip` to appear in the chosen Drive folder on Windows.
4. In Rider Notes PC, choose that file once with **Open backup ZIP**.
5. After editing, choose **Sync now — recommended**. Rider Notes safely replaces the
   complete ZIP in the same folder; Google Drive uploads it.

The app refuses to publish if the sibling revision file shows that another device
changed the backup after it was opened. Reopen the ZIP first in that case.

## Media notes

Choose **✎ Add media note** while Windows media is playing. Rider Notes attempts to
pause the current Windows media session and prefill its title, source, creator, and
position. All fields remain editable when an app or browser does not publish metadata.

Typed notes have no recording and do not enter the transcription queue.

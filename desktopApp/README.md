# Rider Notes PC

The first Windows companion is a read-only viewer for `RiderNotes_Full_Backup.zip`.
It intentionally contains only the Notes and Topics views. Editing and two-way cloud
sync will be added after revision/conflict handling is defined.

## Run during development

Open the root project in Android Studio or IntelliJ IDEA, select the `desktopApp`
run configuration, and run `com.praveen.ridernotes.desktop.MainKt`.

## Build for Windows 10+

Run the following on a Windows computer with JDK 17:

```powershell
.\gradlew.bat :desktopApp:packageMsi
```

The installer is created below `desktopApp\build\compose\binaries\main\msi`.

## Import

In the Android app, tap **Create fresh full backup**, wait for cloud sync, download
`RiderNotes_Full_Backup.zip` on the PC, and choose it from **Open backup ZIP**.

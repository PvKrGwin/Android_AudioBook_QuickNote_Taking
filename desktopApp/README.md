# Rider Notes PC — User Manual

Rider Notes PC is the Windows companion for the Rider Notes Android application. It opens the same full backup, displays media notes and Topic folders, plays recordings with synchronized transcripts, creates typed notes at the active Windows media position, and publishes changes back to the shared backup.

## Recommended setup: Google Drive for Desktop

1. Configure Rider Notes automatic cloud sync on the phone and press **Sync now**.
2. Install Google Drive for Desktop on Windows and wait until the Rider Notes cloud folder is synchronized.
3. Open `RiderNotes_Full_Backup.zip` directly from the synchronized Drive folder in Rider Notes PC.
4. Make changes in **Notes** or **Topics**.
5. Press **Sync now — recommended** in Rider Notes PC.
6. Wait for Google Drive to upload the changed ZIP and `RiderNotes_Sync_State.json` before editing on the phone.

The PC checks the local Drive-synchronized revision periodically, immediately before accepting an edit, and again before publishing. A cloud change can be detected only after Google Drive for Desktop downloads it.

## Manual Chrome download/upload

Manual transfer works if Google Drive for Desktop is not installed:

1. Stop editing on the PC and phone.
2. Press **Sync now** on the phone and wait for completion.
3. Download both `RiderNotes_Full_Backup.zip` and `RiderNotes_Sync_State.json` from the same Drive folder into one PC folder.
4. Open the downloaded ZIP in Rider Notes PC.
5. Edit and press **Sync now — recommended**.
6. Upload and replace both rebuilt files in the original Drive folder.
7. Wait for the upload to finish before reopening Rider Notes on the phone.

## Conflict choices

- **Merge both (recommended):** keeps independently created notes, files, Topic groups, and points from both copies.
- **Use cloud:** reloads the cloud-synchronized ZIP and discards unsynchronized PC working changes.
- **Keep PC as base:** deliberately publishes the PC copy as the new cloud base.

## Notes

Notes are grouped first by media source and then by title. Expand a note to view the source, creator, chapter/module, media timestamp, recording time, origin device, typed note, transcript, audio controls, and source link. Timed transcripts follow playback and pause when playback pauses.

Use the **⋮** action on a note to browse Topic folders, create a folder or subfolder, and add the note as the last point in the selected folder.

## Topics

Topics supports nested folders, ordered text, web links, attachments, and recording-linked points. Expanded linked recordings show the same metadata and transcript available in Notes. Points can be reordered, starred, colour-coded, linked, or removed.

## Media-note shortcut

Play media on Windows and choose **Add media note** in the app or notification-area tray. Rider Notes pauses the current Windows media session, fills available metadata and playback position, and asks for a typed note.

## Startup and tray

**Startup: On** creates a Windows Startup shortcut. Rider Notes then launches after Windows sign-in and remains available in the system tray when the main window is closed. Drive for Desktop must also be running for automatic cloud-folder synchronization.

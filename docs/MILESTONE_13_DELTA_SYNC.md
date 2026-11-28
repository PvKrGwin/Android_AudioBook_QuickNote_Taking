# Milestone 13: provider-neutral incremental sync

Milestone 13 keeps `RiderNotes_Full_Backup.zip` as the portable recovery and migration
format. Routine synchronization will move to per-entity deltas so adding one transcript,
renaming one folder, or changing one Topic point does not require uploading every recording.

## Safety rules

- Existing ZIP backups remain readable and are never deleted by migration.
- Every editable entity has a stable ID and its own revision chain.
- Every file revision contains a SHA-256 content hash. File time and size are diagnostic
  information only and never decide which copy wins.
- Deletions are retained as tombstones so an offline device cannot recreate deleted data.
- A direct parent/child revision establishes which copy is newer.
- Two revisions descending independently from the same parent are a conflict. Neither side
  silently overwrites the other.
- Full ZIP creation remains a deliberate manual/monthly recovery action.

## Tracked entities

- Topic groups and subgroup hierarchy
- Topic items, rich text, links, and additional notes
- Topic placements, order, stars, and colours
- Colour legend meanings
- Recording audio and metadata
- Completed transcripts
- Topic attachments

## Rollout

1. **Foundation:** mobile and PC create the same durable per-entity journal; full ZIPs carry
   that journal for backward-compatible migration.
2. **Delta transport:** upload/download only changed blobs, entity records, and tombstones to
   the selected provider. The current Drive folder remains supported.
3. **Conflict UI:** show independent edits side by side and offer keep local, keep remote, or
   keep both where appropriate.
4. **Storage adapters:** use the same protocol for Google Drive, Backblaze B2, or a home-cloud
   endpoint without changing Rider Notes data models.

The provider folder URL is user configuration and is not hard-coded into the app or committed
to the repository.

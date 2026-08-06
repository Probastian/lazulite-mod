## Resolved Decisions

The following decisions were open questions during specification research and
have been approved by the user:

1. **Conflict detection replacement**: SHA-256 whole-world-folder content
   hash (`contentSignature`), computed during the existing `Files.walk`
   traversal already performed for `computeFolderSizeBytes`. This directly
   answers "is the content actually different" rather than merely "did
   something change," fixing the false-conflict symptom at its root. The
   cheaper timestamp-only fallback considered during research was not
   adopted.
2. **Icon/thumbnail transport**: Base64-embedded inside the same
   `WorldCloudMetadata` JSON (`iconBase64`, nullable), not a separate Cloud
   file. One Cloud object carries both the display metadata and the
   thumbnail, avoiding a third per-world Cloud object and any risk of the
   icon and its describing metadata disagreeing about which world/version
   they belong to.
3. **Orphan cleanup**: `CloudFileStore` gains a `delete(String fileName)`
   method as part of this feature's scope, wired into the sibling
   `cloud-sync-threshold-and-full-sync-only` spec's
   `WorldSaveSyncService.handleSyncDisabled` so that un-syncing a world
   deletes the new metadata file alongside the existing archive deletion,
   leaving no orphaned Cloud metadata file behind.

## Known Limitation

As implemented and verified, only the `onWorldUnload` checkpoint currently
supplies a real `Supplier<LevelDatBatch>` (via a per-platform
`readLevelDatBatch` helper that reads the world's actual `level.dat` NBT at
unload time, mirroring `WorldsPanel`'s existing local-side NBT read). This is
the checkpoint the spec's Architecture section calls out as safe ("the world
is fully unloaded and level.dat is stable to read at that point").

The other three checkpoints that also build/upload `WorldCloudMetadata` --
`onWorldSaved`, `handleSyncReenabled`'s post-check path, and
`checkAndUploadStaleWorldsAtStartup`'s stale-upload path (and, if triggered
independently of an unload, `resolveKeepLocal`) -- still call the path that
resolves to `LevelDatBatch.unreadable()`, so metadata uploaded from those
checkpoints currently carries `null`/blank `minecraftVersion`, `seed`,
`gameMode`, `difficulty`, and `hardcore` values (`contentSignature`,
`displayName`, `lastPlayedMillis`, `syncedAtTimestamp`, and `iconBase64` are
unaffected -- those are sourced independently of `LevelDatBatch`). Each
affected call site carries an explicit code comment explaining why it was
left on the sentinel for this pass (signature/interface changes judged
out of scope for those checkpoints, or, for `onWorldSaved` specifically, a
genuine risk of a `LevelStorageAccess` lock conflict from reading `level.dat`
while the world may still be actively saving).

This is an accepted, shipped limitation, not a defect blocking this feature's
completion: a world that has been unloaded at least once since this feature
shipped will have accurate level.dat-derived Cloud metadata; a world that has
only ever been saved-in-place or stale-swept without an intervening unload
will show blank/placeholder values for those specific fields until its next
unload. Widening real `LevelDatBatch` supply to the remaining checkpoints is
left as explicit follow-up work, not part of this feature's scope.

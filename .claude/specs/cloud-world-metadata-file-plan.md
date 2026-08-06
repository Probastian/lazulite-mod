# Implementation Plan: Per-World Cloud Metadata File

Spec: `.claude/specs/cloud-world-metadata-file.md` (all facts/decisions in its
"Resolved Decisions" section are treated as settled here and not re-derived).

Sibling (separate, do-not-touch) feature: `.claude/specs/cloud-sync-threshold-and-full-sync-only.md`
/ `-plan.md` — mid-verification, implementation already landed. This plan only
*reads* its current state as a fixed integration point (see Existing
Implementation below); it does not modify that spec, plan, or any file solely
for that feature's own sake.

## Existing implementation (confirmed by reading, not re-litigated)

- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java`
  — the sibling feature's `handleSyncDisabled(String worldSlug, String displayName)`
  (lines 287-304) is **already implemented and complete**: on the background
  worker, calls `archiveStore.deleteWorldArchive(archiveFileName(worldSlug))`;
  on success, removes the world's fingerprint entry, re-writes the fingerprint
  file via `cloudFileStore.write(...)`, and calls `statusTracker.clearStatus(worldSlug)`;
  on failure, logs + notifies the player and leaves everything else untouched.
  This is the exact method Requirement 10 extends with a second delete call —
  it is added as one more step inside the existing `if (deleted) { ... }`
  success branch (delete the metadata file only after the archive delete is
  confirmed to have worked, mirroring the method's existing all-or-nothing-per-attempt
  shape) or as an independent best-effort call — see Step 5 below for the exact
  placement decision.
  `syncWorldNow` (664-743) is the single orchestration point for
  Requirement 3's checkpoints: it already runs from `onWorldUnload` (162-168),
  `onWorldSaved` (181-194), `handleSyncReenabled`'s post-check path (249-263),
  and `checkAndUploadStaleWorldsAtStartup`'s stale-upload path (207-221) — so
  per the spec's Architecture section, no new checkpoint wiring is needed in
  `CloudSyncCoordinator`; the new metadata build/upload is added as one more
  step inside `syncWorldNow` itself. Today `syncWorldNow` takes only
  `(String worldSlug, Path worldFolder, String displayName)` — it has **no**
  `LevelDatBatch` parameter, and none of its four callers currently have one
  available either (confirmed: all four call sites pass only slug/folder/name).
  This is the plumbing gap the spec's Architecture section calls out explicitly.
  `detailFor` (511-561) is `WorldConflictResolutionHook`'s implementation:
  already receives a `LevelDatBatch levelDatBatch` parameter (513) from its
  caller (a platform `WorldsPanel`) for the *local* side only; the *cloud*
  side (556-558) is built purely from `WorldFingerprint` + `archiveStore.fileSize(...)`,
  with no metadata-file read at all today.
  `pullFingerprints()` (630-648) is the existing synchronous
  `cloudFileStore.read(...)` + parse + cache-replace pattern to mirror for a
  new `cloudMetadataFor` read path (per the spec's Architecture section, no
  new background-thread machinery is needed beyond what already exists).
  `updateFingerprint` (815-828) is the existing "read-modify-write the RAM
  cache + `cloudFileStore.write`" pattern the new `buildAndUploadMetadata`
  helper should structurally mirror (called from `syncWorldNow` alongside,
  not instead of, this call — the spec's Requirement 3 wording).
  `computeFolderSizeBytes`/`computeFolderLastModifiedMillis` (342-353, 673-681)
  share the same `Files.walk(worldFolder).filter(Files::isRegularFile)`
  traversal shape the new SHA-256 `contentSignature` computation reuses per
  the spec's Architecture section (one more `MessageDigest.update` inside the
  same walk, not a new walk).
  `archiveFileName(String)` (688-690) is the exact naming-convention method to
  mirror for a new `metadataFileName(String)` static helper
  (`"lazuli-world-meta-" + worldSlug + ".json"`, per the spec's Public API
  section).
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/CloudFileStore.java`
  — interface has `isAvailable()`/`read(String)`/`write(String, byte[])`/
  `fileTimestamp(String)` only; no `delete` method today. This is the
  interface Requirement 10 adds `boolean delete(String fileName)` to.
  `SteamRemoteStorageCloudFileStore` (real impl) already imports
  `com.codedisaster.steamworks.SteamRemoteStorage` and has the exact
  try/catch(`SteamException`/`RuntimeException`)/`warn(...)`/return-`false`-or-safe-default
  shape every new method must copy (see `read`/`write`, lines 64-97) — no
  `NoopCloudFileStore` class was found in this pass; confirm at implementation
  time whether Groups 1/3/4/5's no-op fallback for `CloudFileStore` lives
  inline in `CloudSyncCoordinator` or as a separate class (search
  `implements CloudFileStore` across the module) before writing the new
  no-op `delete` body.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldArchiveCloudStore.java`
  / `SteamRemoteStorageWorldArchiveStore.java` — the sibling feature already
  added `deleteWorldArchive(String)` (interface: lines 112-124; real impl
  presumably mirrors `forget`'s try/catch/log/return-`false` shape at
  `SteamRemoteStorageWorldArchiveStore.java`) backed by `remoteStorage.fileDelete(...)`.
  This is the **exact shape** Requirement 10's new `CloudFileStore.delete`
  must mirror (per the spec's own wording, "mirroring
  `WorldArchiveCloudStore.deleteWorldArchive`'s own implementation") —
  read `SteamRemoteStorageWorldArchiveStore.deleteWorldArchive`'s real body
  at implementation time and copy its try/catch shape verbatim into
  `SteamRemoteStorageCloudFileStore.delete`.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/config/WorldFingerprintIO.java`
  — the exact `parse`/`serialize` + `ParseResult(entries, warning)` shape
  the new `WorldCloudMetadataIO` mirrors per the spec's Public API section:
  `parse` never throws (catches `RuntimeException` from `CloudSyncJson`,
  falls back to an empty/warning result), blank/null content is "not yet
  synced" with no warning, `CURRENT_SCHEMA_VERSION` is a `public static final int`
  on the IO class (not the record). `CloudSyncJson` (referenced, not read in
  this pass) is the shared hand-rolled JSON reader/writer every `*IO` class in
  this feature uses — the new IO class uses the same `JsonObject`/`JsonArray`
  builder API (`.putString`/`.putNumber`/`.putBoolean`/`.getString`/`.getLong`/
  `.getBoolean`/optional-getters for nullable fields — confirm the exact
  nullable-field accessor name, e.g. `getStringOrNull`/`hasKey`, by reading
  `CloudSyncJson.java` at implementation time since no nullable field exists
  in `WorldFingerprintIO`'s own schema to copy from directly).
- `api/src/main/java/de/lazuli/api/cloudsync/WorldConflictResolutionHook.java`
  — `ConflictDetail.CloudDetail` (185-190) is a 4-field record
  (`displayName`, `syncedAtTimestamp`, `archiveSizeBytes`, `deviceLabel`) —
  the record Requirement 4/6 extends with the richer fields (or replaces with
  a sibling record per the spec's Public API wording "extended (or a new
  sibling record)" — this plan recommends extending in place, see Step 3,
  since `CloudDetail` has exactly one construction site (`detailFor`,
  `WorldSaveSyncService.java:557-558`) and one consumption site
  (`WorldConflictScreen.pairedRows`), so there is no compatibility reason to
  keep the old shape alongside a new one). `LevelDatBatch` (215-227) is the
  existing Minecraft-client-type-free NBT-batch carrier already passed into
  `detailFor` for the local side only.
- `api/src/main/java/de/lazuli/api/cloudsync/CloudOnlyWorldSummary.java` —
  currently a flat 4-field record (`worldSlug`, `displayName`, `deviceLabel`,
  `syncedAtTimestamp`), one construction site
  (`CloudOnlyWorldDetector.detect`, not yet read in this pass — read at
  implementation time) and one consumption site (`WorldsPanel`'s
  cloud-only-worlds render loop, see below).
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/CloudOnlyWorldsFacade.java`
  — thin `CloudOnlyWorldsHook` implementation: `listCloudOnlyWorlds` (46-49)
  delegates to `CloudOnlyWorldDetector.detect(localWorldFolderNames, fingerprintCache.entries())`
  synchronously (FR6.8, must stay cheap/sync on the render thread). This
  facade has no `WorldSaveSyncService`/`CloudFileStore` reference today — it
  will need one (or a `WorldCloudMetadata`-keyed lookup map) to attach the
  richer fields per world, since `CloudOnlyWorldDetector` only knows about
  `WorldFingerprint`, not the new metadata file.
- `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WorldsPanel.java` —
  cloud-only-worlds render/hit-test call sites confirmed at lines: field
  `cloudOnlyWorlds` (142), `refreshCloudOnlyWorlds()` (239-255, calls
  `hook.listCloudOnlyWorlds(localFolderNames)` at 250), the render loop at
  404-425 (draws a flat blue square icon placeholder at 418, `displayName`
  at 421, `deviceLabel + " · " + syncedAtTimestamp` detail line at 422-423),
  and the click/hit-test loop at 843-855 (`openRestoreFlow(cloudOnly)` at 852).
  `IconTextureCache` (field `iconCache`, line 140,
  `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/IconTextureCache.java`)
  already exposes `forServer(String rowId, byte[] faviconBytes)` (lines 66-72)
  — a byte-array-in, `Identifier`-out cache built directly on vanilla's
  `FaviconTexture`, decoding off the render thread and hopping the GL upload
  back via `thenAcceptAsync(..., Minecraft.getInstance())`. This is the exact
  "reuse, don't reinvent" mechanism the spec's UI section points at for the
  cloud-only row's Base64-decoded icon: call `iconCache.forServer("cloud:" + cloudOnly.worldSlug(), decodedIconBytes)`
  (a new cache-key prefix to avoid colliding with the existing `"saved:" + index`
  server-favicon keys) in place of the current flat `0xFF3399FF` square fill at
  line 418, falling back to the existing flat-square placeholder only when
  `iconBase64`/decoded bytes are absent (keeps today's fallback for
  old-metadata-file/no-icon worlds).
- `platform/fabric-26.2/src/main/java/de/lazuli/cloudsync/WorldConflictScreen.java`
  — `pairedRows(LocalDetail, CloudDetail)` (245-253) builds the "Size" row
  (249-250) compared via `valuesMatch` (352-354, plain string equality after
  formatting) inside the render loop (206-213, `COLOR_MATCH`/`COLOR_MISMATCH`
  at 56-57, chosen at 209-210). This is the exact row Requirement 6 replaces.
  `unpairedRows(LocalDetail)` (255-277, local-only section) is confirmed
  unaffected by this spec (matches the spec's UI section note).
  `fabric-1.21.11`/`fabric-26.1` each have their own byte-identical copy of
  this file (per this repo's confirmed three-copy convention, `.claude/context/minecraft.md`) —
  read once (`fabric-26.2`), diff-apply to the other two.
- `SteamCloudSyncConfig.java:14,52-53,61-62` (not re-read here; already fully
  documented in the sibling plan's Existing Implementation) — cited only for
  its `schemaVersion`/`CURRENT_SCHEMA_VERSION` convention precedent, which
  `WorldFingerprintIO.CURRENT_SCHEMA_VERSION = 1` (confirmed above) already
  demonstrates identically for this feature's own JSON files — no need to
  re-read `SteamCloudSyncConfig.java` itself for this plan.

## Dependencies

- No new external (non-Fabric, non-JDK) dependency is introduced by this
  plan. `SHA-256` hashing uses `java.security.MessageDigest` (JDK standard
  library, already implicitly available — no new Maven/Gradle coordinate).
  `Base64` encode/decode uses `java.util.Base64` (JDK standard library).
  Icon texture rendering reuses vanilla Minecraft's own `FaviconTexture`/
  `NativeImage` via the already-present `IconTextureCache`, no new dependency.
  Per this plan's own instructions, no external-registry verification (Maven
  Central etc.) is needed since nothing new is added to any `build.gradle`.

## Files to create

1. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/api/WorldCloudMetadata.java`
   — new record, same package as `WorldFingerprint`, per spec Public API:
   `schemaVersion` (int), `worldSlug` (String), `displayName` (String),
   `lastPlayedMillis` (long), `minecraftVersion` (String, nullable),
   `seed` (Long, nullable), `gameMode` (String), `difficulty` (String),
   `hardcore` (boolean), `contentSignature` (String, hex-encoded SHA-256),
   `syncedAtTimestamp` (long), `iconBase64` (String, nullable). Javadoc
   should state the file-name convention (`lazuli-world-meta-<slug>.json`,
   mirrored from the future `WorldSaveSyncService.metadataFileName(String)`
   helper) and cross-reference `WorldFingerprint`/`SteamCloudSyncConfig` per
   the spec's Non-goals section (this is a companion, not a replacement).
2. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/config/WorldCloudMetadataIO.java`
   — new, mirrors `WorldFingerprintIO`'s `parse`/`serialize` +
   `ParseResult(WorldCloudMetadata metadata, String warning)` shape (a single
   metadata object per parse, not a list, since this file is one-per-world —
   unlike `WorldFingerprintIO`'s all-worlds-in-one-file shape). `parse` must:
   never throw; treat blank/null content as "no metadata yet" (`ParseResult(null, null)`,
   analogous to `WorldFingerprintIO`'s empty-list case); tolerate an
   unknown/newer `schemaVersion` per the spec's Compatibility section by
   reading whatever fields it recognizes and returning a non-fatal warning
   rather than hard-failing (mirrors `WorldFingerprintIO`'s/
   `SteamCloudSyncConfigIO`'s existing "extra/unknown key ignored" tolerance —
   confirm the exact mechanism, e.g. does `CloudSyncJson.JsonObject` already
   silently ignore unrecognized keys on read, or does this need an explicit
   `schemaVersion` branch, by reading `CloudSyncJson.java` at implementation
   time). `CURRENT_SCHEMA_VERSION = 1` as a `public static final int` on this
   class (spec Configuration section).

## Files to modify

1. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java`
   — the largest-surface-area change:
   - Add `private static final String metadataFileName(String worldSlug)`
     (or a `public static` method matching `archiveFileName`'s visibility) —
     `"lazuli-world-meta-" + worldSlug + ".json"`.
   - Add a `WorldCloudMetadataIO metadataIO = new WorldCloudMetadataIO()` field,
     mirroring the existing `fingerprintIO`/`ancestorIO` fields.
   - Change `syncWorldNow`'s signature (and its four call sites in this same
     file: `onWorldUnload`, `onWorldSaved`, `handleSyncReenabled`,
     `checkAndUploadStaleWorldsAtStartup`) to additionally accept a
     `WorldConflictResolutionHook.LevelDatBatch levelDatBatch` parameter (or
     the narrower raw NBT-derived values the spec's Architecture section
     alternatively allows — decide the exact shape at implementation time;
     `LevelDatBatch` reuse is preferred since it already exists and already
     carries `seed`/`difficulty`/`minecraftVersion`, avoiding a second,
     parallel data-carrier type). This is the single biggest structural
     change in this plan — see Risks.
   - Inside `syncWorldNow`, after computing `sizeBytes`/`strategy` but
     **not** gated behind `strategy != SKIPPED` (spec Requirement 3/Architecture),
     add a call to a new private helper `buildAndUploadMetadata(worldSlug,
     worldFolder, displayName, levelDatBatch)` that: computes
     `contentSignature` by extending the existing `Files.walk` traversal
     (either reuse `computeFolderSizeBytes`'s walk directly by folding the
     `MessageDigest.update` into it, or add a new walk if reuse proves
     awkward — prefer reuse per the spec's Performance section, which frames
     this as "no meaningfully new I/O cost" specifically because it rides
     the *same* walk); reads `worldFolder.resolve("icon.png")` via
     `Files.readAllBytes`, Base64-encodes if present, omits `iconBase64`
     entirely (leaves it `null`) if the file is missing (per spec Architecture,
     tolerate-missing, never error); builds a `WorldCloudMetadata` record;
     serializes via `metadataIO.serialize(...)`; writes via
     `cloudFileStore.write(metadataFileName(worldSlug), bytes)`. Call this
     from the same background-worker context `syncWorldNow` already runs in
     (no new thread hop needed — `syncWorldNow` itself already runs off the
     `CloudSyncWorker` background thread per its four callers).
   - Add a public read accessor `Optional<WorldCloudMetadata> cloudMetadataFor(String worldSlug)`,
     structurally mirroring `pullFingerprints()`'s `cloudFileStore.read(...)` +
     parse pattern but performed synchronously per-call (not cached) unless a
     caching layer is judged necessary at implementation time for render-thread
     cost — confirm whether `detailFor`/`CloudOnlyWorldsFacade` are called
     often enough on the render thread that an uncached `cloudFileStore.read`
     per call is acceptable (likely yes, matching `archiveStore.fileSize(...)`'s
     existing per-call, uncached pattern in `detailFor` today).
   - Update `detailFor` (511-561): after the existing `fingerprint`
     null-check, additionally call `cloudMetadataFor(worldSlug)`; if present,
     source the extended `CloudDetail` fields from it (per Requirement 4); if
     absent, fall back to exactly today's fingerprint+archive-size-only
     construction (Requirement 8/Compatibility) — this is an `Optional`
     branch, not an exception path.
   - Add `deleteCloudMetadata(String worldSlug)` (private or package-private,
     called only from `handleSyncDisabled`), backed by the new
     `cloudFileStore.delete(metadataFileName(worldSlug))`.
   - Update `handleSyncDisabled` (287-304, the sibling feature's existing
     method): inside the existing `if (deleted) { ... }` success branch (after
     `statusTracker.clearStatus(worldSlug)`), add a call to
     `cloudFileStore.delete(metadataFileName(worldSlug))` — best-effort, its
     boolean result not itself gating anything further (the archive delete
     already succeeded, which is this method's primary success signal; a
     metadata-delete failure here should at most be logged via
     `warningLogger`, not surfaced as a second player-facing failure message,
     to avoid double-notifying for one un-sync action — confirm this UX
     choice is acceptable or whether the spec wants a distinct message;
     the spec's Requirement 10 text does not specify failure-handling nuance
     for this specific sub-call, so this is a reasonable default, not a
     settled decision).
   - Class Javadoc (30-52) should gain one sentence noting the new metadata
     file's existence and the `WorldCloudMetadataIO` companion, mirroring how
     the existing Javadoc already summarizes the fingerprint/ancestor files.
2. `api/src/main/java/de/lazuli/api/cloudsync/WorldConflictResolutionHook.java`
   — extend `ConflictDetail.CloudDetail` (185-190) in place with the new
   fields from `WorldCloudMetadata` (`lastPlayedMillis`, `minecraftVersion`,
   `seed`, `gameMode`, `difficulty`, `hardcore`, `contentSignature`), keeping
   `archiveSizeBytes` (informational only per Requirement 6) and adding a
   nullable-icon field if the conflict screen is to show a thumbnail too (spec
   UI section marks this "at planning-time discretion... not load-bearing" —
   recommend deferring icon-in-conflict-screen to a follow-up unless trivial,
   since it is explicitly non-load-bearing here). Update its Javadoc `@param`
   list accordingly. This record's one construction site (`detailFor`) and
   one consumption site (`WorldConflictScreen.pairedRows`) are both touched in
   this same plan (Files to modify #1 and #4), so no compatibility shim is
   needed for the shape change (matches the sibling plan's own precedent for
   breaking internal record shapes with no external consumers).
3. `api/src/main/java/de/lazuli/api/cloudsync/CloudOnlyWorldSummary.java` —
   extend the flat record with the same richer optional fields
   (`lastPlayedMillis`, `minecraftVersion`, `seed`, `gameMode`, `difficulty`,
   `hardcore`, `iconBase64`), nullable/sentinel-defaulted per the spec's
   Public API section ("exactly like `ConflictDetail.LocalDetail`'s existing
   `level.dat`-read-failed pattern"). Update its one construction site
   (`CloudOnlyWorldDetector.detect`, read at implementation time) and its one
   consumption site (`WorldsPanel`'s render loop, Files to modify #5).
4. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/CloudFileStore.java`
   — add `boolean delete(String fileName);` to the interface, Javadoc
   contrasting it with the *absence* of a `forget` on this interface (unlike
   `WorldArchiveCloudStore`, `CloudFileStore` never had quota/`fileForget`
   semantics at all — `delete` here is simply "propagate an actual delete,"
   with no `forget` counterpart to contrast against, unlike
   `WorldArchiveCloudStore.deleteWorldArchive`'s Javadoc which explicitly
   contrasts against its own `forget`).
   - `SteamRemoteStorageCloudFileStore.java` — implement `delete`, copying
     `SteamRemoteStorageWorldArchiveStore.deleteWorldArchive`'s exact
     try/catch/log/return-`false` shape (per spec Architecture section),
     calling `remoteStorage.fileDelete(fileName)`.
   - Locate and update every other `implements CloudFileStore` in the module
     (search `implements CloudFileStore` — at minimum, a test double in
     `WorldSaveSyncServiceTest`/`CloudSyncCoordinatorTest`/`CloudSyncableReconcilerTest`,
     and possibly a `NoopCloudFileStore` production class not found in this
     planning pass) with a `delete` implementation (production no-op: return
     `false`; test fakes: implement against their in-memory `Map`, removing
     the key and returning `true`/`false` per whether it existed, mirroring
     `WorldFingerprintIO`-style fakes' existing `write`/`read` bodies).
5. `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
   (primary/real-test-tree platform, per this repo's convention) — in the
   cloud-only-worlds render loop (404-425): replace the flat
   `guiGraphics.fill(iconX, iconY, ..., 0xFF3399FF)` square (418) with a call
   into `iconCache.forServer("cloud:" + cloudOnly.worldSlug(), decodedIconBytesOrNull)`
   drawing the returned `Identifier` via whatever existing
   `guiGraphics`-draw-texture call the *local* world icon rendering already
   uses elsewhere in this file (locate and reuse that exact call shape at
   implementation time — do not hand-roll a new texture-blit call), falling
   back to today's flat square only when `cloudOnly.iconBase64()` is `null`/
   empty or fails to `Base64.getDecoder().decode(...)` (wrap in try/catch,
   never let a malformed Base64 string throw on the render thread). Extend
   the detail line(s) at 421-423 with `lastPlayedMillis`/`minecraftVersion`/
   `seed` per the spec's UI section (exact layout/wording left to
   implementation-time judgment, non-load-bearing per spec).
6. `platform/fabric-1.21.11/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`,
   `platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/WorldsPanel.java` —
   apply the identical change from #5 (diff-apply against the module-specific
   pre-existing mapping differences, per this repo's three-copy convention),
   compile-verify only (no new tests in these two modules, per sibling plan's
   established Step 7 precedent).
7. `platform/fabric-26.2/src/main/java/de/lazuli/cloudsync/WorldConflictScreen.java`
   — in `pairedRows` (245-253), replace the "Size" row (249-250) with a
   "Content match" row (exact label at implementation-time discretion) built
   from `local.contentSignature()`-equivalent (the local side does not
   currently compute a content hash at all — see Risks item 1) vs.
   `cloud.contentSignature()`, compared via the existing `valuesMatch`
   (352-354, unchanged — still plain string equality). The existing raw
   byte-size numbers may remain as a non-color-coded informational line per
   the spec's UI section ("at planning-time discretion") — recommend keeping
   them in `unpairedRows`'s local-only section (a Cloud-side equivalent would
   need a new `unpairedRows`-style cloud-only section, adding UI-layout scope
   this plan treats as optional/deferrable since it is explicitly
   non-load-bearing per spec).
8. `platform/fabric-1.21.11/src/main/java/de/lazuli/cloudsync/WorldConflictScreen.java`,
   `platform/fabric-26.1/src/main/java/de/lazuli/cloudsync/WorldConflictScreen.java`
   — apply the identical `pairedRows` change from #7, diff-applied,
   compile-verified (no new tests in these two modules).
9. Test files (see Test Strategy below for specifics):
   `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/config/`
   (new `WorldCloudMetadataIOTest.java`),
   `.../services/WorldSaveSyncServiceTest.java`,
   `.../services/CloudSyncCoordinatorTest.java` (constructor/interface-fallout
   check only, per the sibling plan's own precedent for this file),
   and (fabric-26.2 only) any existing `WorldConflictScreenTest`/
   `WorldsPanelStatusTest`-style pure-static-helper test class covering
   `valuesMatch`/`computeShowStatusIndicator`-equivalents (locate exact file
   name at implementation time).

## Ordering / sequencing

1. **New types first** (`WorldCloudMetadata`, `WorldCloudMetadataIO` + its
   unit tests) — no dependency on anything else in this plan, fully
   independent, safest to land and verify first.
2. **`CloudFileStore.delete`** (interface + real impl + every test-double
   fallout) — independent of step 1, but must land before step 3's
   `handleSyncDisabled` wiring needs it to compile.
3. **`WorldSaveSyncService` changes** (metadata build/upload in `syncWorldNow`,
   `cloudMetadataFor` read accessor, `detailFor` update, `deleteCloudMetadata` +
   `handleSyncDisabled` wiring) — depends on steps 1+2. This step alone
   requires touching all four `syncWorldNow` call sites for the new
   `LevelDatBatch` parameter (see Risks item 1) and is the highest-risk,
   highest-review-priority step in this plan.
   - **Dependency on sibling feature's completed state**: confirmed above —
     `handleSyncDisabled` is already implemented (post-sibling-implementation)
     with the exact success/failure branch structure this step extends. No
     waiting is required; the sibling implementation is confirmed complete as
     of this planning pass. Re-confirm at implementation time that no further
     sibling-feature commits have landed on `handleSyncDisabled` since this
     plan was written (it is "mid-verification," not merged/closed) — a
     verification-phase fix to that method could shift line numbers/shape
     before this plan's Step 3 edits land.
4. **`ConflictDetail.CloudDetail` + `CloudOnlyWorldSummary` record extensions**
   — depends on step 3 existing (both are populated from
   `WorldSaveSyncService`/`CloudOnlyWorldsFacade`, which in turn depend on
   `WorldCloudMetadata` existing from step 1).
5. **`CloudOnlyWorldsFacade` update** — depends on step 4's
   `CloudOnlyWorldSummary` shape and needs a way to look up `WorldCloudMetadata`
   per world (likely via a new constructor dependency on `WorldSaveSyncService`
   or a shared metadata-cache type — decide exact wiring at implementation
   time; this facade currently has zero dependency on `WorldSaveSyncService`,
   so this is a new coupling to introduce carefully, on top of the sibling
   feature's already-completed `CloudSyncCoordinator` wiring).
6. **`WorldsPanel` (all 3 platforms) + `WorldConflictScreen` (all 3
   platforms)** — depends on steps 3-5's data being available; UI-only,
   lands last, `fabric-26.2` first with tests, other two diff+compile.
7. **Full multi-module compile + test-suite checkpoint** after each of steps
   2-6 that introduces an interface/record shape change (mirrors the sibling
   plan's own "Step 4" checkpoint pattern) — in particular after step 3
   (the `syncWorldNow` signature change ripples through every call site in
   the same file) and after step 4 (record shape changes ripple through both
   API consumers and this feature's implementation).

## Risks

1. **`LevelDatBatch` plumbing into `syncWorldNow`'s checkpoints is new
   callback wiring, not just a data-shape change** — this is the single
   biggest structural risk in this plan, exactly as flagged in the task
   framing. Today, `onWorldUnload`/`onWorldSaved`/`checkAndUploadStaleWorldsAtStartup`
   are called from platform composition-root checkpoints that do not read
   `level.dat` NBT at all (that read only happens today inside a platform
   `WorldsPanel` immediately before calling `detailFor`, i.e. only when a
   conflict screen is about to open). Wiring a `LevelDatBatch` into
   `syncWorldNow`'s checkpoints means either (a) the platform composition
   root's world-unload/save mixins/hooks must newly open a
   `LevelStorageAccess`-equivalent read at every sync checkpoint (a
   meaningfully more expensive, more frequent operation than today's
   conflict-screen-only read), or (b) `WorldSaveSyncService` accepts a
   narrower, cheaper subset of raw values the caller already has cheaply
   available at that checkpoint (e.g. from `LevelSummary`/`ServerData`
   already in hand, without a full NBT batch read) — the spec's Architecture
   section explicitly leaves this as an open choice ("or the caller passes
   just the raw NBT-derived values it already has easy access to"). This
   plan defers the exact resolution to implementation time but flags it as
   the step most likely to require its own follow-up investigation into
   what each of the three platform composition roots' `onWorldUnload`/
   `onWorldSaved`/startup-checkpoint call sites can cheaply supply without a
   new expensive read per save.
2. **Hashing cost on large worlds**: SHA-256 over an entire world folder
   (up to the existing 1024 MB `MAX_WORLD_ARCHIVE_SIZE_MB` threshold, and
   unboundedly larger for `SKIPPED` worlds since the metadata file still
   uploads even when the archive itself is skipped per Requirement 3) is a
   CPU-bound full-file-read pass. The spec's Performance section frames this
   as "no meaningfully new I/O cost" because it rides the same `Files.walk`
   already performed for `computeFolderSizeBytes` — but that existing walk
   only calls `Files.size(path)` (metadata-only, no file *content* read),
   whereas hashing requires reading every byte of every file. This is a
   materially larger I/O+CPU cost than the spec's framing suggests for very
   large `SKIPPED` worlds specifically (a world large enough to skip archive
   upload but still gets a full-content hash computed on every sync
   checkpoint). Flag this discrepancy for implementation-time confirmation
   that hashing a `SKIPPED`-strategy world's full folder content on every
   checkpoint is still acceptable, or whether `contentSignature` should be
   computed only for `WHOLE_ARCHIVE`-strategy worlds (a narrower reading of
   Requirement 3 that would need explicit confirmation, since Requirement 3's
   literal text says the metadata file itself — not necessarily every one of
   its fields — uploads even when `SKIPPED`).
3. **Keeping the three platform `WorldConflictScreen.java` copies (and three
   `WorldsPanel.java` copies) in sync** — this repo's established convention
   (write-once-in-fabric-26.2-with-tests, diff+compile-verify the other two)
   mitigates but does not eliminate the risk of a copy-paste slip; the sibling
   plan's own Step 7 precedent is the model to follow, including its explicit
   "confirm `.claude/context/minecraft.md`'s cross-version table hasn't grown
   a new relevant entry" re-check step.
4. **`CloudFileStore.delete`'s no-op-implementation completeness is unverified
   in this planning pass** — this plan could not locate a `NoopCloudFileStore`
   production class (only `NoopWorldArchiveCloudStore` was found, for the
   *other* interface); confirm at implementation time whether
   `CloudSyncCoordinator` falls back to an inline anonymous `CloudFileStore`
   when Steam is unavailable, or a named no-op class, before adding `delete`'s
   no-op body.
5. **`SteamRemoteStorage.fileDelete`'s exact signature is already accepted as
   verified by the sibling feature's own completed `deleteWorldArchive`
   implementation** — this plan inherits that verification rather than
   re-doing it (no new risk here beyond what the sibling plan already flagged
   and, per this plan's Existing Implementation section, appears resolved).

## Test strategy

1. **`WorldCloudMetadataIOTest`** (new file,
   `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/config/`):
   - Round-trip: serialize a fully-populated `WorldCloudMetadata` (including
     non-null `seed`/`iconBase64`), parse the result, assert field-for-field
     equality.
   - Round-trip with all nullable fields `null` (`seed`, `iconBase64`,
     `minecraftVersion`) — assert the parsed result also has `null` for each,
     not a sentinel/empty-string substitute.
   - Blank/null content → `ParseResult(null, null)` (no warning, "not yet
     synced" per Compatibility section).
   - Malformed JSON → `ParseResult(null, non-null warning)`, never throws.
   - `schemaVersion` newer than `CURRENT_SCHEMA_VERSION` → parses recognized
     fields successfully, returns a non-fatal warning (Compatibility
     section's forward-tolerance requirement) — construct a hand-written JSON
     string with `"schemaVersion": 2` plus an extra unrecognized key to
     exercise this explicitly.
2. **Content-hash determinism** (either in `WorldCloudMetadataIOTest` or a new
   small test class, or inside `WorldSaveSyncServiceTest` if the hash
   computation stays a private method there): given a fixed `@TempDir` world
   folder with known file contents, assert the computed `contentSignature` is
   stable across repeated calls (determinism) and changes when any file's
   bytes change (sensitivity) — mirrors `computeFolderSizeBytes`'s own
   `@TempDir`-based plain-JVM testability precedent (NFR1).
3. **`WorldSaveSyncServiceTest`** additions:
   - `syncWorldNow` (invoked directly per this class's existing
     package-private-test-access pattern) with a fake `CloudFileStore`:
     assert a metadata file is written to `metadataFileName(worldSlug)` on a
     successful `WHOLE_ARCHIVE` sync, containing the expected fields sourced
     from the (fake/injected) `LevelDatBatch` and world folder.
   - Same, but with `strategy == SKIPPED` (an over-threshold world): assert
     the metadata file is still written (Requirement 3's "uploaded even when
     `SKIPPED`" clause) even though no archive write occurs — this is the
     single most spec-load-bearing new test case in this plan.
   - `cloudMetadataFor(worldSlug)`: present-file case (returns a populated
     `Optional`) and missing-file case (`Optional.empty()`, no exception).
   - `detailFor`: with a metadata file present, assert `CloudDetail`'s new
     fields are sourced from it; with a metadata file absent (fingerprint
     exists but no metadata — the Compatibility "old world" case), assert
     `CloudDetail` falls back to exactly today's fingerprint+archive-size-only
     shape with the new fields at their null/sentinel defaults, and that this
     does not throw or return `null` for the whole `ConflictDetail` (only a
     genuinely missing *fingerprint* should still return `null`, per today's
     existing 519-521 behavior — unchanged).
   - `handleSyncDisabled`: extend the existing success-path test (per the
     sibling plan's Step 6 test coverage, already presumably present since
     the sibling implementation is complete) to additionally assert
     `cloudFileStore.delete(metadataFileName(worldSlug))` is invoked; add a
     case where the archive delete succeeds but the metadata delete's fake
     returns `false` — assert this does not surface as a second player-facing
     failure message (per this plan's Files-to-modify #1 UX default) and does
     not prevent `statusTracker.clearStatus`/fingerprint-removal from having
     already completed.
   - Every test-double `implements CloudFileStore`/`WorldArchiveCloudStore`
     in this file must gain the new interface method(s) to keep compiling —
     enumerate and update each at implementation time (mirrors the sibling
     plan's own Step 5 test-fallout instruction).
4. **`CloudSyncCoordinatorTest`**: search for any direct
   `implements CloudFileStore`/`WorldArchiveCloudStore` construction and
   update for the new `delete` method; no behavioral test is expected here
   beyond compile-fallout, per the sibling plan's own established precedent
   for this file (Existing Implementation note: it does not construct
   `SteamCloudSyncConfig` directly and is unlikely to need deep behavioral
   coverage for this spec's changes either, but must still compile/run
   green).
5. **`fabric-26.2` `WorldConflictScreen`** (or wherever `valuesMatch`/
   `pairedRows` already have pure-static-helper test coverage, locate exact
   file at implementation time): add a case asserting the new "Content match"
   row uses `contentSignature` equality, not byte-size equality, for its
   match/mismatch color decision — this is the test that directly proves
   Requirement 6's false-conflict fix (two different-sized-but-content-identical
   inputs, or the reverse: same-size, different-hash, must mismatch).
6. **`fabric-26.2` `WorldsPanel`** (icon/richer-field rendering): if this
   file already has a pure-static-helper test class (per the sibling plan's
   own `WorldsPanelStatusTest` precedent), no new static helper is
   necessarily introduced by this spec's UI changes (icon texture caching and
   field formatting are largely inline/rendering code, not pure functions) —
   if a pure formatting helper is extracted during implementation (e.g. "how
   to render `lastPlayedMillis`/`seed` as strings"), add a unit test for it
   there; otherwise this UI surface is compile-verified only, consistent with
   this repo's general pattern of not unit-testing GL-rendering code directly.
7. **`fabric-1.21.11`/`fabric-26.1`** (`WorldConflictScreen`, `WorldsPanel`):
   no new test files, per this repo's established three-copy convention —
   diff-against-`fabric-26.2` + per-module compile verification only.

## Acceptance criteria

Tied to the spec's Requirements list (`.claude/specs/cloud-world-metadata-file.md`,
"Requirements" section, items 1-10):

1. A new, non-archived JSON Cloud object (`lazuli-world-meta-<slug>.json`) is
   uploaded per world, independent of that world's archive zip (Req. 1).
2. `WorldCloudMetadata` carries every field listed in Req. 2, with
   `contentSignature` as a SHA-256 hex digest and `iconBase64` nullable
   (Req. 2, Decisions 1-2).
3. The metadata file is written/refreshed at every existing archive-write
   checkpoint (`onWorldUnload`, `onWorldSaved`, `handleSyncReenabled`'s
   post-check path, `checkAndUploadStaleWorldsAtStartup`'s stale-upload path)
   via `syncWorldNow`, with no new `CloudSyncCoordinator` checkpoint wiring,
   and is uploaded even when the archive strategy is `SKIPPED` (Req. 3).
4. `WorldConflictResolutionHook.detailFor`'s `CloudDetail` is populated from
   the new metadata file when present (Req. 4).
5. `CloudOnlyWorldsFacade`/`CloudOnlyWorldSummary` expose the richer fields,
   including a decoded icon where present, for a Cloud-only world (Req. 5).
6. `WorldConflictScreen`'s "Size" row is replaced by a `contentSignature`-driven
   match/mismatch row across all three platform copies; raw byte sizes may
   remain as non-color-coded informational text (Req. 6).
7. `WorldCloudMetadata`/`WorldCloudMetadataIO` carry a `schemaVersion` field,
   independently versioned from `SteamCloudSyncConfig`'s own (Req. 7).
8. A missing metadata file degrades gracefully to today's
   fingerprint+archive-size behavior in both `detailFor` and
   `CloudOnlyWorldsFacade`, with no crash/blank-field regression, verified by
   an explicit `Optional.empty()`-path test in each consumer (Req. 8).
9. All new Cloud I/O for the metadata file goes through `CloudFileStore`
   (`read`/`write`/new `delete`), never a new Steam-API-touching class
   (Req. 9).
10. `CloudFileStore.delete(String)` exists, is implemented for the real Steam
    backing and every test double, and is wired into the sibling feature's
    `handleSyncDisabled` so un-syncing a world deletes both the archive and
    the metadata file, leaving no orphan (Req. 10, Decision 3).

Additionally: the false-conflict symptom described in the spec's Overview
(non-deterministic zip compression triggering a spurious "mismatch" on the
Size row) must be demonstrably fixed by the Req. 6 test in Test Strategy
item 5 — two archives with byte-identical *content* but different compressed
sizes must no longer render as a mismatch once `contentSignature` drives the
comparison.

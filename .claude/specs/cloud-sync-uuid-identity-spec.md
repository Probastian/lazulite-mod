# Cloud Sync UUID Identity Specification

> **Revision note**: this spec originally proposed a Cloud-key-only UUID
> (local save folder left untouched). After reviewing that design's
> tradeoffs with the user -- including that vanilla Minecraft already
> auto-suffixes local folder names on collision (`"New World"` ->
> `"New World (2)"`), and that renaming a live save folder is a materially
> riskier, higher-blast-radius change than a purely-internal Cloud-key
> mapping -- **the user explicitly chose the higher-risk option anyway: the
> local save folder itself becomes the UUID.** This revision supersedes the
> "Decision" section below and everything downstream of it accordingly. No
> code was ever written against the superseded Cloud-key-only design (this
> spec was never implemented), so this revision is a clean replacement, not
> a migration from one already-shipped design to another.

## Overview

Today, a Cloud-synced world's Steam Cloud storage identity ("`worldSlug`")
is literally the local save-folder name. This value is threaded, unchanged,
through the entire feature:

- `WorldSaveSyncService.archiveFileName(worldSlug)` /
  `metadataFileName(worldSlug)` build the Cloud file names
  (`features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java:792-803`).
- `WorldFingerprint.worldSlug()` and `WorldCloudMetadata.worldSlug()`
  (`.../api/WorldFingerprint.java:24`, `.../api/WorldCloudMetadata.java:49-61`)
  store it as the primary key of the all-worlds fingerprint list and the
  per-world metadata file.
- `WorldSyncAncestor` (local-only "last known common ancestor" cache) and
  `WorldSyncPreference` (local-only per-world sync toggle) are both keyed by
  the same string.
- `CloudOnlyWorldDetector.detect` (`.../services/CloudOnlyWorldDetector.java:41-60`)
  does a straight set-difference between local save-folder names and
  fingerprint `worldSlug`s to find "cloud-only" (never-restored-here)
  worlds.
- `WorldRestoreService.beginRestore` (`.../services/WorldRestoreService.java:104-169`)
  resolves the restore target as `savesDirectory.resolve(worldSlug)` --
  i.e. it assumes the Cloud key *is* the folder name it should create
  locally.
- Every platform `WorldsPanel.java` (`platform/fabric-1.21.11`,
  `platform/fabric-26.1`, `platform/fabric-26.2`, package
  `de.lazuli.mainmenu`) obtains `worldSlug` from
  `summary.getLevelId()` (vanilla's own save-folder name) for a real local
  world, and from `CloudOnlyWorldSummary.worldSlug()` for a cloud-only row,
  and passes it into every hook call
  (`WorldFreshnessHook`/`WorldConflictHook`/`WorldSyncToggleHook`/
  `WorldRestoreHook`/etc.) verbatim. `WorldsPanel.launchWorld` even calls
  `Minecraft.getInstance().createWorldOpenFlows().openWorld(worldSlug, ...)`
  directly -- vanilla's own Singleplayer world list is keyed by this exact
  folder name (its `LevelStorageSource.LevelDirectory`/`levelId`).

**The bug**: if a player has, or creates, two different local worlds that
happen to share the same save-folder name (Minecraft auto-generates folder
names from the world's display name and silently disambiguates on-disk with
suffixes like `_1`/`_2` only when both are created in the *same* saves
directory in the *same* session of world-creation; two independently
created/imported/copied worlds, e.g. from two different installs, a manual
copy, or an external backup restore, can easily collide on folder name),
syncing the second one overwrites the first one's Cloud archive under
`archiveFileName(worldSlug)` -- silent, unrecoverable data loss with no
warning beyond the pre-existing "different device" fingerprint check (which
does not fire for a same-folder-name collision on the *same* device, and
even where it does fire for a genuine two-device case, it is only a
warning, not a block).

This feature gives every Cloud-synced world a stable, unique identity -- a
generated UUID -- used both as the Cloud storage key **and, per this
revision, as the actual on-disk local save-folder name**. **The
player-visible world name (vanilla `level.dat`'s `LevelName`, shown
everywhere this feature or vanilla itself displays a world's name) must
never change as a result of this feature.** Only the folder's own name (a
detail no ordinary player-facing UI in this feature or vanilla ever shows
as "the world's name") becomes a UUID.

## Risk (read this before planning)

**This is now a substantially higher-risk change than a Cloud-key-only
UUID would have been**, because it renames the actual on-disk directory
Minecraft's Fabric loader/`LevelStorageSource` is actively using to read
and write live save data, instead of only changing an internal Cloud
bookkeeping value. Planning must treat the following as first-class
concerns, not implementation details to figure out later:

1. **Windows file locking.** Vanilla's `LevelStorageSource.LevelStorageAccess`
   holds a `session.lock` file lock (via a `FileChannel` lock, or an
   OS-level exclusive handle depending on Minecraft version) for as long as
   a world is open, specifically to prevent two processes/sessions from
   opening the same save concurrently. On Windows in particular, a
   `Files.move` targeting a directory whose lock file is still held (even
   transiently, during an in-progress shutdown) throws
   `AccessDeniedException`/`IOException` rather than silently succeeding
   like it more often does on Unix filesystems. **A folder must never be
   renamed while there is any chance Minecraft still has it open**,
   including the short window immediately after a player disconnects,
   during which the integrated server's own shutdown/save sequence may
   still be finishing on another thread and the lock may not yet be
   released. See Architecture -- Rename Timing below for exactly which
   checkpoints are safe and which are not.
2. **Partial/interrupted rename.** A rename that fails halfway (Windows in
   particular does not guarantee `Files.move` across two calls is atomic if
   retried, though a same-volume single-directory rename call itself is
   atomic) must never leave a world un-openable, un-findable, or duplicated.
   This spec's design (Architecture -- Migration & Rename Sequencing)
   requires that the physical rename only ever be attempted as an isolated,
   independently-retryable step, never interleaved with Cloud I/O, and
   requires a small durable local breadcrumb so a crash between "Cloud data
   migrated" and "folder renamed" (or vice versa) is safely resumable
   rather than silently orphaning data or minting a second, unrelated
   identity for the same world.
3. **Vanilla's own in-memory world-list cache.** `WorldsPanel` (all three
   platforms) caches `List<LevelSummary> summaries`, refreshed via
   `levelSource.findLevelCandidates()`/`loadLevelSummaries(...)`
   periodically -- see `platform/fabric-26.2/.../mainmenu/WorldsPanel.java:208,245-248`.
   If a folder is renamed by this feature between refreshes, any code still
   holding the *old* `LevelSummary`/`getLevelId()` value for that row (e.g.
   an in-flight click handler, a cached row-expansion id keyed by the old
   folder name) can reference a folder that no longer exists. Planning must
   ensure a rename is always immediately followed by (or only performed as
   part of) a forced re-scan of `summaries`, and that no other per-row
   cached state (expanded-row id, freshness/conflict caches keyed by
   folder name) survives a rename without being re-keyed or cleared.
4. **Other software that remembers a world by folder name.** Anything
   outside this feature's control that persists a reference to a save by
   its folder name (external backup tooling, another mod, OS-level
   shortcuts/bookmarks, a player's own memorized path) breaks silently the
   moment this feature renames that folder out from under it. This is an
   accepted, user-approved tradeoff (the user was told this and chose UUID
   folders anyway), not something this spec attempts to mitigate further,
   but it is called out here so planning does not have to relitigate it.

Given these, planning should treat "when exactly is a rename safe to
attempt" and "what happens if it fails partway" as the two hardest problems
in this feature -- harder than anything in the original Cloud-key-only
design, which never had to answer either question at all.

## Decision: local folder is renamed to the UUID; the Cloud key is the same UUID

The user's request could be read as (a) only the Cloud-facing key changes,
local folder untouched, or (b) the local save folder itself is renamed to
the UUID, with the display name preserved purely via `level.dat`. **This
revision adopts (b)**, per the user's explicit, informed instruction:
*"I want the folders to have UUID folder names. With that change I will
approve the spec, head on to planning."*

This was not an oversight on the user's part -- they were told, before
making this call, that (1) vanilla Minecraft's own world-creation flow
already prevents the specific *local* collision this feature exists to fix
(auto-suffixing `"New World"` -> `"New World (2)"`), and (2) renaming a
live save folder is a strictly riskier operation than a Cloud-key-only
mapping, including making it harder for a player who manually browses
`run/saves/` to identify a specific world by folder name alone. The user
weighed this and chose UUID folder names regardless. This spec does not
re-litigate that tradeoff; it designs the mechanism to implement it as
safely as the constraints above allow.

## Goals

1. Every world that is (or has ever been) Cloud-synced by this feature gets
   a stable, globally-unique identity (a UUID string, "`cloudWorldId`")
   that is used **both** as the Cloud storage key for its archive, its
   per-world metadata file, and its fingerprint-list entry, **and** as the
   name of its local on-disk save folder.
2. Two different local worlds that happen to share the same display name
   (or, today, the same folder name) can both be synced to Cloud without
   either one's archive ever overwriting the other's -- the core bug this
   feature exists to fix.
3. The player-visible world name (vanilla `level.dat` `LevelName`, and
   every screen in this feature or vanilla that shows a world's name) never
   changes as a result of this feature, and a UUID is never shown to the
   player as a world's name anywhere in the UI.
4. A local save folder that has been renamed to its `cloudWorldId` is
   thereafter self-identifying: no separate persisted mapping is required
   to know "which folder is which world" in steady state, since the
   folder's own name *is* the identity (see Architecture -- Why No
   Permanent Mapping File Is Needed).
5. A world already synced under the old, name-keyed scheme migrates to a
   `cloudWorldId` (both its Cloud data and, once safe to do so, its local
   folder) automatically, with no orphaned/duplicate Cloud data, no loss of
   the existing Cloud archive, and no world ever left un-openable by a
   failed or interrupted rename.
6. A local folder is never renamed while there is any realistic chance
   Minecraft still has it open (Risk #1/#3 above) -- Cloud-identity
   resolution (Cloud-key migration) and the physical folder rename are two
   independently-timed steps, and the rename step only ever runs at a
   checkpoint that is guaranteed to be at the main menu, no world loaded.
7. The cloud-only restore/download flow (`WorldRestoreScreen`,
   `WorldRestoreService`, `CloudOnlyWorldSummary`) continues to work
   end-to-end: the player still sees a human-readable name in every log/
   toast/screen, the download/restore still operates on the correct Cloud
   object, and the newly-created local folder is named directly with its
   `cloudWorldId` (no collision-avoidance logic is even needed here, since
   a UUID cannot practically collide with an existing folder).

## Non-goals

- Any change to vanilla's own save-folder naming/disambiguation behavior
  for a world that has **not yet** been synced by this feature -- untouched
  until the moment this feature's migration/rename logic first touches it.
- Renaming a folder that has never had Cloud sync enabled, is not
  currently synced, and is not itself a restored cloud-only world. A
  folder is only ever renamed by this feature once its owning world
  participates in Cloud sync (see Architecture -- Rename Scope).
- Cross-device/cross-version migration compatibility for a fleet of devices
  where some have upgraded to this feature and some have not. This spec
  assumes (consistent with this feature's existing fingerprint/conflict
  design, which already assumes every device runs compatible logic for
  `deviceLabel`/`syncedAtTimestamp` semantics) that a player upgrades every
  device they sync between to a build containing this feature before
  relying on cross-device sync again; a stale, pre-upgrade device syncing
  against an already-migrated Cloud fingerprint list is an accepted,
  documented gap (see Compatibility), not solved by this feature.
- Deduplicating/merging two genuinely different worlds that happen to
  already share a `worldSlug`-keyed Cloud archive from *before* this
  feature shipped (i.e. a pre-existing collision that already silently
  overwrote one world's data in the old scheme). This feature stops *new*
  collisions; it cannot un-overwrite Cloud data already lost under the old
  scheme.
- Any change to the whole-archive-vs-skip size threshold, conflict
  detection algorithm, or freshness classification logic themselves --
  only the identity/key they operate on changes.
- A UI surface for players to see/manage `cloudWorldId` values directly
  (a UUID-named folder in an OS file browser is the one place this feature
  necessarily surfaces the raw UUID -- this spec does not add anything
  further, e.g. no "reveal folder" button, no tooltip showing the UUID).
- Handling a local save folder being copied/duplicated on disk (e.g. a
  manual OS-level copy, or a "Make a Copy" feature this mod does not have
  today) such that two folders share one already-assigned `cloudWorldId`.
  See Future Extensions.
- Mitigating breakage in external tools/other mods that remember a save by
  its old, human-readable folder name (Risk #4) -- an accepted, explicitly
  user-approved consequence of this design, not addressed further here.
- **Bug #3 (`gameMode` showing "Unknown")** -- unrelated, explicitly out of
  scope, as in all prior work on this feature.

## Requirements

### FR1 -- Identity concept: the folder name *is* the Cloud key
FR1.1. Introduce `cloudWorldId` (a `java.util.UUID`, stored/transmitted as
its canonical string form) as the sole Cloud-facing key for a world's
archive, per-world metadata file, and fingerprint-list entry -- exactly as
originally scoped. **What changes in this revision**: once a world has been
fully migrated (Architecture -- Migration & Rename Sequencing), its local
save-folder name is *also* set to this same string, so `cloudWorldId` and
"the folder's current name" become the same value by construction, for
every world this feature has ever finished migrating.

FR1.2. A folder whose current name parses successfully as a UUID
(`UUID.fromString(folder.getFileName().toString())` does not throw) is, by
definition, already fully migrated: its `cloudWorldId` **is** that parsed
UUID, resolvable with zero I/O and no lookup file (Goal 4). This is the
steady-state fast path and is expected to cover the overwhelming majority
of `resolveCloudWorldId` calls once a device has been running this feature
for more than one session.

FR1.3. A folder whose current name does **not** parse as a UUID is either
(a) a world that has never had Cloud sync enabled (no `cloudWorldId` needed
yet -- untouched, per Non-goals), or (b) a world mid-migration: its
Cloud-side data has already been (or is about to be) moved to a
freshly-minted `cloudWorldId`, but the physical rename to match hasn't
happened yet because no rename-safe checkpoint has occurred since. See FR2
for the full migration/rename sequence and Architecture -- Rename Timing
for why these two steps are deliberately decoupled.

FR1.4. Resolution happens lazily, at exactly the checkpoints that already
gate on `preferenceService.isSyncEnabled(...)` being true or about to
become true: `WorldSyncPreferenceService`'s disabled->enabled toggle
(`toggleSync`/`onSyncEnabledListener`, wired to
`WorldSaveSyncService.handleSyncReenabled`), and every existing sync
checkpoint in `WorldSaveSyncService` (`onWorldUnload`, `onWorldSaved`,
`checkAndUploadStaleWorldsAtStartup`) which must resolve (not silently
skip) identity/migration for a world whose preference is already enabled
from a previous session. A folder is never touched (no Cloud I/O, no
breadcrumb write, no rename attempt) for a world whose sync preference is,
and has always been, disabled.

FR1.5. **Un-syncing a world does not undo migration.** If a world's folder
has already been renamed to its `cloudWorldId` by the time the player
disables sync for it (`handleSyncDisabled`), the folder is **not** renamed
back to a human-readable name. Reverting a UUID folder name back to a
guessed-at human name is out of scope (Non-goals) -- the folder's `level.dat`
`LevelName` remains correct and is all vanilla ever needs to display it
sensibly; only the on-disk directory name stays UUID-shaped going forward,
which is the accepted, informed cost of this design (Decision).

### FR2 -- Migration for a world already synced under the old scheme
This is the highest-risk requirement in this spec (see Risk). It is split
into two independently-timed phases so that a physical folder rename is
never on the critical path of an otherwise-safe Cloud sync operation, and
is never attempted at a checkpoint that cannot guarantee the world is
unloaded.

FR2.1. **Phase A -- Cloud-side identity resolution (safe at any of the four
checkpoints in FR1.4, including mid-session).** When `resolveCloudWorldId`
is called for a folder whose current name is not yet UUID-shaped (FR1.3),
and no in-progress migration breadcrumb exists for it (FR2.4):
1. Check whether this folder was already Cloud-synced under the old scheme:
   does a Cloud archive exist at `archiveFileName(currentFolderName)` (the
   pre-this-feature key)? A single `archiveStore.fileSize(...)` call,
   exactly as originally scoped (already how `WorldRestoreService.beginRestore`
   checks existence today, lines 127-131).
2. Generate a new `cloudWorldId` (`UUID.randomUUID()`) regardless of the
   check's result -- there is no previously-assigned UUID to discover or
   reconcile against for any world in the wild today, since the
   Cloud-key-only design this spec supersedes was never implemented or
   shipped (Revision note). The only pre-existing Cloud data any real
   device can have is old-worldSlug-keyed data from before this feature
   existed at all.
3. Write a durable local breadcrumb (FR2.4) recording
   `{oldFolderName, cloudWorldId, cloudMigrated=false, renamed=false}`
   *before* doing any Cloud I/O, so a crash immediately after generating
   the UUID still leaves a resumable record of which UUID this folder
   committed to (never re-mint a second UUID for the same folder on retry).
4. If an old-keyed archive was found (migration path): read its bytes and
   re-write them under `archiveFileName(cloudWorldId)`; if a per-world
   metadata file exists at the old key, copy it to `metadataFileName(cloudWorldId)`
   (rewriting its embedded `worldSlug` field to the new value, as originally
   scoped); rewrite this world's `WorldFingerprint` list entry so
   `worldSlug` holds `cloudWorldId` (same `displayName`/`deviceLabel`/
   `syncedAtTimestamp` as before); only once all of that has succeeded,
   delete the old-keyed archive and metadata file (real delete, matching
   `handleSyncDisabled`'s existing precedent). This is unchanged from the
   original spec's FR2.2 steps 2-5 in substance; see that section's
   reasoning for why each sub-step is ordered this way.
5. If no old-keyed archive was found (fresh-world path): there is nothing
   to copy; proceed directly to marking the breadcrumb's Cloud-side step
   complete.
6. Update the breadcrumb to `cloudMigrated=true`. The folder itself is
   **not** touched in Phase A -- it keeps its current (human-readable) name.
7. If any Cloud I/O step fails, leave the old-keyed archive/metadata/
   fingerprint entry untouched, leave the breadcrumb at `cloudMigrated=false`,
   and retry from step 4 on the next checkpoint (the breadcrumb already
   pins the `cloudWorldId` to use, so retrying never mints a second one).

FR2.2. **Phase B -- physical folder rename (only at a checkpoint guaranteed
to be at the main menu, no world loaded).** The only two checkpoints that
qualify are `CloudSyncCoordinator.reconcileAtStartup()`'s
`checkAndUploadStaleWorldsAtStartup(listKnownWorlds())` pass (client just
started, definitionally no world loaded yet) and
`WorldSyncPreferenceService`'s sync-enable toggle
(`handleSyncReenabled`, only reachable from the Worlds-tab main-menu
screen, which is mutually exclusive with being inside a loaded world).
`onWorldUnload` (fires at disconnect, timing relative to the integrated
server's own shutdown/lock-release is not guaranteed -- Risk #1) and
`onWorldSaved` (fires **while the world is actively loaded and playing**)
must **never** attempt a rename; they may still run Phase A. For every
local folder with a breadcrumb at `cloudMigrated=true, renamed=false`
found at one of the two qualifying checkpoints:
1. Attempt `Files.move(savesDirectory.resolve(oldFolderName), savesDirectory.resolve(cloudWorldId.toString()))`
   (same parent directory, same filesystem -- a single-directory rename,
   the safest form of `Files.move`).
2. On success: update the breadcrumb to `renamed=true` (or delete it
   entirely -- once both flags are true the breadcrumb has no further
   purpose, since the folder's own new name is now self-describing, FR1.2);
   call `WorldSyncPreferenceService`'s new re-keying method (Public API) so
   the sync-enabled preference entry follows the folder to its new name;
   force an immediate re-scan of `WorldsPanel`'s cached `summaries` list
   (Risk #3) and clear/re-key any other row-keyed local cache (expanded-row
   id, freshness/conflict caches) that referenced the old folder name.
2. On failure (locked file, antivirus scan, any `IOException`): log via
   `warningLogger`, leave the breadcrumb at `renamed=false`, leave the
   folder under its old name, and retry at the next qualifying checkpoint.
   A world stuck in this state continues to sync and function completely
   normally under its already-migrated `cloudWorldId` Cloud key throughout
   (Phase A is already done) -- a slow-to-rename folder is a cosmetic
   delay, never a functional regression.

FR2.3. Because Phase A and Phase B are decoupled, a freshly-toggled-on,
never-before-synced world (the common "I just made this world and turned
sync on from the Worlds tab" case) has both phases available
back-to-back at the same checkpoint (`handleSyncReenabled` fires from the
main menu, so Phase B can run immediately after Phase A with no waiting) --
in practice, a newly-synced world's folder is renamed essentially
immediately. Only an existing, already-synced-from-a-previous-build world
whose Phase A first happens to run mid-session (`onWorldUnload`/
`onWorldSaved`) waits until the next main-menu checkpoint for Phase B.

FR2.4. **Migration breadcrumb persistence.** A small local-only,
never-Cloud-synced JSON file, `world-cloud-migration.json` (same
`featureConfigDir` location/style as `WorldSyncPreferencesIO`'s own file),
holds one entry per folder currently mid-migration:
`{oldFolderName, cloudWorldId, cloudMigrated, renamed}`. An entry is
removed once both flags are `true` (or, equivalently, may be left in place
harmlessly forever -- planning's call, since a completed entry is never
consulted again once the folder's own name already satisfies FR1.2). This
file is expected to be empty, or contain only a handful of transient
entries, in steady state -- it exists purely for migration crash-recovery
and the FR4 transient-window fix below, **not** as a permanent
folder-to-identity mapping (see Architecture -- Why No Permanent Mapping
File Is Needed for why this revision does not need one).

FR2.5. Migration (both phases) must run on `CloudSyncWorker`'s existing
background thread for all Cloud I/O (Phase A) and for the `Files.move`
call itself (Phase B) -- never inline on the client tick/render thread,
consistent with every other Cloud-I/O and filesystem-touching path in
`WorldSaveSyncService`.

FR2.6. Once a folder's breadcrumb reaches `cloudMigrated=true`, `renamed=true`
(or is removed), or once a folder's current name already parses as a UUID
(FR1.2), `resolveCloudWorldId` never re-runs FR2.1's existence check or
Phase B's rename attempt for it again -- a player manually deleting the
Cloud archive out-of-band afterward does not "undo" migration or trigger a
second attempt, matching the original spec's FR2.5 reasoning.

### FR3 -- Every existing Cloud-key call site uses `cloudWorldId`
FR3.1. `WorldSaveSyncService.archiveFileName(String)` and
`metadataFileName(String)` are called with a `cloudWorldId`, not a raw
folder name, at every call site listed in Architecture -- Call Sites
Requiring Change below. Because of FR1.2, for a fully-migrated world this
is simply "call it with the folder's own current name" -- no separate
resolution step is visible to most callers.

FR3.2. `WorldFingerprint.worldSlug()` and `WorldCloudMetadata.worldSlug()`
hold a `cloudWorldId` going forward (field name/shape kept as-is per
Compatibility -- only the *meaning* of the existing `worldSlug` field
changes).

FR3.3. `WorldSyncAncestor` (the local-only per-device "last known common
ancestor" cache) is re-keyed to `cloudWorldId` as well, unchanged from the
original spec's reasoning (FR3.3) -- it must use the same key space as the
Cloud fingerprint list it is compared against.

FR3.4. `WorldSyncPreference` (the local, per-folder sync-toggle list) stays
keyed by "the local folder's current name" -- which, for a fully-migrated
world, is the `cloudWorldId` itself, and for a not-yet-renamed world is
still its old human name. **New in this revision**: when Phase B (FR2.2)
successfully renames a folder, the matching `WorldSyncPreference` entry
must be re-keyed from the old name to the new name in the same operation
(Public API -- `WorldSyncPreferenceService.renameKey`), or a sync-enabled
world would silently appear sync-disabled the moment its folder is renamed
(the preference lookup would miss under the new folder name).

FR3.5. `CloudOnlyWorldDetector.detect`'s comparison basis is **simpler**
than the original Cloud-key-only spec required it to be -- see FR4.

### FR4 -- Cloud-only-world detection
FR4.1. Because a fully-migrated local world's folder name and its
fingerprint's `worldSlug` are now **the same string** (both `cloudWorldId`),
`CloudOnlyWorldDetector.detect`'s existing plain set-difference (local
folder names vs. fingerprint `worldSlug`s) is correct again, unmodified in
shape, for the steady-state case -- this is a meaningful simplification
versus the original Cloud-key-only spec, which required inventing a
reverse-lookup-based comparison (its own FR4) specifically because folder
name and Cloud key were permanently decoupled there. Under this revision
they converge once migration finishes, so the original, simpler comparison
is valid again for any world past Phase B.

FR4.2. **The one case FR4.1 alone gets wrong**: a world whose Phase A has
completed but whose Phase B (rename) has not yet run (FR2.3's "waits for
the next main-menu checkpoint" case) has a fingerprint `worldSlug` of
`cloudWorldId` but a local folder still under its old human name -- a plain
set-difference would incorrectly report it as cloud-only (a phantom
"available to download" entry for a world the player already has locally
and is actively using). `CloudOnlyWorldDetector` must additionally treat
any `cloudWorldId` with a `cloudMigrated=true` entry in
`world-cloud-migration.json` (FR2.4) as "known locally," regardless of
whether a same-named local folder exists yet. This is the one remaining
purpose of the migration breadcrumb file beyond crash recovery, and is why
FR2.4 keeps it as a real (if usually near-empty) file rather than only
transient in-memory state.

FR4.3. `CloudOnlyWorldDetector`'s public signature is therefore **unchanged**
from before this feature (`detect(List<String> localWorldFolderNames, List<WorldFingerprint> fingerprints)`)
plus one additional parameter for the small breadcrumb-derived set of
"cloudWorldIds with Cloud migration already done, rename still pending"
(exact shape left to planning -- e.g. `Set<String> pendingRenameCloudWorldIds`).
This is a narrower, cheaper addition than the original spec's full
mapping-file-based redesign of this class (its own FR4.1/FR4.2).

FR4.4. This remains a **pure, local, still-Steam-free** comparison
(`CloudOnlyWorldDetector`'s own javadoc: "Pure, plain-JVM-testable
set-difference logic") -- no new Cloud I/O.

### FR5 -- Cloud-only restore/download continues to work, and is simpler
FR5.1. `CloudOnlyWorldSummary.worldSlug()` becomes a `cloudWorldId`, exactly
as originally scoped; `displayName()` is unchanged and remains the sole
source of the human-readable name shown to the player.

FR5.2. `WorldRestoreService.beginRestore` creates the restored local folder
at `savesDirectory.resolve(cloudWorldId.toString())` **directly** -- this
is a meaningful simplification versus the original spec's FR5.3, which had
to sanitize and uniquify a folder name derived from `displayName` precisely
*because* it kept human-readable folder names. Since the restored folder is
named after a UUID, no sanitization or uniquification is needed: a random
UUID cannot practically collide with any existing folder, so the "does a
folder already exist at this candidate path" question becomes a pure
defensive safety net (still worth keeping, per the existing FR6.13-derived
check at `WorldRestoreService.java` lines 108-124) rather than a real
disambiguation mechanism.

FR5.3. `WorldRestoreHook.beginRestore` still gains a `displayName` parameter
(as the original spec's FR5.3/Public API proposed), but for a **narrower
reason** than before: it is needed only so this service's existing
player-facing log/toast strings (`"Downloading world \"" + worldSlug + "\"..."`,
`WorldRestoreService.java` lines 111, 121, 129, 136, 238) show the world's
real name instead of a raw UUID -- not for computing the target folder path
(FR5.2 already fixed that). Both existing callers
(`WorldsPanel.downloadAndPlay`/`downloadOnly` and `WorldConflictScreen`'s
"Keep Cloud" path) already have a `CloudOnlyWorldSummary`/display name in
hand at their call sites, unchanged from the original spec's assessment.

FR5.4. Because the restored folder is named directly with `cloudWorldId`,
it is **already** fully migrated the instant it is created -- FR1.2's
"folder name parses as a UUID" fast path applies immediately. **The
original spec's FR5.5 ("pre-seed the local mapping so a spurious second
migration doesn't trigger") no longer applies and is dropped**: there is no
permanent local mapping file left to seed (Architecture -- Why No Permanent
Mapping File Is Needed), and FR2.1's Phase A migration check is naturally
skipped for a folder whose name already parses as a UUID (FR1.2), so no
special-casing is needed at restore time beyond FR5.2's direct-UUID-naming.

FR5.5. `WorldSyncPreferenceService.markEnabledAfterRestore` is called
against the newly-created folder's name (`cloudWorldId.toString()`)
directly, consistent with FR3.4 (preferences are keyed by "the folder's
current name," which for a freshly-restored world already is its final
name).

### FR6 -- Conflict/freshness/status tracking follow the same key
FR6.1. `WorldSyncStatusTracker`, `WorldFreshnessHook`,
`WorldConflictHook`, `WorldConflictResolutionHook`'s existing
`String worldSlug` parameters are unchanged in shape. `WorldSaveSyncService`'s
own internal call sites continue to resolve "whatever folder name a caller
passed in" to the correct `cloudWorldId` before touching any Cloud-facing
data (Public API), exactly as the original spec's Public API section
already designed -- this internal resolution step is what transparently
absorbs the FR2.3 transient window (Phase A done, Phase B pending) without
any platform-layer code needing to know about it.

FR6.2. **Unlike the original spec's FR6.2, platform `WorldsPanel` code
requires no change for Cloud-key-resolution purposes.** Because
`summary.getLevelId()` already returns "the folder's current name" -- which
`WorldSaveSyncService`'s hook methods already resolve internally to the
correct `cloudWorldId` per FR6.1 -- `WorldsPanel` keeps calling every hook
(`WorldFreshnessHook`/`WorldConflictHook`/`WorldSyncToggleHook`/etc.)
exactly as it does today, with the same folder-name value it already reads
via `getLevelId()`. This removes the original spec's entire
`WorldCloudIdentityHook`/`WorldCloudIdentityHookHolder` platform-facing
interface addition (Public API) -- it is no longer needed.

FR6.3. `launchWorld`'s `openWorld(worldSlug, ...)` call and any
`LevelStorageSource`/folder-path-based call (`levelSource.getLevelPath(...)`,
`readLevelDatBatch`, `levelSource.createAccess(...)`) continue to use the
real, current local folder name -- which, for a migrated world, already is
the `cloudWorldId` -- with no special-casing needed, since vanilla itself
never cared what the folder is named, only that it exists and contains a
valid `level.dat`.

### FR7 -- Human-readable names at the two call sites that lack one today
FR7.1. `CloudSyncCoordinator` is deliberately kept Minecraft-type-free
(no dependency on `LevelStorageSource`/`LevelSummary`/etc.), which is why
its own `listKnownWorlds()` (`.../CloudSyncCoordinator.java:214-228`) and
its `onSyncEnabledListener`/`onSyncDisabledListener` wiring
(`.../CloudSyncCoordinator.java:167-176`) today pass the raw folder name as
a stand-in `displayName` -- see that file's own comment at lines 172-174:
*"This coordinator layer has no richer display name available at
listener-fire time... worldSlug is passed as both the slug and
display-name arguments."* This was a pre-existing, minor cosmetic
compromise while folder name and display name were usually similar; **it
becomes a real, player-visible regression under this revision**, since a
`cloudWorldId`-named folder is nothing like the display name, and this
placeholder value flows into `WorldFingerprint.displayName()` (visible to
*other devices* as this world's cloud-only listing name) and into
player-facing upload/sync toasts (`playerNotifier.accept("Uploading world
\"" + displayName + "\"...")`).

FR7.2. Introduce a small, Minecraft-type-free local reader (see Public
API -- `LevelDatNameReader`) that reads a save folder's `level.dat` file
directly (`GZIPInputStream` + a minimal NBT walk for exactly the top-level
`Data.LevelName` string tag -- no dependency on Minecraft's `NbtIo`/`CompoundTag`
classes, matching this feature module's existing Minecraft-type-free
design constraint) and returns the real display name, or a documented
fallback (the folder's own current name, or a fixed placeholder such as
`"Unknown World"`) if `level.dat` is missing/unreadable/malformed --
mirroring `WorldConflictResolutionHook.LevelDatBatch::unreadable`'s
existing "readable-or-not" precedent elsewhere in this codebase.

FR7.3. `CloudSyncCoordinator.listKnownWorlds()` and the sync-enable/disable
listener wiring use this reader to supply a real `displayName` instead of
the folder-name placeholder. This is a **live read, not a cached/persisted
value** -- chosen over persisting a displayName mapping (the option this
task's research explicitly asked to weigh) because a live read can never
go stale relative to the save's actual `level.dat` (e.g. if the player
renames the world from in-game between sessions), at the cost of one small
extra file read at these already-infrequent checkpoints (client startup,
and the rare sync-toggle action) -- a cost this codebase's own existing
performance bar for these checkpoints (Performance) already comfortably
accommodates. No other call site in this feature needs this reader: every
other display-name-producing path (`WorldsPanel`'s `summary.getLevelName()`,
`WorldSaveHookMixin`'s `self.getWorldData().getLevelName()`,
`CloudOnlyWorldSummary.displayName()`/`WorldFingerprint.displayName()`) was
already correctly decoupled from folder name before this feature, and
needs no change.

## Public API

### `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/`

New class: `WorldCloudMigrationService` (final, same package/style as
`WorldSyncPreferenceService`) -- **replaces** the original spec's
`WorldCloudIdentityService`. Its scope is narrower: it is a migration
orchestrator plus a thin breadcrumb store, not a permanent identity
mapping.

```java
public final class WorldCloudMigrationService {
    public WorldCloudMigrationService(
            Path breadcrumbFilePath, Path savesDirectory, WorldArchiveCloudStore archiveStore,
            CloudFileStore cloudFileStore, WorldSyncPreferenceService preferenceService,
            Consumer<String> warningLogger, Consumer<String> infoLogger);
    public void load(); // reads world-cloud-migration.json into memory

    /**
     * FR1.2/FR1.3: local-only, synchronous, zero I/O beyond the folder-name
     * parse itself. Returns the folder's cloudWorldId if it is already
     * UUID-shaped (fully migrated); otherwise looks for an in-progress
     * breadcrumb for currentFolderName and returns its pinned cloudWorldId
     * if found; otherwise empty (not yet migrated, no migration started).
     */
    public Optional<UUID> existingCloudWorldId(String currentFolderName);

    /**
     * FR2.1 (Phase A): resolves (minting + migrating Cloud data as needed)
     * this folder's cloudWorldId. Must be called off the client tick
     * thread. Never touches the local folder. Safe to call at any of
     * FR1.4's four checkpoints, including mid-session.
     */
    public UUID resolveCloudWorldId(String currentFolderName);

    /**
     * FR2.2 (Phase B): attempts the physical rename for every breadcrumb
     * entry with cloudMigrated=true, renamed=false. Caller must only
     * invoke this from a checkpoint it can guarantee is at the main menu
     * (Risk #1/#3) -- this method does not itself verify that, by design,
     * since it has no way to know whether a world is loaded; that
     * guarantee comes from which checkpoints call it (FR2.2).
     */
    public void runPendingRenames();

    /** FR4.2: every cloudWorldId with cloudMigrated=true right now (renamed or not). */
    public java.util.Set<UUID> knownLocalCloudWorldIds();
}
```

`WorldSaveSyncService`'s constructor gains a `WorldCloudMigrationService`
dependency; every existing public method that currently takes
`String worldSlug` as its Cloud-key argument internally calls
`migrationService.resolveCloudWorldId(worldSlug)` (mid-session-safe,
Phase A only) at its top, then uses the resolved `cloudWorldId` for every
Cloud-facing call inside that method. `checkAndUploadStaleWorldsAtStartup`
additionally calls `migrationService.runPendingRenames()` once, since it is
a qualifying Phase B checkpoint (FR2.2). `KnownWorld` (the small
`worldSlug`/`worldFolder`/`displayName` record) is unchanged in shape.

New class: `LevelDatNameReader` (FR7.2), same package, Minecraft-type-free:

```java
public final class LevelDatNameReader {
    /** Reads levelDatFolder/level.dat's Data.LevelName; returns fallback if unreadable. */
    public static String readLevelName(Path levelDatFolder, String fallback);
}
```

`CloudOnlyWorldDetector.detect`'s signature gains one parameter per FR4.3.

### `api/src/main/java/de/lazuli/api/cloudsync/`

`CloudOnlyWorldSummary`: no field additions/removals -- `worldSlug()`'s
javadoc is updated to describe it as "this world's identity (a generated
UUID string) -- also this world's local save-folder name once restored"
(FR5.1); `displayName()` is unchanged.

`WorldRestoreHook.beginRestore`: gains a `displayName` parameter
(`beginRestore(String cloudWorldId, String displayName, RestoreProgressListener listener)`),
per FR5.3 -- narrower justification than the original spec (log/toast text
only, not folder naming).

No changes to `WorldFreshnessHook`, `WorldConflictHook`,
`WorldConflictResolutionHook`, `WorldSyncToggleHook`, `WorldSyncStatusHook`
signatures (FR6.1) -- and, unlike the original spec, **no new
`WorldCloudIdentityHook` interface is added at all** (FR6.2).

### `features/steam-cloud-sync/.../services/WorldSyncPreferenceService.java`

New method, `void renameKey(String oldFolderName, String newFolderName)`
(FR3.4/FR2.2 step 1): moves the preference entry (if any) from
`oldFolderName` to `newFolderName`, preserving its enabled/disabled value,
persists the change, and is a no-op if no entry exists under
`oldFolderName`.

### New local persistence format IO class

New `WorldCloudMigrationIO` (`features/.../config/`), mirroring
`WorldSyncPreferencesIO`'s existing JSON-array-of-records shape (see
Persistence below), including the same `ParseResult(entries, warning)`
convention already used by `WorldFingerprintIO`/`WorldSyncAncestorIO`/
`WorldSyncPreferencesIO`.

## Architecture

### Why no permanent mapping file is needed

The original Cloud-key-only spec needed a permanent `localWorldFolderName -> cloudWorldId`
mapping file because the two values were *permanently* decoupled (folder
name never changed). Under this revision, once Phase B (rename) completes,
the folder's own name **is** the `cloudWorldId` (FR1.2) -- there is nothing
left for a permanent mapping to record that isn't already recorded by the
filesystem itself. The only local file this revision needs
(`world-cloud-migration.json`) is a small, expected-to-be-nearly-empty
**transient breadcrumb**, alive only between "Phase A finished" and "Phase
B finished" for any given folder, plus whatever short window a crash might
extend that for. This is a smaller, simpler, and more self-healing piece of
persisted state than the original design's permanent mapping file.

### What stays folder-name-keyed vs. what becomes UUID-keyed

| Concept | Key today | Key after this feature |
|---|---|---|
| Cloud archive file name | folder name | `cloudWorldId` (UUID) |
| Cloud per-world metadata file name | folder name | `cloudWorldId` (UUID) |
| `WorldFingerprint.worldSlug` (Cloud, all-worlds list) | folder name | `cloudWorldId` (UUID) |
| `WorldSyncAncestor` (local-only) | folder name | `cloudWorldId` (UUID) |
| `WorldSyncPreference` (local-only sync toggle) | folder name | folder's *current* name (converges to `cloudWorldId` once migrated, re-keyed on rename, FR3.4) |
| `world-cloud-migration.json` (new, local-only, transient) | n/a | old folder name -> `{cloudWorldId, cloudMigrated, renamed}`, removed once both true |
| `CloudOnlyWorldSummary.worldSlug` | folder name (intended) | `cloudWorldId` (UUID) |
| **Local save folder itself** | folder name | **renamed to `cloudWorldId` once migration completes (FR2.2)** |
| `level.dat` `LevelName` / display name everywhere | display name | **unchanged -- never renamed** |

### Migration & rename sequencing (the core new mechanism)

```
resolveCloudWorldId(currentFolderName) called
        |
        v
  Is currentFolderName UUID-shaped? --yes--> return it (FR1.2, zero I/O)
        | no
        v
  Breadcrumb exists for currentFolderName? --yes--> return its pinned cloudWorldId
        | no
        v
  PHASE A (any checkpoint, safe mid-session):
    - fileSize(archiveFileName(currentFolderName)) check
    - mint cloudWorldId, write breadcrumb {cloudMigrated=false, renamed=false}
    - if old archive found: copy archive+metadata to new key, rewrite
      fingerprint entry, delete old-keyed archive+metadata
    - breadcrumb.cloudMigrated = true
    - return cloudWorldId  (folder NOT renamed yet)

... later, only at a main-menu-guaranteed checkpoint ...

runPendingRenames() called
        |
        v
  For each breadcrumb with cloudMigrated=true, renamed=false:
    - Files.move(oldFolderName -> cloudWorldId)
    - on success: renameKey() the sync preference, mark breadcrumb
      renamed=true (or delete it), force WorldsPanel to re-scan
    - on failure: leave as-is, retry next qualifying checkpoint
```

Phase A may run from `onWorldUnload`, `onWorldSaved`,
`checkAndUploadStaleWorldsAtStartup`, or `handleSyncReenabled` (FR1.4,
unchanged from the original spec). Phase B (`runPendingRenames`) may only
be invoked from `checkAndUploadStaleWorldsAtStartup` and
`handleSyncReenabled` (FR2.2) -- both are only ever reached while the
player is at the main menu.

### Call sites requiring change (grounded in file reads)

- `features/steam-cloud-sync/.../services/WorldSaveSyncService.java` --
  every method taking `String worldSlug` as a Cloud-key argument now
  resolves it via `WorldCloudMigrationService.resolveCloudWorldId` at its
  top (Phase A only); `checkAndUploadStaleWorldsAtStartup` additionally
  calls `runPendingRenames()` once (Phase B); `archiveFileName`/
  `metadataFileName` callers at lines 343, 632, 792-803, 849, 928, 944,
  991, 1074, 1096 are otherwise unchanged in shape.
- `features/steam-cloud-sync/.../services/WorldRestoreService.java` --
  `beginRestore` (lines 104-169): target folder computed directly as
  `savesDirectory.resolve(cloudWorldId.toString())` (FR5.2, simpler than
  originally scoped); gains a `displayName` parameter used only in its
  existing log/toast strings (lines 111, 121, 129, 136, 238) and in
  `markEnabledAfterRestore`'s argument (line 236, now the UUID folder name
  directly, FR5.5); `RestoreContext` unchanged in shape (still carries
  `worldSlug`, which is now directly usable as the folder name too, no
  separate derived-name field needed).
- `features/steam-cloud-sync/.../services/CloudOnlyWorldDetector.java` --
  `detect` (lines 41-60) gains one parameter for the FR4.2 transient-window
  fix; its core set-difference logic is otherwise unchanged from today
  (simpler than the original spec's full redesign).
- `features/steam-cloud-sync/.../services/CloudOnlyWorldsFacade.java` --
  `listCloudOnlyWorlds`/`attachMetadata` (lines 56-85) pass through the new
  `detect` parameter; `attachMetadata`'s `cloudMetadataFor(summary.worldSlug())`
  call at line 76 already naturally becomes a `cloudWorldId`-keyed lookup.
- `features/steam-cloud-sync/.../services/CloudSyncCoordinator.java` --
  constructs and wires the new `WorldCloudMigrationService`
  (constructor-injected into `WorldSaveSyncService`/`WorldRestoreService`/
  `CloudOnlyWorldsFacade`); `listKnownWorlds()` (lines 214-228) and the
  `onSyncEnabledListener`/`onSyncDisabledListener` wiring (lines 167-176)
  use the new `LevelDatNameReader` (FR7.2/FR7.3) instead of the
  folder-name-as-displayName placeholder.
- `features/steam-cloud-sync/.../services/WorldSyncPreferenceService.java` --
  gains `renameKey(String, String)` (FR3.4), called from Phase B (FR2.2).
- `features/steam-cloud-sync/.../api/WorldFingerprint.java`,
  `.../api/WorldCloudMetadata.java`, `.../api/WorldSyncAncestor.java` --
  javadoc-only changes to `worldSlug`'s field description; no field/shape
  changes.
- `api/src/main/java/de/lazuli/api/cloudsync/CloudOnlyWorldSummary.java` --
  javadoc-only change to `worldSlug()`; `WorldRestoreHook.java` signature
  change (FR5.3).
- `platform/fabric-1.21.11/.../mainmenu/WorldsPanel.java`,
  `platform/fabric-26.1/.../mainmenu/WorldsPanel.java`,
  `platform/fabric-26.2/.../mainmenu/WorldsPanel.java` -- **no change
  required for Cloud-key-resolution purposes** (FR6.2, a reduction versus
  the original spec's per-platform hook-call rewrite). The one required
  change is Risk #3's cache-invalidation concern: after any rename
  (surfaced to platform code via, e.g., a boolean return or callback from
  `runPendingRenames()`, exact shape left to planning), `WorldsPanel` must
  force its `summaries` field (line 208, refreshed via
  `levelSource.findLevelCandidates()`/`loadLevelSummaries(...)` at lines
  245-248) to re-scan before the next render, and must clear/re-key any
  row-keyed cache (`freshnessCache`, `conflictCache`,
  `state.expandedRowId()`) that referenced the old folder name, so no stale
  reference to a since-renamed folder survives into the next frame.
  `WorldRestoreScreen.java`/`WorldConflictScreen.java` (all three
  platforms) need only the FR5.3 `beginRestore` signature follow-through
  (passing `displayName` through), same as originally scoped -- no new
  resolution logic.

## UI

No new player-facing UI. Every existing screen (`WorldsPanel`,
`WorldRestoreScreen`, `WorldConflictScreen`) continues to display exactly
the same `LevelSummary.getLevelName()`/`displayName`-sourced text it does
today -- **a UUID is never shown to the player as a world's name anywhere**.
The only UI-adjacent behavior is Risk #3's cache-refresh requirement: a
renamed world's row must not visibly glitch, disappear, or become
unclickable for even one frame after its folder is renamed underneath it;
planning should verify this with a manual test (toggle sync on for an
existing world, confirm its row keeps behaving normally through and after
its folder rename).

## Configuration

No new user-facing configuration. No changes to `SteamCloudSyncConfig`/
`SteamCloudSyncConfigIO`.

## Events

No new event bus / callback types beyond `WorldRestoreHook`'s
`RestoreProgressListener`'s existing shape (unchanged); the value it
carries is `cloudWorldId`, unchanged from the original spec's assessment.

## Networking

No new Cloud calls beyond the migration path's own bounded, one-time-per-
world sequence (FR2.1): one existence check, one archive read + re-write,
one optional metadata read + re-write, one fingerprint-list read (already
cached in-memory) + write, and one archive delete + one optional metadata
delete. Unchanged from the original spec's Networking assessment -- Phase B
(the physical rename) is a pure local filesystem operation with zero Cloud
I/O of its own.

## Persistence

### `world-cloud-migration.json` (new, local-only, transient, `featureConfigDir`)

```json
{
  "schemaVersion": 1,
  "entries": [
    {
      "oldFolderName": "New World",
      "cloudWorldId": "6f9619ff-8b86-d011-b42d-00c04fc964ff",
      "cloudMigrated": true,
      "renamed": false
    }
  ]
}
```

Never written to Cloud (matches `WorldSyncPreferencesIO`'s/
`WorldSyncAncestorIO`'s existing "local-only" precedent). Expected to be
empty, or contain only a small number of in-flight entries, in steady
state (Architecture -- Why No Permanent Mapping File Is Needed) -- unlike
the original spec's `world-cloud-identity.json`, this is not meant to grow
one entry per ever-synced world for the lifetime of the install.

### Cloud-side files (unchanged shape, new key)

`lazuli-world-<cloudWorldId>.zip` (was `lazuli-world-<slug>.zip`),
`lazuli-world-meta-<cloudWorldId>.json` (was
`lazuli-world-meta-<slug>.json`), and each `WorldFingerprint`/
`WorldCloudMetadata` JSON record's own `worldSlug` field value changes from
a folder-name string to a UUID string -- unchanged from the original
spec's assessment; no schema/shape change, `schemaVersion` fields are not
bumped.

### Ancestor cache (`world-sync-ancestor-cache.json`, local-only)

Re-keyed to `cloudWorldId`, same shape, same schema version, same
local-only status. Unchanged from the original spec.

### `world-sync-preferences.json` (existing, local-only)

**New in this revision**: entries are re-keyed in place (old folder name ->
new folder name) at Phase B (FR2.2/FR3.4), via the new `renameKey` method.
No schema/shape change -- only key values change, exactly like the
Cloud-side files above.

## Compatibility

- **Old-style Cloud data before any migration runs**: fully handled by
  FR2's Phase A -- unchanged in substance from the original spec's
  assessment, except that the local folder also stays under its old name
  until Phase B, which does not affect Cloud-side correctness at all.
- **A pre-upgrade device syncing against an already-migrated fingerprint
  list**: out of scope (Non-goals), same accepted gap as the original spec.
- **`WorldFingerprint`/`WorldCloudMetadata`/`CloudOnlyWorldSummary` field
  shapes**: unchanged (no field added/removed/renamed).
- **`WorldRestoreHook.beginRestore` signature change**: same
  internal-interface reasoning as the original spec's Compatibility
  section (not a published/external API).
- **`WorldSyncPreferenceService` gains a method**: purely additive, no
  compatibility concern.
- **A world synced once, then never touched again until this feature
  ships, then synced again after the player has since deleted that local
  folder**: never gets a chance to migrate locally (no folder, no
  `resolveCloudWorldId` call ever fires for it) -- remains a legitimate,
  permanently-old-style-keyed cloud-only world, correctly still reported as
  cloud-only by FR4 (no matching local folder name, no breadcrumb entry).
  Restoring it (FR5) creates a folder already named with a fresh
  `cloudWorldId` directly (FR5.2) -- but the Cloud archive being restored
  from is still stored under its **old** worldSlug-shaped key at that
  point. `WorldRestoreService` must treat "the Cloud key I'm asked to
  restore" (`CloudOnlyWorldSummary.worldSlug()`, whatever value the
  fingerprint list actually holds, old-style or UUID) as opaque and use it
  as-is for the `archiveFileName`/`beginAsyncRead` call, then run the same
  FR2.1 Phase A migration sequence against *that* key immediately after a
  successful restore (mint a fresh `cloudWorldId`, migrate the old-keyed
  Cloud data to it, rename the just-extracted folder from its temporary
  old-style-keyed name to the new `cloudWorldId` -- this rename is safe
  immediately, since a just-restored folder is by definition not yet
  loaded), so this device's own subsequent syncs of the now-local world are
  UUID-keyed and UUID-folder-named like everything else. Planning should
  treat this as the same Phase A/Phase B codepath, invoked from one more
  call site, not a new independent mechanism.

## Performance

- `resolveCloudWorldId` is O(1), zero-I/O for any folder already
  UUID-shaped (FR1.2) -- the expected common case after a device's first
  session running this feature -- cheaper than the original spec's
  best-case (a local map lookup) since it requires no file read or map
  lookup at all, just a `UUID.fromString` parse attempt.
- Migration (Phase A) is a one-time, bounded-size operation per world, same
  order of magnitude as one ordinary sync's own archive read/write,
  unchanged from the original spec's assessment.
- `runPendingRenames()` (Phase B) is O(breadcrumb-entry-count), expected to
  be 0 or a small handful of entries at any given checkpoint -- a single
  `Files.move` per entry, negligible cost, run on the background worker.
- `LevelDatNameReader.readLevelName` (FR7.2) adds one small file read
  (`level.dat`, typically a few KB gzipped) at the client-startup checkpoint
  and at each sync-toggle action -- both already-infrequent, already
  I/O-tolerant checkpoints in this codebase's existing design.
- `CloudOnlyWorldDetector`'s comparison stays O(n) over the fingerprint
  list against O(1) local-set membership tests (now two small local sets --
  folder names and pending-rename cloudWorldIds -- instead of the original
  spec's single inverted mapping), same algorithmic complexity as today.

## Testing / Acceptance Criteria

1. Two local worlds with identical folder names created independently each
   resolve to a distinct `cloudWorldId` via Phase A, and syncing both
   leaves two distinct Cloud archives, neither overwriting the other -- the
   core bug this feature exists to fix.
2. A folder already renamed to a UUID resolves to that same UUID with zero
   Cloud I/O and zero breadcrumb-file I/O, verified via a call-counting
   fake store (FR1.2).
3. **Phase A migration**: given a fake `WorldArchiveCloudStore`/
   `CloudFileStore` pre-seeded with an old-style-keyed archive/metadata/
   fingerprint entry, the first `resolveCloudWorldId` call (a) mints a
   UUID, (b) results in the archive/metadata present under the new UUID
   key and absent under the old key, (c) results in the fingerprint
   entry's `worldSlug` now holding the UUID, (d) persists a breadcrumb with
   `cloudMigrated=true, renamed=false`, and (e) does **not** touch the
   local folder -- verified with a `@TempDir`-based test asserting the
   folder still exists under its original name after this step.
4. **Phase A failure safety**: if the fake store fails any Cloud I/O step,
   the old-keyed archive/metadata/fingerprint entry are left fully intact,
   the breadcrumb stays at `cloudMigrated=false`, and a subsequent call
   retries using the *same* previously-minted `cloudWorldId` (never a
   second one), verified via a call-counting fake store.
5. **Phase B rename**: given a breadcrumb at `cloudMigrated=true,
   renamed=false` and an existing local folder under `oldFolderName`,
   `runPendingRenames()` renames the folder to `cloudWorldId`, re-keys the
   matching `WorldSyncPreference` entry, and marks the breadcrumb
   `renamed=true` -- verified with a `@TempDir`-based test.
6. **Phase B failure safety**: if `Files.move` is simulated to fail (e.g. a
   locked target, injectable via a test seam), the folder remains under its
   old name, the breadcrumb remains `renamed=false`, and a subsequent call
   retries successfully once the simulated lock is released.
7. **Cloud-only detection, steady state**: a fingerprint entry whose
   `cloudWorldId` has no matching local folder name and no breadcrumb entry
   is reported cloud-only; one with a matching local folder name is not
   (FR4.1) -- same test shape as today's existing `CloudOnlyWorldDetector`
   tests.
8. **Cloud-only detection, transient window**: a fingerprint entry whose
   `cloudWorldId` has a breadcrumb entry with `cloudMigrated=true` but no
   matching local folder name yet (rename still pending) is **not**
   reported cloud-only (FR4.2) -- the regression this revision must not
   introduce versus the pre-migration behavior.
9. **Restore produces a UUID-named folder directly**: given a
   `CloudOnlyWorldSummary` with `worldSlug` = some UUID and
   `displayName="My World"`, `WorldRestoreService`'s restored folder is
   named exactly `worldSlug`, and the restore's log/toast messages
   reference `"My World"`, never the UUID (FR5.2/FR5.3).
10. **Restored world needs no further migration**: after a successful
    restore, the newly-created folder's name already parses as a UUID
    (FR1.2), so a subsequent sync of it makes zero Phase A Cloud-migration
    calls (no old-keyed archive check, no archive copy) -- verified by
    asserting no extra calls occur on the fake store on that next sync.
11. **Display name and `level.dat` untouched**: across every scenario
    above, assert that `level.dat`'s `LevelName` is never mutated by any
    code path this feature adds, and that no screen ever renders a raw
    UUID string as a world's display name.
12. **`LevelDatNameReader` fallback**: given a folder with a missing or
    corrupt `level.dat`, `readLevelName` returns the documented fallback
    rather than throwing (FR7.2).
13. **Un-sync then re-sync after migration**: toggling a fully-migrated
    (UUID-folder-named) world's sync preference off then on again keeps the
    same `cloudWorldId`/folder name -- trivially true under this revision
    since there is nothing left to "forget" once the folder itself is the
    identity (FR1.5), but still worth an explicit regression test.
14. **Restore-of-a-never-locally-known-cloud-only-world migrates its old
    key too** (Compatibility): given a `CloudOnlyWorldSummary` whose
    `worldSlug` is old-style (not UUID-shaped, simulating a world synced
    before this feature shipped and never restored anywhere else since),
    restoring it results in (a) a locally-created folder named with a
    freshly minted UUID, not the old-style key, and (b) the old-keyed Cloud
    archive/metadata migrated to that new UUID key, verified end-to-end
    with a fake store.

## Future Extensions

- Detecting and gracefully handling a local save folder that has been
  manually copied/duplicated on disk after already being renamed to its
  `cloudWorldId` (Non-goals) -- e.g. two folders both named after the same
  UUID (impossible on one filesystem, but possible if a player copies a
  UUID-named folder onto a *different* device or a different saves
  directory). Not addressed by this feature since today's codebase has no
  first-class "duplicate/export a world" feature to trigger it.
- Supporting a genuinely mixed-version fleet (some devices upgraded to
  this feature, some not) by having a migrated fingerprint entry also
  retain a legacy `legacyWorldSlug` field an old device could still
  recognize. Deliberately deferred (Non-goals), unchanged from the
  original spec.
- Any UI affordance that makes a UUID-named folder easier for a player to
  identify from an OS file browser (e.g. writing a small human-readable
  `.txt`/`.json` sidecar file inside the folder itself, purely for the
  player's own reference) -- raised by the tradeoff discussion that led to
  this revision, but not requested by the user and not part of this
  feature's scope today.

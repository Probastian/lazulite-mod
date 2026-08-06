# Proactive Stale Save-Folder Healing

## Overview

`WorldRestoreService` (the Steam-Cloud-restore half of Steam Cloud Sync) already
auto-heals one specific case: a local save folder that exists on disk but
contains no `level.dat` (a "stale/junk" folder — e.g. leftover from an
interrupted/cancelled world creation, or a stray `session.lock`-only directory
from an old bug), sitting at the exact slug a Cloud restore is about to write
into. Today this heal only runs **reactively**, inside
`WorldRestoreService.beginRestore()`'s same-slug collision check, immediately
before that one specific restore.

A real bug was hit because of this: a junk folder named
`"New WorldNew WorldNew WorldNew W"` (containing only `session.lock`, no
`level.dat`) sat in `platform/fabric-1.21.11/run/saves/` and had to be
manually deleted before a retest could proceed — nothing in the mod ever
revisits an existing junk folder unless a Cloud restore happens to target that
exact slug again.

This spec adds a **second, proactive** heal pass: every time the Worlds tab
(re)loads its local world list, it scans the whole local saves directory,
finds every folder that is not a real save and is not busy (mid-download),
and silently deletes it — no restore needs to be in flight, and no popup is
shown. This must behave identically on all three platform modules
(`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`).

## Researched Current-State Facts

### `WorldRestoreService` (reactive heal, today)

`features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldRestoreService.java`:

- `beginRestore(String worldSlug, RestoreProgressListener listener)` (lines
  104-169): if `Files.exists(targetWorldFolder)` and
  `!isRealSaveFolder(targetWorldFolder)`, logs at `infoLogger` and calls
  `deleteRecursively(targetWorldFolder)` before proceeding (lines 108-124).
- `private static boolean isRealSaveFolder(Path candidate)` (lines 314-321):
  `false` if not a directory; otherwise `Files.isRegularFile(candidate.resolve("level.dat"))`.
- `private static void deleteRecursively(Path path)` (lines 323-338): no-op if
  `Files.notExists`; otherwise `Files.walk` + `sorted(Comparator.reverseOrder())`
  + `Files.deleteIfExists` per entry, swallowing every `IOException`
  (best-effort).
- Both are **currently private static** methods with no test-visible or
  cross-class access.
- `extractAndFinish(...)` (lines 179-246) extracts into a hidden staging
  directory `savesDirectory.resolve(".tmp-restore-" + worldSlug)`, and only
  once every zip entry has extracted without error does
  `Files.move(stagingDirectory, targetWorldFolder)` (line 235) — an **atomic
  rename**, not an in-place, progressive write into the final folder name.
  Consequence: under the existing restore code path, the real target folder
  name never exists in a partially-extracted state; it either doesn't exist
  yet (download/extraction in progress) or appears already-complete (with a
  real `level.dat`) the instant the move succeeds. The proactive scan must
  still treat any folder without `level.dat` defensively (Safety Requirement
  2 below) since this invariant is an implementation detail of the current
  restore path, not a hard guarantee for all folder-creation code paths
  (e.g. vanilla's own `CreateWorldScreen` writes progressively into the real
  slug name while creating a new world).
- Existing unit test coverage:
  `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/WorldRestoreServiceTest.java`,
  `staleNonSaveLocalFolderIsAutoHealedAndRestoreProceeds` (line 195) — creates
  a `session.lock`-only folder, asserts it's cleared and the restore proceeds.
  This test's fixture is a direct precedent for the new utility's own test.

### The three `WorldsPanel.java` files

`platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`.
All three have the identical relevant shape (per this codebase's established
"one class, near-identical copy per platform, no shared module" pattern for
this panel — see `WorldsPanelStatusTest`'s own doc comment, only carried on
`fabric-26.2`, about `computeConsolidatedStatus` being duplicated 3x with
drift caught by a manual diff pass rather than a shared module):

- **Constructor** (`fabric-1.21.11` line 249, `fabric-26.1`/`fabric-26.2` line
  234): `public WorldsPanel(MainMenuStateMachine state, MainMenuScreen owner)`
  — calls `reload()` once, immediately, as its only body statement. This is
  the "Worlds tab opened" moment (the panel is constructed when the tab is
  built).
- **`void reload()`** (package-private; `fabric-1.21.11` line 289,
  `fabric-26.1`/`fabric-26.2` line 241): the single re-entrant refresh point.
  Called (a) once from the constructor above, and (b) from
  `WorldRestoreScreen`'s/`WorldConflictScreen`'s completion/cancel callback
  (doc comment: "Package-private so a completed/cancelled `WorldRestoreScreen`
  (FR-E.5) can refresh this tab's list on return"), confirmed by call sites in
  `downloadOnly(...)` (`onComplete`/`WorldRestoreScreen`'s own `reload()`
  callback, e.g. `fabric-26.2` `WorldsPanel.java` lines 1078, 1112) and the
  conflict-resolution flow. `reload()` is **not** the periodic/per-frame path
  — `render()` runs every frame but never calls `reload()`; there is no
  separate polling `refreshCloudOnlyWorlds()` timer distinct from `reload()`
  in any of the three files (that method is only ever invoked from inside
  `reload()`'s own completion continuation).
  - `fabric-1.21.11` (Yarn/obfuscated mapping): builds a headless
    `WorldListWidget`, drives `LevelStorage.loadSummaries(levelStorage.getLevelList())`,
    and on success stores `this.entries = loaded` (a `List<WorldListWidget.WorldEntry>`)
    then calls `refreshCloudOnlyWorlds()` + `refreshFreshnessCache()`.
  - `fabric-26.1`/`fabric-26.2` (Mojang mapping): use
    `levelSource.findLevelCandidates()` + `levelSource.loadLevelSummaries(candidates)`
    directly (no `WorldListWidget`), store `this.summaries` (a sorted
    `List<LevelSummary>`), then the same `refreshCloudOnlyWorlds()` +
    `refreshFreshnessCache()` pair.
- **`private void refreshCloudOnlyWorlds()`**: builds `List<String> localFolderNames`
  by iterating the just-loaded `entries`/`summaries`
  (`entry.getLevel().getName()` on 1.21.11,`summary.getLevelId()` on
  26.1/26.2) and passes it to `CloudOnlyWorldsHook.listCloudOnlyWorlds(...)`.
  This is the **existing, already-correct enumeration of "real local save
  folder names"** the new proactive scan must reuse the *shape* of (real
  folders only — vanilla's own `LevelStorage`/`LevelStorageSource` summary
  loaders silently skip any folder lacking a valid `level.dat`, which is
  exactly why a stale folder never appears as a phantom row today, and exactly
  why it can sit forever unless something else notices it). The scan this
  spec adds is a **separate, independent directory listing** (it must find
  folders precisely *because* they are invisible to this existing
  save-summary enumeration), not a reuse of `entries`/`summaries` themselves.
- **Holder imports** — confirmed on `fabric-1.21.11` (identical pattern on
  the other two): `WorldsPanel.java` imports only
  `de.lazuli.api.cloudsync.*` interfaces/records plus a set of per-platform,
  package-`de.lazuli` static holder classes (`CloudOnlyWorldsHookHolder`,
  `WorldSyncStatusHookHolder`, `WorldRestoreHookHolder`,
  `WorldFreshnessHookHolder`, `WorldConflictHookHolder`,
  `WorldSyncToggleHookHolder`). It **never** imports anything from
  `de.lazuli.features.steamcloudsync.*` directly. Each holder (e.g.
  `platform/fabric-26.1/src/main/java/de/lazuli/CloudOnlyWorldsHookHolder.java`)
  is a `volatile` static field of an `api`-module interface type, `publish()`ed
  once from that same platform module's own
  `SteamCloudSyncClientInitializer.onInitializeClient()` composition root.
- **Saves-root path**: `WorldsPanel` never itself holds the raw saves-directory
  `Path` as a field; per-world paths are resolved ad hoc via
  `MinecraftClient.getInstance().getLevelStorage().resolve(worldSlug)`
  (`fabric-1.21.11`) or `levelSource.getLevelPath(worldSlug)`
  (`fabric-26.1`/`fabric-26.2`, `levelSource` = `Minecraft.getInstance().getLevelSource()`,
  a field). Each platform's own
  `SteamCloudSyncClientInitializer.java` (line 56, identical across all
  three) separately computes the saves root as
  `Path savesDirectory = FabricLoader.getInstance().getGameDir().resolve("saves");`
  and passes that exact `Path` into `WorldRestoreService`'s constructor — this
  is the same root the reactive heal already operates under. The proactive
  scan should resolve the saves root the same way
  (`FabricLoader.getInstance().getGameDir().resolve("saves")`) so both heal
  paths are guaranteed to target the identical directory, with no risk of a
  path-computation drift between the two.

### `WorldSyncStatusHook` (the mid-download safety guard)

`api/src/main/java/de/lazuli/api/cloudsync/WorldSyncStatusHook.java`:

- `default boolean isDownloadInProgress(String worldSlug)` (lines 86-88,
  defaults `false`) — `true` while a "Keep Cloud" conflict-resolution restore
  *or* a cloud-only-world `beginRestore()` download is running for that slug.
- `default void markDownloadPending(String worldSlug)` / `markDownloadFinished(String worldSlug)`
  (lines 102-116) — the set/clear pair backing the flag above.
- **Confirmed bracketing at every `beginRestore()` call site** (all three
  platforms, identical): `WorldRestoreScreen`'s constructor
  (`platform/*/src/main/java/de/lazuli/cloudsync/WorldRestoreScreen.java`,
  ~line 104) and `WorldsPanel.downloadOnly(...)`'s "Download" pill
  (`fabric-26.2` `WorldsPanel.java` lines 1090-1124, mirrored on the other two)
  both call `statusHook.markDownloadPending(worldSlug)` **before** calling
  `restoreHook.beginRestore(worldSlug, ...)`, and call
  `statusHook.markDownloadFinished(worldSlug)` in both the listener's
  `onComplete` and `onFailed` branches. `WorldConflictScreen`'s "Keep Cloud"
  flow does the same. So `isDownloadInProgress(worldSlug)` is `true` for the
  **entire** window of any restore this codebase currently drives — from
  before `beginRestore()`'s own collision-check/delete runs, through Cloud
  read, extraction, and the atomic move — with no known gap.
- Implemented by `features/steam-cloud-sync/.../services/WorldSyncStatusTracker.java`
  (`markDownloadPending`/`markDownloadFinished`/`isDownloadInProgress` at
  lines 83/97/103).

### `CloudOnlyWorldsHook`

`api/src/main/java/de/lazuli/api/cloudsync/CloudOnlyWorldsHook.java` — not
directly relevant to the heal logic itself, but confirms the same module
boundary already crossed by `WorldsPanel` (a Minecraft-free `api`-module
interface, consumed via a per-platform holder). No changes needed here.

### Module dependency structure

- `settings.gradle`: `api`, `common`, `services`, `features:*`, `platform:*`
  are separate Gradle subprojects.
- Every `platform/*/build.gradle` declares (identically):
  `api project(':api')` + `include project(':api')`, and
  `implementation project(':features:steam-cloud-sync')` + `include project(':features:steam-cloud-sync')`
  (the `include` is Loom's Jar-in-Jar — required in addition to
  `implementation`/`api` for the class to actually be bundled into the
  shipped/launched jar, not just available at compile time — see the comment
  block at the top of each platform's `build.gradle`).
- `features/steam-cloud-sync/build.gradle`: `api project(':api')` (this
  feature's own public constructor/hook signatures leak `api`-module types,
  so `api`, not `implementation`) and `implementation project(':services')`.
- `api` module: configured only via the root `build.gradle`'s `subprojects {}`
  block (`java-library` plugin, Java 21 toolchain) — no per-module
  `api/build.gradle` override needed for a new plain-Java class. It is
  Minecraft-free (confirmed by existing class doc comments, e.g.
  `CloudOnlyWorldsHook`'s "Stable, Minecraft-free abstraction...") and already
  contains **concrete utility classes**, not just interfaces — precedent:
  `api/src/main/java/de/lazuli/api/cloudsync/DownloadProgressPresenter.java`,
  a `public final class` of pure static/instance helper logic with its own
  unit test (`api/src/test/java/de/lazuli/api/cloudsync/DownloadProgressPresenterTest.java`).
- **Conclusion**: `WorldsPanel` (all 3 platforms) already has a live,
  already-used dependency path to the `api` module directly (not merely
  transitively through `features:steam-cloud-sync`) — it imports
  `de.lazuli.api.cloudsync.*` types today. Placing the shared healer utility
  in `api` (package `de.lazuli.api.cloudsync`, alongside
  `DownloadProgressPresenter`) lets both `WorldRestoreService` (already
  depends on `api` via `api project(':api')` in its own `build.gradle`) and
  all three `WorldsPanel`s call it with **zero new inter-module dependency
  edges** — the exact constraint the feature request calls for. This is
  preferred over putting it in `features:steam-cloud-sync` itself, since that
  would require every `WorldsPanel` to add a *new*, direct
  `de.lazuli.features.steamcloudsync.*` import where today it only ever
  reaches that feature indirectly through `api` interfaces + per-platform
  holders — a boundary this codebase has consistently kept clean everywhere
  else in this panel.

### Jar-in-Jar / build verification precedent

`platform/fabric-26.2/build/processIncludeJars/steamworks4j-1.10.0.jar`
(referenced in `.claude/context/minecraft.md` line 97) confirms the real
Loom task name, `processIncludeJars`, that repackages every `include`d
subproject/dependency jar into each platform module's own bundled output —
this is the task (per platform, or `assemble`/`build` transitively) that must
be re-run (not just `compileJava`) for a source change inside `api` or
`features:steam-cloud-sync` to actually reach a launched game jar, per the
governing constraint from a previous session's mistake.

## Goals

1. Detect and silently delete stale/junk local save folders (no `level.dat`,
   not mid-download) automatically, with no user or agent action required,
   every time any platform's Worlds tab is opened or reloaded.
2. Share the exact same "what is a real save folder" / "how do I safely
   delete a folder" logic between the existing reactive heal
   (`WorldRestoreService.beginRestore()`) and the new proactive heal
   (`WorldsPanel.reload()`), across all three platform modules, via one
   extracted, reusable utility.
3. Never delete a folder that is, or might be, a real world save, or that is
   the target of any in-flight write (download/extraction, or otherwise).
4. Ship identically on `fabric-1.21.11`, `fabric-26.1`, and `fabric-26.2`.

## Non-goals

- Bug #3 (`gameMode` always `"Unknown"` for cloud-only entries) — untouched.
- Any change to `SteamworksService.java` or the fabric-26.2 progress-bar
  stall another session may be investigating — the `WorldRestoreService`
  extraction in this spec must stay a minimal, mechanical move of the two
  existing private methods, not a broader refactor of that file.
  `SteamworksService.java` is not touched by this feature at all.
- Any UI affordance (button, message, popup, log-viewer entry) surfacing a
  healed folder to the player. Per the explicit request, this is silent
  except for an INFO-level log line.
- Changing `beginRestore()`'s existing reactive-heal *behavior* or its
  existing test's assertions — only its two private helper methods move to
  the shared utility; call sites are updated to the new location, behavior
  is unchanged.
- A periodic/background re-scan while the Worlds tab stays open (e.g. a
  tick-based timer). The trigger is exclusively "the tab (re)loads" — tab
  construction plus every existing `reload()` call site — matching the
  user's explicit ask ("best if it happens on opening the world tab").
- Configurability (no config toggle, no user-facing setting to disable this;
  matches the existing reactive heal, which is also unconditional).
- Launching Minecraft for live verification — the implementer/verifier must
  not do this; the user tests live themselves.

## Requirements

### Functional

- **FR1**: Extract `isRealSaveFolder(Path)` and `deleteRecursively(Path)` out
  of `WorldRestoreService` into a new public utility class in the `api`
  module (package `de.lazuli.api.cloudsync`, see Public API below).
  `WorldRestoreService.beginRestore()`'s existing collision-check call sites
  (lines 110, 123 today) are updated to call the new utility's static
  methods; no behavioral change to the reactive path.
- **FR2**: The new utility additionally exposes one batch entry point that
  scans a given saves-root directory, identifies every immediate child
  directory that is not a real save folder (FR1's `isRealSaveFolder` check)
  and is not currently busy (caller-supplied predicate, see FR4), deletes
  each one (FR1's `deleteRecursively`), and returns the list of healed
  folder names (for the caller to log).
- **FR3**: Each of the three `WorldsPanel.reload()` methods calls this batch
  entry point once, synchronously, at the very start of `reload()` — before
  kicking off the async `LevelStorage`/`LevelStorageSource` summary load —
  so a freshly-healed folder never renders a stale-but-since-deleted row
  during this same reload pass, and any subsequently-healed slug becomes
  immediately eligible for `CloudOnlyWorldsHook.listCloudOnlyWorlds(...)` to
  surface as a cloud-only row again (if it exists in Cloud metadata) on this
  same reload.
- **FR4**: The busy-predicate passed to the batch scan wraps
  `WorldSyncStatusHook.isDownloadInProgress(worldSlug)` (via the same
  `WorldSyncStatusHookHolder.getOrNull()` pattern already used throughout
  each `WorldsPanel`) — a folder whose slug currently reports
  `isDownloadInProgress(...) == true` is never a healing candidate, matching
  the guarantee documented under "Researched Current-State Facts" above
  (every `beginRestore()` call site already brackets the whole restore with
  `markDownloadPending`/`markDownloadFinished`). If the hook is unavailable
  (`null`, e.g. Steam Cloud Sync not activated this session), the busy check
  degrades to "never busy" for this predicate's purposes — but note Safety
  Requirement 2 below still applies independently of hook availability.
- **FR5**: Every folder actually deleted by the proactive scan is logged once
  at INFO level, including the healed folder's name, from the caller
  (`WorldsPanel`) using that platform's existing logger
  (`LazuliMod.LOGGER.info(...)`, the same logger `refreshCloudOnlyWorlds()`
  already uses) — not from inside the shared utility itself, so the utility
  stays free of any platform/Minecraft logging dependency (it already needs
  to work standalone for `WorldRestoreService`, which uses its own
  `Consumer<String> infoLogger` field, a different logging seam). No
  popup/UI message is shown (Non-goal, restated as a hard requirement).
- **FR6**: Applied identically to all three platform modules — the call
  added to `reload()` and the busy-predicate wiring must be the same shape
  (accounting only for each platform's own local-save-folder-name
  enumeration idiom already present in that file, e.g.
  `entry.getLevel().getName()` vs `summary.getLevelId()`, which the scan does
  not need at all since it operates purely on the filesystem, not on
  already-loaded summaries).

### Safety (autonomous filesystem deletion — mandatory)

- **SR1 (never touch a real save)**: the scan's only "is this a save"
  criterion is FR1's existing `isRealSaveFolder` check (directory +
  `level.dat` present as a regular file) — identical criterion already
  proven safe by the reactive heal and its existing test. A folder that
  fails to *load* as a valid world (corrupt/old-format `level.dat`) is still
  "real" by this definition and is never touched — this matches the
  existing reactive heal's own behavior exactly (no new, stricter check is
  introduced that the reactive path doesn't already also apply).
- **SR2 (never race an in-flight or just-finished write)**: two independent
  guards, both required:
  1. FR4's `isDownloadInProgress(worldSlug)` check (the primary,
     event-driven guard, already proven to cover the entire restore window
     for every current call site).
  2. An **additional, independent time-based guard**: skip any candidate
     folder whose most-recent filesystem modification timestamp is within a
     short trailing window (recommend on the order of tens of seconds — the
     exact constant is an implementation-time decision, see Open Questions)
     of "now," regardless of what `isDownloadInProgress` reports. This
     covers any write in progress from a code path this spec's author did
     not enumerate (a future feature, a race between `markDownloadPending`
     being set and the folder actually existing, or a call site added later
     that forgets the bracketing convention) — belt-and-suspenders per the
     feature request. Implementation should determine "most-recent
     modification timestamp" defensively: the folder's own
     `Files.getLastModifiedTime`, and if the folder is non-empty, the max
     `getLastModifiedTime` across its immediate children too (a single-level
     check is sufficient — this guard only needs to catch an *active*
     write, which by definition is touching a file very recently; it is not
     a substitute for SR1's `level.dat` check for older/abandoned junk).
  3. Any I/O error or exception while computing timestamps or listing a
     candidate folder's contents (e.g. a concurrent delete elsewhere,
     permission error) must cause that single candidate to be skipped
     (treated as "do not touch"), not aborting the whole scan and not being
     treated as "safe to delete."
- **SR3 (saves-directory root only, no path traversal)**: the scan only ever
  lists **immediate children** of the resolved saves-root `Path`
  (`FabricLoader.getInstance().getGameDir().resolve("saves")`, the same root
  `WorldRestoreService` already receives via constructor injection) — never
  recurses into grandchild directories to look for more candidates, never
  follows a path derived from any external/Cloud-supplied string, and never
  operates on a path outside that root. This mirrors `beginRestore()`'s own
  scope (it only ever resolves `savesDirectory.resolve(worldSlug)`, a single
  path segment appended to the trusted root).
- **SR4 (silent failure never blocks the tab)**: any exception while
  listing the saves directory itself (e.g. it doesn't exist yet on a brand
  new install) must be caught and logged (or silently ignored, matching
  `deleteRecursively`'s own existing best-effort-cleanup convention), never
  thrown out of `reload()` — a `WorldsPanel` failing to open because of a
  filesystem hiccup in this new code would be a strictly worse outcome than
  the bug this spec fixes.

## Public API

New class, `api` module, package `de.lazuli.api.cloudsync` (co-located with
`DownloadProgressPresenter`, the module's existing precedent for a concrete,
Minecraft-free utility class rather than a pure interface):

```
public final class StaleSaveFolderHealer {

    private StaleSaveFolderHealer() { }

    /** Moved verbatim from WorldRestoreService (FR1). */
    public static boolean isRealSaveFolder(Path candidate);

    /** Moved verbatim from WorldRestoreService (FR1). */
    public static void deleteRecursively(Path path);

    /**
     * FR2: scans the immediate children of savesDirectory, deletes every
     * child directory that (a) is not a real save folder per
     * isRealSaveFolder, and (b) is not reported busy by worldSlugIsBusy,
     * and (c) has no filesystem activity within the safety-margin window
     * (SR2). Returns the folder names actually deleted (never null, empty
     * if none). Never throws; any per-candidate or listing-level failure
     * is swallowed (SR4) after being reported via a caller-supplied
     * error callback (name/shape left to implementation — e.g. a
     * Consumer<String> warning sink mirroring WorldRestoreService's own
     * warningLogger convention).
     */
    public static List<String> healStaleFolders(
            Path savesDirectory,
            java.util.function.Predicate<String> worldSlugIsBusy,
            java.util.function.Consumer<String> warningLogger);
}
```

`WorldRestoreService`'s two call sites (lines 110/123 of the current file)
become `StaleSaveFolderHealer.isRealSaveFolder(...)` /
`StaleSaveFolderHealer.deleteRecursively(...)`; its own two private methods
are deleted. No change to `WorldRestoreService`'s own public
`WorldRestoreHook` surface, constructors, or behavior.

Each `WorldsPanel.reload()` gains, as its first statement:

```
List<String> healed = StaleSaveFolderHealer.healStaleFolders(
        savesRootPath,
        worldSlug -> {
            WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
            return statusHook != null && statusHook.isDownloadInProgress(worldSlug);
        },
        warning -> LazuliMod.LOGGER.warn(warning));
for (String slug : healed) {
    LazuliMod.LOGGER.info("Healed stale local save folder \"{}\" (no level.dat, not mid-download).", slug);
}
```

(Exact variable names/placement are implementation detail; the shape above is
identical across all three platforms per FR6.)

## Architecture

```
api (Minecraft-free)
 └─ de.lazuli.api.cloudsync.StaleSaveFolderHealer   <- NEW, this spec
      ▲                                  ▲
      │ (already: api project(':api'))   │ (already: api project(':api') + include)
      │                                  │
features:steam-cloud-sync                platform:fabric-1.21.11 / fabric-26.1 / fabric-26.2
 └─ services.WorldRestoreService          └─ mainmenu.WorldsPanel (x3, near-identical)
    (reactive heal call site updated;        (NEW proactive heal call, in reload())
     no new dependency edge)                 (no new dependency edge — api already used)
```

No `settings.gradle`/`build.gradle` changes are required anywhere: every
module that needs to call `StaleSaveFolderHealer` already has an existing
`api project(':api')` (or `implementation`, for `features:steam-cloud-sync`
via its own `api project(':api')`) dependency edge on the `api` module.

## UI

None. Explicitly silent per the feature request (Non-goals) — no dialog,
toast, tooltip, or Worlds-tab text change. The existing
`downloadOnlyStatusMessage` short-lived status-line mechanism already present
in `WorldsPanel` (used for the "Download" pill's own failure reporting) is
not reused or extended for this feature.

## Configuration

None. Always-on, matching the existing reactive heal (no config key exists
for that one today either).

## Events

No new cross-module event/hook. `StaleSaveFolderHealer` is a stateless static
utility, not a hook interface — it needs no publish/subscribe wiring, no
holder class, and no entry in any `SteamCloudSyncClientInitializer`.

## Networking

None. Purely local filesystem logic; no Steam Cloud I/O of any kind.

## Persistence

None introduced. Operates directly on the local saves directory (already the
`WorldRestoreService`-managed root); no new file/config is read or written
by this feature.

## Compatibility

- No wire/save-format change. No effect on any real, `level.dat`-bearing
  world.
- A fresh install with no `saves` directory yet: `healStaleFolders` must
  tolerate `Files.notExists(savesDirectory)` (or an empty directory) as a
  trivial no-op, consistent with `deleteRecursively`'s existing
  `Files.notExists` early-return.
- No change to any existing public hook interface (`WorldRestoreHook`,
  `WorldSyncStatusHook`, `CloudOnlyWorldsHook`, etc.) — purely additive new
  class plus an internal refactor of `WorldRestoreService`'s private
  implementation.

## Performance

- The scan is a single, shallow (`Files.list` or `Files.newDirectoryStream`,
  non-recursive except SR2's one-level-deep timestamp check) pass over the
  saves directory's immediate children, run only at `reload()` time — the
  same "not per render frame" discipline `WorldsPanel` already documents for
  `refreshFreshnessCache()`/`refreshCloudOnlyWorlds()` (its own FR-P3
  comment). For a typical saves directory (tens of worlds), this is cheap;
  for a save with many worlds it is still bounded by one directory listing
  plus, per non-real candidate only, a small constant number of
  `Files.getLastModifiedTime` calls (real, valid saves are never touched
  beyond the one `isRealSaveFolder` check that already filters them out
  immediately).

## Future Extensions

- Surfacing a one-time, dismissible notice the *first* time this session a
  folder is healed (currently explicitly out of scope per the user's
  "should be silent" instruction, but noted here in case that preference
  changes later).
- Extending the same healer to also catch a stale `.tmp-restore-<slug>`
  staging directory left behind by a crash mid-extraction (today,
  `extractAndFinish`'s own `catch` block already calls `deleteRecursively`
  on its staging dir on failure, but a hard crash/process kill between
  `Files.createDirectories(stagingDirectory)` and that `catch` running would
  leave it orphaned forever). Not required by this spec (the user's report
  was specifically about a real save-slug-named junk folder, not a
  `.tmp-restore-*` one), but the same time-based safety guard (SR2) would
  need to gate this identically, and it would need to filter by the
  `.tmp-restore-` prefix rather than `isRealSaveFolder` to identify
  candidates.

## Open Questions

1. **Exact time-based safety-margin constant (SR2.2)**: this spec
   deliberately does not pin an exact number of seconds — recommend the
   planning phase choose a concrete constant (something on the order of
   30-60 seconds is a reasonable starting point, generous enough that a slow
   Steam Cloud read of a large world archive can't finish and have
   `markDownloadFinished` clear the busy flag while this guard is still the
   *only* thing preventing a delete, yet short enough that a genuinely stale
   folder from a session that ended minutes/hours/days ago is still healed
   promptly on next tab-open) and document the chosen value with a short
   rationale comment at the constant's declaration site.
2. **Where the new unit test(s) for `StaleSaveFolderHealer` should live**:
   recommend `api/src/test/java/de/lazuli/api/cloudsync/StaleSaveFolderHealerTest.java`,
   parallel to the existing `DownloadProgressPresenterTest.java` in the same
   package, covering `isRealSaveFolder`/`deleteRecursively` (moved,
   unchanged behavior — largely mirroring assertions already implicit in
   `WorldRestoreServiceTest`'s `staleNonSaveLocalFolderIsAutoHealedAndRestoreProceeds`)
   plus new coverage of `healStaleFolders`'s scan/predicate/timestamp-guard
   logic. `WorldRestoreServiceTest`'s existing test should keep passing
   unchanged (it only depends on observable `beginRestore()` behavior, not
   on the moved methods' visibility) — confirm this during planning/review
   rather than assuming it.
3. **Per-platform proactive-heal test coverage**: per this codebase's own
   established precedent (`WorldsPanelStatusTest` exists only on
   `fabric-26.2`, covering logic duplicated 3x, with a manual three-way diff
   substituting for 3x automated coverage), should the planning phase add an
   equivalent single-platform integration test for the new `reload()` call
   site, or rely on `StaleSaveFolderHealer`'s own `api`-module unit tests
   (which cover all the real logic) plus a manual diff pass across the three
   `WorldsPanel.reload()` edits? Recommend following the existing precedent
   (unit-test the shared logic once in `api`, diff-check the three
   near-identical call-site edits) rather than introducing new
   per-platform-Minecraft-bootstrapping test infrastructure for a
   ~5-line call site.
4. **Build/packaging verification checklist for the planning phase to carry
   forward** (per the feature request's explicit constraint, restated here
   for visibility): touching `api` and `features:steam-cloud-sync` plus all
   three `platform/*` modules means a full verification pass must run, at
   minimum, `:api:test`, `:features:steam-cloud-sync:test`, each
   `:platform:fabric-*:compileJava`, and each
   `:platform:fabric-*:processIncludeJars` (or an equivalent full
   `:platform:fabric-*:build`/`assemble`) — not only `compileJava` — so the
   bundled Jar-in-Jar output actually contains the updated `api` and
   `features:steam-cloud-sync` classes before any live test.

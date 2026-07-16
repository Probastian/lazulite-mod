# Steam Cloud Sync

Syncs six categories of small-to-medium player data across a player's devices
via Steam Cloud (`ISteamRemoteStorage`): feature settings/preferences, UI/
accessibility preferences (via the shared `CloudSyncable` mechanism), a
bookmarked-server list, a "continue where you left off" pointer, personal
notes/waypoints, and world-save sync with an explicit size-limiting strategy.
See `specification.md` (why) and `implementation-plan.md` (how) for the full
design and rationale; this README is a shorter, copyable-template-oriented
summary.

## Folder layout

| Required folder | Realized as |
|---|---|
| `api/` | `src/main/java/.../steamcloudsync/api/` -- `BookmarkedServer`, `LastPlayedPointer`, `Note`, `WorldSyncPreference`, `SteamCloudSyncConfig`, `WorldFingerprint` |
| `config/` | `src/main/java/.../steamcloudsync/config/` -- `CloudSyncJson` (shared parser) + one `*IO` class per local file |
| `services/` | `src/main/java/.../steamcloudsync/services/` -- see below |
| `events/` | empty placeholder package (Fabric event registration is a `platform/`-only concern) |
| `gui/` | empty placeholder package (all Minecraft-touching UI lives in `platform/`) |
| `mixins/` | permanently empty placeholder package (Group 6's synthetic world-select rows need a real `@Mixin`, but it lives in each `platform/fabric-<version>/.../mixin/` package instead) |
| `resources/` | `src/main/resources/`, currently unused beyond a `.gitkeep` |
| `tests/` | `src/test/java/...` |
| `README.md` | this file |

## The `CloudFileStore`/`WorldArchiveCloudStore` seam

Every business-logic class in this feature (data models, `*IO` classes, all
six services) depends only on the small `CloudFileStore` (Groups 1/3/4/5) and
`WorldArchiveCloudStore` (Group 6) interfaces -- never
`com.codedisaster.steamworks.*` directly. `SteamRemoteStorageCloudFileStore`
and `SteamRemoteStorageWorldArchiveStore` are the **only** two classes in this
feature that import steamworks4j; `NoopCloudFileStore`/
`NoopWorldArchiveCloudStore` back every group when Steam is unavailable, so no
service needs its own `if (steamAvailable)` branch. This keeps every service,
model, and `*IO` class unit-testable on a plain JVM with a hand-written fake
of either interface.

## Services

- `CloudSyncCoordinator` -- constructs the store pair and all six group
  services, applies the FR0.4 reconciliation rule at the client-startup
  checkpoint, pushes at shutdown.
- `CloudSyncWorker` -- one background thread plus a per-tick pump queue: Group
  6's archive compression/restore extraction run on the background thread;
  the actual steamworks4j calls are queued back onto the client tick thread.
- `BookmarkedServersService` / `NotesService` / `LastPlayedPointerService` --
  pure CRUD + Cloud sync for Groups 3-5.
- `WorldSyncPreferenceService` -- local-only per-world Group 6 toggle.
- `CloudOnlyWorldDetector` / `CloudOnlyWorldsFacade` -- pure set-difference
  logic (FR6.8) plus a thin `CloudOnlyWorldsHook` wrapper.
- `WorldSaveSyncService` / `WorldRestoreService` -- Group 6's
  archive-build/upload and restore/extract halves.

## Config schema

Location: `config/steam-cloud-sync.json`.

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "syncSettings": true,
  "syncAccessibility": true,
  "syncBookmarkedServers": true,
  "syncContinuePointer": true,
  "syncNotes": true,
  "maxWorldArchiveSizeMb": 50,
  "allowSelectiveFallback": true
}
```

Group 6 has no shared toggle here by design -- its opt-in state lives entirely
in the per-world, local-only `config/steam-cloud-sync/world-sync-preferences.json`
(FR6.1), never itself Cloud-synced. Both files fail closed to defaults/an
empty list with a logged warning on any malformed content, via the shared,
conservative `CloudSyncJson` parser (never best-effort partial parsing).

## Platform-side pieces (per `platform/fabric-<version>` module)

- `SteamworksServiceHandoff` -- narrow static hand-off so this feature's
  composition root can obtain the same `SteamworksService` instance
  `SteamworksClientInitializer` already constructed, without double-`init()`ing.
- `SteamCloudSyncClientInitializer` -- composition root; constructs
  `CloudSyncCoordinator`, the `HelloWorldMainMenuCloudSyncAdapter` (per
  ADR-0003), and every Version Adapter below.
- `cloudsync/FabricBookmarkToggleInjector` / `FabricWorldSyncToggleInjector` --
  Pattern 1 (`ScreenEvents.AFTER_INIT` + `Screens.getWidgets`/`getButtons`)
  overlay-icon widgets for Groups 3 and 6's own toggle.
- `cloudsync/FabricCloudOnlyWorldListInjector` / `CloudOnlyWorldListEntry` --
  Pattern 2 (needs a `@Mixin`) synthetic cloud-only world-select rows
  (FR6.8/FR6.9).
- `cloudsync/WorldRestoreScreen` -- the honest "Restoring.../Extracting..."
  progress screen (FR6.11).
- `mixin/WorldSelectionListInvokerMixin` (26.2/26.1) /
  `WorldListWidgetInvokerMixin` (1.21.11) -- narrow `@Accessor`/`@Invoker`
  mixins exposing the world-list widget's otherwise-`protected`
  `addEntry`/`clearEntries`, confirmed via `javap` against each version's real
  resolved Minecraft jar (see `.claude/context/minecraft.md`'s Known
  Cross-Version API Differences table).

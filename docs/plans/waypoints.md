# Implementation Plan: Waypoints

Spec: `docs/specs/waypoints.md` (approved).

**Scope note (token-budget decision made after spec approval, not part of the
spec itself): this plan and its first implementation increment target
`platform/fabric-26.2` ONLY.** `platform/fabric-1.21.11` and
`platform/fabric-26.1` are deferred — out of scope for this pass. Every
per-platform subsection below is written for 26.2 only; the two deferred
platforms are called out explicitly wherever the spec would otherwise require
a third/second copy, with a `// TODO(waypoints-26.2-only): implement for
fabric-1.21.11/fabric-26.1` marker convention (see "Files to create" and
"Files to modify"). The spec's own Compatibility section is unchanged and
still describes the full 3-version target; this plan simply scopes its
deliverables to one increment of it.

## 1. Existing implementation (facts recorded here; do not re-derive)

### Hotbar-render injection point on 26.2 (Planning Prerequisite)

- No mixin in this repo currently targets a hotbar-render method on any
  platform, on any version (`platform/fabric-26.2/src/main/java/de/lazuli/mixin/`
  has 24 mixin classes; grepped for `Hud`/`Gui` — only `HudCustomCrosshairMixin`,
  `BossHealthOverlayDisableBossBarsMixin`, `WaterFogEnvironmentClearWaterMixin`,
  `CameraZoomFovMixin`, `GuiTitleScreenRedirectMixin` touch that render surface,
  none of them the hotbar itself).
- `HudCustomCrosshairMixin` (`platform/fabric-26.2/.../mixin/HudCustomCrosshairMixin.java`)
  confirms the closest precedent: on 26.2, `@Mixin(Hud.class)` (package
  `net.minecraft.client.gui.Hud`), injecting into
  `extractCrosshair(GuiGraphicsExtractor, DeltaTracker)` at `@At("HEAD")`,
  cancelling and drawing directly via `GuiGraphicsExtractor.fill(...)`. This is
  part of Minecraft 26.x's render-state-extraction model (`extract*` methods,
  not `render*`).
- `.claude/context/minecraft.md`'s "Crosshair/underwater-overlay/boss-bar HUD
  render" row (Known Cross-Version API Differences) confirms: 26.1 has
  `Gui.extractCrosshair`/`Gui.extractTextureOverlay`; on **26.2 specifically**,
  those same-named/same-signature methods moved off `Gui` onto a **new class
  `Hud`** (`Gui` now just holds `public final Hud hud`). No row in that table
  yet documents the hotbar-render method itself on any version — this plan's
  finding above (no existing mixin) confirms it is a genuine gap, not an
  oversight to re-derive.
- **This planning agent has no `javap`/shell tool access** (Read/Glob/Grep/
  Write/WebFetch/WebSearch only) and no decompiled/mapped Minecraft source was
  found anywhere in the repo tree via `Glob` (`**/*Hotbar*.java`,
  `**/Hud.java` all returned nothing). The spec's own Planning Prerequisites
  section anticipated this ("planning's first step should be exactly that
  [`javap`] pass") — that `javap -p` pass against 26.2's own resolved
  Minecraft jar is therefore **implementation's real first step**, not
  something this plan can resolve from static repo inspection alone.
  - **Working hypothesis to verify at implementation time** (not to be
    implemented on faith): given the extraction-model pattern
    `HudCustomCrosshairMixin` already confirms, and given every other 26.2
    HUD/overlay draw call this repo has so far found lives on `Hud`
    (post-split), the hotbar-render call is most likely
    `Hud.extractHotbar(GuiGraphicsExtractor, DeltaTracker)` or a similarly-named
    `extract*` method on `net.minecraft.client.gui.Hud`. Implementation must
    `javap -p` the resolved 26.2 jar (via the Loom-resolved dependency, the
    same way this repo's other `javap`-confirmed mixins were built) to find the
    real method name/signature before writing the mixin, and must record the
    confirmed shape as a new row in `.claude/context/minecraft.md`'s Known
    Cross-Version API Differences table (per that file's own "living record"
    convention) once confirmed — this is a real risk, not a formality (see
    Risks §1).
  - The compass bar mixin injects at `@At("TAIL")` (not `HEAD`/cancelling,
    unlike the crosshair mixin) — it must draw *after* vanilla's own hotbar
    extraction runs, not replace it, since Goal 2 is "rendered above the
    hotbar," not "replaces the hotbar."

### `api` module placement (Planning Prerequisite)

- Confirmed via `Glob` of `features/tweaks/src/main/java/de/lazuli/features/tweaks/**`:
  no `api/` subpackage exists inside `features/tweaks` at all. `TweakId`,
  `TweakState`, `TweakDefinition` all live directly in the root
  `api/src/main/java/de/lazuli/api/tweaks/` module (confirmed via `Glob` of
  `api/src/main/java/de/lazuli/api/**`, alongside `api/cloudsync`,
  `api/mainmenu`, `api/crossworldstats`, etc. — every existing feature's
  cross-boundary types live in the root `api` module, none in a feature-local
  `api/` subpackage). **Decision: `Waypoint`/`WaypointScopeResolver`/
  `WaypointCompassHook` go in `api/src/main/java/de/lazuli/api/waypoints/`**,
  matching this established precedent exactly, not a feature-local package.

### `common`-module JSON parser relocation (Planning Prerequisite)

- Confirmed **already landed**: `common/src/main/java/de/lazuli/common/config/MainMenuJson.java`
  exists (a small hand-rolled `JsonValue`/`JsonObject`/`JsonArray`/parser/writer
  model, originally from `features/main-menu`, per its own Javadoc). 
  `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java`
  already consumes it directly (`import de.lazuli.common.config.MainMenuJson;`),
  and `features/tweaks/build.gradle` already declares
  `implementation project(':common')` for exactly this purpose (its own
  comment: "MainMenuJson ... relocated here from features/main-menu ... this is
  the dependency edge the spec's Architecture section calls out explicitly").
  `platform/fabric-26.2/build.gradle` already declares both
  `implementation project(':common')` and `include project(':common')`
  directly (not just transitively), so platform code (the R23 Cloud sync
  adapter, written inside `SteamCloudSyncClientInitializer.java`) can also use
  `MainMenuJson` with zero new Gradle dependency edges.
  - **Decision: `features/waypoints` reuses `common`'s `MainMenuJson` for
    `WaypointsConfigIO`**, exactly as `features/tweaks` already does — no
    hand-rolled local parser needed, the "if not yet landed" branch of the
    spec's Architecture section does not apply.
  - Note: `features/steam-cloud-sync` has its own, separate, near-identical
    hand-rolled JSON model, `CloudSyncJson`
    (`features/steam-cloud-sync/.../config/CloudSyncJson.java`) — per
    `architecture.md`'s Feature → Feature ban, `features/waypoints` must not
    import it; `MainMenuJson` (via `common`) is the only shared JSON model
    `features/waypoints` may depend on.

### Scope-key filename sanitization (Planning Prerequisite)

- The spec's Persistence section (R6) asserts "existing
  `WorldSyncPreferenceService`/steam-cloud-sync code already sanitizes
  world-slug-derived filenames elsewhere in this codebase" — **this claim does
  not hold up under inspection and should be treated as corrected here, not
  reused as-is.** `WorldSyncPreferenceService` (`features/steam-cloud-sync/.../services/WorldSyncPreferenceService.java`)
  stores `worldSlug` as a JSON map key, never as a filename component, and
  needs no sanitization (a save-folder name is already filesystem-safe by
  construction). `WorldRestoreService`'s own Javadoc (line 42) states plainly:
  "no sanitization/uniquification is needed" for its own folder-naming case.
  No `sanitize`/`slugify`/filename-safe-encoding helper exists anywhere in
  `features/steam-cloud-sync` or elsewhere in the real (non-worktree,
  non-spec-doc) source tree (grepped repo-wide for
  `sanitiz|toFileName|safeFileName|replaceAll\("\[\^`).
  - **Decision:** this is a genuinely new, small piece of code, not a reuse —
    `features/waypoints` needs its own `scopeKeySlug` computation (R6) since
    it is the first feature in this codebase to turn a possibly-`:`-containing
    string (a multiplayer server's `host:port` address, per R3) into a
    filesystem-safe filename. Implemented as a small, directly unit-testable
    pure function (see "Files to create"), not folded into `WaypointsConfigIO`
    itself.

### R23 Cloud sync adapter feasibility (Planning Prerequisite)

- Read `api/src/main/java/de/lazuli/api/cloudsync/CloudSyncable.java` in full:
  `cloudSyncId()`/`exportState(): byte[]`/`importState(byte[])`/
  `localLastModifiedMillis(): long` — no assumption anywhere in the interface
  that the implementation is backed by exactly one file; bundling an entire
  directory into one opaque `byte[]` blob is fully within the contract as
  written.
- Read `features/steam-cloud-sync/.../services/CloudSyncCoordinator.java` in
  full: `cloudSyncables: List<CloudSyncable>` is a constructor parameter, built
  once by the platform composition root and stored immutably
  (`List.copyOf(...)`, line 114) — confirms the spec's citation. Cloud file
  naming is coordinator-internal and fixed per adapter:
  `cloudSyncableFileName(syncable) = "lazuli-cloudsync-" + syncable.cloudSyncId() + ".dat"`
  (line 312-314) — a waypoints adapter registered with `cloudSyncId() =
  "waypoints"` gets Cloud file `lazuli-cloudsync-waypoints.dat`, one flat file,
  regardless of how many scope-keyed local files it bundles internally.
  `reconcileAtStartup()`/`syncOnShutdown()` iterate `cloudSyncables` uniformly
  (lines 196-213, 256-268) — no per-adapter special-casing needed, confirming
  no change to `CloudSyncCoordinator` itself is required.
- Read `features/steam-cloud-sync/.../services/CloudFileStore.java` in full:
  its own Javadoc (lines 8-9) **already explicitly anticipates this feature**
  ("Groups 1/3/4/5 ... bookmarked servers, the continue-pointer, and
  notes/**waypoints**") as a consumer of this exact small-whole-file
  read/write/timestamp seam — confirms the spec's citation of
  `CloudFileStore.java:9` and that no interface change is needed there either.
- Read `platform/fabric-26.2/.../SteamCloudSyncClientInitializer.java` in
  full: `TweaksJsonCloudSyncAdapter` (lines 218-257) and
  `CrossWorldStatsCloudSyncAdapter` (lines 271-322) are private static nested
  classes inside this one composition-root file, each wrapping exactly one
  fixed `Path`, added to the `List.of(...)` construction at lines 65-69. This
  is the exact shape ADR-0003 licenses (composition-root-only
  cross-Feature-adjacent wiring) and the exact shape
  `WaypointsJsonCloudSyncAdapter` will follow, generalized from "wrap one
  fixed file" to "wrap and enumerate one fixed directory."
  - **Import/export semantics decision** (spec flags whole-bundle
    last-write-wins as the accepted tradeoff, but does not fully spell out
    directory-vs-file merge mechanics): `exportState()` enumerates every
    `*.json` file currently under `config/waypoints/` via
    `Files.list(...)`, wraps `{scopeKeySlug -> raw file text}` pairs into one
    `MainMenuJson.JsonObject` envelope (each file's raw JSON text embedded as
    a `JsonString` value, keyed by filename), and serializes the envelope with
    `MainMenuJson.write(...)` to UTF-8 bytes. `importState(byte[])` parses
    that envelope and **overwrites only the files named inside it** under
    `config/waypoints/`, leaving any local-only scope file (a scope this
    device has visited that the imported envelope doesn't mention) untouched
    — this is what makes the Architecture section's "each device's own local
    files for scopes it hasn't touched are simply carried through unchanged"
    claim actually true; a naive "delete directory then write envelope
    contents" implementation would violate it. `localLastModifiedMillis()`
    returns the max `Files.getLastModifiedTime(...)` across all files
    currently under `config/waypoints/` (matching the spec's own description),
    or `-1L` if the directory doesn't exist yet (no scope visited on this
    device yet).

### Pause panel / sub-view-swap precedent

- `platform/fabric-26.2/.../mainmenu/PausePanel.java` (current, full file
  read): minimal today — one constructor param (`Runnable onReturnToGame`),
  `render`/`mouseClicked` only, no `init`, no sub-view state, exactly one
  "Return to Game" button (`BUTTON_WIDTH=200`, `BUTTON_HEIGHT=24`, drawn at
  `x + (width-200)/2, y+24`). No dimension-selector/list precedent exists in
  this file yet — R19-R22 are genuinely new content for it.
- `platform/fabric-26.2/.../mainmenu/TweaksPanel.java` (current, full file
  read) is the confirmed sub-view-swap precedent the spec cites:
  `configuring: TweakId` — a nullable field gating `render`/`mouseClicked`
  dispatch between row-list and config-sub-view (`if (configuring != null) {
  renderConfigScreen(...); return; }`, same shape in `mouseClicked`), a
  `Back` `Button` widget constructed lazily in `ensureBackButton` and added
  via an injected `Consumer<AbstractWidget> addWidget`, torn down in
  `leaveConfigScreen()` (also called externally by `MainMenuScreen` on tab
  switch / screen close to avoid a leaked stale widget — plan §7 Risk #1 in
  that panel's own history). `TweaksPanel.init(addWidget, removeWidget, x, y,
  width)` is called once from `MainMenuScreen.init()` (line 177); `render`/
  `mouseClicked`/`mouseScrolled` all take `(x, y, width, height, mouseX,
  mouseY, ...)` matching `PausePanel`'s existing signatures.
  `MainMenuScreen.java` calls `tweaksPanel.leaveConfigScreen()` at 3 call
  sites (tab-switch-away, and twice around screen close, lines 386/468/475)
  — the same 3 call sites will need a `pausePanel.leaveWaypointManager()`
  (or equivalently named) call added alongside them.
- `platform/fabric-26.2/.../mainmenu/AchievementsPanel.java` (partial read,
  first 80 lines) confirms the row-list + filter-pill idiom the spec cites
  for R22 (list rendering) and is reused here for R20 (dimension selector):
  a `private enum Filter { ALL, UNLOCKED, LOCKED }` field driving a row of
  pill buttons (`filterLabel`, active/hover-colored fill,
  `guiGraphics.centeredText`), and a plain vertical row list below it. The
  waypoint Manager's dimension selector reuses this exact pill-row idiom
  (dimension ids instead of a fixed 3-value enum, since dimension ids are
  data-driven per R1), and its waypoint list rows reuse the same row-fill/
  hover/text idiom `TweaksPanel`'s own row list already uses
  (`COLOR_ROW_IDLE`/`COLOR_ROW_HOVER`/`COLOR_TITLE`/`COLOR_DESC` constants).
- **Delete-confirmation mechanism precedent**: `TweaksPanel`'s own
  arm-then-act pattern (`armedBindTarget`/`COLOR_ARMED_BG`, "press a key to
  bind... (Esc to cancel)") is reused for R21's delete confirmation: a
  waypoint row's delete control, once clicked, arms itself (highlighted,
  showing "Confirm delete?" in place of its normal label) and requires a
  second click on the same control within the same interaction to actually
  delete; any other click (a different row, a scroll, opening the dimension
  selector) disarms it. No new interaction idiom invented.
- `MainMenuScreen.java` constructs `pausePanel = new PausePanel(this::onClose)`
  (line 139) and dispatches `case PAUSE -> pausePanel.render(...)` (line 292)
  / `pausePanel.mouseClicked(...)` (line 427) inside its existing `switch`.
  `PausePanel`'s constructor will gain a `WaypointsBundle`-equivalent
  parameter (mirroring `TweaksPanel(TweaksBundle bundle)`'s shape), and
  `MainMenuScreen`'s constructor will need `pausePanel = new
  PausePanel(this::onClose, waypointsBundle)`.

### Composition-root / Handoff wiring precedent

- `platform/fabric-26.2/.../TweaksClientInitializer.java` (full file read) is
  the direct precedent for `WaypointsClientInitializer`: resolves
  `FabricLoader.getInstance().getConfigDir()`, loads a `*ConfigIO`, constructs
  a registry with a write-through save callback, publishes a bundle via a
  static `*Handoff` class, registered in `fabric.mod.json`'s `"client"`
  entrypoint array **before** `MainMenuClientInitializer` (order is
  load-bearing, per that file's own Javadoc and `TweakRegistryHandoff`'s
  Javadoc: "Correctness depends only on `TweaksClientInitializer` appearing
  before `MainMenuClientInitializer`").
- `platform/fabric-26.2/.../TweakRegistryHandoff.java` (full file read,
  lives in the root `de.lazuli` package, not `de.lazuli.tweaks`) is the
  `publish(T)`/`require(): T` static-volatile-field handoff shape
  `WaypointRegistryHandoff` will copy exactly (same package placement: root
  `de.lazuli`, alongside `TweakRegistryHandoff`/`SteamworksServiceHandoff`/
  etc. — confirmed via `Glob` of `platform/fabric-26.2/src/main/java/de/lazuli/*.java`,
  19 existing `*Handoff`/`*Holder` classes all live directly there).
- `platform/fabric-26.2/.../tweaks/TweakEngineHandoff.java` (full file read,
  lives in `de.lazuli.tweaks`, the platform's own feature-adjacent package,
  not the root `de.lazuli` package) is the separate precedent for a
  *mixin-facing* handoff — `TweakEngineHandoff.require()` is called directly
  from `HudCustomCrosshairMixin` to reach `TweakHooksImpl` without threading
  it through a constructor. `WaypointEngineHandoff` (in a new
  `de.lazuli.waypoints` platform package, mirroring `de.lazuli.tweaks`) is the
  same shape, giving the compass-bar mixin a way to reach the
  `WaypointCompassHook` implementation.
- `MainMenuClientInitializer.java` (grepped, not fully read) calls
  `TweaksBundle tweaksBundle = TweakRegistryHandoff.require();` then threads
  it into `MainMenuScreen`'s constructor — `WaypointsBundle
  waypointsBundle = WaypointRegistryHandoff.require();` follows the same
  shape and gets threaded into the same constructor call alongside it.
- `platform/fabric-26.2/.../SteamCloudSyncClientInitializer.java` (full file
  read) already hooks `ClientPlayConnectionEvents.JOIN`/`DISCONNECT` for its
  own `LastPlayedPointerService` wiring (`onPlayJoin`/`onPlayDisconnect`,
  lines 324-348) — confirms the exact lifecycle checkpoint the spec's R9 and
  Architecture section describe as already-established
  (`LastPlayedPointerService.recordWorldEntered`/`recordServerJoined`
  equivalents). `WaypointsClientInitializer` registers its own, independent
  listener on the same two Fabric events (not a shared mechanism, per
  Architecture's explicit note that these are "two independent features
  reacting to the same lifecycle event").
- `fabric.mod.json` (`platform/fabric-26.2/src/main/resources/`, full file
  read): current `"client"` entrypoint order ends
  `..., "TweaksClientInitializer", "MainMenuClientInitializer"`.
  `WaypointsClientInitializer` must be inserted **after**
  `SteamCloudSyncClientInitializer` is irrelevant (waypoints doesn't depend on
  it) but **before** `MainMenuClientInitializer` (whose construction needs
  `WaypointRegistryHandoff.require()` to already be published) — same
  ordering constraint `TweaksClientInitializer` already satisfies.
- `lazuli.mixins.json` (`platform/fabric-26.2/src/main/resources/`, full
  file read): flat `"mixins"` array, 24 entries today, `"required": true`,
  `defaultRequire: 1`. The new compass-bar mixin's simple class name gets
  appended.

## 2. Files to create

### `api` module (root, not feature-local — see Existing Implementation)

- `api/src/main/java/de/lazuli/api/waypoints/Waypoint.java` — `record
  Waypoint(String id, String name, int x, int y, int z, String dimensionId,
  int color, long createdAtMillis)` (R1). No Minecraft imports (matches
  `architecture.md`'s api-layer rule, `api/tweaks`'s own precedent).
- `api/src/main/java/de/lazuli/api/waypoints/WaypointScopeResolver.java` —
  `interface WaypointScopeResolver { String currentScopeKey(); String
  currentDimensionId(); }`, per spec's Public API section verbatim.
- `api/src/main/java/de/lazuli/api/waypoints/WaypointCompassHook.java` —
  `interface WaypointCompassHook { List<Waypoint>
  waypointsForCurrentDimension(); }`. Resolves the spec's "shape TBD" note:
  a single no-arg method returning the current dimension's live list is
  sufficient — the platform mixin already has direct access to the player's
  own position/yaw (per spec's own note) and only needs the waypoint data
  itself to cross the Feature → Platform boundary.

### `features/waypoints` (new Gradle module)

- `features/waypoints/build.gradle` — `api project(':api')` (Waypoint/etc.
  appear in `WaypointRegistry`'s own public signatures, same rationale
  `features/tweaks/build.gradle`'s own comment gives for its identical line);
  `implementation project(':common')` (for `MainMenuJson`, per Existing
  Implementation finding above).
- `features/waypoints/src/main/java/de/lazuli/features/waypoints/services/WaypointRegistry.java`
  — implements `WaypointCompassHook`; in-memory `Map<String dimensionId,
  List<Waypoint>>` for the *currently loaded scope only* (R4/R9 — one scope
  resident at a time); `list(dimensionId)`, `add(name,x,y,z,dimensionId)`,
  `rename(id,newName)`, `editPosition(id,x,y,z,dimensionId)`, `delete(id)`
  exactly per spec's Public API "Services surface" list; every mutation
  method calls a write-through save callback immediately (R7), mirroring
  `WorldSyncPreferenceService`'s `persist()`-after-every-mutation shape
  (constructor takes a `Consumer<WaypointsFile>`-equivalent save callback, not
  a `Path` directly — keeps this class Minecraft/`java.nio.file`-import-light
  and directly unit-testable, matching `TweakRegistry`'s own shape); a
  `loadScope(scopeKey, WaypointsFile)` / `unloadScope()`-equivalent pair for
  the platform composition root to call on world-join/disconnect (R9).
- `features/waypoints/src/main/java/de/lazuli/features/waypoints/services/WaypointColorAssigner.java`
  — small pure function, `static int colorFor(String waypointId)` (R5):
  hashes the UUID string to a hue value on a fixed-saturation/fixed-lightness
  HSL wheel (e.g. S=65%, L=55%, matching typical "distinct, readable, not
  too dark/too pale" swatch conventions), converts to packed ARGB. Kept
  separate from `WaypointRegistry` specifically so it is directly,
  deterministically unit-testable (same UUID in → same color out, every
  time, across JVM runs — no `Random`/timestamp involved).
- `features/waypoints/src/main/java/de/lazuli/features/waypoints/services/ScopeKeySlugger.java`
  — small pure function, `static String slug(String scopeKey)` (R6): lowercases
  and replaces every character outside `[a-z0-9_-]` with `_` (e.g.
  `"play.example.com:25565"` → `"play_example_com_25565"`,
  `"my_world_folder"` → unchanged). New code, not a reuse (see Existing
  Implementation finding correcting the spec's R6 claim) — kept as its own
  small class specifically so it, too, is directly unit-testable in isolation
  (collision behavior, e.g. two different addresses slugging to the same
  string, is an accepted, documented limitation, not solved here — matches
  this codebase's existing address-keying tradeoff already accepted for
  bookmarks/last-played per spec R3).
- `features/waypoints/src/main/java/de/lazuli/features/waypoints/config/WaypointsFile.java`
  — internal persistence-shape record: `record WaypointsFile(int
  schemaVersion, String scopeKey, Map<String, List<Waypoint>> dimensions)`
  (R6/R8).
- `features/waypoints/src/main/java/de/lazuli/features/waypoints/config/WaypointsConfigIO.java`
  — hand-rolled load/parse/serialize for **one** scope's file, using
  `common`'s `MainMenuJson`, mirroring `WorldCloudMetadataIO`'s shape exactly
  (`ParseResult(WaypointsFile file, String warning)` record with `ok`/
  `fallback` factories; `load(Path)`: load-or-create-with-empty-defaults on
  first access (R7/R9), fail-closed to an empty `WaypointsFile` with a
  human-readable warning on malformed content (R7); `CURRENT_SCHEMA_VERSION =
  1` constant (R8), matching `WorldCloudMetadataIO.CURRENT_SCHEMA_VERSION`'s
  convention, including its "schemaVersion newer than this build" tolerant
  branch).
- `features/waypoints/src/main/java/de/lazuli/features/waypoints/gui/package-info.java`,
  `.../mixins/package-info.java`, `.../events/package-info.java` — empty
  placeholders per `feature-guidelines.md`, mirroring `features/tweaks`' and
  `features/steam-cloud-sync`'s identical placeholder packages (all real
  HUD-render/Pause-panel/mixin code lives in `platform/fabric-26.2`).
- Tests (pure JVM, no Minecraft classes, mirroring `TweaksConfigIOTest`/
  `TweakRegistryTest`'s existing shape exactly):
  - `features/waypoints/src/test/java/de/lazuli/features/waypoints/config/WaypointsConfigIOTest.java`
    — parse/serialize round-trip, malformed-content fallback, missing-file
    load-or-create, forward-compatible unknown-field tolerance (mirrors
    `TweaksConfigIOTest`'s style).
  - `features/waypoints/src/test/java/de/lazuli/features/waypoints/services/WaypointRegistryTest.java`
    — add/rename/editPosition/delete CRUD, write-through-on-every-mutation
    (asserts the save callback fires with the expected `WaypointsFile` after
    each mutation), per-dimension `list(dimensionId)` isolation (R4/R17: a
    mutation in one dimension must not appear in another dimension's list).
  - `features/waypoints/src/test/java/de/lazuli/features/waypoints/services/WaypointColorAssignerTest.java`
    — determinism (same UUID string → same color across repeated calls), and
    a basic distinctness spot-check across a handful of distinct UUIDs (not
    an exhaustive collision-freedom proof — HSL-wheel hashing can theoretically
    collide, matching R5's own "keeps colors stable... avoids collision" intent
    without claiming a stronger guarantee than a hash can actually provide).
  - `features/waypoints/src/test/java/de/lazuli/features/waypoints/services/ScopeKeySluggerTest.java`
    — `host:port` → filesystem-safe slug, already-safe strings pass through
    unchanged, empty/edge-case input doesn't throw.

### `platform/fabric-26.2` (only platform touched this pass)

- `platform/fabric-26.2/src/main/java/de/lazuli/WaypointsClientInitializer.java`
  — new `ClientModInitializer`, mirrors `TweaksClientInitializer.java`'s
  shape: resolves `configDir.resolve("waypoints")` as the per-scope-file
  directory (R6); constructs `WaypointRegistry` with a save callback writing
  through `WaypointsConfigIO`; implements `WaypointScopeResolver` inline
  (singleplayer: `Minecraft.getInstance().getSingleplayerServer()`'s world
  folder name via the same `LevelResource.ROOT`-based resolution
  `SteamCloudSyncClientInitializer.singleplayerWorldInfo` already uses for
  `worldSlug`, per R3; multiplayer: `client.getCurrentServer().ip`, matching
  `LastPlayedPointerService.recordServerJoined`'s own `client.getCurrentServer().ip`
  usage; current dimension: the joined `ClientLevel`'s own dimension-id
  string); registers `ClientPlayConnectionEvents.JOIN`/`DISCONNECT` listeners
  that call `WaypointRegistry.loadScope(...)`/`unloadScope()` (R9), mirroring
  `SteamCloudSyncClientInitializer.onPlayJoin`/`onPlayDisconnect`'s shape but
  as an independent listener registration (Architecture: "two independent
  features reacting to the same lifecycle event"); publishes a
  `WaypointsBundle` (or reuses `WaypointRegistry` directly if no second
  collaborator ends up needed — mirrors `TweaksBundle`'s
  registry-plus-keybindings shape, but Waypoints has no keybindings per
  Non-goals, so this may simply be `WaypointRegistry` itself, decided at
  implementation time once the exact constructor shape is known) via
  `WaypointRegistryHandoff.publish(...)`; publishes the same
  `WaypointCompassHook` implementation via `WaypointEngineHandoff.publish(...)`
  for the HUD mixin.
- `platform/fabric-26.2/src/main/java/de/lazuli/WaypointRegistryHandoff.java`
  — root `de.lazuli` package (matching `TweakRegistryHandoff`'s placement),
  `publish`/`require` static-volatile-field shape, consumed by
  `MainMenuClientInitializer`.
- `platform/fabric-26.2/src/main/java/de/lazuli/waypoints/WaypointEngineHandoff.java`
  — new `de.lazuli.waypoints` platform package (mirrors `de.lazuli.tweaks`),
  `publish`/`require` shape for `WaypointCompassHook`, consumed by the compass
  bar mixin.
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/HudWaypointCompassBarMixin.java`
  (name provisional — implementation confirms once the real target method
  name is `javap`-confirmed, see Existing Implementation §1) — `@Mixin(Hud.class)`,
  `@Inject(method = "<javap-confirmed hotbar-extraction method>", at =
  @At("TAIL"))`, non-cancelling. Reads `WaypointEngineHandoff.require()
  .waypointsForCurrentDimension()` plus `Minecraft.getInstance().player`'s
  live position/yaw directly (no round-trip through the API, per spec's
  Public API note), computes bearing/distance/screen-position per R11-R16
  (see §3 Numeric defaults below), and draws each visible waypoint's dot
  (R12/R15), elevation chevron (R13), and near-center name label(s) (R14) via
  `GuiGraphicsExtractor.fill`/`text`/`centeredText` calls, matching
  `HudCustomCrosshairMixin`'s direct-draw style. Early-returns with zero
  allocation when the current dimension's waypoint list is empty (R17).
- `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WaypointManagerPanel.java`
  — new panel class, constructor takes the feature's `WaypointRegistry`/
  bundle plus the `WaypointScopeResolver` (for "current dimension" default
  and "Add at current position" — needs live player position too, obtained
  the same way `WorldsPanel`/other panels reach `Minecraft.getInstance()`
  directly, not through the API layer, since this is platform code); `render`/
  `mouseClicked`/`mouseScrolled` matching `PausePanel`'s existing method
  signatures (R19); dimension-selector pill row (R20, `AchievementsPanel.Filter`
  idiom, generalized to a `List<String>` of known dimension ids rather than a
  fixed 3-value enum); scrollable waypoint list (R22, `TweaksPanel`/
  `AchievementsPanel` row idiom) with per-row name/color-swatch/coordinates/
  edit/delete controls (R21); "Add at current position" + manual x/y/z entry
  flows (R21, `EditBox` widgets, mirroring `TweaksPanel`'s own
  `beginStringListAdd`/`EditBox`-lifecycle pattern for text input); arm-then-
  confirm delete (see Existing Implementation, `TweaksPanel`'s bind-arm
  precedent).

## 3. Files to modify

### `settings.gradle`

- Add `include 'features:waypoints'`, positioned after `include
  'features:tweaks'` (per user's stated preference for module ordering).

### `platform/fabric-26.2/build.gradle`

- Add `implementation project(':features:waypoints')` +
  `include project(':features:waypoints')`, positioned after the existing
  `features:tweaks` pair (lines 28-29), matching every other feature module's
  existing two-line `implementation`+`include` shape in this file.

### `platform/fabric-26.2/src/main/resources/fabric.mod.json`

- Insert `"de.lazuli.WaypointsClientInitializer"` into the `"client"`
  entrypoint array, after `"de.lazuli.TweaksClientInitializer"` and before
  `"de.lazuli.MainMenuClientInitializer"` (ordering constraint: see Existing
  Implementation).

### `platform/fabric-26.2/src/main/resources/lazuli.mixins.json`

- Append the new compass-bar mixin's simple class name to the flat
  `"mixins"` array.

### `platform/fabric-26.2/src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java`

- Add a new private static nested class `WaypointsJsonCloudSyncAdapter
  implements CloudSyncable` (R23), following `TweaksJsonCloudSyncAdapter`'s
  placement/shape but bundling a directory instead of one file (see Existing
  Implementation §"R23 Cloud sync adapter feasibility" for the exact
  export/import merge semantics decided above). Constructor takes the
  `config/waypoints/` directory `Path`.
- Add `new WaypointsJsonCloudSyncAdapter(configDir.resolve("waypoints"))` to
  the `cloudSyncables = List.of(...)` construction (lines 65-69), alongside
  the existing four adapters.

### `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/PausePanel.java`

- Constructor gains a new parameter (the Waypoints registry/bundle, plus
  whatever `WaypointScopeResolver`-adjacent context `WaypointManagerPanel`
  needs — exact shape settled at implementation time once
  `WaypointManagerPanel`'s own constructor is written).
- Add a new "Waypoints" button below "Return to Game" (R19, same
  fill/hover/centered-text idiom the existing button already uses, per spec's
  UI section citation of `PausePanel.java:36-43`).
- Add a nullable sub-view field (e.g. `boolean managingWaypoints` or a
  `WaypointManagerPanel`-presence check — exact field shape mirrors
  `TweaksPanel.configuring`'s single-nullable-field style) gating `render`/
  `mouseClicked` dispatch between normal Pause content and the embedded
  `WaypointManagerPanel`, plus a `Back` button (R19) and a
  `leaveWaypointManager()`-equivalent method for `MainMenuScreen` to call at
  its 3 existing `tweaksPanel.leaveConfigScreen()` call sites (widget-leak
  prevention, same rationale `TweaksPanel`'s own history already established).
- Gains an `init(addWidget, removeWidget, x, y, width)` method (currently
  absent — `PausePanel` has none today since it owns no widgets), mirroring
  `TweaksPanel.init(...)`'s signature exactly, needed once the Waypoints
  button and Back button become real `Button` widgets requiring
  add/remove-widget wiring.

### `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/MainMenuScreen.java`

- Constructor: add a `WaypointsBundle waypointsBundle` parameter (mirrors the
  existing `TweaksBundle tweaksBundle` parameter), obtained by
  `MainMenuClientInitializer` via `WaypointRegistryHandoff.require()`.
- Update `this.pausePanel = new PausePanel(this::onClose)` (line 139) to pass
  the new parameter(s).
- Add `pausePanel.init(this::addRenderableWidget, this::removeWidget,
  panelX(), panelY(), panelWidth())` alongside the existing
  `tweaksPanel.init(...)` call (line 177).
- Add `pausePanel.leaveWaypointManager()` (or equivalently named) calls
  alongside each of the 3 existing `tweaksPanel.leaveConfigScreen()` call
  sites (lines 386, 468, 475).

### `platform/fabric-26.2/src/main/java/de/lazuli/MainMenuClientInitializer.java`

- Add `WaypointsBundle waypointsBundle = WaypointRegistryHandoff.require();`
  (mirrors the existing `TweaksBundle tweaksBundle =
  TweakRegistryHandoff.require();` line) and thread it into the
  `MainMenuScreen` constructor call.

### `.claude/context/minecraft.md`

- Append a new row to the "Known Cross-Version API Differences" table once
  implementation's `javap -p` pass against 26.2's resolved jar confirms the
  real hotbar-render method name/class (per that file's own "living record,
  append whenever implementation work turns up a real divergence" convention)
  — not edited by this plan itself, flagged here as a required implementation
  step (see Existing Implementation §1, Risks §1).

### Deferred (explicitly NOT modified this pass)

- `platform/fabric-1.21.11/**` and `platform/fabric-26.1/**` — no
  `WaypointsClientInitializer`, no compass-bar mixin, no `PausePanel`/
  `WaypointManagerPanel` changes, no `SteamCloudSyncClientInitializer`
  adapter, on either platform this pass. Where `features/waypoints`' own
  shared code would otherwise need a second/third platform-specific
  implementation registered (there currently is none — `WaypointScopeResolver`/
  `WaypointCompassHook` are pure interfaces with no platform-count-sensitive
  registry inside `features/waypoints` itself), no `TODO` marker is actually
  needed inside `features/waypoints`; the marker convention requested for this
  pass applies at the point a genuine "one implementation per platform slot"
  registration would otherwise exist, and no such slot exists in this
  feature's shared-module code (unlike, say, a `Map<Platform, X>` table
  would need one). If a later refactor introduces one, mark it
  `// TODO(waypoints-26.2-only): implement for fabric-1.21.11/fabric-26.1`
  per the task's instructions.

## 4. Numeric/visual-design defaults (Planning Prerequisite — resolved here, not left to implementation)

Cross-referenced against `features/tweaks/.../services/ConfigSchemas.java`/
`TweakDefinitions.java`'s existing numeric-default precedent (e.g.
`HIDE_PLAYER_NAMES.range` defaults to `16.0` blocks out of a `0.0-64.0` range;
`FORCE_BRIGHTNESS.minBrightness` defaults to `4.0` out of `0.0-4.0`;
`CLEAR_WATER.opacity` defaults to `0.0` out of `0.0-1.0`) as evidence this
repo already picks concrete round-number visual/gameplay-feel defaults at
planning/implementation time rather than deriving them from a formula — the
same approach is used below. None of these are exposed as user-configurable
`Tweak`s (Waypoints has no config screen of its own per spec's Configuration
section); they are hardcoded constants in the compass-bar mixin and
`WaypointManagerPanel`.

- **R10 placement / pixel offset.** Compass bar width = 182px (vanilla's own
  known hotbar width constant: 9 slots × 20px + 2px border), horizontally
  centered on screen (matches hotbar centering). Height = 10px. Vertical
  position: bottom edge fixed at `screenHeight - 40`, i.e. spanning
  `screenHeight - 50` to `screenHeight - 40` — chosen to sit above vanilla's
  health/hunger/armor row + XP bar cluster (which together occupy
  approximately the bottom 39px of screen when both are visible) with a small
  buffer gap. **Flagged as an implementation-time visual check, not a
  `javap`-confirmed pixel value** (vanilla's exact HUD-stack Y coordinates on
  26.2 were not independently re-derived here) — implementation should
  visually verify no overlap in-game and adjust this one constant if needed;
  this is a cosmetic-only risk, not a functional one.
- **R11 angular field-of-view window.** ±90° (180° total), one of the two
  options the spec's own R11 text explicitly suggests ("a fixed wider window
  like ±90 degrees") — chosen over "matches the player's actual FOV" because
  the actual FOV is itself a user-configurable vanilla option (30-110°),
  and a fixed window keeps the compass bar's bearing-to-pixel mapping
  independent of that setting, avoiding a dependency this feature doesn't
  otherwise need. Bearing angle `theta` (normalized to player yaw, range
  `[-90, 90]`) maps linearly to pixel x: `x = barCenterX + (theta / 90.0) *
  (barWidth / 2.0)`.
- **R12 distance-based dot scaling.** Linear interpolation (not logarithmic —
  simplest option, matches this repo's general preference for linear ranges
  in `ConfigFieldSpec.numeric` defaults over anything curve-shaped). Min dot
  size (floor) = 2px diameter at distance ≥ 128 blocks; max dot size (cap) =
  6px diameter at distance ≤ 8 blocks; linearly interpolated between, clamped
  outside that range.
- **R13 elevation-indicator threshold.** A fixed Y-delta of 10 blocks (no
  confirmed Locator Bar threshold was discoverable per the spec's own
  "if that's confirmed practical to discover" hedge — this plan picks an
  independent, reasonable value instead, as the spec explicitly permits): if
  `|waypoint.y - player.y| >= 10`, draw a small up/down chevron above/below
  the dot.
- **R14 near-center name-label threshold.** ±5° from bar center (angular,
  consistent with R11's angle-to-pixel mapping — equivalent to roughly ±5px
  from bar center given the 180°/182px mapping above). If multiple waypoints
  fall within this window simultaneously, **show only the nearest one's name
  label** (simplest of the spec's explicitly-offered resolutions; avoids
  vertical label stacking's added layout complexity for a rare edge case).
- **R15 color rendering.** Dot drawn in the waypoint's own color (R5) at full
  opacity; name label text drawn in that same color directly (no neutral-fill
  fallback) — simplest option, revisited only if an in-game visual check
  shows a real readability problem against the HUD background (not assumed
  here).
- **Manager panel — dimension selector (R20).** Pill-row idiom
  (`AchievementsPanel.Filter` precedent), populated from the union of
  dimension ids actually present in the loaded scope's `WaypointsFile`
  (R4's `Map<String, List<Waypoint>>` keys) plus the player's current
  dimension if not already present (so a scope with zero waypoints in the
  current dimension still shows a selectable, empty entry for it).
- **Manager panel — "Add at current position" availability (R21 open
  question).** Enabled/shown only when the Manager's active dimension
  selection equals the player's actual current dimension; when a different
  dimension is selected, only the manual x/y/z entry flow is offered — avoids
  the ambiguity of "current position" meaning a location in a dimension the
  player isn't standing in.
- **R21 dimension reassignment on edit.** Allowed — the spec's own Public API
  section already settles this: `editPosition(id, x, y, z, dimensionId)`
  takes a `dimensionId` parameter, so moving a waypoint between dimensions is
  already part of the confirmed Services surface, not an open design
  question for this plan to re-litigate.

## 5. Risks

1. **Hotbar-render injection point is a genuine unconfirmed unknown, and this
   planning pass had no `javap`/shell access to resolve it** (see Existing
   Implementation §1). The working hypothesis (`Hud.extractHotbar`-shaped
   method) is reasoned from a real, confirmed pattern
   (`HudCustomCrosshairMixin`'s `extractCrosshair`) but is **not** itself
   `javap`-confirmed. If wrong, implementation's mixin will fail to load
   (Sponge Mixin's `defaultRequire: 1` + `requireAnnotations: true` in
   `lazuli.mixins.json` will throw at startup, not silently no-op) —
   implementation must budget real time for a `javap -p` pass against the
   Loom-resolved 26.2 Minecraft jar as its literal first step, before writing
   the mixin body, exactly as the spec's own Planning Prerequisites section
   anticipated. Mitigation: this is a fail-loud, not fail-silent, risk (a
   missing/wrong mixin target crashes client startup immediately, is
   impossible to ship unnoticed) — but it does mean this risk could not be
   fully retired during planning and is carried into implementation
   explicitly, unlike every other "confirmed via direct file read" item in
   this plan.
2. **Pixel-perfect HUD placement (R10) is a cosmetic, not functional, risk.**
   The fixed `screenHeight - 40` constant chosen in §4 was reasoned from
   general vanilla HUD-stack knowledge, not `javap`/in-game-confirmed against
   26.2 specifically. Worst case: the compass bar renders slightly
   overlapping the health/hunger row or with an oversized gap above the
   hotbar on first implementation pass — a one-constant visual fix, not a
   design-level rework.
3. **Whole-directory Cloud sync bundle (R23) merge semantics are new code,
   not a straight copy of `TweaksJsonCloudSyncAdapter`.** The "overwrite only
   files named in the imported envelope, preserve local-only scope files"
   requirement (see Existing Implementation) is easy to get wrong in a way
   that silently deletes a player's local-only-scope waypoints on next Cloud
   sync (e.g. a naive "clear directory, write envelope" implementation) —
   this needs its own dedicated unit test (directory with 2 local scope files,
   import an envelope containing only 1 of them, assert the untouched one
   survives) beyond what `WaypointsConfigIOTest`/`WaypointRegistryTest`
   already cover, called out explicitly in Test strategy below.
4. **Scope-key collisions from `ScopeKeySlugger` are a known, accepted, not
   solved, limitation** (per R3's own carried-forward Open Question and this
   plan's Existing Implementation note) — two distinct server addresses that
   happen to slug to the same filesystem-safe string would silently share one
   waypoint file. Not solved here, matching the spec's own explicit
   acceptance of the equivalent address-keying tradeoff for bookmarks/
   last-played; flagged so implementation doesn't attempt to "fix" it
   unprompted (e.g. by adding a hash suffix), which would diverge from the
   spec's stated intent.
5. **`PausePanel`'s constructor/method-signature changes are a breaking
   change to an existing, actively-used class.** Every existing call site
   (`MainMenuScreen`'s constructor and its 3 `leaveConfigScreen`-adjacent call
   sites) must be updated in the same change; missed call sites would compile-
   fail (Java, not silently break), so this is a low-severity, easily-caught-
   at-compile-time risk, but worth enumerating since it's the one change in
   this plan touching an existing widely-used class rather than adding new
   files.

## 6. Dependencies

No new external (non-Fabric) dependency is introduced by this plan. Every
library used is already declared in this repo:
- `net.fabricmc.fabric-api:fabric-api` (already a `platform/fabric-26.2`
  dependency) — `ClientPlayConnectionEvents`/`ClientTickEvents`/
  `ClientLifecycleEvents`, all already used identically by
  `SteamCloudSyncClientInitializer`/`TweaksClientInitializer`.
- `common`'s `MainMenuJson` (in-repo module, already depended on by
  `platform/fabric-26.2` directly and by `features/tweaks`).
- `api` (in-repo root module).
- JUnit 5 / AssertJ (already provided to every subproject by the root
  `build.gradle`, per `features/tweaks/build.gradle`'s own comment).

No Maven Central / external registry lookup was needed for this plan (no new
coordinate is being pinned), so no `WebFetch` verification against
`search.maven.org` applies here.

## 7. Test strategy

Pure-JVM, Minecraft-import-free tests only (per `ui-guidelines.md`'s Testing
section, mirroring `TweaksConfigIOTest`/`TweakRegistryTest`'s existing shape
— no `Screen`/mixin-layer testing attempted, consistent with this codebase's
established convention that render/mixin code is verified by live in-game
checks, not unit tests):

- `WaypointsConfigIOTest` — parse/serialize round-trip (including the
  illustrative R6 JSON shape from the spec verbatim), malformed-content
  fail-closed-to-empty-with-warning (R7), missing-file load-or-create (R7),
  `schemaVersion` newer-than-current tolerance (R8, mirroring
  `WorldCloudMetadataIO`'s own tolerant branch).
- `WaypointRegistryTest` — full CRUD (add assigns id/color/createdAtMillis
  per R1/R5; rename; editPosition including cross-dimension move per R21;
  delete), write-through-on-every-mutation (asserts the injected save
  callback is invoked with the updated state after each mutation, matching
  `WorldSyncPreferenceService`'s own `persist()`-after-mutation testable
  shape), per-dimension list isolation (R4), `loadScope`/`unloadScope`
  lifecycle (R9 — a second `loadScope` call for a different scope key must
  not leak the previous scope's waypoints into the new one's `list(...)`
  calls).
- `WaypointColorAssignerTest` — determinism + basic distinctness spot-check
  (R5), per §2's caveat about not over-claiming collision-freedom.
- `ScopeKeySluggerTest` — safe-character passthrough, `:`/other-unsafe-char
  replacement, case-folding (R6).
- **New, dedicated test for the R23 directory-bundle merge semantics** (Risk
  §3): construct a temp `config/waypoints/` directory with 2 scope files on
  "this device," build an envelope containing only 1 of them (simulating a
  Cloud pull where the other scope was never touched on the other device),
  call `importState(...)`, and assert the untouched scope file's on-disk
  content is byte-identical to before the import (proves no accidental
  deletion) while the touched one reflects the imported content. Lives
  alongside `WaypointsJsonCloudSyncAdapter` — since that class is a private
  nested class inside a platform composition-root file
  (`SteamCloudSyncClientInitializer.java`, matching
  `TweaksJsonCloudSyncAdapter`'s own existing placement, which per this
  repo's precedent is **not** independently unit-tested today, e.g. no
  `TweaksJsonCloudSyncAdapterTest` exists) — implementation should extract
  just the export/import merge logic (not the whole adapter) into a small,
  separately-testable pure function if this repo wants a real unit test for
  it, mirroring `CrossWorldStatsOfflineBucketFilter`'s own precedent (a
  directly-unit-tested pure function pulled out of a similarly-shaped
  adapter for exactly this reason, per that adapter's own Javadoc: "The
  offline-bucket filter/merge logic itself is a separate, directly-unit-
  tested pure function"). Decide the exact class name/location at
  implementation time; the requirement is "this merge logic must have its
  own real test," not a specific file layout.

No test coverage is planned for the compass-bar mixin, `PausePanel`, or
`WaypointManagerPanel` themselves (render/mixin/`Screen`-layer code, per this
repo's established convention) — verified by an in-game live check instead
(see Acceptance criteria).

## 8. Acceptance criteria

1. `./gradlew :features:waypoints:test` passes, covering every item in
   Test strategy above.
2. `./gradlew :platform:fabric-26.2:build` compiles clean (mixin
   annotation-processing succeeds, meaning the `javap`-confirmed hotbar
   injection point from Risk #1 was real and Sponge Mixin's
   `requireAnnotations`/`defaultRequire: 1` checks pass at build/launch
   time, not just at compile time).
3. Live in-game check on `platform/fabric-26.2` (singleplayer world): 
   - Opening Pause shows a new "Waypoints" button below "Return to Game";
     clicking it swaps to the Waypoint Manager panel; a "Back" button returns
     to normal Pause content (R19).
   - Adding a waypoint via "Add at current position" (name-only prompt) and
     via manual x/y/z entry both work, appear in the scrollable list with
     name/color swatch/coordinates (R21/R22), and immediately (no reopen)
     appear as a dot on the compass bar above the hotbar when in the same
     dimension and within the bearing window (R18).
   - Rename, edit-position (including moving a waypoint to a different
     dimension), and delete (via the arm-then-confirm control) all work and
     persist across a world-relog (R7/R9).
   - The compass bar does not visually overlap the hotbar or the vanilla
     health/hunger/XP row (R10) — if it does, the §4 pixel constant needs a
     one-line adjustment, not a design change.
   - A waypoint's dot visibly shrinks as the player walks away from it and
     grows on approach, floored/capped per §4's min/max (R12); an elevation
     chevron appears when standing ≥10 blocks above/below a waypoint (R13);
     only the nearest near-center waypoint shows its name label (R14); a
     waypoint outside the ±90° window or behind the player is simply absent
     from the bar, with no pinned-edge indicator (R16).
   - Creating ≥2 waypoints with different UUIDs produces visibly distinct
     colors (R5), consistently across a client restart (determinism).
4. Live Steam Cloud check (requires two local device profiles or two save
   locations simulating "device A"/"device B," per this repo's existing
   Steam Cloud testing convention for other `CloudSyncable`s): waypoints
   created on one device appear after a Cloud pull on a second device for
   the same scope key; a scope untouched on device B is not deleted by a
   sync cycle that only touched a different scope on device A (Risk #3).
5. `.claude/context/minecraft.md` has a new row documenting the real,
   `javap`-confirmed hotbar-render class/method for 26.2 (Risk #1),
   regardless of whether the working hypothesis in §4 turned out correct.

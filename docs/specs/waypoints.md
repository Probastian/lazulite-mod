# Spec: Waypoints

Status: specification only (no plan, no implementation code in this document).
Owner feature: new module `features/waypoints`, consumed by
`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`.
Integrates with (but does not depend on, per `architecture.md`'s
Feature -> Feature ban) the existing `features/main-menu` pause-screen
framework and `features/steam-cloud-sync`'s Cloud sync machinery, purely
through `api`-layer hooks/contracts -- a new `api`-layer hook for the former,
the existing `api/cloudsync/CloudSyncable` contract for the latter (see
Architecture/Compatibility for how each wiring avoids a Feature -> Feature
dependency).

## Overview

Players can create named, colored waypoints marking locations in the world
they're currently in. Waypoints are scoped per server/world and, within
that, per dimension. A new HUD "compass bar," rendered directly above the
hotbar, shows every waypoint in the player's current dimension as a small
directional dot whose size shrinks with distance -- the same visual language
as vanilla's Locator Bar (Minecraft ~1.21.6+, "Bundles of Bravery"), adapted
here to render waypoints instead of other players. The waypoint(s) nearest
the center of the bar (i.e. roughly in front of the player) additionally
show their name above the bar. A Waypoint Manager panel, reachable via a new
button on the existing Pause panel (not a new sidebar tab), provides CRUD
over the current scope's waypoints.

### Vanilla Locator Bar reference model (research findings)

Confirmed via Minecraft Wiki and community documentation (this repo's
`minecraft.md` documentation-priority list ranks the Minecraft Wiki below
Fabric/Mojang sources, but no Fabric/Mojang API doc describes a player-
authored analog of this vanilla HUD feature, since it isn't a modding API --
it's a vanilla rendering feature planning must still `javap` the real class
shape of per platform version before implementing, per this repo's
established convention):

- The Locator Bar occupies the screen region normally used by vanilla's XP
  bar (hidden there during XP-gain flashes), and shows other players as
  small colored markers positioned left-to-right along a horizontal strip
  based on their bearing relative to the direction the local player is
  facing -- not a top-down map, a directional strip.
- Marker size/shape scales down as distance to the tracked player increases,
  giving a rough "how far away" cue without numeric distance being shown.
- Small up/down arrows on a marker indicate the tracked player is
  significantly above/below the local player's elevation.
- This feature's compass bar reuses that same visual language (bearing ->
  horizontal position, distance -> dot size) but is:
  - Positioned directly above the hotbar, not in place of the XP bar (the
    user's explicit placement requirement) -- so it does not need vanilla's
    XP-bar-conflict hide/show logic at all, sidestepping that whole
    complication.
  - Driven by this feature's own waypoint list, not vanilla's player-position
    packet stream.
- Exact vanilla class names/render call sites for the Locator Bar on each of
  this repo's three supported versions are **not** confirmed in this spec
  (out of scope for a from-scratch, non-Locator-Bar-reusing HUD element --
  see Non-goals). Planning does not need to hook the vanilla Locator Bar at
  all; it only needs the injection point for "draw something above the
  hotbar," which is the same general HUD render pass `HudCustomCrosshairMixin`
  (T5 Custom Crosshair, `platform/fabric-26.2/.../mixin/HudCustomCrosshairMixin.java`)
  already demonstrates hooking, adapted to the hotbar's own render method
  instead of the crosshair's.

## Goals

1. A `Waypoint` data model (name, x/y/z, dimension id, color, scope key,
   creation metadata) persisted per server/world, nested per dimension.
2. A compass bar HUD element, rendered above the hotbar on all three
   supported Minecraft versions, showing every current-dimension waypoint as
   a distance-scaled, colored dot positioned by bearing, with the
   near-center waypoint's name shown above the bar.
3. A deterministic default color-assignment scheme so every waypoint has a
   visually distinct, stable color with no user interaction required.
4. A Waypoint Manager panel reachable from a new button on the existing
   Pause panel (`platform/fabric-*/.../mainmenu/PausePanel.java`), following
   the same "in-panel sub-view swap" pattern `TweaksPanel` already
   established for its per-tweak config screen (a nullable "which sub-view
   is active" field + a Back button) -- explicitly **not** a new
   `MainMenuTab` enum member.
5. Waypoint CRUD (add/edit/rename/recolor/delete) exposed through that panel.
6. Steam Cloud sync of the per-scope waypoint files, via a
   `WaypointsJsonCloudSyncAdapter`-equivalent implementing the existing
   `CloudSyncable` contract (`api/src/main/java/de/lazuli/api/cloudsync/
   CloudSyncable.java`) and wired into each platform's own
   `SteamCloudSyncClientInitializer` alongside `TweaksJsonCloudSyncAdapter`/
   `CrossWorldStatsCloudSyncAdapter`, so a player's waypoints follow them
   across devices the same way this codebase's other per-world/per-feature
   files already do (R23).

## Non-goals

- Not a full minimap or world map -- no top-down map rendering, no
  fog-of-war/exploration tracking, no terrain rendering of any kind. The
  compass bar is a 1-D directional strip only, per the user's explicit
  request.
- Not implementing anything -- no code changes are made or planned by this
  document; HOW to implement (exact mixin injection points, exact widget
  classes, exact `javap`-confirmed vanilla method names per version) is
  deferred to the planning phase, per this repo's established convention for
  HUD/render work (`.claude/context/minecraft.md`'s "Known Cross-Version API
  Differences" table is a living record other features append to, not
  something a specification pass populates from memory).
- Not hooking, reusing, or modifying vanilla's own Locator Bar rendering
  pipeline. This feature reuses only its **visual language** (bearing -> x
  position, distance -> dot size), as a from-scratch render pass over the
  hotbar, independent of whatever internal vanilla class implements the
  Locator Bar. No dependency on the Locator Bar being enabled/present/even
  existing on a given server (some servers disable it via
  `send-player-locator-updates` or version-gate it out).
- Not sharing waypoints between players (no server-side/multiplayer
  broadcast of one player's waypoints to another) -- every waypoint set is
  local to this client's own config directory, matching every other
  per-world config this codebase already ships (`WorldSyncPreferenceService`,
  `AccountStats`). This is separate from -- and unaffected by -- the Cloud
  sync added in Goal 6/R23, which follows one player's own waypoints across
  that same player's own devices, not between different players.
- Not a "death point" / auto-waypoint-on-death feature -- out of scope
  unless requested later (Future Extensions).
- Not user-customizable waypoint colors in v1 (deterministic auto-assignment
  only) -- confirmed for v1; see Future Extensions for the deferred manual
  recolor follow-up.
- Not adding a distance/coordinate readout, teleport command, or
  "waypoint reached" notification -- CRUD and passive HUD display only, per
  the user's request (nothing about navigation assistance beyond the visual
  compass bar was asked for).
- Not a keybinding to quick-add a waypoint at the player's current position
  from in-world play (e.g. a hotkey) -- v1 adds waypoints only through the
  Waypoint Manager panel (opened via Pause), consistent with every other
  per-world CRUD surface in this codebase (`BookmarkedServer`,
  `WorldSyncPreference`) being pause/menu-driven, not hotkey-driven. Flagged
  as a plausible Future Extension, not invented into v1 scope.

## Requirements

### Data model

- **R1. `Waypoint` record.** Fields: `id` (stable UUID string, survives
  rename/recolor), `name` (player-facing, freeform text), `x`/`y`/`z`
  (integer block coordinates -- confirmed; matches vanilla's own
  block-coordinate display/command granularity, and is sufficient for the
  compass bar's bearing/distance math, which does not benefit meaningfully
  from sub-block precision at HUD-strip render scale), `dimensionId` (string,
  e.g. `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`, or
  a custom dimension's own identifier -- stored as the raw dimension
  identifier string, not an enum, so custom/modded dimensions work without a
  code change), `color` (packed ARGB int, see R5), `createdAtMillis` (epoch
  millis, for stable sort order/auditability, mirroring
  `BookmarkedServer.addedAt()`'s convention).
- **R2. Scope key.** Every persisted waypoint set is namespaced under a
  scope key identifying "which server/world" per Requirement R3, then
  nested per-dimension under that scope per Requirement R4.
- **R3. Scope key derivation.** Following this codebase's existing
  `LastPlayedPointer.Type`-style singleplayer/multiplayer split
  (`features/steam-cloud-sync/.../api/LastPlayedPointer.java:14`,
  `WorldSyncPreferenceService`'s `worldSlug`-keyed map):
  - Singleplayer: scope key = the world's save-folder name (matches
    `WorldSyncPreferenceService`/`AccountStats.worldBaselines`'s existing
    per-world keying convention exactly -- no new identity scheme invented).
  - Multiplayer: scope key = the server address (`host:port`), matching
    `BookmarkedServer.address()`/`LastPlayedPointer`'s existing
    per-server keying convention. Note (carried into Open Questions): an
    address-keyed scope conflates "same server, different session" (stable,
    desired) with "server changed IP/port" (waypoints silently orphaned) --
    this is the same tradeoff every other per-server feature in this
    codebase (bookmarks, last-played) already accepts, so this spec follows
    suit rather than inventing a new, stronger server-identity scheme.
  - Realms: not separately handled -- a Realm is joined through the same
    multiplayer connection path, so it falls under the multiplayer case
    above (Realms present an address the same way regular servers do,
    confirm exact value's shape during planning).
- **R4. Per-dimension nesting.** Within one scope key, waypoints are stored
  in a `Map<String dimensionId, List<Waypoint>>`. The compass bar (R7-R11)
  reads/renders only the current dimension's list.
- **R5. Deterministic color assignment.** A new waypoint receives a color
  computed **deterministically from its UUID** (e.g. hash the UUID to a hue
  value on a fixed-saturation/fixed-lightness HSL wheel, then convert to
  RGB) rather than from creation order or a small fixed palette -- this
  keeps colors stable even if waypoints are added/deleted out of order and
  avoids two waypoints ever colliding on the exact same color from a small
  palette running out of distinct entries once more than ~8-12 waypoints
  exist. No user color override in v1 (see Non-goals); confirmed for v1 --
  manual recolor remains a Future Extensions candidate, not built here.

### Persistence

- **R6. File location and shape.** One JSON file per scope key, under this
  mod's existing config directory (`FabricLoader.getInstance().getConfigDir()`,
  confirmed pattern from `TweaksClientInitializer.java:35`,
  `SteamCloudSyncClientInitializer.java:53-55`), inside a feature-owned
  subdirectory: `config/waypoints/<scopeKeySlug>.json` (mirroring
  `SteamCloudSyncClientInitializer`'s `featureConfigDir =
  configDir.resolve("steam-cloud-sync")` per-feature-subdirectory
  convention rather than flat top-level files, since this feature can have
  many scope-keyed files, unlike the single flat `tweaks.json`/
  `cross-world-stats.json` files other features use). `scopeKeySlug` is the
  scope key (R3) sanitized to a filesystem-safe filename (existing
  `WorldSyncPreferenceService`/steam-cloud-sync code already sanitizes
  world-slug-derived filenames elsewhere in this codebase for the same
  reason -- an address like `play.example.com:25565` needs a safe
  filename; planning reuses/mirrors that existing sanitization rather than
  inventing a new one). This one-file-per-scope-key layout is also what
  makes R23's Cloud sync adapter possible without a `WaypointRegistry`/
  `WaypointsConfigIO` change (see Public API, Architecture) -- the file set
  is directly enumerable from the filesystem. File content shape
  (illustrative, finalized during planning alongside this codebase's
  existing hand-rolled-JSON conventions, e.g. `WorldCloudMetadataIO`/
  `CloudSyncJson`):

  ```json
  {
    "schemaVersion": 1,
    "scopeKey": "my_world_folder",
    "dimensions": {
      "minecraft:overworld": [
        {
          "id": "6a1e...-uuid",
          "name": "Base",
          "x": 120, "y": 68, "z": -45,
          "color": -13312256,
          "createdAtMillis": 1700000000000
        }
      ],
      "minecraft:the_nether": []
    }
  }
  ```

- **R7. Load/save contract.** Load-or-create-with-empty-defaults on first
  access to a given scope, fail-closed to an empty waypoint set with a
  human-readable warning on malformed content (matching `WardrobeConfigIO`/
  `TweaksConfigIO`'s established contract, per `tweaks.md`'s Persistence
  section), write-through on every mutation (add/edit/delete/rename/
  recolor) -- no explicit "Save" action in the Manager panel UI.
- **R8. Schema versioning.** `schemaVersion` field present from v1 onward
  (matching `WorldCloudMetadataIO.CURRENT_SCHEMA_VERSION`'s convention) so a
  future migration has a version to branch on; no migration logic needed yet
  since this is a net-new file format with no prior version to migrate from.
- **R9. When is the current scope's file loaded/unloaded?** Loaded on
  world-join / server-connect (the same lifecycle checkpoint
  `LastPlayedPointerService.recordWorldEntered`/`recordServerJoined` already
  hooks), unloaded (or simply left cached -- planning decision, not
  behaviorally significant for a single-scope-at-a-time client) on
  world-leave / disconnect. Only one scope's waypoint set needs to be
  resident in memory at a time, since the compass bar only ever renders the
  currently-joined scope+dimension's waypoints.

### Compass bar HUD

- **R10. Placement.** Rendered as a horizontal strip directly above the
  hotbar, full compass-bar-width centered on the hotbar's own horizontal
  center (matching the hotbar's existing screen-space centering), a fixed
  vertical offset above it (exact pixel offset is a planning/visual-design
  detail, not specified here beyond "must not overlap the hotbar or the
  XP/health/hunger bars above it").
- **R11. Bearing-to-screen-position math.** For each waypoint in the current
  dimension: compute the horizontal angle between the player's current look
  direction (yaw) and the bearing from the player's position to the
  waypoint's position (standard `atan2(dx, dz)`-style bearing calculation,
  normalized to the player's yaw so "straight ahead" = bar center). Map that
  angle to an x position along the bar using a fixed angular field-of-view
  window (e.g. the compass bar visually represents roughly the player's own
  horizontal FOV, or a fixed wider window like ±90 degrees -- exact window
  width is a planning/visual-design decision, not specified here since it's
  the kind of numeric-tuning detail this repo's other tweak specs
  (`tweaks.md`'s config field ranges) also leave to planning/implementation
  rather than fixing in the spec). A waypoint whose bearing falls outside
  the bar's represented angular window is simply not drawn -- confirmed: no
  edge-clamping, no pinned-to-edge directional indicator (see R16, which
  states the same rule for the fully-behind-player case so it is applied
  uniformly rather than as two independent decisions).
- **R12. Distance-based dot scaling.** A waypoint's dot size shrinks as
  distance from the player increases, mirroring vanilla Locator Bar's
  distance-scaling visual language (see Overview's reference-model
  research). A minimum dot size floor must exist so distant waypoints never
  shrink to invisible/zero-pixel; a maximum size caps very-close waypoints
  from growing unboundedly. Exact scale curve (linear vs. logarithmic
  falloff, exact min/max pixel sizes, exact distance thresholds) is a
  planning/visual-design decision, not fixed by this spec.
- **R13. Vertical elevation indicator.** Mirroring the Locator Bar's
  above/below arrow convention (Overview), a waypoint significantly above
  or below the player's own Y level shows a small directional indicator
  (e.g. an up/down chevron on or near its dot) -- exact "significantly"
  threshold is a planning decision (a fixed Y-delta, e.g. matching whatever
  threshold the Locator Bar itself uses if that's confirmed practical to
  discover, or a reasonable independently-chosen value otherwise).
- **R14. Name label near bar center.** Only the waypoint(s) whose dot
  currently falls within a defined "near center" zone of the bar show their
  name, rendered as text above the bar (not for every dot simultaneously,
  per the user's explicit request). "Near center" is defined as a threshold
  window around the bar's exact horizontal center -- either an angular
  threshold (e.g. within ±N degrees of the player's look direction) or an
  equivalent pixel-distance-from-bar-center threshold (the two are
  equivalent given R11's angle-to-pixel mapping) -- exact threshold value is
  a planning/visual-design decision. If multiple waypoints fall within the
  threshold simultaneously (e.g. two waypoints roughly overlapping in
  bearing but at different distances), planning decides whether to stack
  their name labels vertically, show only the nearest, or another
  reasonable resolution -- not specified further here since it's a rare
  edge case with several equally-reasonable resolutions.
- **R15. Color rendering.** Each waypoint's dot is drawn in its assigned
  color (R5); its name label (R14), when shown, is also drawn in that same
  color (or that color used as an accent/outline against a neutral text
  fill, if pure-colored text proves hard to read against the HUD background
  -- a planning/visual-design call, not fixed here).
- **R16. Off-bearing/behind-player handling (confirmed).** Any waypoint
  whose bearing falls outside the compass bar's represented angular window
  -- including one directly behind the player -- is simply not drawn on the
  compass bar; there is no edge-clamping and no pinned-to-edge directional
  indicator (the same rule R11 states for the general case, applied
  uniformly rather than as two independent decisions). This is a deliberate
  v1 simplification, not an oversight: vanilla's own Locator Bar (per the
  Overview's research) typically keeps a marker always visible, pinned to
  whichever edge is nearest, even for behind-player targets -- this feature
  intentionally diverges from that vanilla "always shown, pinned to an edge"
  behavior for v1.
- **R17. Performance -- many waypoints.** The compass bar's per-frame work
  is O(number of waypoints in the current dimension only) -- never iterates
  waypoints from other dimensions/scopes. No expected waypoint count ceiling
  is specified or added by this spec (confirmed: no max-count cap or
  pagination in v1 -- the Manager panel remains a plain scrollable list per
  R22); the render loop must stay allocation-light on the hot per-frame
  path (matching `tweaks.md`'s Performance section precedent for HUD-adjacent
  render hooks -- an early-return guard when the current dimension has zero
  waypoints, and no per-frame heap allocation in the steady-state per-dot
  draw loop beyond what vanilla's own `GuiGraphicsExtractor`/`DrawContext`
  draw calls already allocate).
- **R18. Live update.** The compass bar reflects waypoint add/edit/delete
  immediately (no reopen/reload needed) -- it reads the current scope+
  dimension's live in-memory waypoint list every frame, the same "poll
  current state each render pass" pattern `tweaks.md`'s Events section
  establishes for `TweakEngine` (no push/event-bus needed).

### Waypoint Manager panel

- **R19. Entry point.** A new button on `PausePanel` (currently rendering
  only "Return to Game," `platform/fabric-*/.../mainmenu/PausePanel.java`),
  positioned below/alongside the existing "Return to Game" button. Clicking
  it swaps `PausePanel`'s own rendered content to the Waypoint Manager's
  list/CRUD UI, following exactly the sub-view-swap pattern `TweaksPanel`
  already established (a nullable "which sub-view" field --
  `TweaksPanel.configuring`'s equivalent -- gates `render`/`mouseClicked`
  dispatch inside the same panel class or a small panel-owned sub-panel
  object) plus a Back button returning to the normal Pause content ("Return
  to Game" + "Waypoints" buttons). **Not** a new `MainMenuTab` enum member,
  per the user's explicit requirement -- confirmed consistent with
  `main-menu-pause-integration.md`'s own established pattern of the Pause
  tab hosting exactly one action today, now growing to host a second
  entry-point button without becoming a multi-tab surface itself.
- **R20. Manager panel is world/server-scoped, dimension-aware.** The
  Manager panel operates on the currently-joined scope's waypoint set
  (R2/R3) and, by default, shows the current dimension's waypoints (matching
  what the compass bar itself is currently displaying) -- with a way to
  switch which dimension's list is shown/edited (e.g. a small
  dimension-selector control), since a player may want to manage a
  different dimension's waypoints than the one currently occupied (e.g.
  planning an End waypoint while standing in the Overworld). Exact selector
  UI (dropdown, tab strip, etc.) is a planning/visual-design decision.
- **R21. CRUD operations (v1 scope).**
  - **Add.** Two entry paths: (a) "Add at current position" -- captures the
    player's current x/y/z (and current dimension, unless the selector
    (R20) has been changed to a different one, in which case planning
    decides whether "current position" is even offered for a non-current
    dimension, or only manual entry is available there), prompting only for
    a name; (b) manual x/y/z entry (plus dimension, if not implied by the
    active selector) for a location the player isn't currently standing at.
    Both paths auto-assign color per R5 -- no color entry field in v1 (see
    Non-goals).
  - **Rename.** Edit an existing waypoint's `name`.
  - **Edit position.** Edit an existing waypoint's x/y/z (and, if useful,
    re-assign its dimension -- planning decision on whether moving a
    waypoint between dimensions is a meaningful v1 operation or an
    unnecessary complication versus "delete + re-add in the target
    dimension").
  - **Delete.** Remove a waypoint (with a confirmation step, matching this
    codebase's general UX caution around destructive actions -- exact
    confirmation mechanism, e.g. a second click / confirm modal, is a
    planning/visual-design decision).
  - **Recolor:** explicitly **out of scope for v1** per Non-goals (colors
    are deterministic/auto-assigned only) -- confirmed out of v1 scope; the
    CRUD surface described above would only need one more field to support
    it later (Future Extensions).
  - **Teleport-suggest:** explicitly **out of scope** (see Non-goals) -- no
    `/tp` command generation, no "copy coordinates" clipboard action, no
    click-to-teleport (would require server-side permission the mod cannot
    assume it has on arbitrary servers). Flagged as a Future Extension only
    for singleplayer/op'd contexts where such a thing could make sense.
- **R22. List rendering.** A scrollable list, one row per waypoint in the
  active dimension: name, color swatch, coordinates, an edit/delete
  affordance -- following this codebase's existing row-list visual idiom
  (`AchievementsPanel`'s row shape, explicitly cited as the precedent
  `TweaksPanel`'s own redesign already followed).

### Cloud sync

- **R23. Steam Cloud sync of the per-scope waypoint files (confirmed in
  scope for v1).** A `WaypointsJsonCloudSyncAdapter`-equivalent, implemented
  per platform, exports/imports the entire `config/waypoints/` directory
  (R6) as one bundled `CloudSyncable` unit
  (`api/src/main/java/de/lazuli/api/cloudsync/CloudSyncable.java`) and is
  added to that platform's existing `List<CloudSyncable> cloudSyncables`
  wiring inside its own `SteamCloudSyncClientInitializer`, alongside
  `TweaksJsonCloudSyncAdapter`/`CrossWorldStatsCloudSyncAdapter`/etc.
  (`platform/fabric-26.2/.../SteamCloudSyncClientInitializer.java:65-69`).
  See Architecture for the full shape of the adapter (why it bundles the
  whole directory rather than wrapping one fixed file the way
  `TweaksJsonCloudSyncAdapter` wraps `tweaks.json`, and the resulting
  whole-bundle sync granularity). This reuses `features/steam-cloud-sync`'s
  existing reconciliation, tick-pump, and shutdown-sync machinery unchanged
  (`CloudSyncCoordinator.reconcileAtStartup()`/
  `cloudSyncWorker().pumpTickWork()`/`syncOnShutdown()`) -- no new sync
  trigger, lifecycle hook, or change to `features/steam-cloud-sync` itself
  is introduced by this feature.

## Public API

New types under `api/src/main/java/de/lazuli/api/waypoints/` (mirrors the
existing `api/.../tweaks/` package shape, no Minecraft imports per
`architecture.md`'s API-layer dependency rule):

- `record Waypoint(String id, String name, int x, int y, int z, String
  dimensionId, int color, long createdAtMillis)` -- the shared, Minecraft-
  import-free data shape (matches R1's now-settled integer block-coordinate
  fields).
- `interface WaypointScopeResolver { String currentScopeKey(); String
  currentDimensionId(); }` -- the seam a platform Version Adapter implements
  to tell `features/waypoints` "which scope/dimension is currently active,"
  mirroring how `WorldSyncToggleHook`/`MainMenuHook` already bridge a
  platform-supplied fact into feature code without a Minecraft import
  leaking into the feature layer. Backed on each platform by whatever
  world-join/server-connect lifecycle hook `LastPlayedPointerService`'s own
  equivalents already use.
- `interface WaypointCompassHook` -- the seam a platform Version Adapter's
  HUD mixin calls every frame to obtain the current dimension's render-ready
  waypoint list plus the player's current position/yaw (needed for R11's
  bearing math) -- shape TBD at planning time, likely something like
  `List<Waypoint> waypointsForCurrentDimension()` plus the platform mixin
  supplying the player's own position/yaw directly from vanilla state
  (no need to round-trip player position through this feature's API, since
  the platform mixin already has direct access to it -- only the waypoint
  list itself needs to cross the Feature -> Platform boundary).

Services surface (consumed by the platform's Waypoint Manager panel code
and the platform's compass-bar HUD mixin, `features/waypoints/.../services/
WaypointRegistry.java` or similarly named, exact class name a planning
decision):

- `list(dimensionId): List<Waypoint>`
- `add(name, x, y, z, dimensionId): Waypoint` (auto-assigns id/color/
  createdAtMillis per R5/R1)
- `rename(id, newName)`
- `editPosition(id, x, y, z, dimensionId)`
- `delete(id)`
- All mutation methods persist immediately (R7), matching
  `WorldSyncPreferenceService`'s `persist()`-after-every-mutation
  convention.
- No additional Services-surface method is needed to support Cloud sync
  (R23), and `WaypointRegistry` gains no new public method for it. R6's
  one-file-per-scope-key layout under `config/waypoints/` is already
  directly enumerable from the filesystem, so the per-platform
  `WaypointsJsonCloudSyncAdapter`-equivalent reads/writes that directory
  directly via raw file I/O (`java.nio.file.Files.list`/`readString`/
  `writeString`), the same way `TweaksJsonCloudSyncAdapter` already
  reads/writes `tweaks.json` directly rather than going through
  `TweakRegistry` (`platform/fabric-26.2/.../SteamCloudSyncClientInitializer.java:218-263`).

## Architecture

- **New feature module** `features/waypoints` (add to `settings.gradle`
  alongside the existing `features:*` entries, e.g. after
  `features:tweaks`), following the standard `feature-guidelines.md` layout:
  `api/` (re-exports/uses the `api/waypoints` types above, or those types
  live directly in the root `api` module matching `api/tweaks`'s existing
  placement -- planning confirms which, following whichever precedent
  `api/tweaks` vs. a feature-local `api/` subpackage actually establishes
  once re-checked), `services/WaypointRegistry.java` (in-memory CRUD +
  write-through persistence, mirroring `WorldSyncPreferenceService`'s
  shape), `config/WaypointsConfigIO.java` (hand-rolled JSON parse/serialize,
  mirroring `WorldCloudMetadataIO`'s shape and reusing this codebase's
  existing hand-rolled JSON value model -- `CloudSyncJson` lives in
  `features/steam-cloud-sync`, and per `architecture.md`'s Feature ->
  Feature ban this new feature cannot depend on it directly; if `tweaks.md`'s
  own `MainMenuJson`-relocation-to-`common` precedent has landed by the time
  this feature is planned, `features/waypoints` reuses that shared
  `common`-module JSON parser the same way `features/tweaks` does -- if not
  yet landed, planning either waits on that relocation or hand-rolls its own
  minimal JSON parser locally, matching this feature's own scope, per the
  Services layer's "graduate on second use" discipline already established
  for other shared capabilities), `gui/` and `mixins/` left as empty
  placeholders per `feature-guidelines.md` (all actual HUD-render and
  Pause-panel-button code lives in `platform/fabric-*`, never in the
  feature module, per the Dependency Rules table), `tests/` (unit tests for
  `WaypointRegistry`'s CRUD logic and `WaypointsConfigIO`'s parse/serialize
  round-trip, mirroring `TweaksConfigIOTest`/`TweakRegistryTest`'s existing
  shape -- pure JVM-testable, no Minecraft classes involved, per
  `ui-guidelines.md`'s Testing section keeping business/decision logic out
  of the untestable `Screen`/mixin layer).
- **Per-platform Version Adapters** (`platform/fabric-1.21.11`,
  `platform/fabric-26.1`, `platform/fabric-26.2`), each providing:
  - A `WaypointScopeResolver` implementation wired at world-join/server-
    connect, alongside (not necessarily merged into) the existing
    `LastPlayedPointerService` wiring in each platform's
    `SteamCloudSyncClientInitializer`-equivalent composition root -- these
    are two independent features reacting to the same lifecycle event, not
    a shared mechanism (no cross-feature dependency introduced; each
    registers its own listener on whatever vanilla connect/join event
    already exists).
  - A HUD mixin rendering the compass bar above the hotbar -- targets each
    platform's own hotbar-render method (exact class/method name **not**
    confirmed in this spec; planning's first step is the same
    `javap`-against-each-platform's-own-resolved-jar pass this repo already
    establishes as convention, following `HudCustomCrosshairMixin`'s own
    precedent of targeting `Hud`/`InGameHud`'s render methods). Given this
    repo's own `minecraft.md` table already documents the crosshair/overlay
    HUD render surface as one of the rows where **26.1 and 26.2 are NOT
    identical** (`Gui.extractCrosshair` vs. the 26.2-only `Hud` split),
    planning should expect the hotbar-render injection point may diverge
    the same way across all three versions independently, not assume any
    two of the three match without confirming.
  - A new button + sub-view wiring inside `PausePanel.java` (R19), plus a
    new `WaypointManagerPanel.java` (or similarly named) class implementing
    the CRUD list UI (R20-R22), added per-platform following
    `TweaksPanel`'s established shape (constructor takes the feature's
    `WaypointRegistry`/bundle equivalent; `render`/`mouseClicked`/
    `mouseScrolled` methods matching `PausePanel`'s existing method
    signatures so `MainMenuScreen`'s existing `PAUSE ->
    pausePanel.render(...)` dispatch line needs no changes beyond what
    `PausePanel` internally delegates to).
  - A `WaypointsJsonCloudSyncAdapter`-equivalent (R23) implementing
    `api/cloudsync/CloudSyncable`, added as a private static nested class
    inside each platform's own `SteamCloudSyncClientInitializer` (mirroring
    `TweaksJsonCloudSyncAdapter`/`CrossWorldStatsCloudSyncAdapter`,
    `platform/fabric-26.2/.../SteamCloudSyncClientInitializer.java:218,271`)
    and appended to that composition root's existing
    `List<CloudSyncable> cloudSyncables` construction
    (`platform/fabric-26.2/.../SteamCloudSyncClientInitializer.java:65-69`).
    `CloudSyncable`/`CloudSyncCoordinator` expect one fixed,
    mod-init-time-known file per adapter (`cloudSyncId()` is a single stable
    string, and `CloudSyncCoordinator`'s own `cloudSyncables` list is built
    once at mod init and never mutated afterward,
    `features/steam-cloud-sync/.../services/CloudSyncCoordinator.java:63,106`),
    while R6's waypoint files are one-per-scope-key and only exist locally
    once a given scope has actually been visited on this device. To fit the
    existing contract without changing `features/steam-cloud-sync` itself,
    this adapter treats the whole `config/waypoints/` directory as its
    single sync unit rather than wrapping one fixed path: `exportState()`/
    `importState()` (de)serialize a small bundled envelope enumerating every
    scope-keyed file currently present under `config/waypoints/`
    (`scopeKeySlug -> that file's raw JSON bytes`), and
    `localLastModifiedMillis()` reports the newest last-modified time across
    all of them. This is a genuinely anticipated shape, not a novel
    workaround -- `CloudFileStore`'s own interface doc already lists
    "notes/waypoints" as a Group 4/5-style small-file consumer of this same
    seam (`features/steam-cloud-sync/.../services/CloudFileStore.java:9`).
    The tradeoff is whole-bundle, not per-scope, last-write-wins
    granularity (the same FR0.4 convention `CloudSyncable.java`'s own docs
    already establish, applied here at a coarser grain): two devices
    editing *different* scopes' waypoints between syncs still merge fine
    (each device's own local files for scopes it hasn't touched are simply
    carried through unchanged in export/import), and the only lossy case is
    the same scope being edited on two devices between reconciliation
    checkpoints -- which every other `CloudSyncable` in this codebase
    already resolves the same last-write-wins way. No change to
    `features/steam-cloud-sync`'s own `CloudFileStore`/`CloudSyncCoordinator`
    classes is required to support this.
- **No `services/`-layer extraction yet.** Per `architecture.md`'s
  graduate-on-second-use rule, this feature's config I/O and scope-key
  sanitization stay local to `features/waypoints` unless/until a second
  feature needs the identical capability -- no speculative shared service
  built here.

## UI

- **Compass bar.** A horizontal strip above the hotbar; each waypoint is a
  small colored dot (R12/R15) positioned by bearing (R11), with an optional
  up/down chevron (R13) and, only for near-center waypoint(s), a name label
  drawn above the bar (R14/R15) in that waypoint's color.
- **Pause panel addition.** `PausePanel` gains a second button, "Waypoints,"
  below "Return to Game," following the same fill/hover/centered-text button
  idiom already used for "Return to Game"
  (`platform/fabric-26.2/.../mainmenu/PausePanel.java:36-43`).
- **Waypoint Manager panel.** Opened by that button (R19); shows a
  dimension selector (R20) plus a scrollable waypoint list (R22) with
  add/rename/edit-position/delete controls (R21), and a Back control
  returning to the normal Pause panel content. Add flow offers both
  "Add at current position" (single name prompt) and manual x/y/z entry.
  Exact widget choices (buttons, text fields, dropdown vs. tab strip for the
  dimension selector) follow this codebase's existing main-menu widget
  conventions (`ui-guidelines.md`; no third-party UI framework).

## Configuration

Per-scope JSON files under `config/waypoints/<scopeKeySlug>.json`, per R6.
No single global config file for this feature beyond that per-scope set (no
feature-wide on/off toggle is requested by the user, so none is added; if
one is wanted later -- e.g. "hide compass bar" -- see Future Extensions).
These per-scope files are also Steam Cloud-synced as a group via the R23
adapter (Architecture); no separate Cloud-sync-specific config file is added
by this feature -- the existing global `steam-cloud-sync.json`
enabled/Steam-availability gate already covers every `CloudSyncable`,
including this one, per `features/steam-cloud-sync`'s own Configuration.

## Events

No new cross-module event bus. Following `tweaks.md`'s established
precedent (its own Events section): `WaypointRegistry`'s mutation methods
write-through to disk immediately (R7), and both consumers -- the compass
bar's per-frame HUD mixin and the Waypoint Manager panel's per-frame render
pass -- read current in-memory state directly off the registry each frame/
render call (R18), rather than needing a push notification. R23's Cloud
sync adapter likewise needs no new event/notification path -- it is polled
by `features/steam-cloud-sync`'s own existing reconciliation checkpoints
(startup, shutdown, its periodic tick-pump), the same as every other
`CloudSyncable` already registered.

## Networking

No new client-server packets; every waypoint CRUD operation is 100% local
to this client's own config directory, with no server awareness beyond
reading the scope key (R3) from the existing local connection-lifecycle
facts this codebase already tracks (`LastPlayedPointerService`'s
equivalent). No multiplayer waypoint sharing (see Non-goals). The only
"networking" this feature now involves is Steam's own client-to-Valve-
backend Cloud transfer via the R23 Cloud sync adapter -- opaque to this mod
and not directly controllable by it (mirrors
`features/steam-cloud-sync/specification.md`'s own Networking section
precedent for every other `CloudSyncable`), gated by that feature's
existing enabled/Steam-availability checks; this feature does not introduce
any new networking surface of its own.

## Persistence

Covered fully under Persistence (Requirements) and Configuration above:
one JSON file per scope key, loaded on world-join/server-connect, written
through on every mutation, fail-closed-to-empty-with-warning on malformed
content. `schemaVersion` present from v1 for future migration support; no
migration logic needed yet (net-new format). Also Steam Cloud-synced as a
group (R23, Architecture) -- Cloud is a mirror of these local files, never
the sole copy, matching every other `CloudSyncable`-backed file in this
codebase (`features/steam-cloud-sync`'s own Persistence section,
`features/steam-cloud-sync/specification.md:190-191`).

## Compatibility

- All three platform modules need: (a) a `WaypointScopeResolver`
  implementation wired to each platform's own world-join/server-connect
  lifecycle hook, (b) a compass-bar HUD mixin targeting each platform's own
  hotbar-render call site (unconfirmed per-version, see Architecture --
  treat as a `javap`-first planning task, following this repo's established
  convention and explicitly flagged given the precedent that HUD/overlay
  render surfaces have already diverged between 26.1 and 26.2 once before),
  (c) a `PausePanel`/new `WaypointManagerPanel` update, applied identically
  across all three platform modules' own mapped copies of those classes
  (mirrors `PausePanel.java`'s existing per-platform duplication pattern),
  (d) a `WaypointsJsonCloudSyncAdapter`-equivalent (R23) added to each
  platform's own `SteamCloudSyncClientInitializer`.
- No dependency on any other feature's shipped code (`features/main-menu`'s
  `PausePanel`/`MainMenuScreen` classes live in `platform/*`, not in a
  `features/main-menu` module this feature would need to depend on --
  confirmed by this spec's own file-path citations above, all under
  `platform/fabric-*`). Cloud sync (R23) follows the same rule:
  `features/waypoints` never imports `features/steam-cloud-sync`; the
  `CloudSyncable` contract it bridges through lives in the
  Minecraft-import-free `api` module
  (`api/src/main/java/de/lazuli/api/cloudsync/CloudSyncable.java`), and the
  adapter implementing that contract is written and owned by each
  platform's own composition root (`SteamCloudSyncClientInitializer`), the
  same shape `features/tweaks` already relies on for its own Cloud sync via
  `TweaksJsonCloudSyncAdapter` without `features/tweaks` itself depending
  on `features/steam-cloud-sync` (see
  `docs/adr/0003-cloudsyncable-cross-feature-bridging-via-api-contracts.md`
  for the full reasoning this repo already established for that shape). The
  only cross-feature-adjacent coupling remaining is the shared `common`-
  module JSON parser, conditionally, per Architecture's note (same
  shared-module-not-feature-to-feature shape `tweaks.md` already established
  for the same reason).

## Performance

- Compass bar render cost is O(waypoints in current dimension only) per
  frame (R17); zero-waypoint dimensions must add no meaningful per-frame
  cost beyond an early-return guard.
- Waypoint Manager panel list rendering only needs to handle the same
  per-scope, per-dimension waypoint count the compass bar already bounds by
  R17 -- no additional performance concern beyond standard scrollable-list
  rendering already established by `TweaksPanel`/`AchievementsPanel`'s own
  row-list precedent.
- `WaypointRegistry`'s write-through-on-every-mutation persistence (R7) is
  bounded by how often a player edits waypoints (a low-frequency, user-
  initiated action, not a hot per-tick path) -- no batching/debouncing
  needed, matching `WorldSyncPreferenceService.persist()`'s same
  every-mutation-writes-immediately precedent.
- R23's Cloud sync adapter runs only at `features/steam-cloud-sync`'s own
  existing reconciliation checkpoints (startup, shutdown, periodic
  tick-pump), not on every waypoint mutation -- its cost scales with the
  number of scope-keyed files enumerated under `config/waypoints/` at that
  checkpoint, which in practice is small (one file per world/server the
  player has ever visited), not per-waypoint.

## Future Extensions

- User-customizable waypoint colors (recolor CRUD operation) -- explicitly
  deferred from v1 per Non-goals/R21, flagged as a likely-easy follow-up
  given the CRUD surface already supports every other field.
- Teleport-suggest / coordinate-copy / click-to-teleport, scoped to
  singleplayer or op'd-player contexts only (Non-goals/R21).
- A quick-add hotkey to drop a waypoint at the player's current position
  without opening the Pause menu (Non-goals).
- Death-point auto-waypoints (not requested; explicitly not invented into
  this spec's scope).
- A feature-wide "hide compass bar" toggle (e.g. surfaced via the existing
  Tweaks framework as a new tweak, or a dedicated Waypoints-panel setting)
  if players want to suppress the HUD element without deleting their
  waypoints -- not requested, not built here.
- A maximum-waypoint-count guard or pagination in the Manager panel list, if
  real-world usage after v1 ships shows a need (v1 ships with no cap,
  confirmed, R17/R22).
- A per-scope (rather than whole-bundle) Cloud sync granularity for R23, if
  the whole-bundle last-write-wins tradeoff (Architecture) proves too coarse
  in practice -- would likely require `features/steam-cloud-sync` itself to
  grow support for a `CloudSyncable` that owns a *set* of Cloud files rather
  than exactly one, which is a change to that feature, not this one, so it
  is deferred rather than designed here.

## Planning Prerequisites

All v1 scope/behavior decisions previously listed under this spec's Open
Questions have been resolved above (see Goals/Requirements/Non-goals/
Future Extensions for where each landed: Cloud sync is now in scope per
Goal 6/R23; manual recolor stays out of v1 per R5/R21/Non-goals; no
max-waypoint-count cap is added per R17; off-bearing waypoints are hidden,
not clamped, per R11/R16; coordinates are integer block coordinates per
R1). One genuine technical unknown remains, which is a planning-phase task
rather than a user decision:

- **Exact vanilla hotbar-render injection point per platform version.**
  Deliberately not resolved in this specification (would require a full
  `javap` pass across all three platform modules' resolved jars); planning's
  first step should be exactly that pass, per this repo's established
  convention, following `HudCustomCrosshairMixin`'s own precedent as the
  closest existing analog (a HUD element rendered near/around the hotbar
  region, already mixin'd on 26.2 at least).

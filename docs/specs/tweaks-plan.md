# Implementation Plan: Tweaks Framework + Tweak Catalog

Spec: `docs/specs/tweaks.md` (all section refs below are to that document).
This plan covers architecture, files, risks, and acceptance criteria only —
no implementation code.

## Existing Implementation

Findings recorded here so implementation/verification don't need to
re-derive them.

- **`api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java`** — flat
  enum, no javadoc per member beyond a couple of `Batch-N` inline comments.
  `TWEAKS` is a one-line addition (Public API/Architecture "Main-menu tab
  wiring").
- **`platform/fabric-26.2/.../MainMenuScreen.java`** (representative of all
  three platform modules' near-identical screen class): each `MainMenuTab`
  gets (a) a dedicated `*Panel` field constructed in the constructor, (b) one
  `case` arm each in `extractRenderState`'s `switch (active)` (panel
  `render(guiGraphics, font, x, y, w, h, mouseX, mouseY)`), (c) one `case` arm
  in `mouseClicked`'s tab dispatch (`panel.mouseClicked(x, y, w, h, event.x(),
  event.y())`), (d) a label in `tabLabel(tab)`, and (e) optionally a
  `mouseScrolled` forward if the panel needs scroll (only `SERVERS`/
  `STATISTICS` currently do — `StatisticsPanel` is the precedent for a
  scrollable list-of-rows panel, the same shape the Tweaks tab needs). No
  `init()`-time widget registration is required per-panel unless the panel
  owns real `AbstractWidget`s (most panels hand-draw via `GuiGraphics` calls
  and hit-test manually in `mouseClicked`, not via `addRenderableWidget`) —
  **except** the bind-capture control, which needs real key-event capture
  (see Risks #2), a genuine widget/mode-flag on `MainMenuScreen` most likely
  required there specifically (`keyPressed`/`mouseClicked` override already
  exist for `openMenu`'s Escape-close, same pattern to extend).
  `TABS = MainMenuTab.values()` auto-picks up the new enum constant with zero
  further changes to the tab-bar layout/hit-testing code.
- **Per-platform composition root**: `platform/<module>/src/main/java/de/lazuli/MainMenuClientInitializer.java`
  — one per platform module, near-identical shape: resolves
  `FabricLoader.getInstance().getConfigDir()`, loads each `*ConfigIO` via
  `configDir.resolve("<name>.json")`, logs `ParseResult.warning()` via
  `LazuliMod.LOGGER.warn`, builds `MainMenuScreen` via a private `buildScreen`
  helper, publishes the screen factory via `MainMenuScreenFactoryHandoff`.
  `TweaksClientInitializer` (new, one per platform module) follows this exact
  shape: load `tweaks.json` via `TweaksConfigIO`, register all 12
  `KeyBinding`s via `KeyBindingHelper.registerKeyBinding` at
  `onInitializeClient()` time (not lazily), publish `TweakRegistry`'s
  singleton/instance for `MainMenuScreen`'s Tweaks panel and each platform's
  render/input mixins to consume — likely via the same `*Handoff`
  static-broker pattern already used for `FriendsSidebarFacadeHandoff`/
  `SteamAchievementsGatewayHandoff`/etc. (grep confirms this pattern repeats
  per cross-module dependency in this repo; a new `TweakRegistryHandoff`
  class is the natural fit, published early since render mixins in other
  feature modules may need it before `MainMenuClientInitializer` runs).
  Entrypoint ordering in each `fabric.mod.json` needs a new
  `TweaksClientInitializer` entry placed **before**
  `MainMenuClientInitializer` (mirrors `ServerJoinPresenceClientInitializer`
  → `MainMenuClientInitializer`'s existing before/after relationship) so the
  registry is published before `MainMenuScreen`'s constructor (which will
  need `TweakRegistry.all()` to build its Tweaks panel) runs.
- **Config load/save/persist pattern**: `WardrobeConfigIO` (read in full) is
  the concrete template: `load(Path)` returns a `record ParseResult(T config,
  String warning)`, creates-with-defaults if absent, fails closed to a
  `DEFAULT` constant + human-readable warning on `IOException`/
  `RuntimeException`, `parse(String)`/`serialize(T)` are separate public
  methods for testability. `TweaksConfigIO` follows this exact shape;
  `TweaksConfig` (new record, `Map<TweakId, TweakState>`) is the `T`.
  Write-through save is not automatic inside `ConfigIO` — callers
  (`TweaksClientInitializer`'s registry-mutation callback, mirroring
  `MainMenuClientInitializer`'s wardrobe-equip-changed callback at lines
  148-156) call `.serialize(...)` + `Files.writeString(...)` themselves on
  every mutation (spec F5/F6, Events section).
- **`MainMenuJson`** (`features/main-menu/.../config/MainMenuJson.java`,
  package `de.lazuli.features.mainmenu.config`, read in full): a self-
  contained, dependency-free (no imports beyond `java.util.*`) hand-rolled
  JSON value model + recursive-descent parser/writer, ~595 lines, sealed
  `JsonValue` interface (`JsonObject`/`JsonArray`/`JsonString`/`JsonNumber`/
  `JsonBoolean`/`JsonNull`), `JsonParseException`/`JsonSchemaException`.
  Currently consumed by `WardrobeConfigIO`, `StoreCatalogConfigIO`,
  `MainMenuJoinHistoryConfigIO` (all in `features/main-menu`). Relocating
  this file is a pure package-rename + import-update across those three
  consumers (no behavioral change) — see Files to Modify.
- **`common` module** (`common/build.gradle`, `common/src/main/java/de/lazuli/common/`):
  already exists, already has one populated package,
  `de.lazuli.common.mainmenu` (`MeshCubeSpec.java`, `MainMenuPartNames.java`,
  `MainMenuMeshDefinitions.java` — all plain-data, no Minecraft imports, per
  the prior `unified-mainmenu-background` plan/feature). `common` depends
  only on `:api`, Java 21 floor (per that prior plan's own Existing-
  Implementation note — worth re-confirming `common/build.gradle`'s Java
  version at implementation time since `MainMenuJson` itself has no version-
  specific needs either way). All three platform modules already declare
  `implementation project(':common')` (added by the prior mainmenu-background
  feature, confirmed present in `platform/fabric-26.2/build.gradle` read
  during this planning pass) — no new platform `build.gradle` edits needed
  for the `common` dependency edge itself.
  **Resolves spec Open Question 1**: this plan places the relocated
  `MainMenuJson` at `de.lazuli.common.config.MainMenuJson` (a new
  `de.lazuli.common.config` package, sibling to the existing
  `de.lazuli.common.mainmenu` package), not co-located under
  `common.mainmenu` — `MainMenuJson` is a generic JSON utility with no
  main-menu-specific content (unlike `MeshCubeSpec`/`MainMenuPartNames`,
  which genuinely are main-menu mesh data), and `features/tweaks` consuming
  a class from a package literally named `.mainmenu` would be a confusing
  import for a feature that has nothing to do with the main-menu's 3D
  background. Flagged as a recommendation, not re-litigated as blocking per
  the spec's own framing of Open Question 1 as "no behavioral difference."
- **`features/*` module shape precedent** (`features/steam-cloud-sync`,
  `features/steam-world-hosting`, both read via directory listing): each has
  its own `build.gradle`, `src/main/java/de/lazuli/features/<name>/{api,
  config,events,gui,mixins,services}/` package split (`events`/`gui`/
  `mixins` frequently only contain a `package-info.java` placeholder when
  unused by that feature yet), `src/main/resources/.gitkeep`, and (for
  cloud-sync) a `src/test/java/...` tree with plain JUnit tests for every
  `ConfigIO`/service class — this is the direct template for the new
  `features/tweaks` module (Architecture: `services/TweakRegistry.java`,
  `services/TweakEngine.java` + one strategy class per tweak,
  `config/TweaksConfig.java`/`TweaksConfigIO.java`). `steam-world-hosting`'s
  `api/SteamWorldHostingConfig.java` + `config/SteamWorldHostingConfigIO.java`
  split (config record in `api/`, IO class in `config/`) is the narrower
  precedent to follow for `TweaksConfig`/`TweaksConfigIO` specifically, since
  the spec's own Public API section already puts `TweakId`/`TweakState`/
  `TweakDefinition` under `api/src/main/java/de/lazuli/api/tweaks/` (a new
  top-level `api` package, not `features/tweaks/api/`), mirroring how
  `SteamWorldHostingConfig` (record) lives under
  `features/steam-world-hosting/.../api/` while its `ConfigIO` lives in
  `.../config/` — **for Tweaks specifically the record types move one level
  up into the shared `:api` module** per the spec's explicit Public API
  section, not into `features/tweaks/api/`, since `TweakId`/`TweakState`/
  `TweakDefinition`/`TweakRegistry`'s read/write surface must be consumable
  by platform-module mixins that must not depend on `features/tweaks`'
  internal `services` package directly for the same reason `MainMenuTab`
  lives in `:api` today (cross-module public contract).
- **Wardrobe/`WardrobeSlot`** (`api/src/main/java/de/lazuli/api/mainmenu/WardrobeSlot.java`,
  `features/main-menu/.../config/WardrobeConfig.java`): confirmed present,
  needed by T10 Disable Cosmetics — not read in full this pass (T10's gate is
  additive/one small read of an existing enum, low risk, deferred to
  implementation's own quick read).
- No existing `KeyBinding` registration exists anywhere in this repo today
  (grep for `KeyBinding`/`KeyBindingHelper` across `platform/` returned
  nothing beyond this plan's own new references) — Tweaks is this repo's
  first consumer of `fabric-key-binding-api-v1`. All three platform
  `build.gradle`s already declare `implementation "net.fabricmc.fabric-api:fabric-api:${fabric_api_version}"`
  (the full umbrella artifact, confirmed in `platform/fabric-26.2/build.gradle`
  read during this pass; `fabric-26.1`/`fabric-1.21.11` were not re-read
  individually since `settings.gradle` + the shared `gradle.properties`
  convention this repo uses makes them near-certain to match, but
  implementation's first step for each platform should confirm the same line
  exists, not assume). The umbrella `fabric-api` artifact bundles
  `fabric-key-binding-api-v1` as one of its constituent sub-modules (this is
  Fabric API's standard "all submodules included" umbrella-artifact
  behavior, matching the spec's own Compatibility-section claim) — **no new
  Gradle dependency coordinate is needed** on any platform module; this is a
  compile-time-available API already, not a new external dependency (so the
  planner-convention Maven-registry-verification step does not apply here —
  no new coordinate is being introduced).

## Architecture Decisions

1. **`MainMenuJson` relocation** to `common/src/main/java/de/lazuli/common/config/MainMenuJson.java`,
   package `de.lazuli.common.config` (resolves spec Open Question 1, see
   Existing Implementation above). Pure move + package-rename, zero behavior
   change.
2. **`features/tweaks` new module**, added to `settings.gradle` as
   `include 'features:tweaks'`, `build.gradle` shaped like
   `features/steam-world-hosting/build.gradle` (plain-Java feature module,
   `api project(':api')`/`implementation project(':common')`, no Minecraft
   dependency — tweak *strategy logic* that touches actual Minecraft render/
   input types is per-platform, not in this module, per spec Architecture's
   "Per-tweak apply strategy" being described as living inside each
   platform's mixin surface, not in `features/tweaks` itself. `features/tweaks`
   holds the registry/config/state machinery only — `TweakEngine` in the
   spec's own Architecture section is best read as "the platform-side
   dispatcher that reads `TweakState` off `TweakRegistry` and applies it,"
   which structurally must live per-platform (it touches
   `net.minecraft.*`/mixin types) even though the spec names it under
   `features/tweaks/.../services/`. **Correction to spec Architecture
   wording flagged here**: `TweakEngine` and the 12 `*Tweak` strategy classes
   cannot be pure `features/tweaks` plain-Java classes if they contain actual
   mixin-injected render/input hook logic (mixins are per-platform-module
   classes in this repo's existing convention — see `features/friends-sidebar`'s
   `mixins/package-info.java` placeholder pattern, real mixins are declared
   platform-side against that platform's own Minecraft classpath). This plan
   resolves it as: `features/tweaks` holds `TweakEngine` as a thin,
   Minecraft-agnostic **interface** per tweak (e.g. `interface
   AntiDropHook { boolean shouldCancelDrop(String itemId, boolean shiftHeld);
   }`) plus the registry/config wiring; each platform module implements the
   12 hook interfaces against its own mixin/render call sites and wires them
   through `TweakRegistry`'s state. This keeps `features/tweaks` plain-Java
   (buildable/testable without a Minecraft classpath, consistent with every
   other `features/*` module's `src/test/java` JUnit precedent) while still
   satisfying "one small strategy class per tweak" per platform. **Flagged
   for explicit user confirmation before implementation**, since it narrows
   spec Architecture's literal wording (12 concrete `*Tweak` classes inside
   `features/tweaks/services/`) into "12 interfaces in `features/tweaks` +
   12×3 platform-side implementations" — a materially larger file count than
   the spec's Architecture section reads as implying, and the spec did not
   explicitly anticipate the mixin-locality constraint this plan surfaces.
3. **Per-tweak `javap` verification pass is a prerequisite of implementation,
   not of this plan** (spec Open Question 3) — this plan enumerates the 12
   hook points needing verification (see Risks) but does not perform the
   `javap` pass itself (would require building/inspecting all three
   platforms' resolved jars, which is implementation-phase work per this
   repo's own established convention, not planning-phase).
4. **Bind-capture widget** (spec Open Question 2): this plan recommends a
   thin custom capture control (a small `AbstractWidget`-equivalent state
   flag on the relevant panel: "armed" mode entered on click, next
   `keyPressed`/`mouseClicked` event captures the code and calls
   `KeyBinding.setBoundKey(InputUtil.Type.KEYSYM/MOUSE, code)` +
   `KeyBinding.updateKeysByCode()`, Escape cancels) over embedding vanilla's
   own `KeyBindingWidget`, because `MainMenuScreen` and its panels are not
   vanilla `OptionsScreen` subclasses and do not currently reuse any vanilla
   `Screen`-internal widget beyond plain `AbstractWidget`s (`FriendSidebarWidget`
   is the only reused-widget precedent, and it is this mod's own class, not
   vanilla's) — confirmed by this plan's read of `MainMenuScreen.java`
   showing hand-rolled hit-testing throughout (`mouseClicked` manual bounds
   checks), not vanilla layout containers. Implementation should still spend
   a brief scan of `KeyBindingWidget`'s actual constructor signature/
   accessibility (public API vs. private-package) per platform before
   committing, since this recommendation is based on this plan's read of
   `MainMenuScreen`'s *own* code, not a read of vanilla's `KeyBindingWidget`
   itself (out of this plan's file-reading budget).

## Files to Create

**`common` module:**
1. `common/src/main/java/de/lazuli/common/config/MainMenuJson.java` — moved
   verbatim from `features/main-menu/.../config/MainMenuJson.java`, package
   changed to `de.lazuli.common.config`, no other changes.

**`api` module** (new package `de.lazuli.api.tweaks/`, mirrors
`de.lazuli.api.mainmenu/`):
2. `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` — enum, 12
   constants per spec Public API.
3. `api/src/main/java/de/lazuli/api/tweaks/TweakState.java` — record
   `(boolean enabled, Map<String, Object> configurables)`.
4. `api/src/main/java/de/lazuli/api/tweaks/TweakDefinition.java` —
   interface per spec Public API (`id()`, `translationKey()`,
   `defaultState()`, `keyBinding()`, `secondaryKeyBinding()` default-null).
   Note: `KeyBinding` is a Minecraft type — `api` module's existing
   dependency footprint must be checked at implementation time (does `:api`
   already have a Minecraft-jar dependency for any other class? If not, this
   interface either takes `KeyBinding` as an opaque generic type param, or
   `:api` gains its first Minecraft dependency, or — the more likely fit
   given `:api`'s existing near-Minecraft-agnostic shape — the `KeyBinding`-
   typed accessors move to a platform-facing sub-interface while
   `TweakDefinition` itself stays Minecraft-agnostic and platform code
   supplies the `KeyBinding` mapping separately). **Flagged as an open
   implementation-time question**, not resolved by this plan — the spec's
   Public API section states the interface signature verbatim including
   `KeyBinding keyBinding()`, so this plan defers the "does `:api` already
   depend on Minecraft" check to implementation's first step on this file,
   since `api/build.gradle` was not read this pass (out of this plan's
   reading budget; a 2-line file, low risk, but a real unresolved fact).

**`features/tweaks` module** (new, added to `settings.gradle`):
5. `features/tweaks/build.gradle` — shaped like
   `features/steam-world-hosting/build.gradle` (`api project(':api')`,
   `implementation project(':common')`).
6. `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakRegistry.java`
   — static registry, `all()`/`stateOf(TweakId)`/`setEnabled(TweakId,
   boolean)`/`setConfigurable(TweakId, String, Object)`/
   `keyBindingOf(TweakId)` per spec Public API; holds the 12
   `TweakDefinition` instances + mutable `TweakState` map; each mutation
   method triggers a caller-supplied save callback (constructor-injected
   `Consumer<TweaksConfig>` or similar, matching `MainMenuJoinHistoryWriteHandoff`'s
   write-through pattern) rather than reaching for a config path itself
   (keeps this module I/O-agnostic — actual file write happens in the
   platform composition root, matching every other `features/*` config
   precedent read this pass).
7. `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfig.java`
   — record `Map<TweakId, TweakState>`, `DEFAULT` constant (all 12 tweaks at
   their spec-stated default state).
8. `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java`
   — `load`/`parse`/`serialize`, shaped exactly like `WardrobeConfigIO`
   (read in full this pass), consuming `de.lazuli.common.config.MainMenuJson`.
9. `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
   — the 12 static `TweakDefinition` instances (one per `TweakId`),
   translation keys, default states, per spec Requirements T1–T12.
10–21. `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/*.java`
   — 12 thin, Minecraft-agnostic hook interfaces, one per tweak, per
   Architecture Decision 2 above (e.g. `AntiDropHook`, `ForceBrightnessHook`,
   `ChatFilterHook`, `ChatPlayerHeadsHook`, `CustomCrosshairHook`,
   `DisableAnimationsHook`, `DisableParticlesHook`, `HidePlayerNamesHook`,
   `ClearWaterHook`, `DisableCosmeticsHook`, `ZoomHook`,
   `DisableBossBarsHook`) — exact method shape per tweak's configurables
   (e.g. `ZoomHook` needs `applyFov(float baseFov, TweakState state,
   boolean keyHeld): float` or similar; finalize signatures during
   implementation once each tweak's real vanilla hook point is `javap`-
   confirmed, since the natural method shape depends on what the platform
   call site actually needs to pass in).
22. `features/tweaks/src/main/java/de/lazuli/features/tweaks/events/package-info.java`,
    `.../gui/package-info.java`, `.../mixins/package-info.java` — placeholder
    packages matching sibling `features/*` modules' convention (this module
    has no cross-module events, no GUI of its own — the Tweaks tab UI lives
    platform-side per the main-menu tab precedent — and no mixins of its own
    for the same reason).
23. `features/tweaks/src/main/resources/.gitkeep`.
24. `features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`
   — JUnit, mirrors `WardrobeConfigIOTest`-equivalent precedent (no such file
   currently exists for `WardrobeConfigIO` specifically, but
   `steam-cloud-sync`'s `*IOTest.java` files are the direct template; note:
   confirm at implementation time whether `features/main-menu` has any
   `src/test` tree at all — this plan's directory listing did not show one,
   so `features/tweaks` would be introducing this module's first test tree,
   consistent with `steam-cloud-sync`/`steam-world-hosting`'s precedent, not
   `features/main-menu`'s apparent gap).
25. `features/tweaks/src/test/java/de/lazuli/features/tweaks/services/TweakRegistryTest.java`
   — JUnit, covers `setEnabled`/`setConfigurable`/save-callback invocation
   with an in-memory fake, no Minecraft classpath needed (this is exactly why
   Architecture Decision 2 keeps `TweakEngine`/hooks Minecraft-agnostic here
   — testability parity with every other `features/*` module).
26. `features/tweaks/README.md` — short module summary, matching
    `features/steam-world-hosting/README.md`/`features/steam-cloud-sync/README.md`
    precedent.

**Per platform module** (×3: `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`):
27–29. `platform/<module>/src/main/java/de/lazuli/TweaksClientInitializer.java`
   — loads `tweaks.json` via `TweaksConfigIO`, constructs `TweakRegistry`
   with a write-through save callback (mirrors `MainMenuClientInitializer`'s
   wardrobe-save-callback shape, lines 148-156 of that file), registers 12
   primary + 1 secondary (Anti-Drop's second binding) = 13 `KeyBinding`s via
   `KeyBindingHelper.registerKeyBinding` under one shared category string,
   publishes `TweakRegistry` via a new `TweakRegistryHandoff` static broker.
30–32. `platform/<module>/src/main/java/de/lazuli/TweakRegistryHandoff.java`
   — static publish/require broker, mirrors `FriendsSidebarFacadeHandoff`'s
   existing shape (not read this pass, but its call-site usage in
   `MainMenuClientInitializer.java` — `FriendsSidebarFacadeHandoff.require()`
   — is enough to confirm the shape: a static field + `publish`/`require`
   pair). Confirm exact file at
   `platform/<module>/src/main/java/de/lazuli/FriendsSidebarFacadeHandoff.java`
   during implementation as the literal template.
33–35. `platform/<module>/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`
   — new panel class, mirrors `StatisticsPanel.java`'s scrollable-list shape
   (chosen as template over `AchievementsPanel` since Tweaks needs per-row
   expand/collapse + scroll, same shape need as Statistics' per-stat rows) —
   `render(guiGraphics, font, x, y, w, h, mouseX, mouseY)`,
   `mouseClicked(x, y, w, h, mx, my): boolean`,
   `mouseScrolled(x, y, w, h, mx, my, amount): boolean`, plus new surface
   this panel alone needs: `keyPressed`/bind-capture entry point (Architecture
   Decision 4), and per-row expand-state tracking (a `Set<TweakId>` of
   currently-expanded rows, panel-local, not persisted).
36–38 (×3 platforms × up to 12 hook implementations, actual count TBD per
   `javap` findings). `platform/<module>/src/main/java/de/lazuli/tweaks/*.java`
   or embedded directly in existing mixin/render classes where a tweak's hook
   point already has a natural existing mixin home (e.g. T10 Disable
   Cosmetics likely gates inside whatever class already renders Wardrobe
   cosmetics, not a new standalone mixin) — **exact file list deliberately
   not enumerated here**, since it depends on each tweak's confirmed hook
   point per platform (spec Open Question 3); implementation's first task per
   tweak is the `javap` pass, which determines whether a new mixin file, an
   addition to an existing mixin, or a non-mixin hook (e.g. `KeyBinding`
   polling in an existing per-tick handler) is the right shape. This plan's
   Risks section enumerates the 12 hook points needing this pass.

## Files to Modify

1. `settings.gradle` — add `include 'features:tweaks'`.
2. `platform/fabric-1.21.11/build.gradle`, `platform/fabric-26.1/build.gradle`,
   `platform/fabric-26.2/build.gradle` — add
   `implementation project(':features:tweaks')` to each `dependencies {}`
   block (alongside the existing `features:*` entries); `implementation
   project(':common')` already present on all three (confirmed for 26.2 this
   pass, near-certain for the other two per shared-convention — verify at
   implementation time).
3. `features/main-menu/src/main/java/de/lazuli/features/mainmenu/config/WardrobeConfigIO.java`,
   `StoreCatalogConfigIO.java`, `MainMenuJoinHistoryConfigIO.java` — update
   import from `de.lazuli.features.mainmenu.config.MainMenuJson` to
   `de.lazuli.common.config.MainMenuJson`; delete the old
   `features/main-menu/.../config/MainMenuJson.java` file (Files to Create
   item 1 is its replacement).
4. `features/main-menu/build.gradle` — confirm it already depends on
   `implementation project(':common')` (or add it) so the three `*ConfigIO`
   classes above can resolve the relocated import; not confirmed this pass
   (file not read), flagged for implementation's first step on this change.
5. `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java` — add
   `TWEAKS` constant (spec Architecture "Main-menu tab wiring"; append at end
   per spec UI section's stated ordering decision — after `STATISTICS`).
6. `platform/fabric-1.21.11/.../MainMenuScreen.java`,
   `platform/fabric-26.1/.../MainMenuScreen.java`,
   `platform/fabric-26.2/.../MainMenuScreen.java` — add `tweaksPanel` field +
   constructor wiring (needs `TweakRegistry` param, threaded through from
   each platform's `MainMenuClientInitializer.buildScreen`), one `case
   TWEAKS ->` arm in `extractRenderState`'s switch, one in `mouseClicked`'s
   switch, one in `mouseScrolled` (Tweaks tab needs scroll, per Architecture
   Decision above), a `"Tweaks"` label in `tabLabel(tab)`, and (Architecture
   Decision 4) a bind-capture-armed state flag + `keyPressed` override
   extension for capturing the next key/mouse event when a "Bind" control is
   clicked.
7. `platform/fabric-1.21.11/.../MainMenuClientInitializer.java`,
   `platform/fabric-26.1/.../MainMenuClientInitializer.java`,
   `platform/fabric-26.2/.../MainMenuClientInitializer.java` — thread
   `TweakRegistry` (obtained via `TweakRegistryHandoff.require()`, mirroring
   the existing `FriendsSidebarFacadeHandoff.require()` call-site pattern at
   line 53) into `buildScreen(...)`'s parameter list and the `MainMenuScreen`
   constructor call.
8. `platform/fabric-1.21.11/src/main/resources/fabric.mod.json`,
   `platform/fabric-26.1/src/main/resources/fabric.mod.json`,
   `platform/fabric-26.2/src/main/resources/fabric.mod.json` — add
   `TweaksClientInitializer` to each module's `"client"` entrypoint list,
   positioned before `MainMenuClientInitializer` (per Existing Implementation
   note above); exact file path/JSON shape not read this pass, confirm at
   implementation time (each platform's `entrypoints` array format is a
   standard Fabric Loader convention, low risk).
9. Per-tweak vanilla hook-point files (T2 lightmap/gamma pipeline, T3/T4 chat
   message construction/render, T5 crosshair HUD element, T6 texture
   animation tick, T7 particle spawn, T8 name-tag render, T9 underwater
   overlay render, T10 Wardrobe cosmetic renderer (existing
   `features/main-menu` render call site, exact file not identified this
   pass), T11 FOV-per-frame, T12 boss-bar HUD render) — one modified or new
   mixin per platform per tweak, **exact file list is implementation-phase
   output of the `javap` pass** (spec Open Question 3), not enumerable here
   without that pass; this plan's Risks section lists the 12 hook areas so
   implementation knows what to search for per platform (`grep`-friendly
   starting points: search each platform module's existing `mixins/` package
   for "lightmap"/"gamma", "particle", "boss" i.e. `BossHealthOverlay`/
   `BossBarRenderer`, "nameTag"/"labelRenderer", "waterOverlay"/
   "underwaterOverlay", "crosshair").
10. `.claude/context/minecraft.md` — append a new row (or rows) recording
    each tweak's confirmed Yarn-vs-Mojmap hook-point class/method divergence
    once the `javap` pass runs, per this repo's established convention (the
    file's own stated purpose, confirmed by this plan's grep of its header:
    "Avoid exposing Yarn/intermediary names in public APIs" + the existing
    API-divergence table).

## Dependencies

No new external (non-Fabric) Maven coordinates are introduced. The only
"new" dependency this plan actually exercises is `fabric-key-binding-api-v1`,
which is a sub-module of the `fabric-api` umbrella artifact
(`net.fabricmc.fabric-api:fabric-api:${fabric_api_version}`) already declared
in all three platform `build.gradle`s (confirmed present for `fabric-26.2`
this pass) — per the planner-convention Dependencies rule, external-
dependency verification via Maven Central only applies to *new* coordinates;
since no new coordinate is added, no `search.maven.org` lookup was performed.
The umbrella-includes-all-submodules behavior of Fabric API is a well-
established, version-stable characteristic of that artifact (also asserted
by spec Compatibility section), not something a Maven-registry search would
independently confirm or deny (Maven Central lists the artifact's existence/
version, not its internal submodule composition) — implementation's first
real check is a compile-time smoke test: reference
`net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper` in
`TweaksClientInitializer` and confirm each platform module compiles; if it
doesn't, the fallback is adding an explicit
`implementation "net.fabricmc.fabric-api:fabric-key-binding-api-v1:<version>"`
line, at which point that *would* need the Maven-verification step this plan
is currently exempt from.

Intra-repo dependency edges added: `features:tweaks` → `api`, `features:tweaks`
→ `common`; `platform:*` → `features:tweaks` (×3); no edge from
`features:tweaks` → `features:main-menu` (spec Architecture explicitly avoids
this — T10's Wardrobe gate is implemented platform-side, reading
`WardrobeSlot`/existing Wardrobe render state from within the platform
module, which already depends on both `features:tweaks` and
`features:main-menu`, so no direct feature-to-feature edge is needed for
T10 either).

## Risks

1. **12 per-tweak vanilla hook points, ×3 platform modules, all unconfirmed
   until a `javap` pass runs** (spec's own flagged Open Question 3) — by far
   the largest unresolved surface in this plan. Each of T2/T3/T4/T5/T6/T7/T8/
   T9/T10/T12 touches a different vanilla render/registry subsystem; per this
   repo's own `.claude/context/minecraft.md` precedent, some of these
   diverge not just Yarn-vs-Mojmap but potentially between 26.1 and 26.2 too
   (e.g. the mainmenu-background plan found exactly this for player-model
   builder APIs). Mitigate: implementation should run one `javap`-first pass
   per tweak, cheapest/most-isolated tweaks first (T9 Clear Water and T12
   Disable Boss Bars are both single, well-known vanilla HUD overlay classes
   in every prior Minecraft version and are good low-risk first targets;
   T5 Custom Crosshair and T2 Force Brightness are the likely highest-
   divergence/highest-effort hook points given lightmap/gamma pipeline's
   documented history of per-version churn in this repo's own context file).
2. **Bind-capture widget implementation approach is a recommendation, not a
   confirmed fact** (Architecture Decision 4) — based on reading
   `MainMenuScreen.java` only, not vanilla's own `KeyBindingWidget`/
   `ControlsScreen` source. If vanilla's bind-capture flow turns out to be
   more directly reusable than assumed, the custom-capture-control estimate
   in this plan overstates the work; if less reusable, it's about right.
   Either way this is implementation's first-hour task on the Tweaks-tab UI,
   not a blocking unknown for the rest of the plan.
3. **`:api` module's Minecraft-dependency status is unconfirmed**
   (`api/build.gradle` not read this pass) — `TweakDefinition.keyBinding():
   KeyBinding` per spec's literal Public API signature requires `:api` to
   either already depend on a Minecraft jar or gain that dependency now. If
   `:api` is currently Minecraft-agnostic (likely, given `MainMenuTab`/
   `WardrobeSlot` are both plain enums), adding a Minecraft dependency to a
   currently-shared, multi-consumer module is a more invasive change than
   this spec's wording implies, and might warrant instead exposing
   `KeyBinding` only from a platform-side wrapper while `:api`'s
   `TweakDefinition` stays opaque/generic. Flagged for explicit resolution
   at the start of implementation, before `TweakDefinition.java` is written,
   since it changes that one file's shape materially and this plan's other
   files (registry, config) are unaffected either way.
4. **Config-directory-conflict risk between `TweaksConfig`'s enabled/
   configurable state and vanilla's own `options.txt`-persisted `KeyBinding`
   state**: none identified structurally (two genuinely independent files/
   mechanisms, spec Persistence section is explicit about this split), but
   implementation must ensure `TweaksClientInitializer` registers all 13
   `KeyBinding`s (12 primary + Anti-Drop's secondary) **before** vanilla's
   own options-load reads `options.txt`, or a previously-bound key silently
   resets to default on next launch — standard Fabric mod-init ordering
   concern (`KeyBindingHelper.registerKeyBinding` must run during
   `onInitializeClient`, which this plan's `TweaksClientInitializer` already
   satisfies; flagged only so implementation doesn't move this call later by
   accident).
5. **13 `KeyBinding`s all defaulting to `GLFW_KEY_UNKNOWN` (unbound)**
   (spec F1) — confirm at implementation time that Fabric's `KeyBinding`
   constructor genuinely accepts an unbound default without throwing/
   misbehaving in vanilla's `Controls` screen (expected to be fine — this is
   a normal, supported Fabric pattern for many real mods — but not verified
   against this repo's actual Minecraft version's `KeyBinding` source this
   pass).
6. **T1 Anti-Drop's secondary `KeyBinding`** is the one tweak needing 2
   bindings under one `TweakDefinition` — `TweakRegistry.keyBindingOf(TweakId)`
   returns only the primary per spec Public API; the Tweaks-tab UI must special-
   case rendering a second "Bind" control only for `ANTI_DROP`'s row (reading
   `secondaryKeyBinding()`), a small but easy-to-miss UI branch.
7. **Scope size**: this is a 12-sub-feature, 3-platform feature landing as
   one plan per the task's explicit instruction — expect implementation to
   be delivered/reviewed in multiple batches (framework + a handful of
   tweaks per batch is the natural cut, mirroring this repo's own existing
   "Batch-2"/"Batch-3" commit-message convention visible throughout the
   files read this pass), even though this plan itself is not subdivided
   into separate planning documents.

## Test Strategy

1. **`features/tweaks` unit tests** (JUnit, no Minecraft classpath, mirrors
   `steam-cloud-sync`/`steam-world-hosting`'s `src/test/java` precedent):
   `TweaksConfigIOTest` (load/parse/serialize round-trip, fail-closed-to-
   defaults on malformed JSON, matching `WardrobeConfigIOTest`-equivalent
   coverage style), `TweakRegistryTest` (`setEnabled`/`setConfigurable`
   mutate in-memory state and invoke the save callback exactly once per
   mutation, `stateOf` returns current not stale state).
2. **`common` module**: no new tests needed for the relocated `MainMenuJson`
   itself (pure move, its own existing test coverage if any — not found this
   pass — moves with it; if `features/main-menu` currently has zero test
   coverage for `MainMenuJson`, this plan does not introduce new coverage
   for it either, since that's a pre-existing gap outside this feature's
   scope).
3. **Compile check per platform**: `:features:tweaks:build`, then each of
   `:platform:fabric-26.2:build`, `:platform:fabric-26.1:build`,
   `:platform:fabric-1.21.11:build` (the last via `remapJar`, per
   `.claude/context/minecraft.md`'s Obfuscation Boundary convention) after
   each new file lands — confirms `KeyBindingHelper` resolves without a new
   Gradle coordinate (Dependencies section) and that the relocated
   `MainMenuJson` import updates compile clean.
4. **In-game manual verification, per platform, per tweak** (12 tweaks × 3
   platforms is a large manual matrix — recommend at minimum one full pass
   per platform covering all 12, plus targeted re-checks after any hook-point
   rework):
   - Tweaks tab renders all 12 rows, each togglable, each expandable,
     configurables editable and taking live effect (F6) without a menu
     reopen.
   - Hotkey binding: bind a tweak's key from the Tweaks tab, confirm it
     appears correctly bound in vanilla's own `Controls` screen under the
     shared tweaks category (F3); rebind from `Controls`, confirm the Tweaks
     tab reflects it on next redraw (same `KeyBinding` instance, no second
     source of truth per F3/F4).
   - Per-tweak in-game effect verification against each tweak's spec
     Requirements entry (T1–T12) — e.g. T1: dropping a whitelisted item is
     blocked with a message, Shift+Q override works if enabled, the
     secondary whitelist-toggle hotkey works; T11: hold-to-zoom vs.
     toggle-to-zoom both work correctly, scroll-to-adjust works while
     zoomed, transition animation (if enabled) is smooth.
   - `tweaks.json` round-trips correctly across a game restart (enabled
     flags + configurables persist; bindings persist via `options.txt`
     separately — confirm both independently, per spec Persistence's
     explicit two-path split).
   - No regression to existing `WORLDS`/`SERVERS`/`STORE`/`WARDROBE`/`HOME`/
     `ACHIEVEMENTS`/`STATISTICS` tabs after `MainMenuScreen`'s constructor/
     switch statements gain the new `TWEAKS` arm.
5. **Cross-platform parity check**: same 12 tweaks behave identically (same
   defaults, same configurable ranges/UI) across all three platform modules,
   even though each platform's underlying hook-point implementation is
   necessarily different code (per Risk #1) — a tweak passing on 26.2 but
   silently no-op-ing on 1.21.11 due to a missed/wrong hook point is the
   single most likely class of bug this feature can ship with, given the
   large unconfirmed-hook-point surface.

## Acceptance Criteria

1. `TWEAKS` tab exists on `MainMenuTab`, appears in the tab bar after
   `STATISTICS`, and its panel lists all 12 tweaks with toggle, bind
   control, and expandable configurables (F2).
2. Every tweak's hotkey is a real vanilla `KeyBinding` (F3) visible and
   rebindable from vanilla's own `Controls` screen under one shared
   category, and the Tweaks-tab "Bind" control reads/writes the same
   `KeyBinding` instance (no second source of truth, F3/F4) — verified by
   rebinding from each surface and confirming the other reflects it.
3. All enabled flags + configurables persist to `tweaks.json`
   (`FabricLoader.getInstance().getConfigDir().resolve("tweaks.json")`),
   fail closed to defaults with a logged warning on malformed content (F5),
   independent of `KeyBinding` state which persists via vanilla's own
   `options.txt` (Persistence).
4. Toggling any tweak (checkbox or hotkey) or changing any configurable
   takes effect immediately with no menu/game restart (F6).
5. No tweak sends or requires any network packet (F7) — verified by code
   review of each hook implementation (no `ClientPlayNetworking`/packet
   types referenced anywhere in `features/tweaks` or the platform-side hook
   implementations).
6. Each of T1–T12's behavior and configurables match spec Requirements
   verbatim, including stated defaults (all off by default; Anti-Drop empty
   whitelist; Zoom hold-to-zoom default; etc.).
7. `MainMenuJson` is relocated to `de.lazuli.common.config.MainMenuJson`
   with zero behavior change; `WardrobeConfigIO`/`StoreCatalogConfigIO`/
   `MainMenuJoinHistoryConfigIO` compile and behave identically against the
   relocated class (existing main-menu features show zero regression).
8. No compile errors on `:features:tweaks:build` or any of the three
   platform modules' `build`/`remapJar` tasks.
9. `features/tweaks` has JUnit coverage for `TweaksConfigIO` (round-trip +
   fail-closed) and `TweakRegistry` (mutation + save-callback invocation),
   runnable without a Minecraft classpath.
10. `.claude/context/minecraft.md` is updated with each newly-confirmed
    Yarn-vs-Mojmap (and 26.1-vs-26.2, where applicable) hook-point
    divergence discovered during the per-tweak `javap` passes.
11. Risk #3 (`:api` module's Minecraft-dependency question) and the
    Architecture Decision 2 correction (hook interfaces vs. spec's literal
    "12 concrete classes in `features/tweaks`" wording) are both explicitly
    resolved with the user before implementation begins on `TweakDefinition.java`
    and the hook-interface files, respectively, since both narrow/reinterpret
    specific spec statements rather than implementing them exactly as
    literally written.

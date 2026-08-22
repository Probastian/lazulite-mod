# Implementation Plan: Loot Bin UI (`TweakId.LOOT_BIN`)

Ground truth: `docs/specs/tweaks-loot-bin-ui.md` (approved spec; all Open
Questions resolved except #3's cosmetic corner/border treatment and #4's
mode-indicator treatment, both explicitly left as implementation-time
judgment calls, not gated on anything in this plan). Structural precedent:
`docs/specs/tweaks-no-rain-freecam-plan.md` (per-platform-file duplication
convention, `javap`-first sequencing discipline, Addendum-style risk
framing) and `docs/specs/tweaks-zoom-fov-plan.md` (small, self-contained
mixin/registration plan shape). No `tweaks-compass-plan.md` exists in this
repo — Compass shipped without a separate plan document; not used as
precedent here beyond the spec's own citations.

## Existing Implementation

Confirmed by direct read this pass (not re-derived from the spec's own
possibly-stale citations):

- `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` (32 lines) — plain
  enum, 15 constants, `COMPASS` last (line 30). Append-only convention
  confirmed via the file's own Javadoc. `LOOT_BIN` is appended as the 16th
  constant, after `COMPASS`.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
  — `of(TweakId, String, Map, boolean hasSecondary)` (4-arg, defaults
  `enabled=false`) and a 5-arg overload adding `defaultEnabled` (Compass's
  own documented exception, lines 25-36). `ANTI_DROP`'s definition (lines
  75-77) is the exact shape match for `LOOT_BIN`: 4-arg `of(...)` with
  `hasSecondary=true`, `enabled` defaulting `false` — no need for the 5-arg
  overload. `ALL` list (lines 141-144) is a flat `List.of(...)` in
  declaration order; `byId(TweakId)` (146-153) does a linear scan. `LOOT_BIN`
  is appended as both a new `public static final TweakDefinition LOOT_BIN`
  field (after `COMPASS`, line 139) and a new trailing entry in `ALL`.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
  — `ALL` is a `Map<TweakId, List<ConfigFieldSpec>>` populated in a static
  block (lines 23-129), `fieldsFor(TweakId)` throws if absent (131-137).
  `COMPASS`'s entry (123-128) is the shape precedent: 4 `ConfigFieldSpec.bool`
  rows, no enum rows. `LOOT_BIN`'s entry is the exact 6-row block already
  fully specified in spec R3 (2 bool, 2 enum, 1 bool, 1 enum) — copied
  verbatim into a new `ALL.put(TweakId.LOOT_BIN, List.of(...))` block.
  `ConfigFieldSpec.bool/numeric/enumField/stringList(...)` factory names all
  confirmed present and already used exactly as spec R3's block assumes.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/CompassHook.java`
  and `FreecamHook.java` — both confirmed read in full. `CompassHook` is the
  closest shape precedent for `LootBinHook` (`isXActive()` +
  `Object xConfigurable(String key)`, 2-method generic-accessor interface,
  spec Public API cites this exactly). `FreecamHook` is the precedent for
  "hook is state-only, all Minecraft-typed behavior lives platform-side, not
  behind this interface" (spec Public API's explicit framing).
- `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
  (353 lines, read in full) — single class implementing 15 hook interfaces
  (lines 48-50), one shared `state(TweakId)` helper (62-64), each hook
  method a trivial `state(TweakId.X).enabled()`/`.configurable(key)` read.
  `isCompassActive()`/`compassConfigurable(String)` (lines 344-352) is the
  exact shape `isLootBinActive()`/`lootBinConfigurable(String)` copies
  verbatim per spec Public API. Same class shape confirmed present on
  `fabric-1.21.11` and `fabric-26.1` (not re-read line-for-line this pass,
  but the class is Mojmap/Yarn-identical Java apart from imported Minecraft
  types, per this repo's own standing convention already documented in the
  no-rain-freecam plan).
- `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweaksKeyBindings.java`
  (59 lines, read in full) — primary `KeyMapping`s built generically in a
  loop over `TweakId.values()` (lines 42-46, confirmed): `LOOT_BIN`'s
  **primary** binding requires **zero code change** here once the enum
  constant exists. `secondaryKeyBindingOf(TweakId)` (lines 55-58) is
  currently a single hardcoded ternary, `null` for every `TweakId` except
  `ANTI_DROP` — exactly the "framework touch beyond purely additive" the
  spec's Public API section flags. This is the one file in this list that
  needs real new logic, not just a new data row.
- `platform/fabric-26.2/src/main/java/de/lazuli/TweaksClientInitializer.java`
  (59 lines, read in full) — `onInitializeClient()` composition root;
  `ZoomTicker.register(...)`, `TweaksToggleTicker.register(...)`,
  `FreecamTicker.register(keyBindings, hooks, registry)` (lines 53-55) are
  the existing per-tweak platform-wiring call sites. `LootBinScreenRegistration.register(keyBindings, hooks)`
  is added as one more call at this same site, matching spec Architecture's
  literal proposed call.
- `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweaksToggleTicker.java`
  (41 lines, read in full) — edge-trigger toggle loop iterates
  `TweakId.values()` generically except a special-cased `ZOOM` skip (lines
  30-33, confirmed): `LOOT_BIN`'s master on/off hotkey works with **zero
  code change** here too, confirming spec Architecture's claim directly
  rather than merely citing it.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java`
  (202 lines, read in full) — `parse(...)`'s per-`TweakId` loop seeds
  `configurables` from `TweaksConfig.DEFAULT.stateOf(id).configurables()`
  then overlays whatever the file has (lines 97-106); the one existing
  `id == TweakId.FREECAM` migration branch (line 107-109) is the **only**
  per-`TweakId` special case in this file. `serialize(...)` (145-160) writes
  every `TweakId`'s configurables generically. **Confirmed: `LOOT_BIN` needs
  zero changes to this file** — spec Configuration section explicitly states
  no schema change and no old-scale value to migrate (unlike Freecam's
  `moveSpeedRescaled`), so the existing default-backfill overlay loop alone
  is sufficient.
- `features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`
  (297 lines, read in full) — per-tweak round-trip test pairs already
  established for `NO_RAIN`/`FREECAM`/`COMPASS` (e.g.
  `compassNonDefaultConfigurablesRoundTrip`, lines 199-215;
  `compassMissingFromFileBackfillsToEnabledDefault`, 217-240) — this is the
  exact test shape `LOOT_BIN`'s new config coverage follows. The single
  `parseRoundTripsSerializedDefault` test (lines 18-40) also needs 6 new
  assertion lines for `LOOT_BIN`'s default configurables.
- `features/tweaks/src/test/java/de/lazuli/features/tweaks/services/TweakRegistryTest.java`
  (56 lines, read in full) — entirely `TweakId`-agnostic (uses `ZOOM`/
  `ANTI_DROP` as arbitrary stand-ins); `allReturnsAllDefinitions()` (lines
  50-55) asserts `registry.all()` size equals `TweakDefinitions.ALL.size()`,
  which exercises `LOOT_BIN`'s registration for free once added — **no
  `LOOT_BIN`-specific edit needed in this file**.
- `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/TweaksPanel.java` —
  confirmed via targeted grep (not full read) that `hasSecondaryKeyBinding()`
  and `secondaryKeyBindingOf(id)` are both already read generically at 5
  call sites (lines 169-187, 254, 451-464, 645) to render Anti-Drop's second
  "Bind" row and size that row's height. **Confirmed: zero changes needed to
  `TweaksPanel.java`** for `LOOT_BIN`'s checkbox row, its 6 config rows
  (already fully generic off `ConfigSchemas.fieldsFor(id)`, matching the
  no-rain-freecam plan's own confirmed-by-grep finding for `FREECAM`'s
  Config screen rows), or its second "Bind" row — this becomes the second
  real consumer of the secondary-binding UI path, exercising code that today
  only Anti-Drop exercises.
- **New finding, not previously documented in this catalog's plans: lang
  file gap.** `platform/{fabric-26.2,fabric-26.1,fabric-1.21.11}/src/main/resources/assets/lazuli/lang/en_us.json`
  confirmed read (26.2) / grepped (26.1, 1.21.11): all three currently
  contain `tweak.lazuli.*`/`key.lazuli.*` entries only through
  `DISABLE_BOSS_BARS` — **`NO_RAIN`, `FREECAM`, and `COMPASS` have no lang
  entries on any of the three platforms today**, meaning those three
  tweaks' names/keybind labels currently render as their raw translation
  key text in-game (a pre-existing, already-shipped cosmetic gap, not
  something this plan is responsible for fixing). Established recent
  precedent is therefore "ship without lang entries," but this plan
  recommends `LOOT_BIN` **do** add its 2-3 lang entries per platform
  (`tweak.lazuli.loot_bin`, `key.lazuli.loot_bin`,
  `key.lazuli.loot_bin_toggle_view`) since it's a one-line-per-platform,
  zero-risk addition and Loot Bin's secondary-binding row makes a missing
  label more confusing than a missing checkbox label alone — flagged as a
  recommendation, not a hard requirement inherited from the spec (spec is
  silent on lang files entirely).
- `net.minecraft.client.gui.GuiGraphicsExtractor` (26.1, 26.2) vs.
  `net.minecraft.client.gui.DrawContext` (1.21.11) — confirmed via grep/read
  of each platform's own `TweaksPanel.java`: `GuiGraphicsExtractor` is a
  **real vanilla Mojmap class** (`net.minecraft.client.gui` package),
  imported identically on both 26.1 and 26.2 (`platform/fabric-26.1/.../mainmenu/TweaksPanel.java`
  also imports it — confirmed present on both, not just 26.2, closing the
  no-rain-freecam plan's own flagged "confirm 26.1 parity" open item for
  this specific class). 1.21.11 uses the Yarn-named `DrawContext` directly,
  no wrapper class. This is a naming divergence only (same underlying
  draw-primitive concept, per-platform low-level fill/text/scissor calls),
  not a capability gap — `LootBinScreen`'s R8/R16 custom-panel rendering can
  use the same low-level primitives `TweaksPanel.java` already uses on each
  platform.
- **No existing precedent for vanilla screen-factory registration anywhere
  in this codebase** — confirmed via a repo-wide grep for
  `HandledScreens.register`/`MenuScreens.register`: zero matches. This is
  genuinely new code, not a copy of an existing pattern, and is the single
  largest structural risk in this plan (see Risks).
- Platform modules do have real JUnit test source sets for pure-Java logic
  extracted from Minecraft-typed classes: `platform/fabric-26.2/src/test/java/de/lazuli/cloudsync/CrossWorldStatsOfflineBucketFilterTest.java`
  tests `CrossWorldStatsOfflineBucketFilter`, a class that is itself
  duplicated across all three platform `src/main` trees (confirmed via
  glob) but whose **test is written once**, only in the `fabric-26.2` test
  tree, because the tested logic (`Map<String, AccountStats>` filtering) has
  no Yarn/Mojmap-specific surface and is byte-identical across all three
  platform copies. This is the direct, load-bearing precedent for this
  plan's Test Strategy recommendation below (extract Loot Bin's pure
  grouping/sort/slot-resolution logic the same way, test it once).
- Jar paths for the mandatory `javap` pass (same three modules/pins as every
  prior tweak in this catalog, unchanged from the no-rain-freecam plan):
  - 1.21.11 (Yarn): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-6dd721cd7d/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-6dd721cd7d-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`
  - 26.1 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a26c9a9f3c/26.1/minecraft-merged-a26c9a9f3c-26.1.jar`
  - 26.2 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`

## Mandatory first implementation step: `javap` verification pass

Per the spec's own confidence-tiered Architecture table and this repo's
standing convention (every prior tweak in this catalog, most recently
Freecam Addenda AD-1/AD-4/AD-6 in `tweaks-no-rain-freecam-plan.md`), **no
platform-typed code in this feature may be written until each row of the
spec's Architecture table is independently `javap`-confirmed on all three
jars above.** In priority order (highest-risk/most load-bearing first):

1. **Item/component-equality test (R5)** — spec's own "Medium" confidence,
   explicitly flagged as touching the actively-churning 1.20.5+ component
   system. Confirm the exact `ItemStack` method name/overload per platform
   (Yarn `areItemsAndComponentsEqual`-shaped; Mojmap
   `isSameItemSameComponents`/`matches`-shaped, per spec). This is the
   single method every "two stacks are the same displayed entry" decision
   (R5, R6, R12) depends on — get this wrong and grouping correctness fails
   silently, not loudly.
2. **Creative-tab reverse index accessor (R4)** — spec's own "Medium"
   confidence, flagged as having "seen real churn historically." Confirm
   `ItemGroup`/`ItemGroups.getGroupsToDisplay()`+`getDisplayStacks()`
   (Yarn) vs. `CreativeModeTab`/`CreativeModeTabs.allTabs()`+
   `getDisplayItems()` (Mojmap) resolve on all three pins.
3. **Screen-registration method visibility (Architecture table, "Medium-high"
   confidence)** — confirm `HandledScreens.register`/`MenuScreens.register`
   is `public` (not still requiring a Fabric API `ScreenRegistry` wrapper)
   on all three pins before `LootBinScreenRegistration` is written; this is
   the mechanism the entire feature's screen-swap depends on and has zero
   existing precedent in this codebase to fall back on (see Existing
   Implementation).
4. **`onMouseClick`/`slotClicked` accessibility from a subclass (R13)** —
   spec's own "High" (Yarn) / "Medium" (Mojmap) confidence; confirm the
   exact signature and `protected` (not `private`) visibility on 26.1/26.2
   independently (spec's own caution: do not assume 26.1 and 26.2 share a
   signature just because both are "26.x Mojmap" — direct, repeated
   precedent in this repo, most recently AD-1's mouse-look call site
   diverging from this same assumption).
5. **Player-inventory-slot test (R9)** — `slot.inventory ==
   player.getInventory()` (Yarn) vs. `slot.container ==
   player.getInventory()` (Mojmap), spec's "Medium" confidence.
6. **`GENERIC_9x1`-`GENERIC_9x6`/`SHULKER_BOX` constant spelling (R2)** —
   spec's "Medium-high" confidence; low individual risk but gates R2's two
   family-toggle booleans routing to the correct vanilla type set.
7. **Tooltip-line computation (R10)** — `ItemStack.getTooltip(...)`-shaped
   (Yarn) vs. `getTooltipLines(...)`-shaped (Mojmap), spec's "Medium-high"
   confidence; needed for R10's search-by-tooltip-text filter.

Record all seven findings in `.claude/context/minecraft.md` per this repo's
standing convention (the no-rain-freecam plan's own "Findings to record"
sections are the precedent), before or during implementation of the files
that depend on each one — not deferred to a separate pass.

## Files to Create

**One new Minecraft-agnostic hook interface:**

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/LootBinHook.java`
  — exact shape given verbatim in spec Public API:
  `boolean isLootBinActive()`, `Object lootBinConfigurable(String key)`.
  Copies `CompassHook.java`'s file shape/Javadoc style.

**Three new platform classes, ×3 platforms (`fabric-1.21.11`, `fabric-26.1`,
`fabric-26.2`), package `de.lazuli.tweaks`, following the
`FreecamCameraEntity`/`FreecamTicker` per-platform-duplicated-file
convention (no shared source set exists between platform modules today, per
the zoom-fov plan's own confirmed finding):**

- `LootBinScreen.java` — extends `HandledScreen<T>`/`AbstractContainerScreen<T>`
  (generic over the container's own handler type; exact generic bound
  resolved per javap step 3/4 above). Owns the transient `groupedViewActive`
  boolean (R15, defaults `true`, never persisted). Per spec Architecture
  "Screen-replacement mechanism": when `groupedViewActive`, overrides
  rendering/click-routing for the container-side region only (R8-R10, R16)
  via `LootBinGrouping`'s aggregation pass plus R12/R13's slot-resolution
  and one `onMouseClick`/`slotClicked` call per player gesture (R11 hard
  invariant); delegates the player-inventory region (R9) to whatever
  superclass sub-draw covers just that slot range (exact decomposition is
  an implementation-time `javap`/decompile detail per spec, not fixed
  further here). When `!groupedViewActive`, delegates the **entire** screen
  to the unmodified superclass `render`/`mouseClicked` (R15's zero-override
  fallback). Polls its own secondary `KeyMapping`
  (`TweaksKeyBindings.secondaryKeyBindingOf(TweakId.LOOT_BIN)`) once per
  render/tick to flip `groupedViewActive`. Panel corner/border treatment
  (Open Question #3's one remaining unresolved cosmetic detail) and the
  secondary-hotkey mode-indicator treatment (Open Question #4) are both
  small, non-blocking implementation-time choices inside this file — not
  gated on anything else in this plan.
- `LootBinGrouping.java` — owns the cached creative-tab reverse index (R4,
  lazily built, invalidated on resource/data reload) and the per-render
  aggregation pass (R5-R7) over a given container's own slot range (R9's
  container-vs-player split). Per this plan's Test Strategy below, this
  class should also expose a small set of **Minecraft-type-free** static
  helper methods for the genuinely pure sub-algorithms inside R6/R7/R12
  (sort-mode comparator selection, largest-quantity-with-lowest-index slot
  choice, generic key-based aggregation) — see Test Strategy for the exact
  extraction shape recommended.
- `LootBinScreenRegistration.java` — exposes `static void
  register(TweaksKeyBindings keyBindings, TweakHooksImpl hooks)` (spec
  Architecture's literal proposed call, `TweaksClientInitializer` is the
  sole caller). For each of the two eligible type families (R2), registers
  a `HandledScreens.register`/`MenuScreens.register` factory that, at each
  individual container-open call (not cached at registration time), reads
  `TweakEngineHandoff.require().isLootBinActive()` AND-ed with that
  family's own configurable, constructing either `LootBinScreen` (both
  true) or vanilla's own existing screen class (either false) — see spec
  Architecture for the exhaustive-by-construction safety argument (Goal 8).

## Files to Modify

1. `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` — append `LOOT_BIN`
   after `COMPASS` (line 30 today).
2. `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
   — add `public static final TweakDefinition LOOT_BIN = of(TweakId.LOOT_BIN,
   "<description>", map("applyToChestFamily", true, "applyToShulkerBox",
   true, "groupOrder", "CREATIVE_TAB_ORDER", "sortWithinGroup",
   "CREATIVE_ORDER", "showSearchBar", true, "countTextStyle", "PLAIN"),
   true);` (4-arg `of(...)`, matching `ANTI_DROP`'s shape — `hasSecondary =
   true`, `enabled` defaults `false` per spec R1); append `LOOT_BIN` to
   `ALL`. Description string is not fixed by the spec — pick prose matching
   the existing one-sentence-summary style of every other entry (e.g.
   "Replaces storage-container screens with a searchable, grouped item list;
   every click still issues one ordinary vanilla slot click.").
3. `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
   — add `ALL.put(TweakId.LOOT_BIN, List.of(...))` with the exact 6-row
   block from spec R3 (copied verbatim, no wording changes needed — spec's
   own labels are already final).
4. `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
   (×3) — add `LootBinHook` to the `implements` clause and its import; add
   `isLootBinActive()`/`lootBinConfigurable(String)`, copying
   `isCompassActive()`/`compassConfigurable(String)`'s exact shape (lines
   344-352 on the 26.2 copy).
5. `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/tweaks/TweaksKeyBindings.java`
   (×3) — add a `lootBinSecondary` field constructed/registered in the
   constructor next to `antiDropSecondary` (translation key
   `key.lazuli.loot_bin_toggle_view`); change `secondaryKeyBindingOf(TweakId)`
   from its current single-tweak ternary to a small `switch` covering both
   `ANTI_DROP` and `LOOT_BIN` (returning `null` for every other id) — this
   is the second-ever consumer of a mechanism built single-consumer-shaped,
   per spec Public API's own honest framing.
6. `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/TweaksClientInitializer.java`
   (×3) — add
   `de.lazuli.tweaks.LootBinScreenRegistration.register(keyBindings, hooks);`
   at the same composition-root site as the existing `FreecamTicker.register(...)`
   call (line 55 on the 26.2 copy), before `TweakRegistryHandoff.publish(...)`.
7. `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/resources/assets/lazuli/lang/en_us.json`
   (×3, recommended, not spec-mandated — see Existing Implementation's lang
   gap finding) — add `tweak.lazuli.loot_bin`, `key.lazuli.loot_bin`,
   `key.lazuli.loot_bin_toggle_view` entries.
8. `features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`
   — extend `parseRoundTripsSerializedDefault` with `LOOT_BIN`'s 6 default
   assertions; add `lootBinNonDefaultConfigurablesRoundTrip` (mirroring
   `compassNonDefaultConfigurablesRoundTrip`) and
   `lootBinMissingFromFileBackfillsToDisabledDefault` (mirroring
   `compassMissingFromFileBackfillsToEnabledDefault`, but asserting
   `enabled = false` per R1, unlike Compass's own documented exception).
9. New test file (recommended, one platform only — see Test Strategy):
   `platform/fabric-26.2/src/test/java/de/lazuli/tweaks/LootBinGroupingLogicTest.java`
   covering the pure sub-algorithms extracted into `LootBinGrouping.java`
   (see Test Strategy).

No changes expected to `TweaksPanel.java`, `TweaksToggleTicker.java`,
`TweakRegistry.java`/`TweakRegistryTest.java`, `TweaksConfigIO.java`, or
`common/` — all confirmed generic over `TweakId.values()`/
`ConfigSchemas.fieldsFor(id)` already (Existing Implementation).

## Dependencies

No new external (non-Fabric) dependency. Everything this feature needs is
either:

- Vanilla Minecraft classes already on each platform module's existing
  Yarn/Mojmap compile classpath (`HandledScreen`/`AbstractContainerScreen`,
  `ScreenHandler`/`AbstractContainerMenu`, `Slot`, `SlotActionType`/
  `ClickType`, `HandledScreens`/`MenuScreens`, `ItemGroup`/`CreativeModeTab`
  family, `ItemStack`) — no `build.gradle` change on any platform module.
- This mod's own existing low-level draw primitives
  (`GuiGraphicsExtractor`/`DrawContext`, already used by `TweaksPanel.java`
  on each platform, confirmed present — Existing Implementation).
- Plain Java (`features/tweaks` module) for the new `LootBinHook` interface
  — no Minecraft-jar dependency, matching every other hook interface in
  that package.

No Maven Central verification is required for this plan (no new coordinate
proposed anywhere in this feature).

## Risks

- **No existing precedent for `HandledScreens.register`/`MenuScreens.register`
  anywhere in this codebase** (confirmed via repo-wide grep, Existing
  Implementation) — this is genuinely new integration surface for the mod,
  not a copy of an established pattern the way every mixin-based tweak so
  far has been. Budget real implementation time for getting the
  factory-registration mechanics right (constructor-argument shape for
  vanilla's existing screen classes when falling back to them, exact
  `HandledScreenSupplier`/`MenuScreens.ScreenConstructor` functional-interface
  signature) beyond what the spec's Architecture section already
  describes at a conceptual level.
- **R5's item/component-equality method is the single highest-consequence
  `javap` unknown** — spec's own "Medium" confidence, explicitly flagged as
  touching the actively-churning 1.20.5+ component system. Every other
  requirement in Grouping/Rendering/Click-resolution assumes this method
  exists and behaves as described; get the wrong overload (or one with
  subtly different semantics, e.g. ignoring vs. respecting `count`) and
  grouping correctness fails in a way that's easy to miss in casual
  testing (visually-plausible but subtly-wrong merges/splits).
- **`onMouseClick`/`slotClicked` protected-visibility assumption (R13,
  Architecture's "scoping note") is load-bearing for the entire
  click-safety design** — if this method turns out `private` (unlike the
  spec's own "High" Yarn confidence) or has a signature this repo's Java
  version/module boundary can't call from a subclass in `de.lazuli.tweaks`
  the way `HandledScreen`/`AbstractContainerScreen` are extended, R11's
  hard one-click invariant would need a materially different mechanism
  (the spec explicitly rules out reflection and a custom lower-level call
  path as acceptable alternatives) — this would be a plan-invalidating
  finding, not a minor implementation adjustment, and should be the very
  first thing confirmed once the screen classes are underway.
- **26.1 vs. 26.2 divergence, unconfirmed beyond `GuiGraphicsExtractor`** —
  this plan confirms `GuiGraphicsExtractor` parity (Existing Implementation)
  but every other Architecture-table row still needs independent 26.1 **and**
  26.2 confirmation per this repo's own repeated precedent (most recently:
  AD-1's mouse-look call site hard-coding `client.player` identically on
  both, but only after independent verification, not by assumption).
- **Modded/plugin-server false-positive routing (Compatibility, spec's own
  documented limitation)** — not a bug to fix, but implementation should
  confirm R15's secondary hotkey genuinely works with zero reopen needed
  (the documented mitigation), since it's the only escape hatch this
  feature ships for that scenario.
- **No live-launch verification by the implementing/verifying agent** —
  per `feedback_no_launch_minecraft_remote.md` (user is on remote control
  and cannot see/close a launched Minecraft window), every in-game
  manual-test item in this plan's Test Strategy must be executed by the
  **user**, later, not simulated or assumed passing by any agent. Agent-side
  verification this round is limited to compiling all three platform
  modules and running the extended `TweaksConfigIOTest`/new
  `LootBinGroupingLogicTest` suites, plus a careful diff review — not an
  in-game pass. Flag this explicitly at hand-off, matching the no-rain-
  freecam plan's own established practice for this same standing
  constraint.
- **Panel corner/border treatment (Open Question #3, remaining) and
  secondary-hotkey mode-indicator treatment (Open Question #4)** are both
  genuinely open, non-blocking implementation-time judgment calls, not
  risks to the feature's correctness — noted here only so implementation
  doesn't mistake "undecided" for "blocked."

## Test Strategy

**Automated/unit (agent-executable):**

- `features/tweaks`' existing `TweaksConfigIOTest` pattern, extended per
  Files to Modify item 8 above — this is a complete, zero-Minecraft-
  dependency correctness check for R1/R3/Configuration (default
  configurables, non-default round-trip, missing-`TweakId` backfill to
  `enabled=false`).
- **New pure-logic extraction, following the
  `CrossWorldStatsOfflineBucketFilter`/`CrossWorldStatsOfflineBucketFilterTest`
  precedent directly** (Existing Implementation): the class under test is
  duplicated ×3 across platform `src/main` trees, but since the *tested
  slice* has no Yarn/Mojmap-specific surface, its test is written **once**,
  in `fabric-26.2`'s test tree only. Recommend `LootBinGrouping.java`
  expose 2-3 small static methods with **Minecraft-type-free** signatures
  so this same pattern applies:
  - **R12's slot-resolution strategy is fully pure and testable as-is**:
    given a small `record SlotCandidate(int slotIndex, int quantity)`, a
    static `int chooseSlot(List<SlotCandidate> candidates)` implementing
    "largest quantity, ties broken by lowest slot index" has zero
    Minecraft-type dependency. Test cases: single candidate; clear largest;
    tie broken by lowest index; empty list (defensive — should not be
    reachable in practice, but worth asserting the contract).
  - **R7/R4's `sortWithinGroup`/`groupOrder` comparator selection for the
    `COUNT_DESC`/`ALPHABETICAL` modes is pure** (not `CREATIVE_ORDER`,
    which inherently depends on vanilla's own tab list ordering and is not
    testable this way): given a small `record DisplayEntry(String
    displayName, long totalCount)`, a static method resolving a mode string
    to a `Comparator<DisplayEntry>` (or directly sorting a list) is
    Minecraft-type-free. Test cases: `COUNT_DESC` descending order with a
    tie; `ALPHABETICAL` case-insensitive-or-not (confirm intended
    collation at implementation time); an unrecognized/default mode string
    falling back sensibly.
  - **R6's "sum across every matching real slot" is pure if abstracted over
    a generic key**: a static `<K> Map<K, Integer> aggregate(List<Map.Entry<K,
    Integer>> rawSlots)` merging by key is Minecraft-type-free and testable
    with `String`/`Integer` stand-in keys, even though *which* real
    `ItemStack`s count as the same key (R5) is not testable this way (see
    below).
  - R16's fullness-indicator arithmetic (`occupied / total`) is trivial
    enough to cover in the same test file for completeness, though low
    value on its own.
  - **Not unit-testable this way (genuinely Minecraft-typed, manual-only):**
    R5's actual `ItemStack` component-equality behavior (needs a real
    `ItemStack`/component registry); R4's creative-tab reverse-index
    construction (needs real `ItemGroup`/`CreativeModeTab` registry
    contents); R9's player-vs-container slot classification (needs a real
    `ScreenHandler`/`AbstractContainerMenu` instance); R13's actual
    `onMouseClick`/`slotClicked` dispatch and R10's tooltip-line
    computation (both need a live screen/client). These remain manual-only,
    called out explicitly rather than silently assumed covered.

**Compile-only, per platform (agent-executable):** a full
`:platform:fabric-1.21.11:compileJava :platform:fabric-26.1:compileJava
:platform:fabric-26.2:compileJava` (or equivalent `build`/`check`) after
each file lands, confirming the new `LootBinHook` interface change ripples
cleanly through all three `TweakHooksImpl.java` copies, the new screen
classes compile against each platform's real Yarn/Mojmap types, and no
`HandledScreens.register`/`MenuScreens.register` type mismatch exists —
this is a genuinely new compile-time check this feature exercises that no
prior tweak's plan needed (mixin-based tweaks don't get a compile-time
target-resolution check the way an ordinary registration call does, so a
clean compile here is somewhat more informative than for e.g. the zoom-fov
mixins).

**Manual, in-game, per platform (×3) — deferred to the user, not run by any
agent this round** (Risks section's remote-control constraint):

1. Open a chest/barrel/ender chest/double chest/shulker box with `LOOT_BIN`
   enabled and both family toggles on — confirm the grouped/searchable view
   replaces the raw grid (Goal 2), one row per non-empty creative-tab group
   (R4/R6), correct per-entry totals including >64 (R6), distinct
   component/NBT variants shown as separate rows (R5).
2. Disable `LOOT_BIN` (or the relevant family toggle) and reopen the same
   container type — confirm the vanilla screen renders completely
   unchanged (Goal 2's explicit symmetry requirement).
3. Left-click, right-click, shift+left-click an aggregated row — confirm
   each resolves to exactly one real vanilla action on the expected
   (largest-quantity) slot (R11-R13), and that right-click behaves exactly
   like real vanilla right-click, no "withdraw 1" approximation (Open
   Question #1).
4. Type in the search bar — confirm live filtering by name and by
   tooltip-derived text (enchantments, lore, custom name) (R10); confirm
   the fullness indicator (R16) reflects real occupied/total slots, not
   aggregated-entry count.
5. Press the secondary hotkey while a Loot Bin screen is open — confirm
   live flip to vanilla-grid mode with no reopen, and back (R15/Goal 7);
   confirm the mode-indicator (Open Question #4's chosen treatment) is
   visible and correct.
6. Open a furnace, crafting table, anvil, and horse inventory — confirm
   none of them ever show the grouped view regardless of config (Goal 8,
   Non-goals).
7. Toggle `LOOT_BIN`/a family flag while a container screen is **already
   open** — confirm the documented limitation (no retroactive swap on an
   already-open screen; only the *next* open reflects the change) holds as
   expected, and that R15's hotkey remains the live-without-reopen
   alternative.
8. Confirm the Tweaks tab shows `LOOT_BIN`'s row with both a primary and a
   second "Bind" control (Anti-Drop-mirroring UI requirement), and that the
   6 config rows render/persist correctly across a restart.

## Acceptance Criteria

- `TweakId.LOOT_BIN` exists; `TweakDefinitions.LOOT_BIN`/`ConfigSchemas`
  entries match spec R1/R3 exactly (default `enabled=false`,
  `hasSecondaryKeyBinding()=true`, the 6-field config schema verbatim).
- `LootBinHook` exists with the exact 2-method shape from spec Public API;
  all three `TweakHooksImpl.java` copies implement it identically to
  `CompassHook`'s existing shape.
- All three platforms have `LootBinScreen`, `LootBinGrouping`,
  `LootBinScreenRegistration` in `de.lazuli.tweaks`, wired into
  `TweaksClientInitializer.onInitializeClient()` at the same composition-root
  site as every other tweak's platform wiring.
- `TweaksKeyBindings.secondaryKeyBindingOf` correctly returns a non-null,
  independently-bindable `KeyMapping` for both `ANTI_DROP` and `LOOT_BIN`,
  `null` for every other `TweakId` (regression-checked, not just assumed).
- Goal 8 holds by construction (verified via the `javap`-confirmed
  `ScreenHandlerType`/`MenuType` registration, not a runtime check) — a
  non-storage screen's factory is never even called with Loot Bin's logic.
- R11's one-click invariant is verified through direct read of the final
  `LootBinScreen` click-handling code (no batching/retry path exists) —
  this is a code-review-level acceptance criterion, not solely a manual
  in-game one, given the anti-cheat-safety stakes the spec assigns it.
- `tweaks.json` round-trips `LOOT_BIN` correctly, including the
  missing-key backfill-to-`enabled=false` path (unlike Compass's own
  documented exception) — covered by the extended `TweaksConfigIOTest`.
- All three platform modules compile cleanly with the new files/edits;
  `TweaksConfigIOTest` (including the new `LOOT_BIN` cases) and the new
  `LootBinGroupingLogicTest` (if created) both pass.
- No agent-side claim of "verified in-game" is made — the 8-item manual
  checklist above is handed back to the user as their own follow-up step,
  per the Risks section's remote-control constraint.
- Panel corner/border treatment (Open Question #3, remaining) and
  secondary-hotkey mode-indicator treatment (Open Question #4) are both
  present in some reasonable form (not required to match any specific
  design not already fixed by the spec) — implementation is free to choose,
  consistent with R8/UI's already-resolved minimal/dark/vanilla-palette-
  adjacent, exactly-vanilla-width, modestly-taller-than-double-chest
  constraints.

## Open Questions (carried from spec, not resolved by this plan)

1. **Panel corner/border treatment** (spec Open Question #3, remaining) —
   implementation-time judgment call, non-blocking.
2. **Secondary-hotkey mode-indicator treatment** (spec Open Question #4) —
   implementation-time judgment call, non-blocking.
3. **This plan's own addition, not in the spec:** exact
   `HandledScreenSupplier`/`MenuScreens.ScreenConstructor` functional-
   interface shape and vanilla fallback-screen constructor argument list
   per platform — genuinely unknown until the mandatory `javap` step 3
   above runs; not expected to change this plan's file list, only the
   internal implementation of `LootBinScreenRegistration`.

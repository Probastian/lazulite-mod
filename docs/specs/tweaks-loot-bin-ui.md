# Spec: Loot Bin UI (Tweaks Framework)

Status: specification only (no plan, no implementation code in this document).
Owner feature: `features/tweaks` (new `TweakId.LOOT_BIN`), plugging into the
existing Tweaks framework (`docs/specs/tweaks.md`) exactly like `NO_RAIN`/
`FREECAM`/`COMPASS` did (`docs/specs/tweaks-no-rain-freecam.md`,
`docs/specs/tweaks-compass.md`) — no framework redesign. Consumed by all
three platform modules (`platform/fabric-1.21.11`, `platform/fabric-26.1`,
`platform/fabric-26.2`).

**Methodology note, matching `tweaks-no-rain-freecam.md`'s own precedent**:
this pass had `Read`/`Glob`/`Grep`/`Write`/`WebFetch`/`WebSearch` but no
`javap` access against this repo's own resolved jars. Every vanilla class/
method name below is confidence-tiered exactly as that spec's own convention
established (**high** = confirmed this pass via official Yarn javadoc or an
unambiguous, long-stable core API; **medium** = plausible/sourced but not
`javap`-confirmed against this repo's exact pin; **low/design-only** = a
genuine open product decision, not a vanilla-API fact). A real `javap -p`/
`javap -c -p` pass against `.gradle/loom-cache/minecraftMaven/net/minecraft/
minecraft-merged-*/**` (paths already enumerated in `tweaks-zoom-fov.md`) is
the mandatory first implementation step, exactly as every prior tweak in
this catalog required.

## Overview

An Apex-Legends-"death box"-style alternative UI for genuine **storage**
containers — chest, double chest, barrel, shulker box, and ender chest —
replacing vanilla's raw fixed slot grid with a searchable, scrollable list of the
container's contents grouped the same way the Creative-mode inventory groups
items (by creative tab), one full-width row per non-empty group, each
distinct item shown once with its *total* count across every real backing
slot (not capped at one stack), with NBT/component-distinct variants (custom
name, enchantments, other item-component data) kept as separate entries.

The single hardest requirement, and the one this spec's Architecture section
is built around, is that **every player action in this UI must be exactly
one ordinary vanilla slot click — one `SlotActionType`/`ClickType`, one real
`Slot`, one `ServerboundContainerClickPacket`/`ClickSlotC2SPacket`** — sent
through the exact same code path (`HandledScreen`/`AbstractContainerScreen`'s
own click-forwarding method) a raw, un-tweaked click on the real grid would
already use. This tweak never introduces a custom packet, never batches or
reorders multiple slot operations behind one player input, and never asks
the server to do anything a vanilla client wouldn't already ask it to do —
it is a **client-side visual rearrangement/relabeling of the real slot
grid**, not a new inventory-management model, and works against a fully
vanilla, unmodified server. This is explicitly *not* the same category of
feature as an inventory-sorting mod that reorders/merges slots server-
detectably; see Non-goals.

Sources for the framework this rides on: `TweakDefinitions.java`
(`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/
TweakDefinitions.java:140-143` for the append-only `ALL` list),
`ConfigSchemas.java` (same package), `TweaksConfigIO.java`
(`features/tweaks/src/main/java/de/lazuli/features/tweaks/config/
TweaksConfigIO.java`), `TweakHooksImpl.java`/`TweaksKeyBindings.java`/
`TweaksClientInitializer.java` (one copy each per platform module, e.g.
`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`,
`.../tweaks/TweaksKeyBindings.java`,
`platform/fabric-26.2/src/main/java/de/lazuli/TweaksClientInitializer.java`).

## Goals

1. A new `TweakId.LOOT_BIN` tweak following the established `TweakDefinition`/
   `ConfigFieldSpec`/`TweakHooksImpl` pattern exactly — one more entry in the
   existing catalog, no framework redesign.
2. When enabled (and, for a given container, its container-type family is
   also enabled — see Requirements R2), opening that container shows a
   grouped, scrollable, searchable list instead of vanilla's raw slot grid.
   When disabled (master toggle or that container-type family's own toggle),
   the vanilla container screen renders completely unchanged — same class,
   same behavior, same click handling, zero difference from today.
3. Items are grouped exactly like Creative-mode's own tab grouping (reusing
   vanilla's own creative-tab item index, not a reimplementation), one
   full-width row per non-empty group, groups stacked vertically in a
   scrollable list; a group with zero matching items in the container does
   not render at all.
4. Each distinct (item, exact component/NBT data) combination is shown once,
   with its *total* count summed across every real backing slot in the
   container (uncapped, can exceed 64); different component/NBT variants of
   the same base item (renamed, enchanted, custom data, etc.) are always
   separate entries, never merged.
5. A search bar, positioned in a divider strip (R16) between the grouped
   list and the player's own inventory rather than sticky to the bottom of
   the panel (configurable on/off via `showSearchBar`), filters the visible
   list live by item display name or by any of the item's own tooltip-
   derived text (enchantments, lore, custom name, etc.), reusing vanilla's
   own tooltip-line computation rather than re-deriving enchantment/lore
   text by hand; that same divider strip also shows a container-fullness
   indicator (R16) of real occupied vs. real total container slots.
6. Right-click, left-click, and shift+left-click on an aggregated row entry
   each resolve to exactly one ordinary vanilla click (`SlotActionType.PICKUP`
   button 1, `PICKUP` button 0, and `QUICK_MOVE` button 0 respectively) on
   one concrete real `Slot` object already present in the container's own
   `ScreenHandler`/`AbstractContainerMenu`, sent through the same
   `HandledScreen.onMouseClick`/`AbstractContainerScreen.slotClicked`
   choke-point vanilla's own default click handling already uses — see
   Architecture "Click-to-slot resolution" for the exact mechanism.
   Right-click deliberately performs vanilla's own right-click action
   unmodified rather than approximating "withdraw one item" (Open Questions
   #1, resolved).
7. A per-tweak secondary hotkey (mirroring Anti-Drop's existing secondary-
   binding precedent) lets a player flip the *currently open* container
   screen between grouped-list mode and an unmodified vanilla-grid fallback
   mode, live, without closing the container or disabling the tweak — an
   escape hatch for any interaction vanilla's grouped view intentionally
   doesn't support (Non-goals) and a way to verify "is this container being
   mis-classified as storage" without touching config.
8. The alternative UI is architecturally incapable of appearing for a
   non-storage container (furnace, crafting table, anvil, enchanting table,
   brewing stand, beacon, horse/llama inventory, etc.) — gated by vanilla's
   own `ScreenHandlerType`/`MenuType` constant, not by title text or a
   denylist, so a non-storage screen simply never routes through this
   tweak's registered factory in the first place (Architecture R2/R3).

## Non-goals

- **Not an inventory-sorting/auto-stack mod feature.** This tweak never
  reorders, merges, or moves items in the background, never sends a
  server-facing action the player didn't explicitly trigger with one click,
  and never batches multiple slot operations behind a single player input.
  It is purely (a) a different way of *drawing* the container's existing
  contents and (b) a client-side lookup translating one click on that drawing
  into one ordinary click on the one real slot it visually represents. This
  is explicitly **not** the same risk category as mods that silently
  re-sort/compact a player's inventory via many rapid synthetic clicks or a
  custom packet — no such behavior exists anywhere in this design.
- **No custom packet, no multi-slot atomic transaction, no server component
  of any kind.** Every requirement in this spec that could be read as
  wanting "move N stacks in one click" is deliberately *not* built that way
  — see Architecture "Click-to-slot resolution" for why the hard one-click/
  one-real-slot-click constraint shapes every interaction rule below. This
  spec deliberately does not attempt to approximate "withdraws one item" on
  right-click — right-click performs vanilla's own real right-click action,
  unmodified, on the same slot left-click would target (Open Questions #1,
  resolved).
- **Never applies to non-storage containers.** Furnace/blast furnace/smoker,
  crafting table, anvil, enchanting table, grindstone, stonecutter, loom,
  cartography table, smithing table, brewing stand, beacon, horse/llama/
  chest-boat inventories, and any other screen with a fixed, non-generic
  slot layout are excluded **by construction** (gated on vanilla's own
  `ScreenHandlerType`/`MenuType` constant at screen-registration time, R2/
  R3) — not by a title-text heuristic, which would be unreliable (containers
  can be player-renamed via an anvil).
- **No whitelist/denylist by container title text.** Because chest, barrel,
  and ender chest are genuinely indistinguishable from each other at the
  client-protocol level (all three share the same `GENERIC_9x3`-family
  `ScreenHandlerType`/`MenuType`, confirmed via the shape of vanilla's own
  registration — see Architecture), per-container-type configurables are
  scoped to what's *actually* distinguishable (the shared-type "family"), not
  to individual block types. See Requirements R2 for the exact granularity
  and Compatibility for the known modded-server edge case this implies.
- **No drag-distribute (`QUICK_CRAFT`), double-click-collect-full-stack,
  number-key hotbar swap, or throw-out-of-inventory (`THROW`) interaction in
  grouped mode.** Only the three interactions explicitly requested (right-
  click, left-click, shift+left-click) are redefined for aggregated rows;
  everything else is out of scope for v1 grouped-mode interaction and is
  only available via the vanilla-grid fallback mode (Goal 7).
- **No persistence of search text or scroll position** across container
  opens/closes or game sessions — each container open starts with an empty
  search box and the list scrolled to top (see Future Extensions if this is
  wanted later).
- **No change to the player's own inventory/hotbar area** of the screen —
  that region keeps rendering and behaving exactly as vanilla already does;
  grouping/aggregation/search apply only to the container's own slots (see
  Requirements R9).
- **No claim of correctness against modded servers whose custom GUIs happen
  to reuse a storage `ScreenHandlerType`/`MenuType`** (e.g. a shop plugin
  built on `GENERIC_9x3`) — see Compatibility for this known, inherent
  limitation of any tool that only observes the vanilla container protocol.

## Requirements

### Framework registration

- **R1. `TweakId.LOOT_BIN`.** New enum constant appended to
  `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` after `COMPASS`
  (append-only convention, unchanged from Compass's own R1/C1 precedent).
  New `TweakDefinitions.LOOT_BIN` (`features/tweaks/.../services/
  TweakDefinitions.java`), `translationKey` auto-derived as
  `"tweak.lazuli.loot_bin"`, `hasSecondaryKeyBinding()` = `true` (Goal 7 —
  see Public API/Architecture for why this is not quite the "zero framework
  change" every prior tweak enjoyed). Default **enabled = false**, matching
  every tweak except Compass's own documented exception (this tweak changes
  vanilla interaction surface materially, so it must be opt-in).
  `defaultConfigurables`:
  ```java
  map("applyToChestFamily", true, "applyToShulkerBox", true,
      "groupOrder", "CREATIVE_TAB_ORDER", "sortWithinGroup", "CREATIVE_ORDER",
      "showSearchBar", true, "countTextStyle", "PLAIN")
  ```
- **R2. Per-container-type-family configurables.** Because chest, barrel,
  ender chest, minecart-with-chest, and double chest all share the exact
  same client-visible `ScreenHandlerType`/`MenuType` family (`GENERIC_9x1`
  through `GENERIC_9x6`, vanilla only ever opening the `9x3`/27-slot and
  `9x6`/54-slot members — see Architecture), they cannot be independently
  toggled from each other client-side without an unreliable title-text
  heuristic (Non-goals). This spec instead exposes two booleans, each keyed
  to an actually-distinguishable vanilla type family:
  - `applyToChestFamily` (default `true`): `GENERIC_9x1`-`GENERIC_9x6` —
    covers chest, double chest, barrel, ender chest, minecart chest, and any
    modded container sharing this same vanilla type.
  - `applyToShulkerBox` (default `true`): `SHULKER_BOX` type.
  Dispenser/dropper (`GENERIC_3x3`) and hopper (`HOPPER`) are dropped from
  scope entirely — not just defaulted off — since they read as small
  utility containers rather than "loot" (Open Questions #2, resolved).
  Each family flag is rendered as a `ConfigFieldSpec.Kind.BOOLEAN` row
  (ConfigSchemas below); the master `LOOT_BIN` enabled flag is a
  prerequisite AND-ed with the relevant family flag before a given
  container-open routes to the grouped screen (Architecture R2/R3).
- **R3. `ConfigFieldSpec` schema.** New `ConfigSchemas.ALL` entry:
  ```java
  ALL.put(TweakId.LOOT_BIN, List.of(
      ConfigFieldSpec.bool("applyToChestFamily", "Chests / Barrels / Ender Chests"),
      ConfigFieldSpec.bool("applyToShulkerBox", "Shulker Boxes"),
      ConfigFieldSpec.enumField("groupOrder", "Group Order",
              List.of("CREATIVE_TAB_ORDER", "ALPHABETICAL")),
      ConfigFieldSpec.enumField("sortWithinGroup", "Sort Within Group",
              List.of("CREATIVE_ORDER", "COUNT_DESC", "ALPHABETICAL")),
      ConfigFieldSpec.bool("showSearchBar", "Show Search Bar"),
      ConfigFieldSpec.enumField("countTextStyle", "Count Text Style",
              List.of("PLAIN", "WITH_STACK_HINT"))
  ));
  ```
  No new `ConfigFieldSpec.Kind` needed — `BOOLEAN`/`ENUM` already exist and
  already render generically via `TweaksPanel`'s config-screen machinery
  (`platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`).

### Grouping model

- **R4. Creative-tab grouping, reused not reimplemented.** Groups correspond
  1:1 to vanilla's own creative-inventory tabs (`ItemGroup`/`ItemGroups`
  Yarn, `CreativeModeTab`/`CreativeModeTabs` Mojmap — see Architecture for
  exact confidence-tiered names), built into a `(Item -> group)` reverse
  index once (cached, rebuilt only on resource/data reload, same lifecycle
  as vanilla's own creative-tab contents), not recomputed per frame or per
  container-open. Group **display order** matches `groupOrder`:
  `CREATIVE_TAB_ORDER` (default, vanilla's own tab registration order) or
  `ALPHABETICAL` (groups sorted by their own display name).
- **R5. Distinct-entry key = item + exact component/NBT data, ignoring
  count.** Two real stacks are the same displayed entry iff
  `ItemStack.areItemsAndComponentsEqual`-shaped equality holds ignoring
  count (exact vanilla method name to confirm per-platform, Architecture) —
  i.e. same `Item`, same full component/NBT payload (custom name,
  enchantments, potion contents, custom model data, everything). A renamed
  or enchanted copy of an otherwise-identical item is always a separate row
  from the plain copy, never merged, matching the explicit hard requirement.
- **R6. Per-entry total = live sum across every matching real slot in the
  container's own slot range** (excluding the player-inventory slots that
  the same `ScreenHandler`/`AbstractContainerMenu` also lists — see R9),
  recomputed fresh on every render pass directly from each real `Slot`'s
  current `getStack()` (no cached snapshot carried between frames — matches
  every other tweak's "poll current state, no staleness" discipline,
  `tweaks.md` F6). Uncapped — may exceed 64, rendered as plain text (see R8).
  A group whose every member entry's total is zero (i.e., the container
  currently has none of any item in that creative tab) does not render a row
  or take up list space at all.
- **R7. Within-group sort order.** `sortWithinGroup`: `CREATIVE_ORDER`
  (default — same relative order the items already appear in within that
  creative tab's own vanilla list), `COUNT_DESC` (highest total first), or
  `ALPHABETICAL` (by display name).

### Rendering

- **R8. Layout.** A custom panel whose horizontal width is **exactly**
  vanilla's own inventory/container UI width — the same ~176px-scaled
  width shared by vanilla's chest-family GUI background and the player's
  own inventory grid rendered directly beneath it (R9) — never a bespoke
  or custom *wider* width (this corrects an earlier draft of this spec,
  which incorrectly called for "just wider than vanilla"; see Architecture/
  UI for the same correction). Vertically, the panel may be **modestly**
  taller than vanilla's own largest container UI (double chest /
  `GENERIC_9x6`), but must **not** be much taller than that double-chest
  height — a small, bounded increase, not open-ended growth to fit
  content. R10/R16's scrolling (unchanged) is precisely what accommodates
  group/entry content beyond that bounded height, so the panel's own
  footprint stays compact and roughly fixed-size regardless of how many
  groups/entries the container currently holds. Replacing only the
  *container-side* slot area (R9 excludes the player's own inventory/
  hotbar). **Visual style (decided, Open Questions #3, resolved)**: a
  minimal, dark, semi-transparent, vanilla-palette-adjacent panel — meant
  to blend into normal gameplay the same way vanilla's own container GUIs
  do, not to look like a menu screen — rather than reusing this mod's own
  `TweaksPanel` main-menu chrome. Corner/border treatment remains an
  implementation-time detail (Open Questions #3); exact width and the
  bounded-height range are now decided per above, not open. One row per non-
  empty group (R6): a group-header line (group display name) followed by
  that group's entries, each entry rendered as one line: item icon
  (rendered from one representative real backing `ItemStack`, count clamped
  to 1 so vanilla's own tiny per-icon count overlay doesn't also draw and
  visually collide with this tweak's own count text), item display name,
  and a right-aligned count string per `countTextStyle`: `PLAIN` (just the
  number, e.g. `"384"`, default) or `WITH_STACK_HINT` (number plus a
  parenthetical real-stack-count hint, e.g. `"384 (6 stacks)"`). The whole
  group list is vertically scrollable (mouse wheel / scrollbar, matching
  this mod's existing main-menu list-scrolling convention, e.g.
  `TweaksPanel`'s own row list, `TweaksPanel.java:106-118`).
- **R9. Player-inventory area is untouched.** The screen's own player-
  inventory/hotbar region (the real `Slot`s whose backing inventory is the
  viewing player's own `PlayerInventory`/`Inventory`, distinguished the same
  way vanilla's own `quickMoveStack`/`quickMoveStack`-equivalent
  implementations already do internally — see Architecture) keeps rendering
  and behaving exactly as vanilla's default `HandledScreen`/
  `AbstractContainerScreen` drawing and click routing already do, completely
  unmodified by this tweak. Grouping/aggregation/search (R4-R7, R10) apply
  only to the container's own slots; R16's divider-strip search bar and
  container-fullness indicator sit visually between the grouped list and
  this player-inventory region but are not part of it and do not alter its
  rendering or click routing in any way.
- **R10. Search bar filtering.** When `showSearchBar` is `true` (default), a
  single-line text field — positioned in the divider strip between the
  grouped/scrollable item list and the player's own inventory area, **not**
  pinned to the bottom of the panel itself as an earlier draft of this spec
  incorrectly stated (see R16 for the exact position and the
  container-fullness indicator that shares that same strip) — filters the
  visible group/entry list live as the player types: an entry stays visible
  iff its display name OR any line of its own vanilla tooltip text
  (case-insensitive substring match; matches enchantment names, lore lines,
  custom name, potion effect text, etc., since all of that is already
  present in vanilla's own computed tooltip lines — see Architecture) contain
  the current search text. A group with zero currently-visible entries after
  filtering does not render, same as R6's "zero total" rule. When
  `showSearchBar` is `false`, no search field renders (R16's fullness
  indicator still renders in the divider strip regardless of this flag) and
  the full grouped list (R4-R7) always shows.
- **R16. Divider strip: search bar position and container-fullness
  indicator.** (Numbered R16, after R15, rather than renumbering the
  click-to-slot-resolution/fallback requirements below it — this
  requirement belongs here in Rendering, logically alongside R8-R10.)
  Between the grouped/scrollable item list (R8) and the player's own
  inventory/hotbar region (R9, rendered exactly as vanilla immediately
  below) sits a thin divider strip that is part of neither region. Two
  elements render in this strip, both drawn in R8's minimal, dark,
  vanilla-palette-adjacent panel style (exact treatment — bar vs. text,
  colors, spacing — is an implementation-time detail, same as R8's own
  corner/border treatment):
  - **The search bar (R10)** — when `showSearchBar` is `true`, R10's
    single-line filter field renders here, in this divider strip between
    the grouped list and the player's own inventory, not sticky to the
    bottom edge of the grouped-list panel.
  - **Container-fullness indicator (new requirement, always shown
    regardless of `showSearchBar`)** — a simple readout of how full the
    container currently is, e.g. `"42/72 slots"` or an equivalent bar/
    text treatment (exact visual form is an implementation-time detail,
    consistent with R8's resolved panel style). Computed as occupied real
    slot count vs. total real slot count over the container's own slot
    range only (the same R9 container-vs-player-slot split used
    throughout this spec), using the same live per-slot polling R6
    already performs (`Slot.getStack()`; occupied = a slot whose current
    stack is non-empty) — **not** the aggregated-entry/group count from
    R4-R7, since a single aggregated row can represent many real slots and
    entry count is therefore a different, smaller number than real
    occupied-slot count. Recomputed fresh every render pass, same "poll
    current state, no staleness" discipline as R6/R14.

### Click-to-slot resolution (the anti-cheat-safety core requirement)

- **R11. One player action = exactly one real-slot click, always.** No
  interaction in grouped mode ever issues more than one
  `onMouseClick`/`slotClicked` call (Architecture) per player mouse click.
  This is a hard invariant, not a default — there is no configurable or
  fallback path that would ever batch, retry, or chain multiple slot clicks
  behind a single player input while in grouped mode.
- **R12. Slot-resolution strategy.** When a player clicks an aggregated
  entry (R5's displayed group-member), this tweak must pick exactly one real
  backing `Slot` (out of potentially several matching real slots spread
  across the container) to issue the click against. **Left-click,
  shift+left-click, and right-click all use the same strategy**: resolve to
  whichever real matching slot currently holds the **largest** quantity of
  that exact item (ties broken by lowest slot index). This maximizes the
  amount moved by a single click and matches what a player rationally
  trying to "grab a stack" would target if manually hunting for the biggest
  pile, and naturally drains the largest pile first across repeated clicks
  (each click may change which slot is now "largest"). There is no
  separate smallest-slot heuristic for right-click — right-click does not
  try to approximate "withdraw exactly one item" (Open Questions #1,
  resolved); it simply issues vanilla's own real right-click action (R13)
  against the same slot left-click would target, so whatever vanilla's
  actual right-click semantics are for that slot's current contents
  (half-stack-rounded-up pickup onto an empty cursor, or vanilla's own
  "deposit 1 per right-click" behavior once the cursor already holds a
  matching item) apply unmodified — nothing designed here beyond reusing
  the same slot.
  This strategy is recomputed fresh at click time from the container's
  current live state (R6), not cached from when the row was last drawn.
- **R13. Exact vanilla actions issued per gesture**, all via the same
  `HandledScreen.onMouseClick(Slot, int slotId, int button, SlotActionType)`
  choke-point (Yarn; Mojmap: `AbstractContainerScreen.slotClicked(Slot, int
  slotId, int mouseButton, ClickType)` — see Architecture) vanilla's own
  default click routing already funnels every real click through:
  - Right-click on an entry → one `PICKUP`/`PICKUP` call, button `1`, on the
    same slot left-click would resolve to (R12 — left-click, shift+left-
    click, and right-click all share one slot-resolution strategy now).
    This is real, unmodified vanilla right-click semantics for that slot:
    half-stack-rounded-up pickup onto an empty cursor, or vanilla's own
    "deposit 1 per right-click" behavior once the cursor already holds a
    matching item — both simply fall out of `PICKUP` button 1's existing
    server-side handling; nothing new is designed here.
  - Left-click on an entry → one `PICKUP`/`PICKUP` call, button `0`, on the
    slot resolved by R12's strategy. Reproduces vanilla's own "pick up this
    slot's entire current stack to cursor" left-click semantics — capped at
    whatever that one real slot currently holds (which may be less than the
    group's *aggregate* total; R12's "largest slot" choice minimizes how
    often this matters in practice).
  - Shift+left-click on an entry → one `QUICK_MOVE`/`QUICK_MOVE` call,
    button `0`, on the slot resolved by R12's strategy (same slot-selection
    rule as plain left-click and right-click). Reproduces vanilla's own
    shift-click "move this slot's entire stack into the player's inventory"
    semantics for that one real slot.
  No other button/action combination is handled in grouped mode (Non-goals).
- **R14. Live re-render after every click.** Because R13's call already
  mutates the real `Slot`'s backing `ItemStack` (and sends the real network
  packet — Networking) exactly as a genuine click would, the very next
  render pass's fresh R6 recomputation automatically reflects the new
  totals/positions with no special-cased "optimistic update" logic needed —
  same "poll fresh, don't cache" discipline already used throughout this
  tweak and every sibling tweak.

### Vanilla-grid fallback

- **R15. Secondary hotkey toggles render/interaction mode for the currently
  open screen only.** A per-tweak secondary `KeyMapping` (Goal 7, Public
  API/Architecture), when pressed while a Loot Bin screen is the current
  screen, flips a transient (never persisted to `tweaks.json`, resets to
  grouped-mode-on on every fresh container open) boolean on that screen
  instance. When flipped to vanilla-grid mode, the screen renders and routes
  clicks exactly as vanilla's own default `HandledScreen`/
  `AbstractContainerScreen` behavior would (no override active at all in
  that mode — see Architecture for why this requires zero extra code beyond
  "don't call the grouped-mode override this frame"), for the container-side
  slots; the player-inventory area (R9) is unaffected either way since it
  never used the grouped rendering/click path to begin with.

## Public API

New Minecraft-agnostic hook interface,
`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/
LootBinHook.java`, following `CompassHook`'s established generic-accessor
shape (`features/tweaks/.../services/CompassHook.java`, cited in
`docs/specs/tweaks-compass.md` Public API):

```java
public interface LootBinHook {
    boolean isLootBinActive();
    Object lootBinConfigurable(String key);
}
```

`TweakHooksImpl` (one copy per platform) gains `implements ... LootBinHook`
plus:

```java
@Override
public boolean isLootBinActive() {
    return state(TweakId.LOOT_BIN).enabled();
}

@Override
public Object lootBinConfigurable(String key) {
    return state(TweakId.LOOT_BIN).configurable(key);
}
```

matching `isCompassActive()`/`compassConfigurable(String)`'s existing
implementation shape exactly (`TweakHooksImpl.java:330-337`, 26.2 copy).

**Everything else this tweak needs (grouping index, per-frame aggregation,
click-slot resolution, the replacement `Screen` class itself) is
Minecraft-typed and therefore cannot live behind this Minecraft-agnostic
interface** — it lives entirely platform-side (new classes, not new hook
methods), exactly the same "hook is state-only, behavior is platform-side"
split `FreecamHook`/`FreecamCameraEntity`/`FreecamTicker` already established
(`docs/specs/tweaks-no-rain-freecam.md` Public API/Architecture).

**Framework touch beyond "purely additive" (flagged honestly, unlike every
prior tweak in this catalog).** `TweaksKeyBindings.secondaryKeyBindingOf`
(`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/
TweaksKeyBindings.java:56-58`) is currently hardcoded: *"Non-null only for
`TweakId.ANTI_DROP`; `null` for every other tweak."* Giving `LOOT_BIN` a
working secondary binding (Goal 7/R15) requires a small, additive edit to
this one platform class per platform module: one new `KeyMapping` field
(constructed and registered in the constructor next to `antiDropSecondary`)
and one new branch in `secondaryKeyBindingOf`. This is routine, bounded, and
was presumably exactly what happened the first time Anti-Drop itself needed
this (i.e., this is the second-ever consumer of a mechanism that happened to
be built single-consumer-shaped) — not a redesign, but real enough to call
out rather than claim "zero framework changes" the way Compass's spec
correctly could.

## Architecture

### Framework fit

`TweakDefinitions`/`ConfigSchemas`/`TweaksConfigIO`/`TweaksPanel` all
generalize over `TweakId.values()` already (per every prior tweak's own
"Framework Fit" precedent, e.g. `docs/specs/tweaks-no-rain-freecam.md`
Architecture) — `LOOT_BIN` is a 16th additive entry needing no change to any
of those files' *logic*, only new data rows (Requirements R1/R3).
`TweaksKeyBindings`'s primary-binding loop
(`TweaksKeyBindings.java:41-46`) and `TweaksToggleTicker`'s edge-trigger loop
(`TweaksToggleTicker.java:28-40`) both already iterate `TweakId.values()`
generically, so `LOOT_BIN`'s master on/off toggle (hotkey or checkbox) works
with zero code change; only the *secondary* binding needs the small edit
described in Public API above.

`TweaksClientInitializer.onInitializeClient()`
(`platform/fabric-26.2/src/main/java/de/lazuli/TweaksClientInitializer.java:29-59`)
is the existing composition root where every other tweak's platform-side
wiring is registered (`ZoomTicker.register(...)`,
`TweaksToggleTicker.register(...)`, `FreecamTicker.register(...)`, lines
53-55). This spec adds one more call at the same site, e.g.
`LootBinScreenRegistration.register(keyBindings, hooks)`, run once at client
init, well before any world/container screen could possibly open (same
ordering guarantee `TweakEngineHandoff.require()` already relies on for
every render-hook tweak).

### Vanilla container/screen surface — confidence-tiered findings

| Concept | Yarn (1.21.11) | Mojmap (26.1/26.2) | Confidence |
|---|---|---|---|
| Container screen base class | `net.minecraft.client.gui.screen.ingame.HandledScreen<T extends ScreenHandler>` | `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<T extends AbstractContainerMenu>` | High — `HandledScreen` confirmed this pass via direct Yarn 1.21.1 javadoc fetch (constructor `HandledScreen(T handler, PlayerInventory inventory, Text title)`, fields `x`/`y`/`backgroundWidth`/`backgroundHeight`/`focusedSlot`); Mojmap name is the well-known, long-stable counterpart per multiple Forge/Fabric doc sources found this pass, not independently `javap`-confirmed against this repo's exact 26.1/26.2 pins. |
| Server-side menu/handler class | `net.minecraft.screen.ScreenHandler` | `net.minecraft.world.inventory.AbstractContainerMenu` | High/Medium, same basis as above. |
| Slot click entry point (the reuse target for R13) | `protected void onMouseClick(Slot slot, int slotId, int button, SlotActionType actionType)` on `HandledScreen` | `protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type)` on `AbstractContainerScreen` | High for Yarn (confirmed this pass, exact signature from official javadoc). Medium for Mojmap — name/signature inferred from the Fabric official docs page's own description ("`slotClicked()` delegates to `clicked()` on the menu") plus Forge-javadoc-family precedent; not independently `javap`-confirmed. |
| Click type enum (the reuse target for R13) | `net.minecraft.screen.slot.SlotActionType` — `PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL` | `net.minecraft.world.inventory.ClickType` — same seven members | Medium-high — this is core, long-unchanged Container-rewrite (1.14+) API; exact member spelling not independently `javap`-verified this pass. |
| Handler-side click logic (what `onMouseClick`/`slotClicked` calls into, confirming R13's calls are the *real* vanilla path, not a shortcut around it) | `ScreenHandler.onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player)` | `AbstractContainerMenu.clicked(int slotId, int button, ClickType clickType, Player player)` | High — confirmed present this pass (Yarn javadoc search result: *"the `onSlotClick` method contains the actual logic that handles a slot click"*); Mojmap name is the standard, widely-documented counterpart. |
| Screen registration (mechanism this spec's screen-swap relies on, Requirements R2/R3) | `net.minecraft.client.gui.screen.ingame.HandledScreens.register(ScreenHandlerType<T>, HandledScreenSupplier<T>)` | `net.minecraft.client.gui.screens.MenuScreens.register(MenuType<T>, MenuScreens.ScreenConstructor<T>)` | Medium-high — confirmed this pass as a real, officially-documented vanilla registration point (Fabric's own official `docs.fabricmc.net/develop/blocks/container-menus` tutorial calls it directly; older Fabric API also shipped a now-largely-superseded `ScreenRegistry`/`ScreenHandlerRegistry` wrapper for versions where the vanilla method wasn't yet public — this repo's three pinned versions are recent enough that the vanilla method itself should be directly usable, but confirm via `javap` that it isn't still `private` on any of the three pins before assuming no wrapper is needed). |
| Generic-storage type family (Requirements R2, Non-goals' "chest/barrel/ender chest indistinguishable" finding) | `ScreenHandlerType.GENERIC_9X1`…`GENERIC_9X6`, `SHULKER_BOX` | `MenuType.GENERIC_9x1`…`GENERIC_9x6`, `SHULKER_BOX` | Medium-high — these are the well-known, stable vanilla type constants (confirmed this pass via web search: *"GENERIC_9x3 GUI appears when interacting with a single chest, minecart with chest, ender chest, or barrel"*, and shulker box is a distinct type despite visually-identical GUI); exact enum-constant spelling per this repo's pins not `javap`-verified. |
| Player-inventory-slot test (Requirements R9) | `slot.inventory == player.getInventory()` | `slot.container == player.getInventory()` | Medium — standard idiom already used inside vanilla's own `quickMove`/`quickMoveStack` implementations for the same "is this slot mine or the container's" distinction; exact field name per this repo's pins not `javap`-verified. |
| Item/component-equality test (Requirements R5) | `ItemStack.areItemsAndComponentsEqual(ItemStack, ItemStack)`-shaped (exact overload TBC) | Mojmap family likely includes `ItemStack.isSameItemSameComponents`/`ItemStack.matches` | Medium — post-1.20.5 component-system API; this repo's own `.claude/context/minecraft.md` documents general precedent for cross-mapping churn in adjacent areas, so this specific method must be independently `javap`-confirmed per platform, not assumed identical across Yarn/Mojmap naming. |
| Creative-tab reverse index (Requirements R4) | `ItemGroup`/`ItemGroups.getGroupsToDisplay()` + per-group `getDisplayStacks()` | `CreativeModeTab`/`CreativeModeTabs.allTabs()` + `CreativeModeTab.getDisplayItems()` | Medium — conceptually certain this exists in some shape (Creative mode itself depends on it), exact accessor names not `javap`-verified. |
| Tooltip-line computation (Requirements R10 — reused, not reimplemented) | `ItemStack.getTooltip(...)`-shaped | `ItemStack.getTooltipLines(...)`-shaped | Medium-high — stable, long-present API family; exact overload/parameter list (context object, player, tooltip-flag) to confirm per platform. |
| Click packet actually sent (Networking) | `net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket` | `net.minecraft.network.protocol.game.ServerboundContainerClickPacket` | High — extremely stable, long-present packet identity; exact field list not needed for this spec since R13 never constructs this packet directly (it is sent internally by whatever `onMouseClick`/`slotClicked` already calls, e.g. `client.interactionManager.clickSlot(...)`/`Minecraft.gameMode.handleInventoryMouseClick(...)`, itself not directly invoked by this tweak's code — see below). |

**Important scoping note**: R13's design deliberately calls
`onMouseClick`/`slotClicked` (the `Screen`-level method), **not** the
lower-level `client.interactionManager.clickSlot(...)`/
`Minecraft.gameMode.handleInventoryMouseClick(...)` network-plus-prediction
call directly — because `onMouseClick`/`slotClicked` is exactly the single
method vanilla's own `mouseClicked`/hit-testing already calls for a real
grid click, reusing it (rather than the lower-level call it eventually
reaches) means this tweak inherits vanilla's own cursor-stack bookkeeping,
double-click-timing state, and any other screen-level side effects for free,
with zero risk of this tweak's code diverging from what a "real" click does
at that layer. The exact reachability of `onMouseClick`/`slotClicked` from
subclass code (`protected`, confirmed accessible to a subclass per the Yarn
javadoc fetched this pass) is what makes "extend `HandledScreen`/
`AbstractContainerScreen` and call this one protected method directly" the
correct, minimal-surface-area design — not a mixin, not reflection.

### Screen-replacement mechanism

**New platform classes (three, one per platform module, package
`de.lazuli.tweaks`, following the `FreecamCameraEntity`/`FreecamTicker`
per-platform-file precedent):**

- **`LootBinScreen<T extends ScreenHandler/AbstractContainerMenu>`**,
  extending `HandledScreen<T>`/`AbstractContainerScreen<T>`. Holds a
  transient `groupedViewActive` boolean (default `true` on construction,
  R15) and, each render pass:
  - If `groupedViewActive`: renders the container-side region via R8-R10,
    R16 (custom grouped/searchable list plus the R16 divider-strip search
    bar/fullness indicator, drawn with this mod's own existing low-level
    `GuiGraphicsExtractor` fill/text/scissor primitives —
    `HudWaypointCompassBarMixin`/`TweaksPanel` already use the same calls,
    confirmed present in this codebase at
    `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/TweaksPanel.java:13,106-118`
    — reusing those low-level draw calls for convenience only; the panel's
    actual *visual style* is its own minimal, dark, semi-transparent,
    vanilla-palette-adjacent look (R8), deliberately not `TweaksPanel`'s
    own main-menu chrome, and not vanilla's stretched/tiled chest-texture
    background either — though its horizontal width is a precise,
    deliberate match to vanilla's own inventory/container UI width (R8): a
    dimension match only, never a texture reuse), and resolves clicks via
    R12/R13 instead of the superclass's default position-based slot hit-test.
    Renders the player-inventory region (R9) via an ordinary call into
    whatever superclass drawing covers just that slot range (exact
    decomposition of `HandledScreen`'s monolithic `drawSlots`-shaped method
    into "container region" vs "player region" sub-draws is an
    implementation-time detail to confirm via `javap`/decompiled source, not
    fixed further here).
  - If `!groupedViewActive` (R15): calls straight into the superclass's own
    default `render`/`mouseClicked` behavior for the *entire* screen
    (container region included) — zero override active, byte-for-byte
    vanilla behavior for that container's whole grid.
  - Polls its own secondary `KeyMapping`'s `consumeClick()`
    (`TweaksKeyBindings.secondaryKeyBindingOf(TweakId.LOOT_BIN)`, Public
    API) once per render/tick to flip `groupedViewActive` — scoped to this
    screen instance only, no global per-tick loop needed since the binding
    is meaningless while no `LootBinScreen` is the current screen.
- **`LootBinGrouping`** (or equivalent small helper class/set of static
  methods): owns the cached creative-tab reverse index (R4, built lazily on
  first use, invalidated on resource/data reload the same way vanilla's own
  creative tab contents are) and the per-render aggregation pass (R5-R7)
  over a given `ScreenHandler`/`AbstractContainerMenu`'s container-side
  slots (R9's player-vs-container slot split).
- **`LootBinScreenRegistration`** (mod-init-time registration, called once
  from `TweaksClientInitializer.onInitializeClient()` per Framework Fit
  above): for each eligible `ScreenHandlerType`/`MenuType` (the two families
  in Requirements R2), calls `HandledScreens.register`/`MenuScreens.register`
  with a factory that, **at each individual container-open call time** (not
  once, cached, at registration time), reads
  `TweakEngineHandoff.require()`'s `isLootBinActive()` plus that container
  family's own configurable (R2) and constructs either a `LootBinScreen`
  (both true) or vanilla's own existing screen class (either false) — e.g.
  `GenericContainerScreen`/`ContainerScreen`-equivalent for the chest family,
  `ShulkerBoxScreen`-equivalent for shulker boxes. This per-open check is
  what makes toggling the tweak or a family's configurable apply the very
  next time that container type is opened (see Performance/UI for the
  documented limitation that an *already-open* screen doesn't retroactively
  swap class — R15's secondary hotkey is the live, no-reopen-needed
  alternative for the specific "go back to vanilla-like interaction" case).

**Why gating on `ScreenHandlerType`/`MenuType` (not a runtime block/entity
check) structurally guarantees Goal 8**: every non-storage vanilla screen
(furnace, crafting table, anvil, etc.) is registered against its own,
different type constant entirely (`FURNACE`, `CRAFTING`, `ANVIL`, ...) — a
factory registered only for `GENERIC_9x*`/`SHULKER_BOX` is never even
called for those screens; there is no runtime
conditional to get wrong, the routing itself is exhaustive and disjoint by
construction, the same "safety by construction, not by convention" property
this repo already values (compare Freecam's own "entity collision excluded
by construction" design, `docs/specs/tweaks-no-rain-freecam.md` Architecture
T14, "never invoked" not "flag checked").

### Aggregation/rendering cost model

Per Requirements R6/R14, grouping/totals are recomputed fresh every render
pass directly from the container's own live `Slot` list — no incremental
cache carried between frames (matches every render-hook tweak's existing
"poll fresh" discipline, `tweaks.md` F6). Container slot counts are small
(max 54 for a double chest; every other eligible type is smaller), so this
is a cheap O(container slot count) pass per frame while a `LootBinScreen` is
open, not a hot path shared with normal gameplay rendering (see Performance).

## UI

- **Tweaks tab row.** "Loot Bin UI" (or whatever display string the
  translation key resolves to) appears in the Tweaks tab row list with the
  standard checkbox, "Bind" (master toggle) control, and — new, per Public
  API's flagged framework touch — a second "Bind" control for the secondary
  grouped/vanilla-grid-toggle binding, matching Anti-Drop's own two-binding
  row precedent exactly (the only other tweak with `hasSecondaryKeyBinding()
  = true`).
- **Config screen.** Six rows (Requirements R3) via the existing generic,
  data-driven config-screen mechanism (`docs/specs/tweaks-panel-config-
  screen.md`) — three `BOOLEAN` checkbox rows (per-container-type-family
  toggles, R2, plus `showSearchBar`), three `ENUM` "pill"/cycle rows
  (`groupOrder`, `sortWithinGroup`, `countTextStyle`) — no new widget kind.
- **In-game Loot Bin panel.** A minimal, dark, semi-transparent,
  vanilla-palette-adjacent panel (R8, resolved per Open Questions #3) —
  exactly vanilla's own inventory/container UI width (never wider, never a
  bespoke custom width), and only modestly, never much, taller than
  vanilla's largest container UI (double chest / `GENERIC_9x6`) — not this
  mod's own `TweaksPanel` main-menu chrome — meant to blend into normal
  gameplay rather than read as a menu screen. Scrollable list of
  group-header + entry rows for the container's own slots; below that, a
  divider strip (R16) — not the bottom edge of the scrollable list itself
  — holds the search field (when `showSearchBar` is on) and a
  container-fullness indicator (e.g. `"42/72 slots"`, always shown
  regardless of `showSearchBar`); the player's own inventory/hotbar area
  beneath that strip renders exactly as vanilla's default container-screen
  layout already does (R9), unchanged.
- **Secondary-hotkey feedback.** When the secondary binding flips
  `groupedViewActive` (R15), the screen should visibly indicate which mode
  is active (e.g. a small mode label in a corner of the panel) so the player
  isn't confused about why clicks suddenly behave differently — exact visual
  treatment is an implementation-time detail, not fixed further here.

## Configuration

New top-level entry under the existing `tweaks.json`'s `"tweaks"` key
(`TweaksConfigIO`'s existing forward-compatible/backfill contract,
`features/tweaks/.../config/TweaksConfigIO.java:32-36`), no schema change:

```json
{
  "tweaks": {
    "LOOT_BIN": {
      "enabled": false,
      "configurables": {
        "applyToChestFamily": true,
        "applyToShulkerBox": true,
        "groupOrder": "CREATIVE_TAB_ORDER",
        "sortWithinGroup": "CREATIVE_ORDER",
        "showSearchBar": true,
        "countTextStyle": "PLAIN"
      }
    }
  }
}
```

Any pre-existing `tweaks.json` written before this tweak shipped simply has
no `"LOOT_BIN"` entry; on next load it's backfilled with
`TweakDefinitions.LOOT_BIN.defaultState()`, the same missing-`TweakId`
fallback path `NO_RAIN`/`FREECAM`/`COMPASS` already exercised (see Compass
spec's own Configuration/Migration note and Planning Prerequisites item 3 for
this same not-yet-independently-re-verified `TweaksConfigIO` parse-path
assumption, carried forward unchanged here). `groupedViewActive` (R15) is
never persisted — session/screen-instance-only state, not part of
`TweakState`.

## Events

No new event bus. `TweakRegistry.setEnabled`/`setConfigurable` write-through
to `tweaks.json` immediately (existing mechanism). `LootBinScreenRegistration`
registers its factories once at client init (Architecture) — not a per-tick
or per-event registration. The secondary-hotkey poll (R15) happens inside
`LootBinScreen`'s own render/tick, not a global `ClientTickEvents`
registration, since it's meaningless outside that screen's own lifetime.

## Networking

**No new packet, no server-side component.** Every interaction this tweak
allows (R13) issues the exact same `ServerboundContainerClickPacket`/
`ClickSlotC2SPacket` traffic vanilla's own default click handling would
already send for a raw click on that same one real slot — this tweak never
skips, batches, reorders, adds to, or replaces that traffic; it only decides
*which* real slot and *which* `SlotActionType`/`ClickType`+button a given
player gesture maps to, then hands off to the exact same vanilla method
(`onMouseClick`/`slotClicked`, Architecture) that would send that packet for
a manual click on that slot. From the server's own perspective the resulting
packet stream is indistinguishable from a player manually clicking the real
grid — this is the literal mechanism by which R11/the anti-cheat-safety
requirement is satisfied, not just a claim about it. Works against a fully
vanilla, unmodified server (no mod-side server component of any kind, no
custom container type, no data query beyond what vanilla's own container-
open packet already sends the client).

## Persistence

Covered fully under Configuration — one new `TweakId`-keyed entry in the
existing `tweaks.json`, same load/fail-closed/write-through contract as
every other tweak. No new file, no new persistence mechanism, no persisted
per-screen state (R15's `groupedViewActive`, search text, and scroll
position are all session-only per Non-goals).

## Compatibility

- All three platform modules need this tweak. The vanilla surface it
  depends on (`HandledScreen`/`AbstractContainerScreen`, `ScreenHandler`/
  `AbstractContainerMenu`, `Slot`, `SlotActionType`/`ClickType`,
  `HandledScreens`/`MenuScreens` registration, `ScreenHandlerType`/`MenuType`
  constants) is core, long-stable inventory/container plumbing, not part of
  the render-state-extraction churn documented elsewhere in
  `.claude/context/minecraft.md` for HUD/camera code — expected (medium-high
  confidence) to be structurally similar across all three pins, unlike e.g.
  Freecam's FOV/camera surface. Still, per this repo's established
  convention, every method/class name in the Architecture table above must
  get its own independent `javap` confirmation per platform before
  implementation — nothing here is assumed identical without verification,
  especially the item-component-equality method (R5) and the creative-tab
  reverse-index accessor (R4), both of which touch newer, more
  actively-changed subsystems (the 1.20.5+ component rewrite; creative-tab
  internals have seen real churn historically).
- 26.1 vs 26.2: this repo already has one confirmed real divergence directly
  adjacent to this feature's own rendering needs — `GuiGraphicsExtractor`
  (used by this tweak's grouped-panel rendering, Architecture) is a real,
  present class in the 26.2 codebase (confirmed via `TweaksPanel.java`'s own
  import, `platform/fabric-26.2/.../mainmenu/TweaksPanel.java:13`); whether
  26.1 exposes an identically-shaped type or something else must be checked
  independently (26.1 has its own `TweaksPanel.java` copy already, per the
  file listing found this pass — confirm its actual GUI-drawing import
  before assuming parity).
- **Known, inherent limitation on modded/plugin servers**: because this
  tweak only ever observes the vanilla container-open protocol (type +
  slot count + title), a modded server's custom GUI built on a shared
  storage `ScreenHandlerType`/`MenuType` (e.g. a shop menu built on
  `GENERIC_9x3`) will still route through this tweak's grouped view when
  enabled — there is no way to distinguish "real storage" from "a menu that
  happens to reuse the same vanilla type" purely client-side. R15's
  secondary hotkey (instant fallback to vanilla-grid interaction without
  disabling the whole tweak) is the documented mitigation; per-server
  disabling is not built (Non-goals, matching every other tweak's existing
  "one global configuration, no per-server profile" scope limit,
  `docs/specs/tweaks.md` Non-goals).
- No dependency on any other shipped feature.

## Performance

- Grouping/aggregation (R6/R14) only runs while a `LootBinScreen` is the
  current screen and only over that container's own small slot range (max
  54) — zero cost when no such screen is open, and negligible cost per frame
  while one is (a linear scan plus a hash-map grouping pass over at most a
  few dozen `ItemStack`s, far smaller than e.g. Disable Animations/Particles'
  existing hot-path whitelist/blacklist lookups, `docs/specs/tweaks.md`
  Performance).
- The creative-tab reverse index (R4) is built once (lazily) and cached,
  not rebuilt per frame or per container-open — same "build once, reuse"
  discipline this repo already applies to other static lookup tables.
- Search-text filtering (R10) only runs while the search field is non-empty
  and only over the already-small per-frame group/entry list — negligible.
- Click resolution (R12/R13) runs once per player click, not per frame — a
  small linear scan over the matching real slots (at most a few dozen) to
  find the largest/smallest, then one ordinary `onMouseClick`/`slotClicked`
  call, the same cost any real click already pays.
- Screen-registration factories (Architecture) run once per container-open
  event (not per frame, not per tick) — the `isLootBinActive()`/family-
  configurable check they perform is the same cheap `TweakRegistry`
  `EnumMap` lookup every other hook already pays once per relevant event.

## Future Extensions

- Persisting search text and/or scroll position per-container (or globally)
  across opens, if repeatedly re-typing the same search proves annoying in
  practice — not requested, not built here (Non-goals).
- A denylist/allowlist by container title text for servers where the
  shared-`ScreenHandlerType` modded-GUI edge case (Compatibility) is a
  recurring annoyance — deliberately not built in v1 given the title-text
  reliability concerns already discussed (Non-goals); R15's secondary
  hotkey is the interim mitigation.
- Extending grouped-mode interaction to drag-distribute/double-click-collect
  (Non-goals) if ever requested — would need its own careful one-click/one-
  real-action design pass exactly like R11-R13's, not assumed automatically
  safe just because the read-only rendering side is already built.
- A "count text style" option showing per-item max-stack-relative info (e.g.
  "6.0 stacks") instead of the two styles this spec builds (`PLAIN`/
  `WITH_STACK_HINT`) — not requested.
- Applying the same grouped/searchable presentation to the player's own
  inventory panel (R9 currently leaves it fully vanilla) — explicitly out of
  scope per Non-goals/R9; would be a materially larger follow-up given the
  player's own inventory has hotbar/armor/offhand slots with their own
  vanilla semantics this tweak doesn't currently need to reason about.

## Open Questions / Recommendations (for user approval, not decided here)

1. **[RESOLVED] Right-click semantics — no "withdraw exactly one item"
   approximation; defers entirely to real vanilla right-click.** The
   original draft proposed resolving right-click to whichever real backing
   slot held the *smallest* non-zero quantity, as the closest single-click
   approximation to "withdraw exactly one item" achievable under R11's hard
   one-click constraint. The user corrected this framing directly: they do
   not want a forced "always withdraw exactly 1" approximation at all.
   Right-click must behave **exactly like real vanilla right-click**, full
   stop — matching the user's explicit instruction that "mouse interaction
   should stay unchanged" / "like vanilla" applies literally, including
   right-click's real half-stack behavior, not a paraphrase of it.
   **Resolution**: right-click now uses the same slot-resolution strategy as
   left-click (R12 — largest quantity, ties broken by lowest slot index) and
   issues one ordinary `PICKUP`/`PICKUP` button-1 call on that slot (R13);
   vanilla's own click-handling then does whatever real vanilla right-click
   already does for that slot's current contents (half-stack split onto an
   empty cursor, or its own "deposit 1 per click" behavior once the cursor
   already holds a matching item) — nothing new designed or approximated
   here.
2. **[RESOLVED] `applyToSmallStorage`/`applyToHopper` — dropped from scope
   entirely.** The user chose to drop dispenser/dropper (`GENERIC_3x3`) and
   hopper (`HOPPER`) from scope completely rather than merely default them
   off. R1/R2/R3/Configuration/UI/Architecture all now reflect only the two
   remaining families: `applyToChestFamily` and `applyToShulkerBox`.
3. **[RESOLVED] Panel visual style — minimal, vanilla-palette-adjacent dark
   panel, not this mod's main-menu chrome.** The user chose a minimal,
   dark, semi-transparent panel using vanilla-adjacent colors, rather than
   reusing `TweaksPanel`'s main-menu panel chrome — the goal is to blend
   into normal gameplay rather than look like a menu screen (R8/UI updated
   accordingly). **[RESOLVED, follow-up correction]** Panel width is
   **exactly** vanilla's own inventory/container UI width, not "just
   wider" than vanilla as an earlier draft of this spec incorrectly
   stated; panel height may be modestly, but not much, taller than
   vanilla's double-chest (`GENERIC_9x6`) UI height, with R8/R10/R16's
   scrolling accommodating any remaining content. Still genuinely
   unspecified and left as implementation-time detail: corner/border
   treatment.
4. **Secondary-hotkey mode-indicator treatment (UI)** — left as an
   implementation-time detail; flag if a specific treatment (label vs. icon
   vs. color shift) is wanted.

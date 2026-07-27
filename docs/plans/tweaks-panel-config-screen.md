# Implementation Plan: Tweaks Panel — Per-Tweak Config Screen

Spec: `docs/specs/tweaks-panel-config-screen.md` (approved, including §6 answers).

## 1. Existing implementation (facts recorded here; do not re-derive)

- `TweaksPanel.java` is byte-for-byte-ish triplicated in `platform/fabric-1.21.11`
  (Yarn: `TextRenderer`, `DrawContext`, `KeyBinding`, `KeyInput`, `MinecraftClient`),
  `platform/fabric-26.1` and `platform/fabric-26.2` (Mojmap: `Font`,
  `GuiGraphicsExtractor`, `KeyMapping`, `KeyEvent`, `Minecraft`; 26.1 and 26.2 copies are
  identical source). No shared source set. Full current source read from fabric-26.2's
  copy (constants `ROW_HEIGHT=22`, `CONFIGURABLE_ROW_HEIGHT=16`, `CONTENT_LEFT_PAD=8`,
  `SCROLL_STEP=16`; fields `bundle`, `expanded: Set<TweakId>`, `scrollOffset`,
  `armedBindTarget`, `armedIsSecondary`; methods `layout`, `configurableCount`, `render`,
  `renderRow`, `formatValue`, `defOf`, `displayName`, `mouseClicked`, `cycleConfigurable`,
  `keyPressed`, `isArmedForBind`, `mouseScrolled`).
- `TweakDefinitions.java` (`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/`)
  holds the 12 static `TweakDefinition` instances via a private `of(...)` factory
  returning an anonymous `TweakDefinition` (interface: `id()`, `translationKey()`,
  `defaultState()`, `hasSecondaryKeyBinding()`) plus a `map(Object... kv)` helper building
  `LinkedHashMap<String,Object>` defaults. `ALL: List<TweakDefinition>` and `byId(TweakId)`
  are the public surface. Defaults for all 12 tweaks are exactly as spec §3's table states.
- `TweakDefinition` interface lives in `api/src/main/java/de/lazuli/api/tweaks/` (not yet
  read in full — implementation phase must open it to see the exact interface shape
  before adding a method).
- `TweakState` (`api/.../TweakState.java`) is `record TweakState(boolean enabled,
  Map<String,Object> configurables)` with `configurable(key)`, `withEnabled`,
  `withConfigurable`. No changes needed/planned here.
- `TweakRegistry` (`features/tweaks/.../TweakRegistry.java`) exposes `all()`,
  `stateOf(id)`, `setEnabled(id,enabled)`, `setConfigurable(id,key,value)`. No changes
  needed/planned here.
- **Enum "known values" ground truth**, read directly from
  `platform/fabric-26.2/.../tweaks/TweakHooksImpl.java` (identical logic expected in the
  other two platforms' copies — implementation phase must verify each platform's own
  `TweakHooksImpl.java` matches before relying on it verbatim):
  - `CHAT_PLAYER_HEADS.position`: only `"AFTER"` is checked (`!"AFTER".equals(...)`
    means head-before); treat as **binary enum: `BEFORE`, `AFTER`** (matches default
    `"BEFORE"` in `TweakDefinitions`).
  - `DISABLE_ANIMATIONS.mode` / `DISABLE_PARTICLES.mode` / `DISABLE_BOSS_BARS.mode`: all
    three share `modeExcludes(...)`'s switch: **`ALL`, `WHITELIST`, `BLACKLIST`** (default
    `"ALL"`).
  - `HIDE_PLAYER_NAMES.mode`: own switch: **`GLOBAL`, `RANGE_INCLUSIVE`,
    `RANGE_EXCLUSIVE`** (default `"GLOBAL"`).
  - `CUSTOM_CROSSHAIR.colorMode`: **not read anywhere in `TweakHooksImpl`** (only
    `isCustomCrosshairActive()` is implemented for this hook; per that class's own
    Javadoc, crosshair styling configurables including `colorMode` are read directly by
    the platform's HUD crosshair renderer, not through the hook interface). No call site
    currently reads `colorMode` at all in the reviewed platform (26.2). Schema will ship
    `VANILLA` as the sole confirmed value plus the default; **do not invent additional
    enum values with no code backing them** — implementation phase should grep each
    platform's crosshair HUD mixin/renderer for any `colorMode` comparison before
    finalizing this list, and if none exists anywhere, ship `{"VANILLA"}` as the only
    known value (still lets the row render/cycle without breaking anything since only one
    option = no-op cycle) and flag it in the PR description as a value the renderer
    doesn't yet branch on.
- **Sub-screen swap precedent**: none exists (spec §4, confirmed). New pattern needed.
  Decision (see §3 below): use a simple local nullable field on `TweaksPanel`
  (`TweakId configuring`), mirroring the existing `armedBindTarget` single-nullable-field
  style already in the class — **not** a generalized `MainMenuStateMachine`-style
  abstraction. Rationale: `MainMenuStateMachine` exists to key an `EnumMap`/switch across
  7 sibling tabs system-wide; here there are exactly two sub-views (row-list, config) for
  one tab, and `armedBindTarget` already proves the codebase's convention for this exact
  shape of "nullable target = alternate mode" state. Introducing a second state-machine
  type for a binary toggle would be over-engineering relative to the existing convention.
- **Text-input precedent found** (spec claims none in `TweaksPanel` specifically — true —
  but a usable panel-level precedent exists elsewhere): `ServersPanel.java` owns an
  `EditBox searchBox` field, constructs it in `ServersPanel.init(Consumer<AbstractWidget>
  addWidget, x, y, width)`, and registers it via the passed-in `addWidget` callback.
  `MainMenuScreen.init()` calls `serversPanel.init(this::addRenderableWidget, panelX(),
  panelY(), panelWidth())`, i.e. the panel hands its `EditBox` to the **host `Screen`**
  (`MainMenuScreen` extends `Screen`), which then handles `charTyped`/`keyPressed`/render
  dispatch to it automatically via vanilla `Screen`'s renderable/`GuiEventListener` widget
  list — the panel does not need to hand-roll character input. `TweaksPanel` currently has
  **no `init(...)` method and is not wired into `MainMenuScreen.init()`** — this must be
  added (see §4).
- `AddServerModalScreen.java` (all 3 platforms) is the closest visual precedent for a
  labeled "Cancel"-style button: `Button.builder(Component.literal("Cancel"), b ->
  onCancel()).bounds(...)`, dark-tinted background fill, positioned via `width/2`/`height/2`
  math (this is a full-screen modal, not embedded in a panel, so its exact bounds math
  doesn't transfer, but its button-construction call shape does).
- `MainMenuScreen` dispatch shape (confirmed in fabric-26.2, same in other two platforms
  per triplication convention): `render` switches on `state.activeTab()` calling
  `panel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY)`; `mouseClicked` similarly
  calls `panel.mouseClicked(panelX(), panelY(), panelWidth(), h, event.x(), event.y())`
  inside an `if (state.activeTab() == MainMenuTab.X)` block; `keyPressed` has one bespoke
  line: `if (state.activeTab() == MainMenuTab.TWEAKS && tweaksPanel.isArmedForBind() &&
  tweaksPanel.keyPressed(event)) return true;`. Constructor wiring is at line ~116
  (`this.tweaksPanel = new TweaksPanel(tweaksBundle);`); `init()` is at line ~150.

## 2. New schema/metadata type design

Add a new **additive** type, `ConfigFieldSpec`, in the same package as `TweakDefinitions`
(`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/`), since §6 answer 2
requires it live "alongside `TweakDefinitions`" and it is feature-owned data, not a
cross-cutting API contract (`TweakState`/`TweakRegistry` stay unchanged per spec §5).

```
public record ConfigFieldSpec(
    String key,               // matches TweakState.configurables() key
    String label,              // display label for the row (can derive from key if no per-field override needed)
    Kind kind,                  // BOOLEAN, NUMERIC, ENUM, STRING_LIST
    List<String> enumValues,    // non-null only for Kind.ENUM; empty list otherwise
    double numericMin,          // used only for Kind.NUMERIC; Double.NaN sentinel if unbounded... (see below)
    double numericMax,
    double numericStep
) {
    public enum Kind { BOOLEAN, NUMERIC, ENUM, STRING_LIST }
}
```

Design notes:
- Numeric bounds: spec §3 flags several ranges as "implied by name, not enforced in code
  today" (Force Brightness 0.0-1.0, Clear Water opacity 0.0-1.0). Since these are *not*
  currently validated anywhere, the schema should document them as UI-level clamps only
  (the stepper widget clamps display/edit range) — this is additive UI polish, not a
  behavior change to `TweakRegistry`/hooks, consistent with spec §5's non-goals. Where no
  named implied range exists (e.g. `magnification`, `transitionDurationMs`,
  `gap`/`length`/`thickness`, `colorR/G/B`), use permissive but sane bounds inferred from
  the field's default and purpose (documented per-field in the table below) rather than
  leaving them unbounded, so the stepper has a sensible step size.
- `List<TweakDefinition>`-level: rather than adding a method to the `TweakDefinition`
  interface itself (which would touch `api/`), add a **sibling lookup**,
  `ConfigSchemas.fieldsFor(TweakId): List<ConfigFieldSpec>`, in the same
  `features/tweaks/services` package, mirroring `TweakDefinitions.byId`'s shape. This
  keeps the `api/tweaks` module's `TweakDefinition` interface untouched (spec §5: "unless
  a schema/metadata addition requires a new read-only accessor... that would be
  additive") — a same-package sibling class is a strictly smaller, equally-additive
  change with zero risk to `api/`'s public contract, so it is preferred over touching
  `TweakDefinition`.
- `ConfigSchemas.ALL: Map<TweakId, List<ConfigFieldSpec>>` built with one entry per tweak,
  ordered to match `TweakDefinitions`' own `map(...)` key order for that tweak (so the
  config screen's row order matches the tweak's documented field order in spec §3).

### Per-tweak field spec table (to hardcode in `ConfigSchemas`)

| TweakId | key | kind | params |
|---|---|---|---|
| ANTI_DROP | whitelist | STRING_LIST | — |
| ANTI_DROP | shiftQForceDrop | BOOLEAN | — |
| FORCE_BRIGHTNESS | minBrightness | NUMERIC | 0.0–1.0, step 0.05 |
| CHAT_FILTER | useBuiltInFilterList | BOOLEAN | — |
| CHAT_FILTER | customTerms | STRING_LIST | — |
| CHAT_PLAYER_HEADS | position | ENUM | `[BEFORE, AFTER]` |
| CUSTOM_CROSSHAIR | outline | BOOLEAN | — |
| CUSTOM_CROSSHAIR | gap | NUMERIC | 0.0–20.0, step 0.5 |
| CUSTOM_CROSSHAIR | length | NUMERIC | 1.0–30.0, step 0.5 |
| CUSTOM_CROSSHAIR | thickness | NUMERIC | 0.5–10.0, step 0.5 |
| CUSTOM_CROSSHAIR | centerDot | BOOLEAN | — |
| CUSTOM_CROSSHAIR | colorMode | ENUM | `[VANILLA]` (see §1 caveat — expand only if implementation phase finds a real call site) |
| CUSTOM_CROSSHAIR | colorR | NUMERIC | 0.0–255.0, step 5.0 |
| CUSTOM_CROSSHAIR | colorG | NUMERIC | 0.0–255.0, step 5.0 |
| CUSTOM_CROSSHAIR | colorB | NUMERIC | 0.0–255.0, step 5.0 |
| DISABLE_ANIMATIONS | mode | ENUM | `[ALL, WHITELIST, BLACKLIST]` |
| DISABLE_ANIMATIONS | list | STRING_LIST | — |
| DISABLE_PARTICLES | mode | ENUM | `[ALL, WHITELIST, BLACKLIST]` |
| DISABLE_PARTICLES | list | STRING_LIST | — |
| HIDE_PLAYER_NAMES | mode | ENUM | `[GLOBAL, RANGE_INCLUSIVE, RANGE_EXCLUSIVE]` |
| HIDE_PLAYER_NAMES | range | NUMERIC | 0.0–64.0, step 1.0 |
| CLEAR_WATER | opacity | NUMERIC | 0.0–1.0, step 0.05 |
| DISABLE_COSMETICS | HEAD | BOOLEAN | — |
| DISABLE_COSMETICS | TORSO | BOOLEAN | — |
| DISABLE_COSMETICS | LEGS | BOOLEAN | — |
| DISABLE_COSMETICS | FEET | BOOLEAN | — |
| ZOOM | holdToZoom | BOOLEAN | — |
| ZOOM | transition | BOOLEAN | — |
| ZOOM | transitionDurationMs | NUMERIC | 0.0–1000.0, step 50.0 |
| ZOOM | magnification | NUMERIC | 1.0–10.0, step 0.5 |
| ZOOM | scrollToAdjust | BOOLEAN | — |
| DISABLE_BOSS_BARS | mode | ENUM | `[ALL, WHITELIST, BLACKLIST]` |
| DISABLE_BOSS_BARS | list | STRING_LIST | — |
| DISABLE_BOSS_BARS | keepRaidBarsVisible | BOOLEAN | — |

No dedicated widget kind for Disable Cosmetics' 4 slots (per spec §3, four BOOLEAN rows
suffice) — no `Kind.GRID` needed, keeping the widget-kind set to exactly four: BOOLEAN,
NUMERIC, ENUM, STRING_LIST.

## 3. Generic config-screen mechanism

Add to each platform's `TweaksPanel.java`:
- New field `private TweakId configuring;` (null = row-list view, non-null = config
  screen for that tweak is active) — mirrors `armedBindTarget`'s existing style per the
  decision in §1.
- New field `private final Map<TweakId, List<StringListEditState>> ...` — not needed if
  string-list edit state is scoped to a small nested class instantiated only while
  `configuring != null` (see §4) — avoids polluting `TweaksPanel`'s own field list with
  state that's only relevant while on the config screen.
- `render(...)`: at the top, branch — `if (configuring != null) { renderConfigScreen(...);
  return; }` else existing row-list body unchanged.
- `mouseClicked(...)`: same top-of-method branch to `configScreenMouseClicked(...)`.
- Row click handling (existing `mouseClicked` loop): replace the current chevron-hit-test
  block (`chevronX`/`expanded.add`/`expanded.remove`) with: **any click on the row body**
  outside the checkbox hitbox, bind-button hitbox, and (if present) secondary-bind-label
  hitbox sets `configuring = row.id()` and returns true. The `expanded: Set<TweakId>`
  field, `layout()`'s expansion-aware Y-math, `configurableCount`, `renderRow`'s inline
  configurable-line block, and `formatValue`/`cycleConfigurable` become dead code and are
  **deleted** (row list no longer inline-expands — spec §2 "Body...replaces the panel's
  entire rendered/interactive content").
- `keyPressed(...)`: existing bind-capture branch (`armedBindTarget`) stays first/as-is
  (spec: "preserve armedBindTarget bind-capture flow"); add a new branch for string-list
  text-entry `EditBox`es only reachable while `configuring != null` — see §4's approach
  (relies on `MainMenuScreen`'s own widget-list dispatch rather than `TweaksPanel`
  hand-rolling `charTyped`, consistent with the `ServersPanel` precedent in §1).
- New `TweaksPanel.init(Consumer<AbstractWidget> addWidget, int x, int y, int width)`
  method (does not currently exist — new, mirroring `ServersPanel.init`'s signature) that
  stores the `addWidget` callback for later use when entering the config screen (string
  list "add" rows need to create an `EditBox` on demand, added via this same callback,
  when the user clicks "+" — not just once at panel-init time, since the fields being
  edited change per tweak). `MainMenuScreen.init()` gains one new line:
  `tweaksPanel.init(this::addRenderableWidget, panelX(), panelY(), panelWidth());`
  (matches the existing `serversPanel.init(...)`/`worldsPanel.init(...)` calls already
  there).
- Config screen layout: heading (reuse existing `displayName(TweakId)` — already
  `static`, callable as-is), a back button (see §4), then one row per
  `ConfigSchemas.fieldsFor(id)` entry, each dispatched to a per-`Kind` render/click
  helper (§4). Returning to row-list (back button, or Escape — optional nicety, not
  required by spec) sets `configuring = null`; per spec §2 "avoid resetting
  `scrollOffset` unnecessarily" — `scrollOffset` is a `TweaksPanel` field untouched by
  entering/leaving the config screen, so this falls out for free as long as no code path
  resets it on that transition (verify in implementation).

## 4. New generic widget rendering/click-handling per kind

All four kinds share row geometry: reuse `CONFIGURABLE_ROW_HEIGHT = 16` for BOOLEAN/
NUMERIC/ENUM rows; STRING_LIST rows need variable height (one sub-row per list entry plus
one "+" add row) — compute per-tweak row list layout analogously to the existing
`layout()` method's Y-accumulation pattern (a new `configLayout(TweakId)` returning a list
of row records with computed Y, one per `ConfigFieldSpec`, expanding STRING_LIST rows by
`(list.size() + 1) * CONFIGURABLE_ROW_HEIGHT`).

- **BOOLEAN**: label + `[x]`/`[ ]` marker, click anywhere on row toggles via
  `bundle.registry().setConfigurable(id, key, !current)`. Directly reuses existing
  `cycleConfigurable`'s Boolean-branch logic inline (no need to keep the old dispatcher
  method, just its case).
  - **STRING_LIST empty-and-non-`ALL`-relevant edge case**: for `list` fields on the three
    mode-based tweaks (Disable Animations/Particles/Boss Bars), the `list` row remains
    always-visible in this design regardless of current `mode` value — the spec's inline
    caveat ("presumably only relevant when...") is explicitly non-binding, so per simplest
    reading of §2 ("one row per entry in `state.configurables()`") every configurable key
    always gets a row; no conditional show/hide logic is added (keeps the mechanism
    generic/data-driven rather than needing tweak-specific conditional wiring, which would
    contradict §2's "one generic mechanism" requirement).
- **NUMERIC**: label + current value formatted (reuse `String.valueOf`/simple
  `%.2f`-style formatting — implementation detail), two `-`/`+` step affordances (text
  glyphs, not vanilla `Button` widgets, consistent with existing hand-rolled rendering
  style throughout `TweaksPanel`) at fixed row-relative X offsets; click on `-`/`+`
  subtracts/adds `numericStep`, clamped to `[numericMin, numericMax]`; value stored as
  whatever numeric type it already is — read `current` via `state.configurable(key)`,
  branch `Double`/`Integer` same as existing `cycleConfigurable` did, to avoid silently
  changing a field's boxed type (e.g. don't turn an `Integer` into a `Double`).
- **ENUM**: label + current value; click cycles to
  `enumValues.get((enumValues.indexOf(current) + 1) % enumValues.size())`; if current
  value isn't found in `enumValues` (defensive — e.g. hand-edited `tweaks.json`), treat as
  index -1 so click lands on `enumValues.get(0)`.
- **STRING_LIST**: label header row, then one row per existing list entry showing the
  text plus a `[x]` remove affordance (click removes that entry, calls
  `setConfigurable(id, key, updatedList)`), then a final "+ Add" row. Clicking "+ Add"
  creates (via the `addWidget` callback stored from `init`) a single-line `EditBox`
  positioned at that row's Y, plus tracks "currently adding to key K" in a small
  transient field (e.g. `private String addingToKey;` cleared on commit/cancel); pressing
  Enter (via `TweaksPanel.keyPressed` — must special-case `GLFW_KEY_ENTER`/`KEY_KP_ENTER`
  when `addingToKey != null` before falling through to the existing armed-bind check, and
  must NOT treat Enter as a bind-capture key) or clicking away commits the `EditBox`'s
  non-blank value by appending to the list, removes the `EditBox` (`removeWidget` — needs
  a `Consumer<AbstractWidget> removeWidget` passed into `init` alongside `addWidget`, or a
  single `BiConsumer`-style pair), and clears `addingToKey`. Escape while `addingToKey !=
  null` cancels without committing (must be checked before the existing
  `armedBindTarget`-Escape branch, or combined into one Escape handler that checks
  `addingToKey` first).
  - This is genuinely new interaction surface for `TweaksPanel` (spec §6 answer 3
    confirmed in-scope); it is the most novel/highest-risk piece of this plan (see
    Risks).

Back button: a small vanilla `Button.builder(Component.literal("< Back"), b -> {
configuring = null; }).bounds(x + CONTENT_LEFT_PAD, y + <headingRowY>, 60, 16).build()`,
added/removed via the same `addWidget`/`removeWidget` callback pair whenever `configuring`
transitions to/from non-null (created lazily when entering the config screen, removed
when leaving) — matches `AddServerModalScreen`'s `Button.builder(...Cancel...)` call shape
per §1's precedent, satisfying §6 answer 4's "use judgment, informed by
`AddServerModalScreen`'s cancel-button conventions." Using a real vanilla `Button` (rather
than a hand-rolled clickable text region like the existing chevron was) is deliberate: it
gets correct hover state and click dispatch for free via `Screen`'s widget list, same
justification as the `EditBox` choice above.

## 5. File-by-file changes

**Create:**
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigFieldSpec.java`
  — new record + nested `Kind` enum (§2).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
  — new `Map<TweakId, List<ConfigFieldSpec>> ALL` + `fieldsFor(TweakId)` lookup (§2),
  hardcoding the table in §2.

**Modify (all three, parallel changes — no shared source set, per §1):**
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`
- `platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`
- `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`
  - Each: add `configuring`/`addingToKey` fields, `init(...)`, config-screen render/click
    dispatch, per-kind widget helpers, back-button lifecycle, string-list `EditBox`
    lifecycle; delete `expanded`, `configurableCount`, inline-expansion block in
    `renderRow`, `cycleConfigurable`, and the old chevron hit-test in `mouseClicked`.
  - Per-mapping API differences to handle identically in intent, differently in exact
    type names, in each copy:
    - fabric-1.21.11 (Yarn): `TextRenderer` (not `Font`), `DrawContext` (not
      `GuiGraphicsExtractor` — check whether this platform has its own
      `GuiGraphicsExtractor`-equivalent wrapper or calls `DrawContext` methods directly;
      implementation phase must re-read this platform's actual `TweaksPanel.java` before
      writing code — this plan has only read fabric-26.2's copy in full), `KeyBinding`
      (not `KeyMapping`), `KeyInput` (not `KeyEvent`), `MinecraftClient` (not
      `Minecraft`). `net.minecraft.client.gui.widget.EditBoxWidget` or Yarn's equivalent
      `EditBox`-analog class name must be confirmed (Yarn mapping name may differ from
      Mojmap's `EditBox`) before implementation.
    - fabric-26.1 / fabric-26.2 (Mojmap): as read — `Font`, `GuiGraphicsExtractor`,
      `KeyMapping`, `KeyEvent`, `Minecraft`, `net.minecraft.client.gui.components.EditBox`,
      `net.minecraft.client.gui.components.Button`.
- `platform/fabric-1.21.11/.../mainmenu/MainMenuScreen.java`
- `platform/fabric-26.1/.../mainmenu/MainMenuScreen.java`
- `platform/fabric-26.2/.../mainmenu/MainMenuScreen.java`
  - Each: add one line in `init()`: `tweaksPanel.init(this::addRenderableWidget,
    panelX(), panelY(), panelWidth());` (and a matching `removeWidget` callback if the
    back-button/EditBox lifecycle needs explicit removal — confirm `this::removeWidget`
    exists on this `Screen` subclass, already used by `closeContextMenu()` per §1's
    finding at line ~143).

**Unmodified (confirmed, per spec §5 non-goals and §1 findings):** `TweakState.java`,
`TweakRegistry.java`, `TweakDefinition` interface, `TweakHooksImpl.java` (all 3
platforms), `TweaksBundle`, `tweaks.json`/`TweaksConfig` persistence.

## 6. Dependencies

No new external (non-Fabric, non-Minecraft) dependencies are needed — every type used
(`ConfigFieldSpec`, `ConfigSchemas`, vanilla `EditBox`/`Button`) is either new
project-owned code or an existing vanilla/Fabric API type already imported elsewhere in
the same platform modules (`EditBox` and `Button` already imported in
`AddServerModalScreen.java`/`ServersPanel.java` on all three platforms). No Maven Central
lookup required.

Sequencing:
1. `ConfigFieldSpec` + `ConfigSchemas` first (features/tweaks module) — no compile
   dependency on any platform code, can be built and (lightly) unit-tested standalone.
2. One platform's `TweaksPanel.java`/`MainMenuScreen.java` (pick fabric-26.2 as the
   Mojmap reference implementation since it's the one fully read for this plan) — proves
   out the render/click/EditBox/Button lifecycle mechanism.
3. Port the proven mechanism to fabric-26.1 (near-identical to 26.2 per §1).
4. Port to fabric-1.21.11 last (different mapping names — highest chance of needing
   mapping-specific adjustments, e.g. confirming the Yarn `EditBox`-equivalent class
   actually exists with matching constructor signature before assuming a 1:1 port).

## 7. Risks

1. **String-list `EditBox` add/remove is genuinely new interaction surface** for
   `TweaksPanel` — no existing precedent for *dynamically* creating/removing widgets
   mid-session in response to a click within this class (the `ServersPanel` `EditBox`
   precedent in §1 is created once in `init()`, not on-demand). Get the
   `addWidget`/`removeWidget` lifecycle wrong (e.g. leaking a stale `EditBox` across a
   `configuring` transition, or across a tab switch away from Tweaks) and stray input
   fields could linger or throw. Mitigation: always clear `addingToKey` and remove any
   live `EditBox` when `configuring` transitions to `null`, and additionally when
   `MainMenuScreen` switches tabs away from `TWEAKS` (check
   `state.activeTab()`-change handling — may need a new explicit "deactivate" hook on
   `TweaksPanel`, analogous to `serversPanel.deactivateBrowser()`/`setTabActive(...)`
   already used for other panels per §1's `MainMenuScreen` grep).
2. **Yarn-mapping `EditBox` equivalent unconfirmed.** fabric-1.21.11 has not been read in
   this planning pass; if its `TweaksPanel.java`/other panels don't already use an
   `EditBox`-equivalent widget under Yarn mappings, implementation phase must locate the
   correct Yarn class name and constructor before porting step 4 above.
3. **Numeric bounds are inferred, not spec-mandated**, for several fields (`gap`,
   `length`, `thickness`, `magnification`, `transitionDurationMs`, `colorR/G/B`) — spec
   explicitly flags these as "not enforced in code today." Wrong bounds are a UI/UX
   nit (over/under-permissive stepper range), not a correctness bug, but should be called
   out in the PR description as a judgment call open to adjustment.
4. **`CUSTOM_CROSSHAIR.colorMode` has no confirmed call site reading it** anywhere
   found in the reviewed platform — shipping only `[VANILLA]` as the enum's known-values
   list means the ENUM widget technically works (renders, no-op cycle) but doesn't yet
   do anything visible, consistent with `TweakHooksImpl`'s own Javadoc caveat that
   render/input call sites for several tweaks are deferred to a later batch. Not a
   regression — matches already-documented current-state limitations — but worth flagging
   so it isn't mistaken for a bug introduced by this change.
5. **Triplication drift risk**: any of the three `TweaksPanel.java`/`MainMenuScreen.java`
   copies could silently diverge during manual porting (steps 2-4). Mitigation: after all
   three are done, diff fabric-26.1's and fabric-26.2's final `TweaksPanel.java` against
   each other (expected near-identical, as they are today) as a sanity check;
   fabric-1.21.11's will differ in type names only — confirm no *logic* divergence by
   reading both side-by-side during verification.
6. **Row-list click-target regression**: replacing the old chevron-only click zone with
   "anywhere on the row body outside checkbox/bind/secondary-bind" broadens the clickable
   area significantly. Must re-verify none of the existing checkbox/bind-button/
   secondary-bind hitboxes' bounds shrink or get shadowed by the new whole-row handler
   (order of hit-tests in `mouseClicked` matters — checkbox/bind/secondary-bind checks
   must still run *before* the new whole-row-click fallback, matching the existing
   top-to-bottom `if`-chain structure already in the method).

## 8. Test strategy

- **Unit-testable** (no Minecraft client dependency): `ConfigSchemas.fieldsFor(TweakId)`
  returns non-empty, correctly-ordered lists matching each of the 12 `TweakDefinitions`
  entries' key sets exactly (same keys, same count) — a plain JUnit test in
  `features/tweaks`'s test source set (confirm existing test conventions/build.gradle
  test deps for that module before writing — not yet checked in this planning pass).
  Also unit-testable: NUMERIC clamp-math (pure function, extract `clamp(current, step,
  min, max)` as a static helper) and ENUM cycle-index-wrap math, if factored as small
  pure functions rather than inlined into the widget click handlers.
- **Not unit-testable / needs manual verification per platform** (client-rendering,
  requires a running game instance per the project's existing constraints — no launching
  Minecraft during remote control per project memory, so manual verification must happen
  in the user's own local session, not the agent's):
  - Row click enters config screen for all 12 tweaks; back button returns to row list at
    the same scroll position.
  - Each of the 4 widget kinds renders and responds to clicks correctly for at least one
    representative tweak per kind (e.g. Anti-Drop's `shiftQForceDrop` for BOOLEAN,
    Force Brightness's `minBrightness` for NUMERIC, Chat Player Heads' `position` for
    ENUM, Anti-Drop's `whitelist` for STRING_LIST add/remove).
  - String-list `EditBox` add flow: click "+", type text, Enter commits; Escape cancels;
    clicking away commits (or intentionally doesn't — confirm chosen behavior works as
    implemented); remove `[x]` correctly removes only the clicked entry.
  - Existing behavior not regressed: checkbox toggle, hotkey bind capture (including
    Anti-Drop's secondary bind), scroll behavior — all on the row list, unaffected by this
    change per spec §5.
  - Repeat the above across all three platform modules (different Minecraft/mapping
    versions) since there is no shared source set and each is a separate manual build.

## 9. Acceptance criteria

- [ ] All 12 `TweakId` values have a working config screen reachable by clicking their
      row body (outside checkbox/bind/secondary-bind hitboxes).
- [ ] Config screen shows the tweak's `displayName` as heading and a back button; row
      list's checkbox/name/bind/secondary-bind are not shown on the config screen.
- [ ] Config screen body renders exactly one row per `state.configurables()` entry, using
      the widget kind from `ConfigSchemas.fieldsFor(id)`.
- [ ] Boolean fields toggle on click; numeric fields step by their configured
      `numericStep` clamped to `[numericMin, numericMax]`; enum fields cycle through
      their fixed known-values list on click; string-list fields support both add (via
      `EditBox` entry) and remove (per-entry `[x]`) of single-line text rows.
- [ ] Back button returns to the row list without resetting `scrollOffset`.
- [ ] Row list's enable checkbox, hotkey bind capture (primary and Anti-Drop's
      secondary), and scroll behavior are unchanged from current behavior.
- [ ] No changes to `TweakState`, `TweakRegistry`'s read/write contract, `TweakHooksImpl`,
      or `tweaks.json`'s persisted shape (verified by diff / code review, not just
      runtime behavior).
- [ ] Schema (`ConfigFieldSpec`/`ConfigSchemas`) is genuinely data-driven and consumed
      identically by one generic config-screen mechanism — no per-tweak bespoke screen
      classes or per-tweak `if (id == TweakId.X)` branches in the render/click dispatch
      path (aside from the unavoidable `ConfigSchemas.fieldsFor(id)` lookup itself).
- [ ] Identical mechanism (modulo mapping-specific type names) is implemented in all
      three platform modules; no shared-source-set assumption was introduced that doesn't
      already exist.
- [ ] Enum known-values lists match `TweakHooksImpl`'s actual switch/equality logic per
      §1 of this plan (or are documented as provisional where no call site exists, e.g.
      `colorMode`).

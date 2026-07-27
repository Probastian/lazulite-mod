# Spec: Tweaks Panel — Per-Tweak Config Screen (replace inline expand)

## 1. Current behavior

`TweaksPanel` (one row per `TweakId`: checkbox, name, "Bind" hotkey control, optional
secondary bind for Anti-Drop's whitelist toggle) renders a chevron on each row. Clicking
the chevron toggles membership in a `Set<TweakId> expanded`; when a row is expanded,
`renderRow` inline-appends one text line per `state.configurables()` entry directly below
that row (`CONFIGURABLE_ROW_HEIGHT = 16`px each), and `layout()` grows subsequent rows'
Y-offset accordingly. Clicking a configurable line calls `cycleConfigurable`, which only
handles `Boolean` (flip) and `Double`/`Integer` (+1) — `String`/`List` values are **not
editable** from this UI today (comment: "editable via tweaks.json for now").

Row list / hotkey layout, enable checkbox, and bind-capture flow (`armedBindTarget`,
`keyPressed`) are unaffected by this change and must be preserved as-is.

**Per-platform duplication status**: `TweaksPanel.java` exists identically (same logic,
same field/method names, same 16px row math) in all three platform modules:
- `platform/fabric-1.21.11/.../mainmenu/TweaksPanel.java` (Yarn mappings: `TextRenderer`,
  `DrawContext`, `KeyBinding`, `KeyInput`, `MinecraftClient`)
- `platform/fabric-26.1/.../mainmenu/TweaksPanel.java` (Mojmap: `Font`,
  `GuiGraphicsExtractor`, `KeyMapping`, `KeyEvent`, `Minecraft`)
- `platform/fabric-26.2/.../mainmenu/TweaksPanel.java` (identical to fabric-26.1's copy,
  byte-for-byte same source)

There is no shared source set for this UI code — confirmed no common/`mainmenu` module
backs `TweaksPanel`. Any change here must be made three times (once per platform module),
consistent with every other panel class (`WardrobePanel`, `StorePanel`, etc.) which are
likewise triplicated per platform.

## 2. Desired behavior

Clicking a tweak row (anywhere on the row except the checkbox, bind control, or secondary
bind label — matching today's chevron-click semantics but now triggered by the row itself
since the chevron affordance goes away) replaces the panel's entire rendered/interactive
content with a dedicated config screen for that tweak:

- **Heading**: the tweak's display name (reuse `TweaksPanel.displayName(TweakId)`), where
  "Tweaks" currently renders.
- **Back button**: top-left of the content area; clicking it returns to the row list
  (same scroll position it had before, ideally — not a hard requirement, but avoid
  resetting `scrollOffset` unnecessarily).
- **Body**: one row per entry in `state.configurables()`, each rendered/interacted with by
  a **generic, pluggable widget** appropriate to that key's value type/semantics (see
  inventory below) — not the current flat "label: value, click-to-cycle" line.
- The row list's checkbox, hotkey bind row, and secondary-bind row are **not** shown on
  the config screen — only the configurable rows plus heading/back button. (Toggling
  enabled/disabled and rebinding stay on the row-list, per the "keep this exact
  row/hotkey layout" instruction.)
- This must be one generic mechanism reused by all 12 tweaks (see note in §3 on the
  "~13" count), not 12 bespoke screen implementations. Concretely this likely means: each
  `TweakDefinition` (or a new sibling metadata type) declares an ordered list of
  config-row descriptors (key, label, widget kind, and any widget-specific parameters
  such as enum options or numeric bounds), and the config screen is a single class that
  iterates those descriptors and dispatches to shared widget-rendering/click-handling code
  keyed by widget kind.

## 3. Tweak inventory — configurable() keys and implied widget types

`TweakId` currently has **12** members (`ANTI_DROP, FORCE_BRIGHTNESS, CHAT_FILTER,
CHAT_PLAYER_HEADS, CUSTOM_CROSSHAIR, DISABLE_ANIMATIONS, DISABLE_PARTICLES,
HIDE_PLAYER_NAMES, CLEAR_WATER, DISABLE_COSMETICS, ZOOM, DISABLE_BOSS_BARS`) — not 13.
The task's list also names "Disable Boss Bars" twice in spirit and omits Force Brightness
and Custom Crosshair, which do exist in code with their own configurables. All 12 are
confirmed in scope (see §6, answer 1).

| Tweak | Keys (default) | Implied widget |
|---|---|---|
| Anti-Drop | `whitelist: List<String>` (`[]`), `shiftQForceDrop: Boolean` (`true`) | whitelist → string-list editor (add/remove item rows); shiftQForceDrop → boolean toggle |
| Force Brightness | `minBrightness: Double` (`1.0`) | numeric slider/stepper (0.0–1.0 range implied by name, not enforced in code today) |
| Chat Filter | `useBuiltInFilterList: Boolean` (`true`), `customTerms: List<String>` (`[]`) | boolean toggle; string-list editor |
| Chat Player Heads | `position: String` (`"BEFORE"`) | enum/mode selector (cycles known string values — no enum type in code, just a raw String) |
| Custom Crosshair | `outline: Boolean`, `gap: Double`, `length: Double`, `thickness: Double`, `centerDot: Boolean`, `colorMode: String` (`"VANILLA"`), `colorR/G/B: Double` (0-255 each) | mix: booleans, numeric sliders, enum selector (colorMode), and a 3-channel numeric RGB group (could be a dedicated color-picker widget or three sliders) |
| Disable Animations | `mode: String` (`"ALL"`), `list: List<String>` (`[]`) | enum/mode selector; string-list editor (list is presumably only relevant/shown when mode implies a filtered subset) |
| Disable Particles | `mode: String` (`"ALL"`), `list: List<String>` (`[]`) | same shape as Disable Animations |
| Hide Player Names | `mode: String` (`"GLOBAL"`), `range: Double` (`16.0`) | enum/mode selector; numeric slider (presumably only relevant when mode is range-based) |
| Clear Water | `opacity: Double` (`0.0`) | numeric slider (0.0–1.0 implied) |
| Disable Cosmetics | `HEAD/TORSO/LEGS/FEET: Boolean` (all `false`) | per-slot boolean grid (4 toggle rows, or a 2x2/4-across grid) |
| Zoom | `holdToZoom: Boolean` (`true`), `transition: Boolean` (`true`), `transitionDurationMs: Double` (`150.0`), `magnification: Double` (`4.0`), `scrollToAdjust: Boolean` (`true`) | 3 boolean toggles; 2 numeric sliders |
| Disable Boss Bars | `mode: String` (`"ALL"`), `list: List<String>` (`[]`), `keepRaidBarsVisible: Boolean` (`false`) | enum/mode selector; string-list editor; boolean toggle |

**Widget kinds needed** (minimum generic set): boolean toggle, numeric slider/stepper
(Double, currently mapped via +1.0 cycling — a real slider or +/- stepper both plausible),
enum/mode selector (raw `String` values cycled through a *fixed known list* — see §6 answer
2 for the data-driven schema approach), string-list editor (add/remove text entries — full
add/remove editing, not read-only; see §6 answer 3), and per-slot boolean grid (Disable
Cosmetics — could reuse 4 individual boolean-toggle rows instead of a dedicated grid
widget, simplifying the generic set).

`TweakState.configurables()` is `Map<String, Object>` with no per-field static typing
(`TweakState`'s own Javadoc, api/src/.../TweakState.java, states this is a deliberate
tradeoff). `TweakRegistry.setConfigurable(TweakId, String, Object)` is the sole write path
and is type-agnostic — whatever `Object` a widget produces is what gets persisted. There is
currently **no schema/metadata type** describing widget kind, enum option lists, or numeric
bounds per key; `TweakDefinitions.java` only carries default values via untyped
`Map<String, Object>`. Introducing such a schema (widget kind + parameters per key) is
in scope as the "new generic input-widget type in the shared UI toolkit" the task
anticipates, since without it the config screen cannot know how to render/validate each
row. Per §6 answer 2, this schema must live as proper metadata alongside
`TweakDefinitions` (data-driven), not be hardcoded per-tweak in UI code.

## 4. Existing "sub-screen swap" conventions to reuse

**None found.** Searched `WardrobePanel`, `StorePanel`, `AchievementsPanel`,
`StatisticsPanel`, `HomePanel`, `WorldsPanel`, `ServersPanel` in
`platform/fabric-26.2/.../mainmenu/` (representative of all three platform modules) for
any existing "replace this panel's content with a detail/sub-screen plus a back
control" pattern. None exists: every one of these classes renders one flat scope of
content for its whole tab lifetime. The only "screen swap" precedent in this codebase is
at the **top level** — `MainMenuScreen` holds a `MainMenuStateMachine` keyed by
`MainMenuTab` and dispatches `render`/`mouseClicked`/etc. to the active tab's panel
instance (see `MainMenuScreen.java` lines ~228, ~340-369). `ServersPanel` opens
`AddServerModalScreen`/`DirectConnectModalScreen`, but those are separate top-level
vanilla `Screen` overlays (modal dialogs), not an in-panel content swap — a different
mechanism from what's being asked here (row list stays the *same screen*, just changes
what it renders/handles).

**Conclusion**: this feature introduces a new pattern for the codebase: a panel-internal
"sub-view" state (row-list vs. config-screen) analogous to, but one level below,
`MainMenuStateMachine`'s tab switching. The planner should decide whether to generalize
`MainMenuStateMachine`'s tab-state approach for reuse inside `TweaksPanel`, or use a
simpler local `TweakId selectedForConfig` field (mirroring the existing `armedBindTarget`
single-nullable-field style already used in `TweaksPanel` for bind-capture state).

## 5. Non-goals

- No changes to tweak business logic: `TweakHooksImpl` and individual hook classes
  (`AntiDropHook`, `ZoomHook`, `ClearWaterHook`, etc. in
  `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/`) are untouched.
- No changes to `TweakRegistry`'s read/write contract (`stateOf`, `setEnabled`,
  `setConfigurable`) — the config screen is a new *consumer* of this existing API, not a
  reason to change it, unless a schema/metadata addition (§3) requires a new read-only
  accessor (e.g. on `TweakDefinition`) — that would be additive, not a contract change.
  Metadata additions must be additive to keep the change UI/architecture-only.
- No change to the row-list itself: checkbox, name, "Bind"/secondary-bind controls,
  scroll behavior, and row visuals stay exactly as they are today. The only thing that
  changes about the row list is what a click on the row body (previously the chevron)
  does.
- No change to `tweaks.json` persistence format — configurables remain the same
  `Map<String, Object>` shape; only how they're *edited in-UI* changes.
- No new widget types beyond what's needed to cover the 12 tweaks' actual configurable
  shapes (§3) — e.g. no RGB color-picker widget unless the planner decides Custom
  Crosshair's `colorR/G/B` genuinely needs one over three plain sliders.

## 6. Open questions — resolved by user

1. **Count mismatch (scope)**: **Answered.** All 12 `TweakId` values are in scope,
   including `FORCE_BRIGHTNESS` and `CUSTOM_CROSSHAIR`.
2. **Enum "known values" source of truth**: **Answered.** Add a proper schema definition
   (valid values, numeric bounds, widget kind) alongside `TweakDefinitions`, rather than
   hardcoding per-tweak logic in UI code. This is a data-driven approach: the planner
   should design a metadata type (e.g. a `TweakConfigSchema`/`ConfigFieldSpec` sitting
   next to `TweakDefinitions.java`) that each tweak's definition supplies, and the generic
   config screen reads this schema to decide widget kind and valid values/bounds per key.
3. **String-list editor UX**: **Answered.** Full add/remove editing of single-line text
   rows is in scope (not read-only, not deferred) for `whitelist`, `customTerms`, and the
   `list` fields on mode-based tweaks.
4. **Back-button visual style**: **Answered.** No strong preference from the user — use
   judgment based on existing UI conventions in the codebase (e.g. modal cancel-button
   styling in `AddServerModalScreen` is the closest existing precedent, per §4).

**Spec status: APPROVED.** Proceed to planning phase.

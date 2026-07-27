# Spec: Tweaks Framework + Tweak Catalog

Status: specification only (no plan, no implementation code in this document).
Source ideas: `docs/idea-collection/tweaks/*.md` (README.md + 11 individual
tweak docs).

## Overview

"Tweaks" are toggleable, client-side-only mini-features that alter in-game
rendering/behavior (e.g. Zoom, Fullbright). This spec covers one cohesive
deliverable:

1. A **Tweaks framework**: a registry of tweak definitions, a new "Tweaks"
   tab in the existing main-menu tab bar (`de.lazuli.api.mainmenu.MainMenuTab`,
   `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java:12`) that lists
   every tweak with an on/off toggle plus its own configurables, a per-tweak
   hotkey-binding system built on vanilla Fabric `KeyBinding`s (not custom
   input polling — see Architecture), and JSON persistence of all tweak
   state.
2. **Eleven individual tweaks** specified as sub-features riding on that
   framework, sourced 1:1 from the docs listed below.

Source docs (all under `docs/idea-collection/tweaks/`): `anti-drop.md`,
`brightness.md`, `chat-filter.md`, `chat-player-heads.md`,
`custom-crosshair.md`, `disable-animations.md`, `disable-boss-bars.md` (=
Disable Boss Bars, T12), `clear-water.md` (= Clear Water, T9 — this file was
git-mv'd from the old, misnamed `disable-boss-bars.md`; the current
`disable-boss-bars.md` is a distinct, newly authored doc for an actual boss
bars tweak), `disable-cosmetics.md`, `disable-particles.md`,
`hide-player-names.md`, `zoom.md`.

## Goals

1. One generic `Tweak` model (id, display name, enabled flag, per-tweak
   configurables, optional bound `KeyBinding`) plus a `TweakRegistry` that
   all eleven tweaks plug into — adding a twelfth tweak later should not
   require framework changes.
2. A `TWEAKS` main-menu tab, following the same tab-bar pattern already
   established for `HOME`/`ACHIEVEMENTS`/`STATISTICS`
   (`api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java`), listing
   every registered tweak with a toggle and an expandable per-tweak
   configuration area.
3. A hotkey-binding system where each tweak's hotkey is a real, vanilla
   Fabric `KeyBinding` registered via `KeyBindingHelper` (`fabric-api`'s
   `fabric-key-binding-api-v1`), so it appears in vanilla's own `Controls`
   options screen exactly like any other keybind (e.g. sprint, sneak), and
   is simultaneously rebindable from a "Bind" control on that tweak's row in
   the Tweaks tab — both UIs mutate the same underlying `KeyBinding`
   instance, so they can never fall out of sync.
4. Persist all tweak enabled/disabled state and per-tweak configurables to
   disk, following this repo's existing hand-rolled-JSON config convention
   (`features/main-menu/.../config/WardrobeConfig.java`,
   `WardrobeConfigIO.java`). Hotkey bindings themselves persist through
   vanilla's own `options.txt` `KeyBinding` mechanism, not this mod's JSON
   (see Persistence).
5. Implement the eleven tweaks' actual in-game behavior per their source
   docs, each client-side-only (no server/networking component).

## Non-goals

- No server-side component of any kind — every tweak is a local, client-only
  render/input change, consistent with the source README's framing.
- No custom/duplicate input-polling keybind system — tweak hotkeys are
  vanilla `KeyBinding`s (Goal 3); this spec explicitly does not build a
  second, parallel binding mechanism.
- No anti-cheat/server-detection concerns — tweaks like Anti-Drop, Hide
  Player Names, Clear Water, Disable Particles, Disable Boss Bars etc. only
  change what is rendered/sent from input on the local client; this spec
  does not attempt to make any tweak resilient to server-side detection or
  to interoperate with server-side anti-cheat.
- No tweak-specific matchmaking of settings across worlds/servers (e.g. no
  per-server profiles) — one global configuration applies everywhere, unless
  a specific tweak doc says otherwise (none do).
- Custom Crosshair (T5) ships a reduced v1 scope only. Explicitly **out of
  scope** for this pass, deferred to a later feature pass (see Future
  Extensions): the full CS2-style shape configurator, independent per-line
  (top/bottom/left/right styled separately) configuration, dynamic/
  spread-reactive (moving) crosshair mode, and a live WYSIWYG preview widget
  in the config UI. T5's in-scope v1 configurables are enumerated in
  Requirements below.
- No per-tweak hold-vs-toggle framework generalization beyond what Zoom
  (T11) needs — Zoom's hold/toggle choice is a per-tweak user-facing
  configurable (Requirements T11), not a framework-wide binding-kind system;
  a future tweak wanting the same behavior would add its own equivalent
  configurable, not a new framework primitive (see Future Extensions).

## Requirements

### Framework requirements

- **F1. Tweak registry.** A static, compile-time-known list of `Tweak`
  definitions (id, display name/description, default-enabled flag, the
  concrete configurable fields it exposes, a statically-registered default
  `KeyBinding` with default key `GLFW.GLFW_KEY_UNKNOWN`/unbound). One
  registry entry per tweak below (T1–T12).
- **F2. Tweaks tab.** New `TWEAKS` member on `MainMenuTab`. Tab content is a
  scrollable list, one row per registered tweak: name, on/off toggle,
  "bind hotkey" control (mirrors/wraps the tweak's `KeyBinding`, see F3), and
  an expand/collapse affordance revealing that tweak's configurables (only
  when expanded, to keep the row list compact given some tweaks have several
  configurables — e.g. Zoom has 5, Anti-Drop has 3).
- **F3. Per-tweak hotkey binding via vanilla `KeyBinding`.** Each tweak that
  supports a hotkey (all eleven do) registers one vanilla `KeyBinding` at
  mod init via `KeyBindingHelper.registerKeyBinding` (Fabric API,
  `fabric-key-binding-api-v1`), grouped under a single mod-specific category
  string (e.g. `"key.categories.<modid>.tweaks"`) so all tweak hotkeys are
  visually grouped together in vanilla's `Controls` screen. Binding UI: the
  Tweaks tab's per-row "Bind" control reads/writes the *same* `KeyBinding`
  instance vanilla's `Controls` screen edits — implemented either by
  embedding vanilla's own `KeyBindingWidget`-equivalent bind-capture flow in
  the Tweaks tab row, or a thin custom capture control that calls
  `KeyBinding.setBoundKey(...)` + `KeyBinding.updateKeysByCode()` (Fabric/
  vanilla's own APIs for changing a binding at runtime) — either way, no
  second source of truth is created. Per-tick activation uses vanilla's own
  `KeyBinding.wasPressed()` (edge-triggered, once per physical press,
  already excluded from firing while a text field has focus by vanilla's own
  input-focus handling — no custom "is a text field focused" gating is
  needed, unlike a hand-rolled poll would require).
- **F4. Duplicate-binding policy.** Delegated entirely to vanilla's own
  `KeyBinding` conflict system: vanilla's `Controls` screen already
  highlights/red-flags a `KeyBinding` that collides with another
  `KeyBinding` (tweak-vs-tweak or tweak-vs-vanilla) and lets the player
  proceed anyway (vanilla allows shared bindings; it only warns). The
  Tweaks-tab bind control surfaces the same conflict warning vanilla's
  screen would show for that `KeyBinding` (if feasible to query vanilla's
  conflict state directly) or, at minimum, does not need to reimplement
  conflict detection — one `KeyBinding` registry (vanilla's own
  `KeyBinding.KEYBINDING_ARRAY`/equivalent) is the single source of truth
  for all conflict checks, tweak-vs-tweak included.
- **F5. Persistence.** All registry-instance state (enabled flags,
  configurable values) round-trips through one JSON file per the existing
  config convention: read-or-create-with-defaults on load, fail-closed to
  defaults with a human-readable warning on malformed content, write-through
  on every change (matching `WardrobeConfigIO`'s shape). Hotkey bindings are
  **not** part of this JSON — they persist via vanilla's own
  `options.txt`/`KeyBinding` save mechanism automatically, since they are
  real `KeyBinding`s (see Persistence).
- **F6. Live apply.** Toggling a tweak (via tab checkbox or hotkey) or
  changing a configurable takes effect immediately, no game/menu restart.
- **F7. Client-side-only enforcement.** No tweak requires or sends any
  network packet; every tweak operates purely on local render state, local
  input handling, or client-side world/entity data already available to the
  client (e.g. filtering what's rendered, not what's simulated).

### Individual tweak requirements

Each tweak below states: behavior (from its source doc), configurables (from
its source doc, verbatim), and this spec's default state.

- **T1. Anti-Drop** (`anti-drop.md`). When enabled, dropping a whitelisted
  item (Q / Ctrl+Q / drag-out-of-inventory) is intercepted client-side: the
  drop is cancelled and an action-bar/chat message explains the tweak
  prevented it. Configurables: (a) item whitelist (list of item ids/tags
  the tweak protects), (b) "Shift+Q forces the drop anyway" toggle, (c) a
  dedicated second `KeyBinding` (separate from the tweak's own on/off
  hotkey, per F3) to quick-toggle whitelist membership of the currently held
  or hovered-in-inventory item. Default: off, empty whitelist.
- **T2. Force Brightness** (`brightness.md`). When enabled, forces a minimum
  world/block-light brightness, producing a fullbright-style effect; default
  configuration is full fullbright. Configurable: Min. Brightness (0–1 or
  0–15 light-level scale, matching whatever internal brightness scale the
  client's gamma/lightmap pipeline actually exposes — confirm exact
  range/units during planning's `javap` pass across all three Minecraft
  versions before implementation, since lightmap plumbing is a classic
  per-version-divergence area per `.claude/context/minecraft.md`'s existing
  rows). Default: off; when enabled with no user override, Min. Brightness
  = maximum (full fullbright), per the doc's stated default.
- **T3. Chat Filter** (`chat-filter.md`). When enabled, hides
  slurs/prohibited terms in chat messages and on sign text, replacing
  matched substrings (e.g. with `***`). Configurables: (a) "use built-in
  filter list" toggle, (b) custom list of additional prohibited terms
  (freeform, case-insensitive substring match). Default: off.
- **T4. Show Player Heads in Chat** (`chat-player-heads.md`). When enabled,
  renders each player's head/skin-face icon next to their name in chat
  messages. Configurable: position — before or after the player name.
  Default: off, position = before name.
- **T5. Custom Crosshair** (`custom-crosshair.md`) — reduced v1 scope, see
  Non-goals for what is explicitly deferred. In-scope v1 configurables:
  (a) outline toggle, (b) gap, length, and thickness sliders applied
  uniformly to all 4 crosshair lines (single value each, not per-line), (c)
  center-dot toggle, (d) color mode — either custom RGB (via RGB sliders) or
  "vanilla" (color-inverted against background, matching vanilla's own
  crosshair blend mode). Default: off, vanilla crosshair unaffected.
- **T6. Disable Texture Animations** (`disable-animations.md`). When
  enabled, animated textures (e.g. water, lava, fire, portal) stop
  animating, rendering a single static frame. Configurables: mode — All,
  Whitelist, or Blacklist — plus the corresponding list of animated-texture
  identifiers to include/exclude. Default: off, mode = All.
- **T7. Disable Particles** (`disable-particles.md`). When enabled, filters
  which particle types are allowed to spawn/render client-side.
  Configurables: mode — All, Whitelist, or Blacklist — plus the
  corresponding list of particle type identifiers. Default: off, mode = All.
- **T8. Hide Player Names** (`hide-player-names.md`). When enabled, hides
  the floating name tag above other players' heads. Configurables: mode —
  Global (hide all), Range-Inclusive (hide only players within a configured
  distance), or Range-Exclusive (hide only players beyond a configured
  distance) — plus the numeric range value when either range mode is
  selected. Default: off, mode = Global.
- **T9. Clear Water** (`clear-water.md` — renamed via `git mv` from the
  file previously misnamed `disable-boss-bars.md`; this doc's body was
  always about the underwater overlay, not boss bars). When enabled,
  reduces/removes the blue underwater screen overlay so vision is clearer
  while submerged. Configurable: Opacity (0% = fully clear, 100% = vanilla
  default overlay). Default: off, Opacity = 0% when enabled.
- **T10. Disable Cosmetics** (`disable-cosmetics.md`). When enabled,
  suppresses rendering of this mod's own cosmetic system (the existing
  Wardrobe feature, `features/main-menu/.../config/WardrobeConfig.java`) per
  slot. Configurable: one toggle per `WardrobeSlot`
  (`api/src/main/java/de/lazuli/api/mainmenu/WardrobeSlot.java`) — disabling
  a slot hides only cosmetics equipped in that slot, not the whole
  Wardrobe/equip state. Default: off, all slots rendered normally. Note:
  this is the one tweak whose effect is entirely internal to this mod (it
  suppresses this mod's own renderer, not a vanilla one) rather than
  changing vanilla Minecraft rendering.
- **T11. Zoom** (`zoom.md`). When enabled (a bound hotkey is the primary
  interaction — see Public API/Requirements), narrows FOV to a magnified
  view. Configurables: (a) **"Hold to zoom" vs. "Toggle to zoom"** — a
  per-tweak user-facing configurable controlling whether the bound key must
  be held down to stay zoomed (release un-zooms) or pressed once to toggle
  zoom on/off; default **hold-to-zoom**, matching common Minecraft zoom-mod
  convention; (b) instant vs. transition (smooth animated zoom-in/out), (c)
  transition duration (ms, only relevant if transition mode is on), (d)
  zoom magnification (FOV multiplier/target), (e) "scroll to change zoom
  magnification" toggle (while zoomed, mouse wheel adjusts magnification
  live). Default: off; hold-to-zoom per (a) above.
- **T12. Disable Boss Bars** (`disable-boss-bars.md` — the current file at
  this path, distinct from the pre-rename doc now at `clear-water.md`; see
  T9's note). When enabled, hides boss bars (e.g. Ender Dragon, Wither, raid
  bars) from the top of the screen. Configurables: (a) filter mode — All,
  Whitelist, or Blacklist — matched by boss bar name/text, (b) "keep raid
  bars visible" toggle, exempting raid-omen/raid bars from hiding even when
  mode = All. Default: off, mode = All, keep-raid-bars-visible = off.

## Public API

New types under `api/src/main/java/de/lazuli/api/tweaks/` (mirrors the
existing `api/.../mainmenu/` package shape):

- `enum TweakId` — one constant per tweak (`ANTI_DROP`, `FORCE_BRIGHTNESS`,
  `CHAT_FILTER`, `CHAT_PLAYER_HEADS`, `CUSTOM_CROSSHAIR`,
  `DISABLE_ANIMATIONS`, `DISABLE_PARTICLES`, `HIDE_PLAYER_NAMES`,
  `CLEAR_WATER`, `DISABLE_COSMETICS`, `ZOOM`, `DISABLE_BOSS_BARS`).
- `record TweakState(boolean enabled, Map<String, Object> configurables)` —
  one instance per `TweakId`, the unit persisted and round-tripped by
  `TweaksConfigIO` (new class, same package/shape as `WardrobeConfigIO`).
  Note this no longer carries a binding field — bindings live in vanilla's
  own `KeyBinding` objects (see Architecture), not in `TweakState`.
- `interface TweakDefinition { TweakId id(); String translationKey();
  TweakState defaultState(); KeyBinding keyBinding(); }` (and, for T1's
  second hotkey, an optional `KeyBinding secondaryKeyBinding()` returning
  non-null only for Anti-Drop) — one static instance per tweak, held in a
  `TweakRegistry` (new class in `features/tweaks/.../services/`, same
  package-per-module split as `features/main-menu`).

Public read/write surface (consumed by platform modules' render/input
mixins, mirroring how `MainMenuHook`/`MainMenuTab` are consumed today):

- `TweakRegistry.all(): List<TweakDefinition>`
- `TweakRegistry.stateOf(TweakId): TweakState`
- `TweakRegistry.setEnabled(TweakId, boolean)`
- `TweakRegistry.setConfigurable(TweakId, String key, Object value)`
- `TweakRegistry.keyBindingOf(TweakId): KeyBinding` (returns the same
  `KeyBinding` instance registered with vanilla's `Controls` screen; both
  vanilla's screen and the Tweaks tab bind control read/write through this
  one instance)

## Architecture

- **New feature module** `features/tweaks` (add to `settings.gradle`
  alongside the existing `features:*` entries), holding:
  `services/TweakRegistry.java`, `services/TweakEngine.java` (applies
  enabled/configurable state to actual render/input hooks, one small
  strategy class per tweak, e.g. `AntiDropTweak`, `ZoomTweak`, ...),
  `config/TweaksConfig.java` + `TweaksConfigIO.java` (hand-rolled JSON,
  reusing the shared `MainMenuJson` parser — see relocation below).
- **Vanilla `KeyBinding` registration, not custom input polling.** Each
  tweak's hotkey is registered at mod init via
  `KeyBindingHelper.registerKeyBinding(new KeyBinding(...))` (Fabric API's
  `fabric-key-binding-api-v1`), with a translation key, default `InputUtil`
  key/mouse code (`GLFW.GLFW_KEY_UNKNOWN` = unbound by default, matching F1),
  and a shared category translation key so all tweak bindings group together
  in vanilla's `Controls` screen. Per-tick activation reads
  `KeyBinding.wasPressed()` from each platform module's existing per-tick
  client hook (the same hook vanilla itself uses to poll its own bindings —
  confirm exact call site per platform during planning, e.g.
  `MinecraftClient`/`Minecraft`'s own tick method, consistent with this
  repo's existing mixin-per-platform-module pattern). This supersedes any
  previous custom-poll design: there is exactly one binding registry
  (vanilla's), and the Tweaks tab's per-row "Bind" control is a thin UI
  wrapper that calls the same `KeyBinding` mutation API vanilla's own
  `Controls`/`KeyBindingWidget` screen uses, so a rebind from either surface
  is instantly reflected in the other (same object, not a synced copy).
  T11 Zoom additionally reads `KeyBinding.isDown()` each frame when its
  "Hold to zoom" configurable is active, rather than only edge-triggering
  on `wasPressed()`, to support the hold-to-zoom interaction (Requirements
  T11); "Toggle to zoom" mode uses `wasPressed()` edge-triggering like every
  other tweak's on/off toggle.
- **Per-tweak apply strategy.** Each `TweakEngine` sub-strategy hooks the
  smallest vanilla surface needed for its effect — e.g. T2 Force Brightness
  and T9 Clear Water likely need a render-pipeline mixin (lightmap /
  underwater overlay respectively); T6/T7 (animations/particles) likely need
  a registry-side filter hook (texture animation tick / particle spawn);
  T3/T4 (chat filter/heads) hook chat message construction/render; T8 (hide
  names) hooks the name-tag render call; T1 (anti-drop) hooks the
  drop-item input path; T5 (crosshair) replaces the crosshair HUD element;
  T10 (disable cosmetics) gates the existing Wardrobe renderer per-slot; T11
  (zoom) adjusts FOV each frame while active; T12 (disable boss bars) hooks
  the boss-bar HUD render/overlay call, filtering by name/text against
  mode + whitelist/blacklist, with raid bars exempted when "keep raid bars
  visible" is on. Each of these vanilla hook points diverges per-version
  (Yarn vs. Mojmap, and per the `.claude/context/minecraft.md` precedent,
  sometimes even between 26.1 and 26.2) — planning must run the same
  `javap`-first verification pass this repo already establishes as
  convention before writing any mixin, one pass per tweak's hook point, not
  assumed from memory.
- **`MainMenuJson` relocation to a shared module.** `MainMenuJson`
  currently lives at
  `features/main-menu/src/main/java/de/lazuli/features/mainmenu/config/MainMenuJson.java`
  (package `de.lazuli.features.mainmenu.config`), used today by
  `WardrobeConfigIO`, `StoreCatalogConfigIO`, and
  `MainMenuJoinHistoryConfigIO`, all within `features/main-menu`. Since
  `features/tweaks`' `TweaksConfigIO` needs the same hand-rolled JSON
  value-model/parser/writer, and `features/tweaks` must not take a direct
  dependency on `features/main-menu` (they are sibling feature modules with
  no other coupling), this spec moves `MainMenuJson` to the existing
  `common` module — `common/src/main/java/de/lazuli/common/config/MainMenuJson.java`
  (package `de.lazuli.common.config`), alongside `common`'s existing
  `de.lazuli.common.mainmenu` package
  (`common/src/main/java/de/lazuli/common/mainmenu/*.java`, already present
  in this repo). `features/main-menu`'s three existing `*ConfigIO` classes
  update their import from `de.lazuli.features.mainmenu.config.MainMenuJson`
  to `de.lazuli.common.config.MainMenuJson`; `features/tweaks` depends on
  `common` (already an established dependency direction — `features/*`
  modules depending on `common` is the existing pattern, unlike a
  `features/tweaks` → `features/main-menu` dependency, which this spec
  avoids). No behavior change to `MainMenuJson` itself, pure move + package
  rename. Confirm exact target package name (`de.lazuli.common.config` vs.
  co-locating under `de.lazuli.common.mainmenu`) during planning by checking
  `common`'s existing package conventions once more broadly.
- **Main-menu tab wiring** follows the existing pattern: `MainMenuTab.TWEAKS`
  added to the enum; the platform-specific `MainMenuScreen.java` (one per
  platform module) grows a new tab-content builder reading from
  `TweakRegistry.all()`, matching how `ACHIEVEMENTS`/`STATISTICS` tabs are
  already wired (exact panel class name(s) to confirm from
  `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/MainMenuScreen.java`
  during planning — not read in full during this specification pass; sizing
  budget was spent on tweak-content research instead. Planning should open
  this file first).

## UI

- Tab bar gains a "Tweaks" entry (icon TBD at planning/asset time), inserted
  after `STATISTICS` per current enum ordering (no doc specifies ordering;
  appended at the end is the least disruptive default).
- Tab body: vertical scrollable list, one row per tweak — checkbox, name,
  "Bind" button (shows current `KeyBinding`'s bound key/mouse button or
  "Unbound"; click to enter vanilla-style bind-capture mode, press any
  key/mouse button to bind, Escape cancels; a small "x" clears the binding
  via the same `KeyBinding` mutation vanilla's `Controls` screen uses), and
  a chevron/expand control. Because the Bind button writes through the same
  `KeyBinding` instance vanilla's own `Controls` screen shows, rebinding
  from either screen updates the other on next open/redraw.
- Expanded row reveals that tweak's configurables using existing main-menu
  widget conventions (sliders for numeric ranges like Zoom magnification/
  Force Brightness min-brightness/Clear Water opacity, text-list editors for
  whitelist/blacklist entries, radio/segmented control for mode enums like
  Hide Player Names' Global/Range-Inclusive/Range-Exclusive and Disable Boss
  Bars' All/Whitelist/Blacklist, a hold/toggle segmented control for Zoom's
  new configurable, RGB sliders for Custom Crosshair color).
- No bind-conflict modal is built by this feature — conflict surfacing (F4)
  is vanilla `Controls` screen behavior, inherited for free since tweak
  bindings are real `KeyBinding`s.

## Configuration

Single new JSON file, `tweaks.json`, in this mod's existing per-mod config
directory (same directory `WardrobeConfigIO`/`MainMenuJoinHistoryConfigIO`
write to — confirm exact path constant during planning, likely resolved via
whatever `MinecraftClient`/`Minecraft` config-dir accessor those existing
`ConfigIO` classes already use). This file holds enabled flags and
configurables only — **no hotkey/binding data**, since bindings are real
vanilla `KeyBinding`s persisted by vanilla's own `options.txt` mechanism.
Shape (illustrative, finalized during planning alongside the JSON schema
conventions `WardrobeConfigIO` already sets):

```json
{
  "tweaks": {
    "ANTI_DROP": {
      "enabled": false,
      "configurables": { "whitelist": [], "shiftQForceDrop": true }
    },
    "ZOOM": {
      "enabled": false,
      "configurables": {
        "holdToZoom": true, "transition": true, "transitionDurationMs": 150,
        "magnification": 4.0, "scrollToAdjust": true
      }
    },
    "DISABLE_BOSS_BARS": {
      "enabled": false,
      "configurables": { "mode": "ALL", "list": [], "keepRaidBarsVisible": false }
    }
  }
}
```

Fail-closed-to-defaults-with-warning on malformed content, matching
`WardrobeConfigIO`'s established contract exactly (Requirements F5).

## Events

No new cross-module event bus is introduced. `TweakRegistry` mutation
methods (`setEnabled`/`setConfigurable`) both mutate in-memory state and
trigger an immediate `TweaksConfigIO.save()` write-through (F5/F6) — no
separate pub/sub layer needed since the only listeners are: (a) the Tweaks
tab UI itself (re-reads registry state on its own render pass, no push
notification needed given main-menu screens already poll/rebuild per frame
per this repo's existing `MainMenuScreen` pattern), and (b) each
`TweakEngine` strategy, which reads current `TweakState` directly off
`TweakRegistry` each time it needs it (every render/tick), rather than
caching a stale copy. `KeyBinding` state itself (bound key, pressed/held)
is read directly off vanilla's `KeyBinding` object each tick — no event
needed there either, matching vanilla's own polling model.

## Networking

None. Every tweak and the framework itself are 100% client-side; no packet,
no server awareness, confirmed against every one of the source docs (none
mention multiplayer-synchronized behavior).

## Persistence

Two independent persistence paths:

1. Enabled flags + configurables: `tweaks.json`, covered under Configuration
   above — loaded once at client init (alongside this mod's other config
   loads), written through on every mutation. No migration path needed
   (net-new file, no prior schema version exists).
2. Hotkey bindings: vanilla's own `options.txt` `key_*` entries, written and
   loaded by vanilla's existing `KeyBinding`/`GameOptions` save/load code —
   this mod does not read or write these directly; registering the
   `KeyBinding` via `KeyBindingHelper` is sufficient for vanilla to persist
   it automatically, identical to how every other mod's Fabric keybindings
   persist.

## Compatibility

- All three platform modules (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`)
  need the tweak framework and all 12 tweaks; per-tweak vanilla hook points
  will diverge across the Yarn/Mojmap boundary and potentially between 26.1
  and 26.2 (see Architecture) — treat every tweak's render/input hook as an
  unconfirmed-until-`javap`'d item at planning time, per this repo's
  established convention (`.claude/context/minecraft.md`).
  `fabric-key-binding-api-v1` is confirmed to exist as a Fabric API module
  historically back to 1.19-era releases (via `KeyBindingHelper`/
  `KeyBinding.registerKeyBinding` docs, Fabric API version history), and is
  the mechanism this spec's architecture now builds on directly; confirm
  during planning that all three platform modules' `fabric.mod.json`/Gradle
  dependency sets already pull in (or need to add) this Fabric API submodule
  specifically (some platform setups depend on a bundled `fabric-api` umbrella
  artifact that already includes it; others pull individual submodules).
- T10 Disable Cosmetics depends on the existing Wardrobe feature
  (`WardrobeSlot`, `features/main-menu/.../config/WardrobeConfig.java`)
  already shipped in this repo — no new dependency, just an added gate.
- `features/tweaks` depends on the relocated `common`-module `MainMenuJson`
  (Architecture); `features/main-menu`'s existing `*ConfigIO` classes update
  their import accordingly — this is the only cross-feature-adjacent
  dependency introduced by this spec, and it is a shared-module dependency
  (both features → `common`), not a feature-to-feature dependency.
- No other tweak depends on another shipped feature.

## Performance

- `KeyBinding.wasPressed()`/`isDown()` polling is vanilla's own per-tick
  mechanism (already O(number of registered bindings), already how every
  vanilla and modded keybind works) — no additional performance
  consideration beyond what vanilla already pays for its own bindings.
- Whitelist/blacklist-mode tweaks (T6 Animations, T7 Particles, T12 Disable
  Boss Bars) must use a `Set`/hash lookup against the configured id/name
  list, not a linear scan, since T6/T7's hooks run on the hot
  per-tick/per-particle-spawn path (T12's boss-bar hook is comparatively
  low-frequency — at most a handful of active boss bars — so this is a
  correctness-consistency recommendation there, not a hot-path requirement).
- Render-hook tweaks (T2, T5, T8, T9, T10, T12) must add no allocation on
  the common per-frame path when disabled (an early-return `if (!enabled)
  return;` guard, consistent with how disabled optional features are
  typically gated in this codebase's mixins).

## Future Extensions

- Per-server/per-world tweak profiles (explicitly out of scope now, see
  Non-goals).
- Exporting/importing a tweak configuration as a shareable preset.
- Full CS2-style crosshair shape configurator for T5: per-line
  (top/bottom/left/right) independent styling, dynamic/spread-reactive
  (moving) crosshair mode, and a live WYSIWYG preview widget in the config
  UI — all explicitly deferred from this pass (see Non-goals).
- Extending the hold-vs-toggle configurable pattern introduced for Zoom
  (T11) to any future tweak that wants the same interaction, as its own
  per-tweak configurable (not a new framework primitive, per Non-goals).

## Open Questions / Recommendations (for user approval, not decided here)

1. **`MainMenuJson` target package under `common`.** This spec proposes
   `de.lazuli.common.config.MainMenuJson`, but `common` already has a
   `de.lazuli.common.mainmenu` package (mesh/part-name definitions) that
   could alternatively host it if the team prefers grouping by "things
   main-menu-adjacent features share" rather than by "config utilities."
   Either is a pure package-path decision with no behavioral difference —
   flagged for a quick call during planning, not blocking.
2. **Bind-capture widget implementation.** Architecture assumes the Tweaks
   tab's "Bind" control can either embed/reuse vanilla's own
   `KeyBindingWidget`-equivalent bind-capture flow, or implement a thin
   custom capture control that calls the same `KeyBinding` mutation methods.
   Which of these two is more practical given this repo's existing
   `MainMenuScreen` widget conventions should be confirmed during planning
   (likely requires opening `platform/fabric-26.2/.../MainMenuScreen.java`
   and checking what vanilla `Screen`/widget classes are already available
   to reuse in that context).
3. **Exact vanilla render/input hook point per tweak per platform module**
   — deliberately not resolved in this specification (would require a full
   `javap` pass across all three platform modules' resolved jars for 11
   different subsystems); planning's first step should be exactly that
   pass, per this repo's established convention, one tweak at a time.
4. **`fabric-key-binding-api-v1` availability per platform module** — flagged
   in Compatibility; confirm each platform module's Fabric API dependency
   declaration already covers this submodule before planning assumes it's
   free to use.
</content>

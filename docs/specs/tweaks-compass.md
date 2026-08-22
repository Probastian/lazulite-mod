# Spec: Compass (Tweaks Framework)

Status: specification only (no plan, no implementation code in this document).
Owner feature: `features/tweaks` (new `TweakId.COMPASS`), consumed by
`platform/fabric-26.2` only. Bridges to the existing Waypoints compass bar
mixin (`platform/fabric-26.2/.../mixin/HudWaypointCompassBarMixin.java`,
owned by neither `features/tweaks` nor `features/waypoints` but by the
platform module) without `features/tweaks` and `features/waypoints`
depending on each other, per `.claude/context/architecture.md`'s
`Feature -> Feature` ban.

## Overview

The Waypoints feature (`docs/specs/waypoints.md` R10-R18) shipped a compass
bar HUD element rendered above the hotbar
(`HudWaypointCompassBarMixin.java`, commits `840d49a`, `d0e32fd`, `5b3dc97`).
Today every visual choice on that bar — whether the bar draws at all, its
1px border, the N/E/S/W cardinal letters, and whether waypoint dots/
chevrons/name labels render — is a compile-time `private static final`
constant inside `CompassBarPainter`
(`HudWaypointCompassBarMixin.java:89-139`). There is no way for a player to
turn the bar off, hide waypoint dots independently of the ruler, or see a
numeric heading readout.

This spec adds a new **Compass** tweak to the existing Tweaks framework
(`docs/specs/tweaks.md`) that makes those choices player-configurable
through the standard Tweaks tab + per-tweak config screen
(`docs/specs/tweaks-panel-config-screen.md`), and adds one new capability
(a live numeric heading readout to the left of the bar) that didn't exist
before. It also fixes a positioning bug in the existing near-center
waypoint name label, discovered while reading the mixin for this pass (see
Requirements C6).

## Goals

1. A new `TweakId.COMPASS` tweak, following this codebase's established
   `TweakDefinition`/`ConfigFieldSpec`/`TweakHooksImpl` pattern exactly (no
   framework changes needed — this is a routine 13th tweak, formalizing the
   `T1-T12` labeling in `tweaks.md`'s prose as just descriptive numbering;
   the real `TweakId` enum uses plain names like `NO_RAIN`/`FREECAM` with no
   `T`-number, and `COMPASS` follows that same plain-name convention).
2. Master enable/disable: when the Compass tweak is disabled, the entire
   compass bar — background, border, ruler/ticks, cardinals, waypoint dots,
   chevrons, name label, heading readout — renders nothing.
3. Four independently configurable sub-options, all gated behind the tweak
   being enabled: waypoint-dot display, cardinal letters, a live heading
   readout left of the bar, and the border.
4. Live-apply: every configurable change (including the master toggle)
   takes effect on the very next rendered frame, matching `tweaks.md`
   F6/Events' existing "poll current state every call, no caching" pattern
   (`TweakHooksImpl`'s own Javadoc, `platform/fabric-26.2/.../tweaks/
   TweakHooksImpl.java:26-29`).
5. Fix the existing name-label positioning bug (Requirement C6) as part of
   this same pass, since it's directly adjacent to the code this spec
   otherwise touches and was found while reading it for this spec.

## Non-goals

- No changes to Waypoints' own CRUD, persistence, per-scope/per-dimension
  data model, or Cloud sync (`docs/specs/waypoints.md` R1-R9, R19-R23) —
  this tweak only gates/configures how the *already-existing* compass bar
  renders, it does not touch `WaypointRegistry`/`WaypointsConfigIO`/the
  Waypoint Manager panel.
- No new visual elements beyond the one explicitly requested (the heading
  readout) — bearing-to-pixel math (R11), dot distance-scaling (R12),
  elevation chevrons (R13), name-label near-center gating (R14, distinct
  from its positioning bug fix in C6), and color rendering (R15) are
  unchanged in behavior, only in what's now conditionally drawn.
- No multi-version work. Matching Waypoints' own current scope decision
  (`HudWaypointCompassBarMixin.java` exists only under `platform/fabric-26.2`
  today, per its class Javadoc's implicit single-platform status — no
  `fabric-1.21.11`/`fabric-26.1` equivalent has shipped yet), this tweak
  ships for `platform/fabric-26.2` only. If/when Waypoints' compass bar is
  ported to the other two platforms, this tweak's config plumbing
  (`TweakId`, `TweakDefinition`, `ConfigFieldSpec`, `TweakHooksImpl`
  interface) is already platform-agnostic and needs no rework — only a new
  per-platform mixin wiring, exactly like every other tweak's existing
  Compatibility note.
- No change to `TICK_STEP_DEGREES` (15°), `FOV_HALF_DEGREES` (±90°), dot
  min/max size, or any other numeric visual-design constant not explicitly
  named in Requirements below — those stay hardcoded exactly as-is.
- No new whitelist/blacklist/mode-selector configurable — all four
  sub-options are plain booleans (see Requirements), so this tweak needs no
  new `ConfigFieldSpec.Kind` beyond `BOOLEAN`, which already exists.

## Requirements

### Framework registration

- **C1. `TweakId.COMPASS`.** New enum constant appended to
  `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` (after `FREECAM`,
  matching the file's append-only ordering convention — confirmed no
  numeric/alphabetic reordering is done for existing entries when a new one
  is added, e.g. `NO_RAIN`/`FREECAM` were appended after the original 12,
  not alphabetized in).
- **C2. `TweakDefinition`.** New `TweakDefinitions.COMPASS` static instance
  (`features/tweaks/.../services/TweakDefinitions.java`), following the
  existing `of(TweakId, description, defaultConfigurables, hasSecondary)`
  factory shape exactly (`TweakDefinitions.java:16-118`):
  - `translationKey`: auto-derived as `"tweak.lazuli.compass"` (matches the
    existing `"tweak.lazuli." + id.name().toLowerCase()"` derivation,
    `TweakDefinitions.java:23`).
  - `description`: e.g. `"Configures the waypoint compass bar above the
    hotbar: waypoint dots, cardinal letters, heading readout, and border."`
  - `defaultConfigurables`: `showWaypoints=true, showCardinals=true,
    showHeadingReadout=false, showBorder=true` (see C3-C6 for why
    `showHeadingReadout` alone defaults off — see rationale under C5;
    every other default preserves the mixin's current hardcoded visual
    behavior exactly, so enabling this tweak for the first time changes
    nothing on-screen except adding the master on/off control).
  - `hasSecondary`: `false` (no secondary hotkey, matching every existing
    tweak except Anti-Drop).
  - Added to `TweakDefinitions.ALL` and `TweakId`-keyed lookups the same
    way `NO_RAIN`/`FREECAM` were (`TweakDefinitions.java:120-123`).
  - Default **enabled** state: `true` — unlike every other tweak (which
    default to off, since they change vanilla behavior a player must opt
    into), Compass's master toggle defaults to **on**, because it merely
    continues to render the compass bar Waypoints already ships
    unconditionally today; defaulting it off would be a silent regression
    for existing Waypoints users the moment this tweak ships (see C2's
    Configuration section for the exact default-state/migration
    interaction).
- **C3. `ConfigFieldSpec` schema.** New `ConfigSchemas.ALL` entry for
  `TweakId.COMPASS` (`ConfigSchemas.java:23-120`), four boolean rows, order
  matching the `defaultConfigurables` map's key order (existing convention,
  `ConfigSchemas.java`'s class Javadoc: "row order matches
  `TweakDefinitions`' own `map(...)` key order"):
  ```java
  ALL.put(TweakId.COMPASS, List.of(
      ConfigFieldSpec.bool("showWaypoints", "Show Waypoints"),
      ConfigFieldSpec.bool("showCardinals", "Show Cardinal Letters"),
      ConfigFieldSpec.bool("showHeadingReadout", "Show Heading Readout"),
      ConfigFieldSpec.bool("showBorder", "Show Border")
  ));
  ```
  No `NUMERIC`/`ENUM`/`STRING_LIST` fields needed — all four sub-options are
  plain on/off toggles (see C5 for why the heading *readout* is a toggle,
  not the heading *value* itself, which is computed live every frame, not
  configured).

### Mixin gating (`HudWaypointCompassBarMixin`)

- **C4. Master enable/disable short-circuit.** `CompassBarPainter.paint(...)`
  (`HudWaypointCompassBarMixin.java:141`) gains a new first check, before
  the existing zero-waypoint early return
  (`HudWaypointCompassBarMixin.java:148-153`): read
  `TweakEngineHandoff.require().isCompassActive()` (new hook method, see
  Public API) and return immediately if `false` — no background fill, no
  border, no ruler/ticks, no waypoint loop, no name label, no heading
  readout. This mirrors `HudCustomCrosshairMixin`'s existing
  `if (!hooks.isCustomCrosshairActive()) return;` gate exactly
  (`HudCustomCrosshairMixin.java:36-38`) — same "ask the hook, bail before
  any drawing" shape, just at the top of `paint()` instead of at the
  `@Inject` call site, since `paint()` (not the `@Inject` method itself) is
  where all of this mixin's actual draw calls live.
  - Placement note: this new gate goes *before* the zero-waypoint check
    (not after), since a disabled tweak must also suppress the ruler/border/
    heading readout even when waypoints exist — the existing zero-waypoint
    early return only ever suppressed the waypoint-dot loop implicitly (by
    virtue of returning before the ruler was drawn too), it was never a
    "waypoints-only" gate to begin with, so ordering the two checks
    tweak-gate-first, then waypoint-count-second, preserves both existing
    and new behavior correctly.
- **C5. `showWaypoints` — gates the waypoint loop only.** The `for (Waypoint
  waypoint : waypoints)` loop (`HudWaypointCompassBarMixin.java:180-213`,
  which draws dots R11/R12, chevrons R13, and populates `nearestCentered`
  for the name label R14) is skipped entirely when
  `!hooks.compassConfigurable("showWaypoints")`. The background fill,
  border (C7), and ruler/ticks/cardinals (C8) still draw normally — this
  lets a player show a "pure ruler" compass with no waypoint markers, per
  the user's explicit request that this be independent of the ruler.
  Because `nearestCentered` is only ever populated inside that same loop,
  skipping the loop also correctly skips the name-label draw
  (`HudWaypointCompassBarMixin.java:215-217`) with no separate check
  needed.
- **C6. `showCardinals` — replaces the `SHOW_CARDINALS` constant.**
  `drawRuler`'s existing `boolean cardinal = SHOW_CARDINALS ? ... : null`
  branch (`HudWaypointCompassBarMixin.java:254`) reads
  `hooks.compassConfigurable("showCardinals")` instead of the hardcoded
  constant. **Confirmed by reading the existing code**: when cardinals are
  off, that bearing position already falls through to the `else` branch
  (`HudWaypointCompassBarMixin.java:257-260`) and draws a normal
  `TICK_HEIGHT`-tall tick identical to every other 15°-step position — the
  "replace cardinal letters with an ordinary tick when off" behavior the
  user wants already exists exactly as-is in the current code; this
  requirement only changes *where the boolean comes from* (tweak
  configurable vs. hardcoded constant), not the branching logic itself.
- **C7. `showBorder` — replaces the `SHOW_BORDER` constant.** The existing
  `if (SHOW_BORDER) { drawBorder(...); }` check
  (`HudWaypointCompassBarMixin.java:163-165`) reads
  `hooks.compassConfigurable("showBorder")` instead. No other change —
  `drawBorder`'s own drawing logic is untouched.
- **C8. Heading readout — new feature.** When
  `hooks.compassConfigurable("showHeadingReadout")` is `true` (and the
  master toggle, C4, is on), draw a live numeric bearing readout to the
  left of the bar, updated every frame from the player's current
  `getYRot()` (same source `drawRuler`/the waypoint loop already read,
  `HudWaypointCompassBarMixin.java:167`).
  - **Bearing convention (see Open Questions for the full ambiguity
    writeup).** **Recommendation, adopted by this spec**: the readout uses
    the everyday "compass heading" convention players expect from any
    other game/app (`N=0°, E=90°, S=180°, W=270°`), **not** a raw reuse of
    this mixin's own internal world-bearing convention (`S=0, W=90, N=180,
    E=270`, confirmed at `HudWaypointCompassBarMixin.java:236` and
    `cardinalLabelFor`, `HudWaypointCompassBarMixin.java:292-300`), since
    the internal convention exists only because it happens to match
    `Entity.getLookAngle()`'s raw yaw math (the class Javadoc's own cited
    reasoning, `HudWaypointCompassBarMixin.java:236`) — a coincidence of
    this mixin's implementation, not something a player has any reason to
    expect from a *displayed number*. Conversion: `headingDeg =
    normalizeAngle(yaw + 180)` folded into `[0, 360)` (i.e. `((yaw + 180) %
    360 + 360) % 360`) — derived directly from the mixin's own documented
    yaw-to-cardinal mapping (yaw `0`→S(180°), yaw `180`→N(0°), yaw
    `90`→W(270°), yaw `270`/`-90`→E(90°); every one of those four checks
    against the conventional N=0/E=90/S=180/W=270 scale), not a new
    assumption.
  - **Format.** Integer degrees, no decimal, rendered as e.g. `"127°"`
    (`Math.round(headingDeg) + "°"`), using this codebase's existing
    degree-symbol convention if one exists elsewhere in the HUD (confirm
    during planning; otherwise plain `"°"` literal is fine, no localization
    concern beyond what any other HUD numeric readout in this codebase
    already has).
  - **Position.** To the left of the bar rectangle (`barLeft`), vertically
    centered on the bar (`barTop + BAR_HEIGHT / 2`), with a small fixed
    gap (e.g. 4px) so it never overlaps the border (C7) when both are on.
    Because it sits *outside* the bar rectangle, it is not subject to the
    same `BAR_HEIGHT`-vs-`FONT_GLYPH_HEIGHT` collision `CARDINAL_SCALE`
    exists to solve (`HudWaypointCompassBarMixin.java:123-131`) — no
    scaling needed, plain unscaled text is fine; exact anchor call
    (right-aligned ending at `barLeft - gap`, vs. `centeredText` at a
    precomputed left-of-bar x) is a planning-time detail depending on
    exactly what `GuiGraphicsExtractor` exposes beyond the already-used
    `centeredText` (confirm via the same `javap`-first convention this
    repo already applies to every `GuiGraphicsExtractor` call site).
  - **Update cadence.** Recomputed from `player.getYRot()` every call to
    `paint()` (i.e. every frame this HUD element draws) — this is a live
    instrument reading, not a cached/throttled value, matching every other
    per-frame quantity this mixin already reads fresh each call (yaw,
    player position, `toolHighlightTimer`).
  - **Color.** Reuses `COLOR_CARDINAL_LABEL` (`0xFFFFFFFF`, plain white),
    consistent with the ruler's own cardinal-letter color, since this is
    conceptually "one more piece of ruler-adjacent text," not
    waypoint-colored content.
- **C9. Waypoint name-label positioning bug fix.** **Confirmed by reading
  the current code**: `drawNameLabel` is called as
  `drawNameLabel(extractor, client, barCenterX, barTop, nearestCentered)`
  (`HudWaypointCompassBarMixin.java:216`) and always draws at
  `extractor.centeredText(client.font, ..., barCenterX, barTop - 10, argb)`
  (`HudWaypointCompassBarMixin.java:371`) — a **fixed** x position (the
  bar's exact horizontal center), regardless of where within the
  near-center window (`±NAME_LABEL_HALF_DEGREES`, R14) the waypoint's own
  dot actually currently sits. This contradicts R14's own intent (a label
  "shown above the bar" for the near-center waypoint, where "near center"
  is a *visibility gate*, not a fixed draw position) and produces a visible
  jump/snap of the label whenever a different waypoint becomes the
  nearest-centered one, and a subtle mismatch between the dot's true x and
  the label's x for the entire time a single waypoint is displayed and the
  player keeps turning within the window.
  - **Corrected behavior.** The name label's x position must track the
    waypoint's own dot x position (the same `dotX` computed for that
    waypoint inside the loop, `HudWaypointCompassBarMixin.java:195`), not
    `barCenterX`. Concretely: capture `dotX` alongside `nearestCentered`
    when a new nearest-centered candidate is found (mirroring how
    `nearestCenteredAbsTheta` is already tracked,
    `HudWaypointCompassBarMixin.java:177-178, 209-212`), then pass that
    captured x through to `drawNameLabel` instead of `barCenterX`.
  - **What does not change.** This is a positioning fix only — the
    visibility gate itself (`Math.abs(thetaDeg) <=
    NAME_LABEL_HALF_DEGREES`, R14) is untouched; a waypoint's name is still
    only drawn at all while its dot is within that threshold window. The
    label now visibly tracks the dot horizontally within that window
    (moving smoothly as the player turns, same as the dot does) instead of
    staying pinned to the bar's exact center the whole time.
  - Not gated by any of C4-C8's new configurables beyond the master
    toggle (C4) and `showWaypoints` (C5, since the name label is part of
    the waypoint-loop-derived content) — this is a bug fix riding along
    with this pass, not a new configurable.

### Live update

- **C10. No world-relog / no restart required.** Every configurable change
  (master toggle or any of the four sub-options) is read directly off
  `TweakRegistry.stateOf(TweakId.COMPASS)` inside `TweakHooksImpl`
  (`platform/fabric-26.2/.../tweaks/TweakHooksImpl.java`, `state(TweakId)`
  helper, `TweakHooksImpl.java:61-63`) on every `paint()` call — no
  caching, matching this codebase's existing tweak-application pattern
  (`tweaks.md`'s Events section, and `TweakHooksImpl`'s own class Javadoc,
  `TweakHooksImpl.java:26-29`). A change made on the Compass tweak's config
  screen (while the Tweaks tab is open, e.g. from the Pause menu) is
  visible in-world the very next frame after closing the menu, same as
  toggling Custom Crosshair's outline or Zoom's magnification today.

## Public API

- **New `features/tweaks/.../services/CompassHook.java`** (Minecraft-
  agnostic interface, mirrors `CustomCrosshairHook.java`'s exact shape,
  `features/tweaks/.../services/CustomCrosshairHook.java`):
  ```java
  public interface CompassHook {
      boolean isCompassActive();
      Object compassConfigurable(String key);
  }
  ```
  Following `CustomCrosshairHook`'s own precedent/rationale exactly (its
  Javadoc, `CustomCrosshairHook.java:3-11`): the remaining three boolean
  configurables (`showCardinals`, `showHeadingReadout`, `showBorder`) are
  read via the single generic `compassConfigurable(String)` accessor
  rather than one dedicated interface method each, since the HUD compass
  mixin is the sole consumer and already has this same generic-accessor
  shape available (matches `crosshairConfigurable(String)`,
  `CustomCrosshairHook`'s already-shipped sibling). `showWaypoints` is
  exposed the same way (`compassConfigurable("showWaypoints")`), not as a
  fifth dedicated method, for the same reason.
- **`TweakHooksImpl` gains `CompassHook`** in its `implements` clause
  (`TweakHooksImpl.java:47-49`), plus:
  ```java
  @Override
  public boolean isCompassActive() {
      return state(TweakId.COMPASS).enabled();
  }

  @Override
  public Object compassConfigurable(String key) {
      return state(TweakId.COMPASS).configurable(key);
  }
  ```
  matching `isCustomCrosshairActive()`/`crosshairConfigurable(String)`'s
  existing implementation shape exactly (`TweakHooksImpl.java:148-159`).
- **No `api/tweaks` change beyond `TweakId.COMPASS`** (C1) — `CompassHook`
  lives in `features/tweaks/.../services/` (feature-owned, not `api`),
  matching `CustomCrosshairHook`'s own placement, since it is an
  implementation-detail seam between `TweakHooksImpl` and the platform
  mixin, not a cross-module public contract.

## Architecture

- **No new module, no new cross-feature dependency edge.** `COMPASS` is a
  13th entry in the existing `features/tweaks` catalog — same
  `TweakDefinitions`/`ConfigSchemas`/`TweakHooksImpl` files every other
  tweak already lives in, just with new entries appended. `features/tweaks`
  still does not import anything from `features/waypoints`, and
  `features/waypoints` still does not import anything from
  `features/tweaks` — the `Feature -> Feature` ban
  (`.claude/context/architecture.md:74`) is preserved.
- **Bridging shape, mirroring Waypoints' own established pattern.**
  `HudWaypointCompassBarMixin` (a `platform/fabric-26.2` class, owned by
  neither feature) already imports `de.lazuli.waypoints.WaypointEngineHandoff`
  (a **platform-owned** handoff singleton, not a `features/waypoints`
  class) to get waypoint data, per Waypoints' own Architecture section's
  `WaypointCompassHook` seam (`docs/specs/waypoints.md`, Public API/
  Architecture). This spec adds a second import to that same mixin:
  `de.lazuli.tweaks.TweakEngineHandoff` (the platform-owned handoff
  singleton `HudCustomCrosshairMixin` already uses,
  `TweakEngineHandoff.java`), to obtain the `CompassHook`-shaped
  `TweakHooksImpl` instance. Both handoffs are platform classes exposing
  Minecraft-agnostic `api`/feature-owned interfaces — the mixin bridges
  Waypoints-owned data and Tweaks-owned configuration entirely within
  `platform/fabric-26.2`'s own composition, exactly the shape
  `docs/adr/0001-platform-composition-root-may-depend-on-feature-classes.md`
  already establishes is fine for platform code (this mixin isn't the
  composition root itself, but it depends only on the two platform-owned
  handoff classes and their feature-owned interface return types, never
  on a `features/waypoints` or `features/tweaks` concrete class directly —
  the same shape `HudCustomCrosshairMixin`/`HudWaypointCompassBarMixin`
  already individually demonstrate, just combined in one file for the
  first time).
- **`CompassBarPainter.paint(...)` signature change.** To thread the
  `CompassHook` through to the ruler/border/waypoint-loop/name-label/
  heading-readout logic without a second `TweakEngineHandoff.require()`
  call scattered through multiple private methods, `paint(...)` (and the
  private methods it calls that need gating: `drawRuler`, the new heading-
  readout draw, `drawNameLabel`) take the resolved `TweakHooksImpl`/
  `CompassHook` reference as an added parameter — mirroring exactly how
  `CrosshairPainter.paint(GuiGraphicsExtractor extractor, TweakHooksImpl
  hooks)` already threads it through in the crosshair mixin
  (`HudCustomCrosshairMixin.java:40, 48`). Exact parameter list per private
  method is an implementation-time (planning/coding) detail, not fixed
  further here.

## UI

- **Tweaks tab row.** "Compass" appears in the Tweaks tab's row list
  (`docs/specs/tweaks.md` F2) alongside the other 13 tweaks, with the
  standard checkbox (master enable/disable, C2/C4), "Bind" hotkey control
  (a `KeyBinding` toggling the master enable/disable, matching every other
  tweak's on/off hotkey convention, F3 — exact default key is
  `GLFW_KEY_UNKNOWN`/unbound like every other tweak's default), and
  clicking the row body opens the per-tweak config screen
  (`docs/specs/tweaks-panel-config-screen.md`).
- **Compass config screen.** Reuses the existing generic, data-driven
  config-screen mechanism (`tweaks-panel-config-screen.md` §2-3) — no new
  widget kind needed (all four rows are `ConfigFieldSpec.Kind.BOOLEAN`,
  which the generic screen already renders as a click-to-flip toggle row,
  same as e.g. Freecam's `noclip`/`showOwnBody` or No Rain's
  `includeSnow`/`includeSound`). Rows, in order: "Show Waypoints", "Show
  Cardinal Letters", "Show Heading Readout", "Show Border" (C3). No
  secondary hotkey row (`hasSecondary=false`, C2).
- **Compass bar itself.** Visually unchanged from today's shipped
  behavior when every default is left as-is (C2's defaults intentionally
  reproduce the current hardcoded constants exactly), plus: the heading
  readout (C8, off by default) when enabled, and the corrected name-label
  x-tracking (C9, always-on bug fix, no visual toggle).

## Configuration

- No new JSON file. `COMPASS`'s enabled flag + four boolean configurables
  persist through the existing `tweaks.json` (`docs/specs/tweaks.md`
  Configuration section, F5) exactly like every other tweak — a new
  top-level key under the existing `"tweaks"` object:
  ```json
  {
    "tweaks": {
      "COMPASS": {
        "enabled": true,
        "configurables": {
          "showWaypoints": true,
          "showCardinals": true,
          "showHeadingReadout": false,
          "showBorder": true
        }
      }
    }
  }
  ```
- **Migration note (existing installs).** Because `COMPASS` is a brand-new
  `TweakId`, `TweaksConfigIO`'s existing load-or-create-with-defaults
  contract (`tweaks.md` F5) means any `tweaks.json` written before this
  tweak shipped simply has no `"COMPASS"` entry; on next load, the missing
  entry falls back to `TweakDefinitions.COMPASS.defaultState()` (C2:
  `enabled=true`, current-behavior-preserving configurables) — no explicit
  migration code needed, this is the same fallback path `NO_RAIN`/`FREECAM`
  already went through when they were added after the original 12. Confirm
  during planning that `TweaksConfigIO`'s parse path really does default
  missing-`TweakId` entries this way (matches its documented fail-closed/
  default-on-missing contract, `tweaks.md` F5) rather than, say, requiring
  every `TweakId` to be present.

## Events

No new event/notification path. Following `tweaks.md`'s existing Events
section precedent exactly: `TweakRegistry.setEnabled`/`setConfigurable`
mutations write-through to `tweaks.json` immediately, and the compass HUD
mixin (via `TweakHooksImpl`) reads current state fresh on every `paint()`
call — no push notification, no caching, same "poll each frame" model
every other tweak's `TweakEngine`/`TweakHooksImpl` read path already uses.

## Networking

None. Purely a local, client-side rendering configuration — no packet, no
server awareness, consistent with every other tweak (`tweaks.md`
Networking) and with Waypoints' compass bar itself
(`docs/specs/waypoints.md` Networking).

## Persistence

Covered fully under Configuration above — one new `TweakId`-keyed entry in
the existing `tweaks.json`, same load/fail-closed/write-through contract
as every other tweak (`tweaks.md` F5/Persistence). No new file, no new
persistence mechanism.

## Compatibility

- `platform/fabric-26.2` only (see Non-goals) — the only platform module
  `HudWaypointCompassBarMixin` currently exists in. If Waypoints' compass
  bar is later ported to `fabric-1.21.11`/`fabric-26.1`, this tweak's
  `TweakId`/`TweakDefinition`/`ConfigFieldSpec`/`CompassHook` plumbing
  needs zero changes (already Minecraft-agnostic, per Public API); only a
  new platform mixin wiring `CompassHook` the same way would be needed on
  each newly-ported platform, mirroring how every other tweak's hook
  interface is already platform-agnostic and just awaiting a per-platform
  mixin.
- Depends on `TweaksClientInitializer` (or whichever composition-root class
  constructs/publishes `TweakHooksImpl` via `TweakEngineHandoff.publish(...)`
  on `platform/fabric-26.2`) already running before
  `HudWaypointCompassBarMixin`'s first `paint()` call — this is the same
  ordering dependency `HudCustomCrosshairMixin` already has and already
  works correctly under (`TweakEngineHandoff.require()` throws
  `IllegalStateException` if called too early, `TweakEngineHandoff.java:20-27`
  — an existing, already-proven-safe fail-fast, not a new risk introduced
  by this spec).
- Depends on `WaypointEngineHandoff` being published before `paint()` too
  (already an existing dependency of this mixin, unchanged by this spec).
- No dependency introduced between `features/tweaks` and
  `features/waypoints` themselves (Architecture) — only the platform mixin
  now depends on both platform-owned handoff singletons, which was already
  true independently for each (Custom Crosshair's mixin already depends on
  `TweakEngineHandoff`; Waypoints' compass mixin already depends on
  `WaypointEngineHandoff`) — this spec is the first to need both in the
  same file, not the first to introduce either dependency.

## Performance

- The new `TweakEngineHandoff.require()`/`hooks.isCompassActive()` call at
  the top of `paint()` (C4) is O(1) (a volatile field read plus an
  `EnumMap`/registry lookup, matching every other `state(TweakId)` call
  this codebase's tweaks already make once per frame) — no measurable cost
  added beyond what `HudCustomCrosshairMixin` already pays for the
  identical pattern.
- `showWaypoints=false` (C5) makes the per-waypoint loop's cost zero for
  that frame (skipped entirely) — a strict performance *improvement* over
  today's always-runs-the-loop behavior when a player wants a
  waypoint-free ruled compass, not a regression.
- The heading readout (C8) adds one small conversion (`yaw + 180`, modulo,
  `Math.round`) and one text draw call per frame when enabled — negligible,
  same order of magnitude as the existing cardinal-letter draws this mixin
  already performs up to 4 times per frame.
- The master-disabled path (C4) is now the cheapest possible frame for this
  mixin: one hook call, one boolean check, immediate return — strictly
  cheaper than today's "always draws (or always early-returns on
  zero-waypoints, but still fills the background/border/ruler even with
  zero waypoints)" behavior, since C4's gate is checked before any drawing
  at all, including the background fill.

## Future Extensions

- Per-tweak numeric/enum configurables for compass-bar visual-design
  constants currently left hardcoded (Non-goals) — e.g. a configurable
  `TICK_STEP_DEGREES`, `FOV_HALF_DEGREES`, or dot min/max size — not
  requested, not built here.
- Porting `HudWaypointCompassBarMixin` (and by extension this tweak's
  gating) to `fabric-1.21.11`/`fabric-26.1`, per Compatibility's note.
- A second heading-readout format option (e.g. cardinal-abbreviation-plus-
  degrees like `"N 127°"`, or a mils/other unit toggle) — not requested;
  the single integer-degrees format (C8) is all this spec builds.
- Exposing `showHeadingReadout`'s color as its own configurable (currently
  hardcoded to reuse `COLOR_CARDINAL_LABEL`, C8) if a player wants it
  visually distinguished from the cardinal letters.

## Planning Prerequisites (technical items, not user decisions)

Item 1 below (heading readout bearing convention) was an open product
decision at spec time — **resolved by explicit user confirmation: everyday
compass-heading convention (`N=0, E=90, S=180, W=270`), as this spec
recommended and designed around in C8.** No spec content changes needed;
this section previously flagged it as open pending that confirmation.

1. ~~Heading readout bearing convention~~ — **resolved (N=0°, see above).**
2. **Exact heading-readout text anchor call.** Whether the readout is
   drawn via a right-aligned draw ending just left of `barLeft`, or a
   `centeredText` call at a precomputed left-of-bar x, depends on exactly
   what text-draw methods `GuiGraphicsExtractor` exposes beyond the
   already-confirmed `centeredText` (C8) — a planning-time `javap`
   confirmation, not a spec-level decision.
3. **`tweaks.json` missing-`TweakId`-on-load fallback behavior.** Flagged
   under Configuration's Migration note — this spec assumes
   `TweaksConfigIO` already defaults a missing `TweakId` entry to that
   tweak's `defaultState()` (the same path `NO_RAIN`/`FREECAM` presumably
   already exercised when they were added), but this spec did not
   independently re-verify `TweaksConfigIO`'s parse code during this pass;
   planning should confirm this before assuming COMPASS's `enabled=true`
   default reliably applies to pre-existing installs.

# Implementation Plan: Compass (Tweaks Framework)

Status: plan only — no implementation code in this document.
Spec: `docs/specs/tweaks-compass.md` (cited below by section, e.g. "C1",
"C8"). Scope: `platform/fabric-26.2` only, `features/tweaks` catalog only
(matches spec Non-goals/Compatibility).

## Existing Implementation

Trusting the spec's own research (it already read every file below in
detail) rather than re-deriving it; only the two files this plan needed to
independently verify (Planning Prerequisites 1 and 2) were re-read this
pass.

- **`HudWaypointCompassBarMixin.java`** (`platform/fabric-26.2/src/main/java/de/lazuli/mixin/HudWaypointCompassBarMixin.java`,
  375 lines, read in full this pass to confirm the C8/C9 line numbers and
  the exact `GuiGraphicsExtractor` calls already in production use):
  - `CompassBarPainter.paint(GuiGraphicsExtractor extractor)` (line 141) is
    the single entry point; it early-returns at line 144 (no player/level)
    and line 149-153 (zero waypoints for the current dimension) before any
    drawing. C4 inserts a third early-return (the tweak-disabled gate)
    *before* both of these, per C4's explicit placement note.
  - `SHOW_CARDINALS` (line 122) and `SHOW_BORDER` (line 139) are the two
    `private static final boolean` constants C6/C7 replace with
    `hooks.compassConfigurable(...)` reads. `showWaypoints` (C5) has no
    existing constant — it's new gating around the existing `for (Waypoint
    waypoint : waypoints)` loop (lines 180-213).
  - The waypoint loop already tracks `nearestCentered`/
    `nearestCenteredAbsTheta` (lines 177-178, 209-212) and separately
    computes each waypoint's `dotX` (line 195) inside the same loop body.
    `drawNameLabel` is called once after the loop (line 216) with
    `barCenterX` (line 160, the bar's fixed horizontal midpoint) and draws
    at that fixed x via `extractor.centeredText(...)` (line 371) — this is
    the C9 bug: the label's x never varies with the waypoint's actual
    bearing.
  - Confirmed `GuiGraphicsExtractor` calls actually in production use in
    this file: `fill(...)` (border/dot/chevron/background), `pose()`
    (`pushMatrix`/`scale`/`popMatrix`, cardinal-letter scaling, lines
    283-289), `guiWidth()`/`guiHeight()`, and `centeredText(Font,
    String|Component, int, int, int)` — used twice, once with a `String`
    (`cardinal`, line 288) and once with a `Component`
    (`Component.literal(waypoint.name())`, line 371). **No other
    text-draw method (`text(...)`, a right-aligned variant, etc.) is
    called anywhere in this file** — see Planning Prerequisite 1 below for
    why C8's heading readout should not introduce a dependency on any
    method beyond this already-proven `centeredText` overload.
  - Class Javadoc (lines 23-61) documents this mixin's own `javap`-based
    verification history against `GuiGraphicsExtractor`, including an
    explicit prior finding "`GuiGraphicsExtractor` has no font-size
    parameter" (lines 267-273) — i.e. this codebase already has a working
    convention of running `javap -p`/`javap -c` against the platform's
    resolved Minecraft jar before trusting a guessed method signature on
    this class, and expects future changes to this mixin to keep doing so.
  - `Hud.toolHighlightTimer` short-circuit (lines 66-78) is untouched by
    this spec — C4's new gate goes inside `CompassBarPainter.paint(...)`
    itself (line 141), not at the `@Inject` call site (line 70), per C4's
    explicit note that `paint()` (not the injected method) is where the
    actual draw calls live.
- **`HudCustomCrosshairMixin.java`** (`platform/fabric-26.2/src/main/java/de/lazuli/mixin/HudCustomCrosshairMixin.java`)
  — spec's cited precedent for both the master-gate shape (`if
  (!hooks.isCustomCrosshairActive()) return;`) and the
  `paint(GuiGraphicsExtractor, TweakHooksImpl)` parameter-threading shape
  this plan's C4/Architecture changes mirror. Not modified by this plan.
- **Tweaks framework files** (trusted from spec citations, not re-read this
  pass — spec already quoted their exact line ranges and JSON/Java shapes):
  `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` (C1),
  `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
  (C2, lines 16-123 per spec), `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
  (C3, lines 23-120 per spec), `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/CustomCrosshairHook.java`
  (Public API precedent), `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
  (C10/Public API, lines 26-29/47-49/61-63/148-159 per spec),
  `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweakEngineHandoff.java`
  (Architecture, lines 20-27 per spec).
- **`TweaksPanel.java`** (`platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`)
  — confirmed this pass (grep) to be fully data-driven: its row-rendering
  loop is `for (TweakDefinition def : TweakDefinitions.ALL)` (line 205),
  with no `TweakId`-keyed `switch`/`case` anywhere in the file. **No change
  needed to this file** — appending `TweakDefinitions.COMPASS` to `ALL`
  (C2) is sufficient for the new row, hotkey binding control, and
  click-to-open config screen to appear automatically, matching the spec's
  UI section's expectation.
- **Exhaustive `TweakId`-keyed switches** — confirmed this pass (grep for
  `TweakId.FREECAM`/`TweakId.NO_RAIN` across all `*.java`) that `TweakId`
  is referenced only in `ConfigSchemas.java`, `TweakDefinitions.java`,
  each platform's `TweakHooksImpl.java`, each platform's
  `FreecamTicker.java` (Freecam-specific, irrelevant here), and
  `TweaksConfigIOTest.java`. There is **no other exhaustive switch over
  `TweakId`** anywhere in the codebase that a 13th enum constant could
  silently fall through / need a new `case` in (e.g. no
  `default: throw` construct keyed on `TweakId` outside these already-
  identified files). This closes out a class of risk the spec didn't
  explicitly rule out.
- **Existing tests** (confirmed this pass via glob):
  `features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`
  and `.../services/TweakRegistryTest.java` are the only existing
  `features/tweaks` unit tests. `TweaksConfigIOTest` already has the exact
  pattern this plan's fallback-behavior test should follow: an
  "empty/missing tweaks object backfills every `TweakId` to its default
  state" assertion block (lines 25-31, asserting `NO_RAIN`/`FREECAM`
  defaults) and a "saved file overlay preserves only the fields present"
  block (lines 40-68). No existing test exists for
  `HudWaypointCompassBarMixin`/`HudCustomCrosshairMixin` (no
  `platform/fabric-26.2/src/test/.../mixin/` directory) — this project has
  no established pattern for unit-testing render-state-extraction mixins;
  its own commit history (`840d49a`, `d0e32fd`, `5b3dc97`) shows this
  mixin's own past changes were verified in-game, not via unit test.

## Planning Prerequisites — resolved

1. **Heading-readout text anchor call (spec's Planning Prerequisites §2).**
   Resolved: **use `extractor.centeredText(...)` only** — the same
   overload already in production use twice in this file (lines 288, 371)
   — at a precomputed x, rather than introducing a dependency on any
   right-aligned/left-anchored draw method whose presence on
   `GuiGraphicsExtractor` this plan did not itself re-verify via `javap`.
   Rationale: grepping every `GuiGraphicsExtractor`-typed call site across
   `platform/fabric-26.2` (the two render-state-extraction mixins) turns
   up only `fill`, `pose()`, `guiWidth()`/`guiHeight()`, and
   `centeredText` — never a bare `text(...)`/right-aligned variant. (A
   right-aligned `.text(font, str, x - font.width(str), y, color)` pattern
   *does* exist elsewhere in this codebase — `DropdownWidget.java:141`,
   `StorePanel.java:119` — but those call sites are ordinary `Screen`s
   using full `GuiGraphics`, not the restricted extraction-time
   `GuiGraphicsExtractor` this mixin is confined to; that precedent does
   not by itself confirm the extractor class exposes the same method.)
   Concretely:
   - `String headingText = Math.round(headingDeg) + "°";`
   - `int textWidth = client.font.width(headingText);`
   - `int readoutCenterX = barLeft - HEADING_READOUT_GAP - textWidth / 2;`
     (`HEADING_READOUT_GAP` a new small constant, e.g. `4`, per C8's "small
     fixed gap (e.g. 4px)")
   - `int readoutY = barTop + BAR_HEIGHT / 2 - FONT_GLYPH_HEIGHT / 2;`
     (vertically centered on the bar per C8, unscaled per C8's "no scaling
     needed" note — reuses the existing `FONT_GLYPH_HEIGHT` constant,
     line 130, only for this centering math, not for any `pose()` scale)
   - `extractor.centeredText(client.font, headingText, readoutCenterX, readoutY, COLOR_CARDINAL_LABEL);`
   - This reproduces "right-aligned ending at `barLeft - gap`" visually
     (the text's right edge sits at `readoutCenterX + textWidth/2 ==
     barLeft - HEADING_READOUT_GAP`) using only the already-proven
     `centeredText` call shape — no new method risk introduced.
   - Implementation should still do a quick sanity check that
     `centeredText`'s `String`-typed overload (as used for `cardinal`,
     line 288) behaves identically for a plain degree string as it does
     for the `cardinal` letters, before considering C8 done — this is a
     cheap confirmation, not a full re-`javap` of the class, since the
     overload itself is already proven to work in this exact file.
2. **`tweaks.json` missing-`TweakId` fallback behavior (spec's Planning
   Prerequisites §3).** Resolved by reading
   `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java`
   in full this pass. **Confirmed correct, spec's assumption holds:**
   `parse(String content)` (lines 70-113) first populates the working
   `tweaks` map with `TweaksConfig.DEFAULT.stateOf(id)` for **every**
   `TweakId.values()` (lines 81-84) *before* iterating the JSON's
   `"tweaks"` object and overwriting only the keys actually present (lines
   85-108). A `tweaks.json` written before `COMPASS` existed simply has no
   `"COMPASS"` key in its JSON object, so that loop iteration never touches
   `tweaks.get(TweakId.COMPASS)` — it is left exactly as pre-populated,
   i.e. `TweaksConfig.DEFAULT.stateOf(TweakId.COMPASS)`, which is derived
   from `TweakDefinitions.COMPASS.defaultState()` (C2:
   `enabled=true`). This is the same code path an unknown-forward key hits
   in reverse (line 89's `catch (IllegalArgumentException) continue`) and
   the class's own Javadoc (lines 32-36) already documents this exact
   contract in prose ("A missing `TweakId` entry is backfilled with that
   tweak's default state"). **No risk to flag** — the spec's assumption
   was correct; `COMPASS`'s `enabled=true` default reliably applies to
   every pre-existing install on first load after upgrade, with no
   migration code needed, exactly as C2/Configuration's Migration note
   describes.

## Files to create

1. `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/CompassHook.java`
   — new interface, mirroring `CustomCrosshairHook.java`'s exact shape
   (Public API section): `boolean isCompassActive()`,
   `Object compassConfigurable(String key)`. Package-level Javadoc should
   mirror `CustomCrosshairHook`'s own rationale Javadoc (its lines 3-11)
   for why sub-options use a generic string-keyed accessor instead of one
   method each.
2. No other new files. No new test file needed as a *separate* file — new
   test cases are added to two *existing* test files (see Test strategy).

## Files to modify

1. `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` — append `COMPASS`
   after `FREECAM` (C1).
2. `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
   — add `TweakDefinitions.COMPASS` (C2: description, `defaultConfigurables`
   map with the four booleans in C3's order, `hasSecondary=false`,
   `defaultEnabled=true`), and add it to `ALL`/lookup maps the same way
   `NO_RAIN`/`FREECAM` were (lines 120-123 per spec).
3. `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
   — add the `ALL.put(TweakId.COMPASS, List.of(...))` block exactly as
   specified in C3 (four `ConfigFieldSpec.bool(...)` rows, in
   `defaultConfigurables` key order).
4. `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
   — add `CompassHook` to the `implements` clause, and implement
   `isCompassActive()`/`compassConfigurable(String)` exactly as specified
   in Public API (`state(TweakId.COMPASS).enabled()` /
   `state(TweakId.COMPASS).configurable(key)`, mirroring
   `isCustomCrosshairActive()`/`crosshairConfigurable(String)`, lines
   148-159 per spec). **`fabric-26.2` only** — no change to
   `fabric-26.1`/`fabric-1.21.11`'s `TweakHooksImpl.java` (Compatibility:
   those platforms have no `HudWaypointCompassBarMixin` to bridge to, and
   `CompassHook` is not part of the shared `api` surface, so those two
   modules have no reason to implement it).
5. `platform/fabric-26.2/src/main/java/de/lazuli/mixin/HudWaypointCompassBarMixin.java`
   — the bulk of this plan's work:
   - Import `de.lazuli.tweaks.TweakEngineHandoff` (Architecture).
   - `lazuli$drawWaypointCompassBar` (line 70): resolve the `CompassHook`
     (via `TweakEngineHandoff.require()`, cast/typed as the
     `TweakHooksImpl` instance implementing `CompassHook`, mirroring how
     `HudCustomCrosshairMixin` resolves its own hook) and pass it into
     `CompassBarPainter.paint(...)`.
   - `CompassBarPainter.paint(GuiGraphicsExtractor, CompassHook)` (line
     141): new first statement — `if (!hooks.isCompassActive()) return;`
     — placed before the existing player/level null-check (line 144) and
     zero-waypoint check (lines 149-153), per C4's explicit ordering note.
   - Remove `SHOW_CARDINALS` (line 122) and `SHOW_BORDER` (line 139)
     `private static final boolean` constants; replace their two call
     sites (line 163's `if (SHOW_BORDER)`, line 254's `SHOW_CARDINALS ?
     ... : null`) with `hooks.compassConfigurable("showBorder")` /
     `hooks.compassConfigurable("showCardinals")` reads (C6/C7), threading
     `hooks` through to `drawRuler`'s parameter list (Architecture).
   - Wrap the `for (Waypoint waypoint : waypoints)` loop (lines 180-213)
     in `if ((Boolean) hooks.compassConfigurable("showWaypoints")) { ... }`
     (C5) — the loop, and therefore `nearestCentered`'s population and the
     name-label draw at line 216, are skipped entirely when off.
   - **C9 fix**: inside the loop, when a new `nearestCentered` candidate is
     found (lines 209-212), also capture that waypoint's own `dotX` (line
     195) into a new local (e.g. `nearestCenteredDotX`), mirroring how
     `nearestCenteredAbsTheta` is already tracked. Change the post-loop
     call at line 216 from `drawNameLabel(extractor, client, barCenterX,
     barTop, nearestCentered)` to pass `nearestCenteredDotX` instead of
     `barCenterX`, and change `drawNameLabel`'s own parameter name/usage
     (line 369, currently `barCenterX`) and its `centeredText` call (line
     371) to use that tracked dot-x value as its x argument instead.
   - **C8 new feature**: after the existing draw calls, when the master
     toggle is on (already gated by the top-of-`paint` return) and
     `hooks.compassConfigurable("showHeadingReadout")` is true, compute
     `headingDeg = normalizeAngle(yaw + 180)` folded into `[0, 360)` (spec's
     exact formula) and draw it per Planning Prerequisite 1's resolved
     anchor call above. New small constant `HEADING_READOUT_GAP = 4`
     alongside the file's other visual-design constants (near
     `BAR_WIDTH`/`BAR_HEIGHT` at the top of `CompassBarPainter`).
   - Parameter-threading note (Architecture): `drawRuler` and the new
     heading-readout draw logic need the `CompassHook`/boolean values;
     `drawNameLabel` needs only the tracked `dotX` (no hook reference
     needed there, since C9's fix is unconditional once the loop itself
     ran). Exact private-method parameter lists are an implementation-time
     detail per the spec's own Architecture section — this plan does not
     fix them further.
6. `docs/specs/tweaks.md` — **not modified by this plan** unless the user
   separately asks; the spec's Goals §1 frames `COMPASS` as "formalizing"
   `tweaks.md`'s existing `T1-T12` prose numbering, but does not request an
   edit to that file, and this plan does not infer one. Flagged here only
   so implementation doesn't silently skip a doc update the spec actually
   wanted — Acceptance criteria below calls this out as a explicit
   non-requirement to sanity-check against the spec text again if in
   doubt.

## Risks

1. **`GuiGraphicsExtractor.centeredText`'s `String`-typed overload might
   render subtly differently for a bare Unicode `"°"` glyph than the
   already-proven `cardinal` (`"N"`/`"E"`/`"S"`/`"W"`) call site** — low
   risk (vanilla's default font ships a degree glyph, and C8 documents no
   special localization concern), but worth a single in-game screenshot
   check during verification since this plan (Planning Prerequisite 1)
   intentionally avoided introducing any *other* draw call.
2. **`hooks.compassConfigurable(key)` returns `Object`, requiring an
   unchecked cast to `Boolean` at each of the four call sites** (C5-C8) —
   mirrors `CustomCrosshairHook`'s existing pattern exactly (not a new
   risk this spec introduces), but a malformed/corrupted `tweaks.json`
   entry with a non-boolean value for one of these four keys (e.g. hand-
   edited) would throw a `ClassCastException` inside `paint()` every
   frame, hard-crashing the HUD render. This is an existing, accepted risk
   shape for every other boolean tweak configurable in this codebase
   (`crosshairConfigurable` has the identical exposure) — not something
   this plan needs to newly guard against, but implementation should not
   add defensive null/type checks beyond what `HudCustomCrosshairMixin`
   already omits, to stay consistent.
3. **Ordering dependency on `TweakEngineHandoff.publish(...)` running
   before this mixin's first `paint()` call** — already an accepted,
   proven-safe fail-fast per spec's Compatibility section (throws
   `IllegalStateException` if too early); no new risk, just re-confirming
   it applies identically to this mixin's now-second `TweakEngineHandoff`
   dependency (Waypoints' `WaypointEngineHandoff` was already the first).
4. **`docs/specs/tweaks.md`'s `T1-T12` prose numbering is not updated to
   include a `T13`/`COMPASS` row** (see Files to modify §6) — purely a
   documentation-consistency risk, zero runtime/behavior impact; flagged
   so it isn't silently forgotten if the user does want that doc kept in
   sync, without this plan unilaterally deciding to touch it.

## Dependencies

No new external (non-Fabric) dependency. This is a routine 13th entry in
an existing framework using only classes/methods already present in this
codebase (`ConfigFieldSpec.Kind.BOOLEAN`, `GuiGraphicsExtractor.fill`/
`centeredText`/`pose()`, `Font.width(String)`) — no Maven Central lookup
needed.

## Test strategy

1. **`TweaksConfigIOTest.java`** (`features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`)
   — extend the existing "empty tweaks object backfills every `TweakId` to
   default" test (lines 25-31 pattern) with assertions for
   `TweakId.COMPASS`: `enabled()` is `true`, and each of
   `showWaypoints`/`showCardinals`/`showBorder` is `true` while
   `showHeadingReadout` is `false` — this is the concrete regression test
   for Planning Prerequisite 2's confirmed fallback behavior. Also extend
   the "saved file overlay" test (lines 40-68 pattern) with a `"COMPASS":
   {...}` JSON block exercising a non-default value for each of the four
   configurables plus `enabled=false`, asserting all five round-trip
   correctly.
2. **New test for `TweakDefinitions`/`ConfigSchemas`** (no existing test
   file covers these two classes directly per this plan's file-glob
   check) — either add a small new test class
   `features/tweaks/src/test/java/de/lazuli/features/tweaks/services/TweakDefinitionsTest.java`
   or, if judged unnecessary scope creep at implementation time, rely on
   `TweaksConfigIOTest`'s coverage above (which transitively exercises
   `TweakDefinitions.COMPASS.defaultState()` via
   `TweaksConfig.DEFAULT.stateOf(...)`) plus a manual check that
   `ConfigSchemas.ALL.get(TweakId.COMPASS)` has exactly 4 rows in the
   right order and right labels — implementation should pick whichever is
   proportionate, this plan does not mandate the new file.
3. **Manual in-game verification** (no unit-test precedent exists for
   `HudWaypointCompassBarMixin`, confirmed above) — required for:
   - C4: toggling the Compass tweak off makes the entire bar (background,
     border, ruler, waypoints, heading readout) disappear next frame; on
     brings it all back.
   - C5: `showWaypoints=false` with the tweak enabled still shows the bare
     ruler/border/cardinals, with no dots/chevrons/name label.
   - C6/C7: toggling `showCardinals`/`showBorder` independently produces
     the expected visual change with no other side effects.
   - C8: enabling `showHeadingReadout` shows a live `N=0°/E=90°/S=180°/
     W=270°`-convention integer readout to the left of the bar that
     updates as the player turns, positioned with a small gap left of
     `barLeft`, vertically centered, in `COLOR_CARDINAL_LABEL` white, with
     no overlap against the border when both are on.
   - **C9 regression check**: with two or more waypoints near the FOV
     center at different bearings, confirm the name label's x position now
     visibly tracks each waypoint's own dot (moving as the player turns,
     matching the dot's motion) instead of staying pinned at the bar's
     exact horizontal center — this is the primary manual test for the
     bug fix, since no automated test harness exists for this mixin.
   - C10: toggle any Compass configurable from the Tweaks tab's config
     screen while paused, close the menu, confirm the change is visible
     the very next rendered frame with no relog/restart.
   - Migration: manually verify by editing a pre-existing (or hand-
     authored, `COMPASS`-key-absent) `tweaks.json`, launching, and
     confirming the compass bar renders exactly as before this feature
     shipped (all defaults preserving current visual behavior per C2).
4. **Build verification**: standard Gradle build/test for
   `features/tweaks` and `platform/fabric-26.2` modules — no new Gradle
   task or module needed (Architecture: no new module).

## Acceptance criteria

1. `TweakId.COMPASS` exists (C1), `TweakDefinitions.COMPASS` /
   `ConfigSchemas.ALL.get(TweakId.COMPASS)` exist with the exact shapes in
   C2/C3, and `TweakDefinitions.ALL` includes it — the Tweaks tab shows a
   "Compass" row with checkbox/Bind/click-to-configure exactly like every
   other tweak, with zero changes to `TweaksPanel.java` (confirmed
   data-driven).
2. `CompassHook.java` exists in `features/tweaks/.../services/` matching
   `CustomCrosshairHook`'s shape exactly (Public API); `TweakHooksImpl`
   (fabric-26.2 only) implements it per C10's spec text.
3. With every default left as-is, the compass bar's visual behavior is
   byte-for-byte identical to pre-this-feature behavior (background,
   border, ruler, cardinals, dots, chevrons, name label all still draw) —
   the only new observable is the master on/off Tweaks-tab control (which
   was previously not possible at all) and the corrected name-label x
   (C9), which is an always-on bug fix independent of any new
   configurable default.
4. Disabling the Compass tweak master toggle suppresses 100% of this
   mixin's drawing for that frame (C4), verified to be gated *before* the
   zero-waypoint check per the exact ordering C4 specifies.
5. Each of the four sub-options (`showWaypoints`, `showCardinals`,
   `showHeadingReadout`, `showBorder`) is independently toggleable with
   the exact gating behavior described in C5-C8, live-applying with no
   caching (C10).
6. The heading readout (C8), when enabled, uses the `N=0/E=90/S=180/
   W=270` convention and integer-degree format specified, drawn via
   `extractor.centeredText(...)` only (Planning Prerequisite 1), never
   overlapping the border.
7. The name-label positioning bug (C9) is fixed: the label's x now tracks
   the nearest-centered waypoint's own `dotX`, not a fixed `barCenterX`,
   with the visibility gate (`NAME_LABEL_HALF_DEGREES`) unchanged.
8. A `tweaks.json` file written before this feature shipped (no
   `"COMPASS"` key) loads with `COMPASS` defaulting to `enabled=true` and
   the current-behavior-preserving configurables (Planning Prerequisite
   2), verified by both the extended `TweaksConfigIOTest` assertions and a
   manual load test.
9. No change to `features/tweaks`↔`features/waypoints` module dependency
   graph (Architecture) — `features/tweaks` still does not import
   anything from `features/waypoints` and vice versa; only
   `HudWaypointCompassBarMixin` (a `platform/fabric-26.2` class) imports
   both `TweakEngineHandoff` and `WaypointEngineHandoff`.
10. No change to `fabric-26.1`/`fabric-1.21.11` platform modules
    (Compatibility/Non-goals) beyond what's already shared via `api`/
    `features/tweaks` (i.e. `TweakId.COMPASS` exists everywhere those
    modules already see the shared enum, but no platform-specific wiring
    is added outside `fabric-26.2`).

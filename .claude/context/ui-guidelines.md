# UI Guidelines

How to add or extend in-game UI (screens, widgets, HUD elements) consistently
across features. Read alongside `architecture.md` (layering/dependency rules)
and `minecraft.md` (cross-version API tracking) — this doc is about the
*pattern* to follow, those are about *where code lives* and *what already
differs between versions*.

## No third-party UI framework, for now

This repo does not depend on a UI/widget library (evaluated: `owo-lib`, the
closest Fabric-native fit — rejected for now because it has no build for
Minecraft 26.2, one of this project's three supported versions, as of this
writing). Revisit if/when a candidate library covers all three supported
versions; don't add one that only covers a subset. Until then, build UI with
vanilla's own widget classes plus Fabric API's `fabric-screen-api-v1` module
(`ScreenEvents`, `Screens`) — this is what `hello-world-main-menu` already
does successfully (`platform/fabric-26.2/.../mainmenu/FabricMainMenuHook.java`).

## Pattern 1: adding an overlay widget to an existing vanilla screen

This is the default, cheapest way to add UI and covers most cases (a label, a
button, a toggle icon next to an existing list entry).

1. Define a small `api`-layer hook interface for what the feature needs to
   show/control (e.g. `MainMenuHook`'s `showLabel`/`hideLabel`) — zero
   Minecraft imports, per the Dependency Rules table in `architecture.md`.
2. Implement it once per platform module as a Version Adapter
   (`platform/fabric-<version>/.../<Feature>Hook.java`), following
   `FabricMainMenuHook`'s shape:
   - Register a `ScreenEvents.AFTER_INIT` listener once, at construction.
   - Filter for the target screen type (`instanceof TitleScreen`, etc.).
   - Build/position a vanilla widget class and add it via
     `Screens.getWidgets(screen)` (`Screens.getButtons(screen)` pre-1.21.11 —
     see the Known Cross-Version API Differences table in `minecraft.md`).
   - No `@Mixin` needed for this pattern.
3. Wire the adapter from the platform module's client composition root, per
   the Composition-root exception in `architecture.md`.
4. Verify the widget's exact class name/package and the `Screens` method name
   against each supported version by compiling, not by assuming continuity
   across the obfuscation boundary — log a real divergence in `minecraft.md`'s
   table if you find one.

This pattern only *adds* an independent widget on top of a screen. It cannot
make something behave like a genuine member of an existing scrollable/list
widget (see Pattern 2).

## Pattern 2: a synthetic entry inside an existing vanilla list widget

Needed when a feature must make something appear and behave as if it were a
real row in a vanilla list (e.g. a world-select or server-list entry) even
though no backing vanilla object exists for it yet. This is a materially
bigger commitment than Pattern 1 and is very likely to require a real
`@Mixin`, not just `ScreenEvents`/`Screens`:

- Vanilla list widgets (e.g. `WorldListWidget`, extending `EntryListWidget`)
  typically expose their entry-add/entry-list-population methods as
  `private`/`protected`, with no public API for external code to insert a row
  that scrolls, is keyboard-navigable, and responds to selection/double-click
  like a real one.
- Before assuming a mixin is required, check (per-version, by reading the
  actual decompiled/mapped source, not by guessing): does the list expose a
  live, externally-appendable backing collection via a public accessor? Is
  the real entry type's constructor accessible outside its own package? If
  either is genuinely open, a non-mixin approach may still work — but treat
  "it probably needs a mixin" as the working assumption until checked, not
  the reverse.
- If a mixin is required: it must live in
  `platform/fabric-<version>/.../mixin/`, registered in that module's
  `*.mixins.json` — never in a feature's own `mixins/` package, which stays a
  permanent placeholder for exactly this reason (`feature-guidelines.md`).
  Prefer the narrowest mixin shape that works (`@Accessor`/`@Invoker` to
  expose an existing private member/method) over rewriting vanilla behavior
  with `@Inject`/`@Redirect`, unless the feature genuinely needs to change
  vanilla logic rather than just reach it.
- This needs independent verification per supported Minecraft version — the
  relevant class/method names and visibilities are not guaranteed to match
  across the obfuscation boundary (or even between two unobfuscated versions).
  Confirm by compiling against each version's real mappings; log any real
  divergence found in `minecraft.md`'s Known Cross-Version API Differences
  table.

## Reusable widget components

Don't pre-build a shared "icon toggle button" or similar generic widget
library speculatively. Follow the same graduate-on-second-use discipline
`architecture.md` already applies to `services/`: if a single feature needs
the same small widget shape more than once internally (e.g. one feature
needing both a bookmark-toggle icon and a world-sync-toggle icon), it's fine
for that feature to define one shared widget class for its own internal
reuse. Only promote a widget class to somewhere more broadly shared once a
*second, independent* feature needs the identical shape — and record that
extraction the same way a services/ extraction would be (a short rationale,
not necessarily a full ADR unless the promotion is architecturally
significant).

## Two screens, two features: the collision rule

`architecture.md`'s "Shared-screen extension point" rule already covers this:
the first feature to extend a given vanilla screen may add its own hook +
adapter directly, no coordination needed. The moment a *second* feature wants
to extend the *same* screen, independent `ScreenEvents.AFTER_INIT`
registrations can silently collide (overlapping placement, undefined
ordering) — that's the trigger to introduce a `services/`-layer
layout/stacking coordinator for that specific screen, not before. Two
features extending two *different* screens (e.g. one on the Multiplayer
screen, another on the Singleplayer screen) never trigger this — there's no
shared state to coordinate.

## Textures/icon assets

No convention existed before this doc (`hello-world-main-menu` only added a
text label, no textures). Going forward: custom GUI textures live under each
platform module's own `src/main/resources/assets/lazuli/textures/gui/`,
following vanilla's own `assets/minecraft/textures/gui/sprites/` convention
and referenced via `Identifier`/`ResourceLocation` the same way vanilla code
does — not duplicated per platform module unless a texture genuinely needs to
differ by version (it normally won't; texture PNGs aren't part of the
Yarn/Mojang mapping boundary). Prefer reusing an existing vanilla sprite
(e.g. a vanilla icon that already conveys the right meaning) over adding a
new custom texture, when one reasonably fits — less asset maintenance, more
visually consistent with vanilla.

## Testing

UI code that touches `Screen`/widget classes is not unit-testable on a plain
JVM (no headless Minecraft client in this repo's test setup) — verify it
manually in-game per supported version, same as this repo's other Minecraft-
touching code. Keep any decision logic a UI class depends on (e.g. "should
this icon show as on or off," "which entries are cloud-only") in a plain,
unit-tested class the UI layer merely reads from — don't let business logic
leak into a `Screen`/widget class where it becomes untestable by association.

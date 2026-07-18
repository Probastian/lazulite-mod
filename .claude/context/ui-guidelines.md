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

### The `@Invoker`/`@Accessor` duck-interface trap

If the invoker/accessor's real parameter or return type is itself a
**protected nested type** (common for list-entry base classes — confirmed for
both `EntryListWidget.Entry` and `AbstractSelectionList.Entry`), a plain
`interface` mixin cannot declare it at all (the type isn't nameable outside
its own package), and switching the mixin to an **abstract class extending
the target** to gain protected-type access breaks calling code instead: Java
only permits a compile-time cast between two *unrelated concrete classes* if
one is a real sub/supertype of the other, so external code can no longer cast
to the mixin class directly (confirmed via a real crash/compile failure, not
theory). The fix, learned the hard way (`steam-cloud-sync`,
`WorldSelectionListInvokerMixin`): split into two types —

1. A plain, untyped **duck interface** (e.g. parameters/returns as `Object`
   instead of the real protected type) that calling code casts to. A
   class-to-interface cast always compiles, deferring the real check to
   runtime, by which point Mixin has merged the real implementation in.
2. The actual `@Mixin`-annotated **abstract class**, extending the target's
   **raw** (non-generic) form to avoid a separate ordering problem (a
   protected type referenced in the class's own generic bound is resolved
   before the subclass relationship that would otherwise grant access to it),
   `implements` the duck interface, and delegates to the real, correctly-typed
   `@Invoker`/`@Accessor` method.

**The duck interface must not live in the mod's declared Mixin package**
(`*.mixins.json`'s `"package"` value, `de.lazuli.mixin` in this repo) — Sponge
Mixin's classloader throws `IllegalClassLoadError` for any direct external
reference to *anything* in that package, mixin-annotated or not. Put duck
interfaces in a sibling package (this repo's convention: `de.lazuli.duck`).

**Superseded — the duck-interface pattern above does not actually work,
confirmed via a real in-game crash across every platform module.** Sponge
Mixin's hierarchy validator rejects a mixin class that declares
`extends TargetItself` (the exact shape the duck-interface fix requires,
since a real subclass relationship is the only way to name a protected
nested type from outside its package): `InvalidMixinException: Super class
'X' of Y was not found in the hierarchy of target class 'X'`. It compiles
fine and can even boot without visible errors, which is what made it look
correct at first — the error only surfaces once something actually loads the
target class. A follow-up attempt to dodge this by declaring the plain
`@Invoker` interface directly inside the target's own package (no `extends`
needed at all, gaining access via same-package protected visibility instead
of inheritance) avoids the hierarchy-validator bug, but Sponge Mixin's
mixin-package-ownership mechanism claims the *entire* declared package, not
just the mixin classes inside it — an immediate crash trying to load any
*unrelated* vanilla class that happens to share that package.

**The fix that actually works: abandon `@Mixin`/`@Invoker` for this specific
problem and use plain reflection instead.** Find the target method via
`getDeclaredMethods()` filtered by name and parameter count, `setAccessible(true)`,
and `invoke(...)` with a plain `Object` argument — no Java source-level type
is ever written for the protected parameter, so there is nothing for a
descriptor mismatch, a hierarchy validator, or a package-ownership rule to
reject. `setAccessible(true)` works here because Fabric Loader's classloader
does not run mods under strict JPMS encapsulation. This is less "elegant"
than a generated `@Invoker`, but it is the version that survived actual
in-game testing after three Mixin-based designs each failed differently —
prefer it directly for this exact situation (needing to call a target's own
protected method whose parameter type is also protected) rather than
re-attempting the Mixin route.

## Pattern 3: rendering/click-handling inside an *existing* real list entry

Different from Pattern 2 (which creates whole new synthetic rows): this is
for adding a small piece of UI (an icon, a toggle) *inside* a row that
already exists as a real vanilla object (e.g. a per-world sync-toggle icon
drawn into every real world's own row, rather than a separate overlay
button). Confirmed simpler than Pattern 2's `@Invoker` case: the concrete row
class itself (e.g. `WorldListWidget.WorldEntry` / `WorldSelectionList.WorldListEntry`)
is `public final` on both sides of the obfuscation boundary — no protected-type
naming problem, so no duck-interface split is needed here. A plain `@Inject`
-style `@Mixin` targeting the concrete row class hooks `render`/`mouseClicked`
directly (that mechanism is not the problem below — it worked correctly the
first time).

**Reading the row's own bounds/identity accessors (`getX()`/`getY()`/
`getWidth()`/`getLevelSummary()`/`getLevel()`) is a second, separate trap,
confirmed via a real in-game crash even though these methods are all
`public`.** The obvious approach — `@Shadow public abstract int getX();` —
compiles fine, but fails at Mixin-apply time with
`InvalidMixinException: @Shadow method getX()I ... was not located in the
target class ... $WorldEntry`. The reason: these accessors are declared on
an *ancestor* class (e.g. `AbstractSelectionList.Entry`/`EntryListWidget.Entry`),
not on the concrete row class itself — `@Shadow` only resolves members
declared directly on the exact `@Mixin` target, not merely inherited ones.
**Fix: read them via plain reflection instead**, using `Class.getMethod(name)`
(not `getDeclaredMethod`) — `getMethod` searches the full *public*
inheritance chain automatically, so it finds an inherited public accessor
without needing to know or name the exact ancestor class that declares it
(no `setAccessible(true)` needed either, since these are already public). Cache
the resolved `Method` once per accessor in a small companion reflection
helper class, call it from the `@Inject` handler instead of `this.getX()`.

- Render method: confirmed to take `(graphics, mouseX, mouseY, hovering,
  partialTick)` on both sides — no explicit x/y/width/height parameters; read
  the row's own bounds accessors instead of assuming they're passed in.
  `@Inject(method = "render"/"extractContent", at = @At("TAIL"))` to draw
  after vanilla's own content.
- Click method: confirmed to take a **click-event record** (`.x()`/`.y()`/
  `.button()`) plus a `boolean` (double-click flag) on both sides — not the
  legacy `(double, double, int)` shape. `@Inject(method = "mouseClicked",
  at = @At("HEAD"), cancellable = true)`: check the event's coordinates
  against your icon's bounds first, and if hit, act and cancel (so vanilla's
  own row-click handling — open world, etc. — doesn't also fire).
- A plain `graphics.fill(x1, y1, x2, y2, argbColor)` call is the established,
  working way to draw a simple colored-indicator icon in this codebase
  (`CloudOnlyWorldListEntry`) — it avoids needing to register a real
  texture/sprite, which on ≥26.1 requires an extra `RenderPipeline` argument
  on `blitSprite` (a further rendering-API surface not otherwise needed here).
- Since a real `@Mixin` class (not a duck-interface pair) has no constructor
  parameters vanilla code will ever supply, bridge it to this feature's own
  service (e.g. a `WorldSyncToggleHook`) the same way `SteamworksServiceHandoff`
  already bridges `SteamworksService` to a second composition-root entrypoint:
  a small static holder, `publish(...)`-ed once by the platform composition
  root at startup, `require()`-d by the injected handler method.
- Exact class/method names per version are recorded in `minecraft.md`'s Known
  Cross-Version API Differences table — confirm via `javap` before writing
  the mixin, don't assume continuity from Pattern 2's findings just because
  the class hierarchy is related.

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

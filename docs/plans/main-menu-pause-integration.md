# Plan: Extend Main Menu ("Stonebound") to Serve as the In-Game Pause Menu

Spec: `docs/specs/main-menu-pause-integration.md`. This plan resolves Open
Questions #4-#6 and FR5's injection points/FR5.3/FR6 mechanisms with concrete
decisions. No implementation code is written here.

## Existing implementation (verified this pass)

- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java`: plain enum,
  `HOME, WORLDS, SERVERS, STORE, WARDROBE, ACHIEVEMENTS, STATISTICS, TWEAKS`,
  no Minecraft imports.
- `platform/*/.../mainmenu/MainMenuScreen.java` (identical shape on all three
  platforms, read in full on `fabric-26.2`): `extends Screen`, holds a
  `MainMenuStateMachine state = new MainMenuStateMachine(MainMenuTab.HOME)`
  (hard-codes `HOME` as initial tab), a `static final MainMenuTab[] TABS =
  MainMenuTab.values()` used both to render the tab bar
  (`renderTabBar`) and to hit-test clicks (`mouseClicked`), a
  `MainMenuBackgroundRenderer background` rendered unconditionally first in
  `extractRenderState`/`render`, `tabLabel(MainMenuTab)` switch (exhaustive,
  no default), hard-coded `shouldCloseOnEsc() -> false` and
  `isPauseScreen() -> false` overrides (lines ~159-167 on fabric-26.2,
  confirmed identical on the other two platforms per spec's Current State).
  Single public constructor taking every collaborator directly (no context
  parameter today).
- `features/mainmenu/services/MainMenuStateMachine.java`: plain-JVM class, two
  constructors — no-arg (`activeTab = null`) and `MainMenuStateMachine(MainMenuTab
  initialTab)`. No Minecraft imports; safe to keep passing `MainMenuTab`
  values from platform code.
- `platform/*/.../MainMenuScreenFactoryHandoff.java` (identical shape on all
  three): static holder publishing a `Supplier<Screen>`, `publish(Supplier<Screen>)`
  / `require(): Supplier<Screen>`. Consumed by `MainMenuClientInitializer`
  (constructs+publishes at boot, also used for the `CLIENT_STARTED` initial
  screen) and by `GuiTitleScreenRedirectMixin`/`ClientTitleScreenRedirectMixin`.
- `platform/fabric-26.2/.../mixin/GuiTitleScreenRedirectMixin.java` (fabric-26.1
  identical; `fabric-1.21.11`'s `ClientTitleScreenRedirectMixin` is the Yarn
  port): `@Mixin(Gui.class)` (fabric-1.21.11: `@Mixin(MinecraftClient.class)`),
  single `@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly =
  true)` swapping any `Screen screen instanceof TitleScreen` for
  `MainMenuScreenFactoryHandoff.require().get()`. This is the *single choke
  point* every vanilla "return to title" path already funnels through
  (`Gui.setScreen(Screen)` on 26.x, `MinecraftClient.setScreen(Screen)` on
  1.21.11 — both mixins' own Javadoc cite this as `javap`-confirmed).
- `platform/*/.../MainMenuClientInitializer.java` (read in full on
  fabric-26.2): builds every collaborator, then `Supplier<Screen> screenFactory
  = () -> buildScreen(...)`, publishes it via `MainMenuScreenFactoryHandoff.publish(screenFactory)`,
  and registers `ClientLifecycleEvents.CLIENT_STARTED.register(client ->
  Minecraft.getInstance().setScreenAndShow(screenFactory.get()))` for the
  initial boot screen. `buildScreen(...)` is a private static method taking
  every dependency as a parameter and calling `new MainMenuScreen(...)`.
- `platform/fabric-26.2/.../resources/lazuli.mixins.json`: flat `"mixins"`
  array; `GuiTitleScreenRedirectMixin` already registered. Same shape/list
  structure per module (each module owns its own file/list).
- `.claude/context/architecture.md`: API layer forbids Minecraft imports;
  Platform may depend on Feature classes only at its composition root
  (`MainMenuClientInitializer` is exactly that root, ADR 0001).
- Cross-version API table (`.claude/context/minecraft.md`) already documents
  the `Gui`/`MinecraftClient` `setScreen` choke-point divergence (the exact
  row this feature's own title-screen mixin came from) — this plan's pause
  trigger reuses the same divergence, no new one needed for that part.

## Open Questions — resolved

### #4 — New tab naming and HOME/PAUSE swap

- Add `PAUSE` as a new member of `api/.../MainMenuTab.java`, appended after
  `TWEAKS` (keeps the existing seven members' ordinal values/switch case
  ordering untouched everywhere they're already matched exhaustively).
- The tab **bar's positional slot** currently occupied by `HOME` is not a
  property of the enum itself — it's the `TABS` array's iteration order in
  each platform `MainMenuScreen`. Per-context tab list: replace the single
  `static final MainMenuTab[] TABS = MainMenuTab.values()` field with an
  instance method `MainMenuTab[] visibleTabs()` returning a context-filtered,
  order-preserving array: main-menu context → `{HOME, WORLDS, SERVERS, STORE,
  WARDROBE, ACHIEVEMENTS, STATISTICS, TWEAKS}` (unchanged order/count);
  pause context → the same array with `HOME` replaced by `PAUSE` at the same
  index (`{PAUSE, WORLDS, SERVERS, STORE, WARDROBE, ACHIEVEMENTS, STATISTICS,
  TWEAKS}`). Implement as two `private static final MainMenuTab[]` constants
  (`MAIN_MENU_TABS`, `PAUSE_TABS`) selected by context in `visibleTabs()`,
  not a runtime array-copy-and-mutate — clearer intent, no allocation per
  frame. All existing `renderTabBar`/`mouseClicked` loop sites switch from
  `TABS` to `visibleTabs()`.
- `tabLabel(MainMenuTab)`'s switch gains one new arm: `case PAUSE -> "Pause"`
  wired into each platform's `en_us.json` under a new translation key
  (mirrors how `HOME`'s label is already just an inline literal, not a
  translation key, per the file read — implementer's choice to keep either
  inline-literal or i18n-key consistent with whatever the other tab labels
  do at implementation time; not load-bearing to this plan).

### #5 — Fixing context at construction

- New api-layer enum: `api/src/main/java/de/lazuli/api/mainmenu/MainMenuContext.java`,
  `public enum MainMenuContext { MAIN_MENU, PAUSE }`, zero Minecraft imports
  (NFR2). This is the "enum, not boolean flag" option from the spec's Q5
  list — self-documenting at every call site, extensible if a third context
  is ever needed, and matches this codebase's existing preference for
  context enums over booleans (`MainMenuStateMachine.ServersSubView`).
- `MainMenuScreen`'s constructor (all three platforms) gains one new leading
  parameter: `MainMenuContext context`. Stored as a `private final
  MainMenuContext context` field, read by: `visibleTabs()` (#4 above),
  `shouldCloseOnEsc()`/`isPauseScreen()` (below), `extractRenderState`/`render`'s
  background branch (#6 below), and `MainMenuStateMachine`'s initial-tab
  argument (`context == PAUSE ? MainMenuTab.PAUSE : MainMenuTab.HOME`,
  satisfying FR3.4/FR6.2's "same default-active-tab role" requirement — the
  existing `MainMenuStateMachine(MainMenuTab initialTab)` constructor already
  supports this with zero changes to that class).
- `MainMenuScreenFactoryHandoff` changes shape: `Supplier<Screen>` →
  `Function<MainMenuContext, Screen>` (rename the stored/returned type only;
  keep `publish`/`require` method names). This is a **mechanical, identical
  change across all three platform modules** (the class is otherwise
  boilerplate-identical per module).
- `MainMenuClientInitializer.buildScreen(...)` gains a leading `MainMenuContext
  context` parameter, passed straight through to `new MainMenuScreen(context,
  ...)`. `screenFactory` becomes `Function<MainMenuContext, Screen> screenFactory
  = ctx -> buildScreen(ctx, ...)`. The `CLIENT_STARTED` boot-screen line
  becomes `screenFactory.apply(MainMenuContext.MAIN_MENU)`.
- `GuiTitleScreenRedirectMixin`/`ClientTitleScreenRedirectMixin`'s existing
  `instanceof TitleScreen` branch changes its call from
  `MainMenuScreenFactoryHandoff.require().get()` to
  `MainMenuScreenFactoryHandoff.require().apply(MainMenuContext.MAIN_MENU)`.
- FR5's new pause-trigger redirect (see FR5 section below) reuses this same
  changed handoff, calling `.apply(MainMenuContext.PAUSE)` when the screen
  being set is vanilla's pause screen.

### #6 — Blurred-world background per version

Vanilla's pause-menu blur is implemented as a `GameRenderer` post-process
effect (confirmed present since 1.21 per web research this pass; the
Minecraft/Fabric community's own naming for it — `processBlurEffect`/`blur.json`
post-chain — is consistent across the versions this repo targets, but the
**exact method/class name on each of this repo's three resolved jars was not
`javap`-confirmed this planning pass** — flagged as this plan's top
implementation-time spike, see Risks). Two additional facts *were* confirmed
this pass and are load-bearing to the design:

- The vanilla pause-screen class itself is named differently per mapping,
  matching this repo's existing `TitleScreen`-redirect precedent exactly:
  **Yarn (`fabric-1.21.11`)**: `net.minecraft.client.gui.screen.GameMenuScreen`
  (confirmed via Fabric's own hosted Yarn javadoc, `yarn-1.21.4+build.8` and
  neighboring builds — the class has never been named `PauseScreen` on the
  Yarn side). **Mojmap (`fabric-26.1`/`fabric-26.2`)**: `net.minecraft.client.gui.screens.PauseScreen`
  (Mojang's own official name, consistent with every other Mojmap class this
  repo's table already documents). Add this as a new row to
  `.claude/context/minecraft.md`'s cross-version table once the
  implementation's own `javap` pass reconfirms it against each module's
  resolved jar (per that table's own "append when implementation work turns
  up a real divergence" rule) — do not add it before that reconfirmation.
- Design: `MainMenuScreen`, in `PAUSE` context, must **not** rely on being
  `instanceof` vanilla's pause-screen class to receive the blur (it is a
  `MainMenuScreen`, not a `GameMenuScreen`/`PauseScreen`). Two candidate
  mechanisms, in priority order for the implementer to try (this plan does
  not mandate one without the spike, since the spec explicitly defers this
  to a planning-phase spike that is itself deferred to implementation per
  the Risks section — a targeted `javap` pass is required before code is
  written, not before this plan is approved):
  - **Preferred**: the blur post-process call is a directly-invokable method
    on the render singleton (e.g. `Minecraft.getInstance().gameRenderer`-shaped
    call), not internally gated by an `instanceof` check on the current
    screen. If so, `MainMenuScreen.extractRenderState`/`render`, in `PAUSE`
    context only, calls that method itself (in place of
    `MainMenuBackgroundRenderer.render(...)`, per FR2.1) before drawing the
    tab bar/panel/sidebar, mirroring FR2.3's "layers on top of it" ordering.
    No mixin needed for this part.
  - **Fallback**: if the blur trigger is internally gated by an `instanceof`
    check against vanilla's pause-screen class (inside `GameRenderer`/`Gui`'s
    own per-frame render loop, not something `MainMenuScreen` can call
    itself), add one more `@ModifyExpressionValue`/`@Redirect`-style mixin
    (new file, e.g. `PauseBlurGateMixin`, one per platform module) widening
    that specific `instanceof` check to also match "`MainMenuScreen` whose
    `context == MainMenuContext.PAUSE`" — same minimal-footprint discipline
    as the existing title-screen redirect, just gating a boolean expression
    instead of substituting a variable.
  - Either way, `MainMenuBackgroundRenderer.render(...)` (the continuous 3D
    scene) is simply not called when `context == MainMenuContext.PAUSE`
    (FR2.1) — this branch itself needs no spike, only the "what draws
    instead" half does.

## FR5 — Pause trigger mixin design

- FR5.1/FR5.2: reuse the **exact same choke point** the title-screen redirect
  already uses (`Gui.setScreen(Screen)` on 26.x / `MinecraftClient.setScreen(Screen)`
  on 1.21.11) rather than intercepting Esc key-handling directly. Rationale:
  vanilla's own Esc-opens-pause-menu path (`Minecraft.pauseGame(boolean)` or
  equivalent, name/exact call chain to be `javap`-confirmed at
  implementation time, not this plan) itself ultimately constructs vanilla's
  pause-screen class and hands it to that same `setScreen` method — this was
  not independently `javap`-confirmed this pass (spec FR5.2 explicitly notes
  the call sites were "not `javap`/bytecode-confirmed during this spec
  pass" and defers that confirmation to planning's own spike; this plan
  defers the actual bytecode read to implementation's mandatory first step,
  consistent with this repo's established practice for every other
  cross-version divergence in `.claude/context/minecraft.md`'s table, all of
  which were confirmed via real compiles/`javap`, not guessed).
- Concrete design (pending that spike confirming the call chain funnels
  through the same `setScreen`/`Gui.setScreen` method): extend each existing
  redirect mixin (`GuiTitleScreenRedirectMixin` / `ClientTitleScreenRedirectMixin`)
  with one more branch in the same `@ModifyVariable` method body — `else if
  (screen instanceof GameMenuScreen /* or PauseScreen on 26.x */) { return
  MainMenuScreenFactoryHandoff.require().apply(MainMenuContext.PAUSE); }` —
  rather than a second mixin class, since it's the same method/target/pattern,
  just one more `instanceof` arm. Rename the shared private method from
  `lazuli$redirectTitleScreenToMainMenu` to `lazuli$redirectVanillaScreensToMainMenu`
  and update its Javadoc to describe both branches. No `lazuli.mixins.json`
  change needed (the mixin class is already registered).
- **If** the implementation spike finds vanilla's Esc-to-pause path does
  *not* funnel through that same choke point (e.g. it constructs+shows the
  pause screen via some other route not covered by the existing mixin's
  `javap` findings — a real possibility the title-screen mixin's own Javadoc
  already flags as a known limitation of its non-exhaustive `javap` pass),
  fall back to a new, separate mixin targeting whatever the spike finds is
  the real construction/injection call site (likely `Screen`'s own Esc
  key-handling method, or `GameOptions`/`Minecraft`'s pause-trigger method) —
  new file per platform (e.g. `PauseTriggerRedirectMixin`), registered in
  each module's `lazuli.mixins.json`, following the same `@ModifyVariable`-
  or `@Redirect`-on-construction pattern, whichever the target method's
  shape supports. This plan intentionally does not pre-guess that fallback's
  exact injection point beyond "the real call site the spike finds" — doing
  so without `javap` evidence would violate `.claude/context/minecraft.md`'s
  "never invent APIs" rule.

## FR5.3 — Esc-inside-pause-screen resume

- `shouldCloseOnEsc()` becomes context-conditional: `return context ==
  MainMenuContext.PAUSE;` (main-menu context keeps today's `false`
  unchanged, satisfying AC6). Vanilla's own base `Screen.shouldCloseOnEsc()`
  already defaults to `true` and vanilla's pause screen never overrides it
  to `false` — this restores that same default only in `PAUSE` context, so
  Esc while the pause-context screen is open falls through to `Screen`'s own
  vanilla close-on-Esc handling (`onClose()`), which already resumes
  gameplay by clearing the current screen (existing `MainMenuScreen.onClose()`
  override already tears down `serversPanel`/`tweaksPanel` state — unchanged,
  runs in both contexts, satisfies FR6.3's "no double-fire/leftover-paused-state"
  bar by reusing vanilla's own resume path rather than a hand-rolled one).
- FR3.3.1's "Return to Game" button (new, on the Pause tab's panel — see
  Files to Create) triggers the identical resume path: call `this.onClose()`
  (not a raw `Minecraft.getInstance().setScreen(null)`), so both the button
  and Esc go through the exact same one method — avoids duplicating the
  panel-teardown logic between two call sites.

## FR6 — `isPauseScreen()` context-conditional

- `isPauseScreen()` becomes `return context == MainMenuContext.PAUSE;` —
  full stop, no singleplayer/LAN branching inside `MainMenuScreen` itself.
  Rationale (mirrors vanilla's own division of responsibility, not a new
  mechanism per FR6.3's "not a new mechanism to invent" bar): vanilla's
  pause-screen class itself unconditionally overrides `isPauseScreen()` to
  `true`; the actual singleplayer-vs-multiplayer/LAN gating (FR6.1) already
  lives *outside* the screen, in `Minecraft`/`Gui`'s own per-tick "is the
  game currently paused" check, which combines `screen.isPauseScreen()` with
  its own independent `hasSingleplayerServer() && !isPublished()`-shaped
  condition (exact method names not `javap`-confirmed this pass — same
  spike as FR5/#6 above covers this, since it's the same vanilla class
  family). Because that combining logic is vanilla's own and entirely
  outside `MainMenuScreen`, simply matching vanilla's screen-level override
  (`true` in pause context) is sufficient — no reimplementation of the
  singleplayer/LAN rule inside this feature at all, and no risk of it
  drifting from vanilla's own rule in the future.
- Main-menu context keeps `isPauseScreen() -> false` unconditionally
  (AC6/FR6.2), unchanged from today.

## Files to create

- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuContext.java` — new
  two-member enum, no Minecraft imports (NFR2).
- Per platform module (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`), one
  new Pause-tab panel class mirroring the shape of the existing per-tab panel
  classes (e.g. `de.lazuli.mainmenu.PausePanel`, sibling to
  `HomePanel`/`WorldsPanel`/etc.): `init(...)`/`render(...)`/`mouseClicked(...)`
  matching whatever subset of those methods the existing panels expose
  (confirm exact shape against one real panel, e.g. `HomePanel`, at
  implementation time — not re-derived here since panel internals are
  Non-goal per spec). Content is exactly one "Return to Game" button (FR3.3.1/AC8)
  wired to call the hosting `MainMenuScreen`'s `onClose()` (FR5.3 above).
- **Only if** FR6's spike finds the blur trigger is internally `instanceof`-gated
  (FR6/#6 fallback branch): one new mixin per platform module (e.g.
  `PauseBlurGateMixin`), registered in that module's `lazuli.mixins.json`.
- **Only if** FR5's spike finds Esc-to-pause does not funnel through the
  existing `setScreen` choke point (FR5 fallback branch): one new mixin per
  platform module (e.g. `PauseTriggerRedirectMixin`), registered in that
  module's `lazuli.mixins.json`.

## Files to modify

Per platform module (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`):

- `.../mainmenu/MainMenuScreen.java`: add `MainMenuContext context`
  constructor parameter/field; `visibleTabs()` replacing the `TABS` constant
  at every call site; `tabLabel(...)` gains a `PAUSE` arm; construct
  `MainMenuStateMachine` with a context-conditional initial tab;
  `shouldCloseOnEsc()`/`isPauseScreen()` become context-conditional (FR5.3/FR6
  above); `extractRenderState`/`render`'s background call becomes
  context-conditional (#6 above, 3D scene vs. blur); one new `switch` arm
  routing `MainMenuTab.PAUSE` to the new panel in both the render dispatch
  and `mouseClicked` dispatch (mirroring every existing tab's two dispatch
  sites).
- `de.lazuli.MainMenuScreenFactoryHandoff.java`: `Supplier<Screen>` →
  `Function<MainMenuContext, Screen>` throughout (mechanical rename, #5
  above).
- `de.lazuli.MainMenuClientInitializer.java`: `buildScreen(...)` gains a
  leading `MainMenuContext` parameter passed to `new MainMenuScreen(...)`;
  `screenFactory` becomes a `Function`; `CLIENT_STARTED` boot line passes
  `MainMenuContext.MAIN_MENU` explicitly.
- `de.lazuli.mixin.GuiTitleScreenRedirectMixin.java` (fabric-26.1/26.2) /
  `de.lazuli.mixin.ClientTitleScreenRedirectMixin.java` (fabric-1.21.11):
  add the `PAUSE`-context branch (FR5 above); update the class's own Javadoc
  to describe both branches and cite this spec/plan.
- `src/main/resources/assets/lazuli/lang/en_us.json`: one new translation
  entry for the Pause tab's label (if the implementer chooses the i18n-key
  route over an inline literal, per #4 above).
- `src/main/resources/lazuli.mixins.json`: only touched if either FR5 or FR6
  fallback mixin ends up needed (see Files to Create).

`api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java`: add `PAUSE`
member (shared across all three platforms, single file, #4 above).

Not touched: `features/main-menu/services/MainMenuStateMachine.java` (its
existing `MainMenuTab initialTab` constructor already supports this
unchanged), every other existing panel class (Worlds/Servers/Store/Wardrobe/
Home/Achievements/Statistics/Tweaks — FR4, Non-goals), `MainMenuBackgroundRenderer`
(called conditionally, not itself modified), `docs/specs/unified-mainmenu-background.md`'s
own in-progress scope (explicitly out of scope per spec Non-goals).

## Dependencies

No new external (non-Fabric, non-Minecraft) dependencies. All work uses
classes/mechanisms already present in each platform module's existing Loom/
Minecraft/Fabric dependency set (Sponge Mixin, already-declared
`fabric-api` modules) — no new Maven coordinate to verify against a
registry.

## Risks

1. **Highest priority, blocks FR2.2/#6**: the exact blur post-process
   method/class and whether it's internally gated by `instanceof` on
   vanilla's pause-screen class was not `javap`-confirmed this planning
   pass (web research only, see #6 above). Implementation's mandatory first
   step is a `javap -p`/`javap -c` pass against each of the three resolved
   Minecraft jars (mirroring every other cross-version divergence's
   confirmation method in `.claude/context/minecraft.md`) to pick between
   this plan's "preferred" (directly call it) vs. "fallback" (widen an
   `instanceof` gate via mixin) design before writing any render code.
2. **Blocks FR5.1/FR5.2**: whether vanilla's Esc-to-pause path really
   funnels through the same `setScreen`/`Gui.setScreen` choke point the
   title-screen redirect already uses was not independently confirmed this
   pass either (spec FR5.2 flags this explicitly). Same `javap` spike as
   Risk 1 should confirm/refute this before the mixin edit is made — if it
   funnels through a different site, the fallback new-mixin design (FR5
   above) applies instead, with real implementation cost (three new mixin
   files instead of a three-line edit to three existing ones).
3. Risks 1 and 2 are independent (different vanilla call sites/classes) but
   likely investigated in the same `javap` session against the same three
   resolved jars — sequence the spike to answer both before writing any
   platform code, not as two separate passes.
4. `fabric-1.21.11`'s remap pipeline (`fabric-loom-remap`, not present on the
   26.x modules) has a documented history in this repo of `@Invoker`/generic-
   parameter remap warnings (`.claude/context/minecraft.md`'s
   `steam-cloud-sync` row) — the extended `ClientTitleScreenRedirectMixin`
   branch here uses a plain `instanceof`/`@ModifyVariable`, not an
   `@Invoker`, so this specific pitfall is not expected to recur, but the
   verification phase should still do a real `:platform:fabric-1.21.11:remapJar`
   run (not just a compile) to confirm, consistent with that module's own
   established extra-scrutiny precedent.
5. Uncommitted, in-progress rework already in the working tree (per spec's
   Current State: `unified-mainmenu-background`, `MainMenuJson` config
   split, etc.) touches the same files this plan modifies
   (`MainMenuScreen.java` on every platform). Implementation should rebase/
   re-read those files' *current* state immediately before editing, not
   assume this plan's line-level descriptions (none given, deliberately)
   still match by the time implementation starts.

## Test strategy

- `MainMenuTab`/`MainMenuContext` (api layer, no Minecraft imports): no
  automated test needed beyond compilation — both are trivial enums.
- `MainMenuStateMachine`: already plain-JVM-testable (existing pattern per
  its own Javadoc's usage example) — add/extend a unit test asserting
  `new MainMenuStateMachine(MainMenuTab.PAUSE).activeTab() == MainMenuTab.PAUSE`
  if this project has an existing JUnit setup for `features/main-menu`
  (confirm at implementation time; not verified this pass since the spec's
  Non-goals exclude re-deriving panel-internal test infrastructure).
- Everything else in this feature (mixins, `Screen` overrides, render
  branching) is Minecraft-runtime-dependent and cannot be unit tested per
  this codebase's existing precedent (every prior mixin-based feature in
  `.claude/context/minecraft.md`'s table was verified via real launches/
  `javap`/real compiles, not unit tests) — verification is manual, per
  Acceptance Criteria below, run on **all three platform modules**
  independently (AC7), each via a real client launch (dev-run), not just a
  compile.
- Real-compile check (not just IDE syntax) is mandatory for all three
  modules before manual verification, per Risk 4's `fabric-1.21.11`
  `remapJar`-specific note.

## Acceptance Criteria mapping

- **AC1** (Esc opens `MainMenuScreen`, Pause tab active, blurred background,
  no 3D scene): verify via FR5's mixin (Esc → `setScreen`/redirect →
  `MainMenuContext.PAUSE`), FR3.4's context-conditional initial tab, and
  #6's blur-vs-3D branch. Manual test: launch each platform's dev client,
  join/create a singleplayer world, press Esc, confirm all three sub-claims
  visually.
- **AC2** (Esc/"Return to Game" resumes + removes screen + restores input):
  verify via FR5.3 (`shouldCloseOnEsc()` true in pause context +
  `onClose()`) and the new panel's button calling the same `onClose()`.
  Manual test: from the pause screen opened for AC1, press Esc once (should
  close) and separately (fresh pause-screen open) click "Return to Game"
  (should close identically) — confirm movement/input works immediately
  after either path, no double-open/stuck-screen.
- **AC3** (Home never in pause context, Pause never in main-menu context):
  verify via `visibleTabs()`'s two fixed arrays (#4). Manual test: visually
  confirm the tab bar's contents differ between a freshly-booted title
  screen and an Esc-opened pause screen.
- **AC4** (singleplayer ticking pauses, multiplayer/LAN/realms does not,
  both resume correctly): verify via FR6's `isPauseScreen()` delegation to
  vanilla's own existing singleplayer/LAN rule (no new logic to break).
  Manual test: (a) singleplayer world, Esc, confirm mobs/time freeze, Esc
  again, confirm they resume; (b) join a multiplayer/LAN-hosted world (or
  open-to-LAN singleplayer), Esc, confirm the world keeps ticking (e.g.
  visible mob movement) while the pause screen is open.
- **AC5** (non-Home/Pause tabs identical in both contexts): no context
  branching was added inside any existing panel class (Files to Modify
  explicitly excludes them). Manual test: from a pause-context screen,
  visit Worlds/Servers/Store/Wardrobe/Achievements/Statistics/Tweaks and
  confirm each behaves identically to the same tab from the title screen.
- **AC6** (main-menu context unchanged: 3D background, Home tab,
  `isPauseScreen()` false): verify via every context-conditional branch's
  `MAIN_MENU` arm being the pre-existing behavior, unchanged. Manual test:
  boot each platform's dev client fresh, confirm the title screen looks/
  behaves exactly as before this feature (3D background, Home tab present,
  no regression from the Pause-tab/context-plumbing changes).
- **AC7** (consistent across all three platform modules): every Files to
  Modify/Create entry above is listed per-module; verification phase must
  repeat every other AC's manual test independently on `fabric-1.21.11`,
  `fabric-26.1`, and `fabric-26.2` — not just one representative module.
- **AC8** (Pause tab contains only "Return to Game"): verify via the new
  panel class's content (Files to Create above) — no Options/Save-and-Quit
  widget added to it, those remain reachable only via the existing
  persistent sidebar (already present in both contexts, unmodified).
  Manual test: visually confirm the Pause tab's panel area contains exactly
  one button/action.

# Spec: Extend Main Menu ("Stonebound") to Serve as the In-Game Pause Menu

Status: specification only (no plan, no implementation code in this document).
Owner feature: `features/main-menu`, consumed by
`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`.

## Current state (verified against uncommitted working tree, not a clean baseline)

The "Stonebound" main menu already fully replaces vanilla's `TitleScreen`
(prior features, `unified-mainmenu-background` spec, and further
uncommitted rework in progress right now — see `git status`/`git diff`,
30+ files touched, including an in-flight `MainMenuJson` config-split and a
`docs/idea-collection/tweaks` rename). None of that in-progress rework
touches pause-menu behavior; this spec only adds new scope on top of it.

Confirmed structure, per-platform-module (`fabric-1.21.11`, `fabric-26.1`,
`fabric-26.2`, each an independent Yarn/Mojmap-mapped copy of the same
design):

- `MainMenuScreen` (`platform/*/src/main/java/de/lazuli/mainmenu/`) is a
  `Screen` subclass composing: `MainMenuBackgroundRenderer` (continuous 3D
  background), a right-hand tab bar over `MainMenuTab` (`api/src/main/java/
  de/lazuli/api/mainmenu/MainMenuTab.java`: `HOME, WORLDS, SERVERS, STORE,
  WARDROBE, ACHIEVEMENTS, STATISTICS, TWEAKS`), one panel class per tab, and
  a left-docked friends sidebar. `MainMenuStateMachine` tracks the active
  tab.
- `MainMenuScreen` currently overrides `shouldCloseOnEsc() -> false` and
  `isPauseScreen() -> false` (confirmed at
  `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/MainMenuScreen.java:159-167`,
  identical on the other two platforms) — Esc currently does nothing on this
  screen, and it never pauses a running world (consistent with it only being
  used as a *title* screen today; it is never opened while a world is
  running).
- `MainMenuClientInitializer` is the composition root: it builds one
  `MainMenuScreen` factory (`MainMenuScreenFactoryHandoff`) used both at
  client boot and by a per-platform title-screen redirect mixin
  (`GuiTitleScreenRedirectMixin` on `fabric-26.1`/`fabric-26.2`,
  `ClientTitleScreenRedirectMixin` on `fabric-1.21.11`), which redirects
  every vanilla "return to title screen" call site (confirmed via `javap`
  against each version's own resolved jar, see those mixins' own Javadoc) to
  construct a fresh `MainMenuScreen` instead. This is the existing "single
  choke-point mixin redirecting a vanilla screen class, via `@ModifyVariable`
  on the vanilla screen-setter method" pattern this spec's own pause-menu
  trigger will follow.
- Each platform module has its own `lazuli.mixins.json` mixin list (e.g.
  `platform/fabric-26.2/src/main/resources/lazuli.mixins.json:14` registers
  `GuiTitleScreenRedirectMixin`) and its own `MainMenuBackgroundRenderer` (3D
  character + scenery render, actively being unified across platforms per
  `docs/specs/unified-mainmenu-background.md`, in progress, not yet
  complete/committed).
- `MainMenuTab` is an `api`-layer enum (no Minecraft imports, per
  `.claude/context/architecture.md`'s layering rules); tab labels live in
  each platform module's own `MainMenuScreen.tabLabel(...)` switch and
  `en_us.json`.

## Goal

Reuse the exact same `MainMenuScreen`/tab framework as vanilla's in-game
pause menu (Esc while a world is running), replacing vanilla's `PauseScreen`
outright — not a separate feature, not a second screen class. One
screen/tab system serves two contexts: title-screen ("main-menu context")
and in-world pause ("pause context").

## Non-goals

- Not redesigning the tab framework, panel classes, or any existing tab's
  content (Worlds/Servers/Store/Wardrobe/Achievements/Statistics/Tweaks) —
  this spec only adds context-awareness (main-menu vs. pause) to the
  existing screen, plus one new tab.
- Not resolving the in-progress `unified-mainmenu-background` work or the
  other uncommitted main-menu rework currently in the working tree — this
  spec's scope layers on top of whatever state that work lands in.
- Not implementing anything — no code changes are made or planned by this
  document; HOW to implement (mixin injection points, class hierarchy,
  parameter threading) is explicitly deferred to the planning phase.
- Not adding "Save and Quit to Title," "Options," or any other vanilla
  `PauseScreen` action to the new Pause tab — confirmed unnecessary (see
  Resolved Questions below): Options and Save-and-Quit-to-Title are already
  reachable from `MainMenuScreen`'s persistent sidebar in both contexts, so
  the Pause tab's content stays limited to "Return to Game" only.

## Functional Requirements

**FR1 — Context-aware screen, not a second screen class**

FR1.1. The same `MainMenuScreen` class (per platform module) must serve
both the title-screen context and the in-world pause context. No new
`PauseMenuScreen`/duplicate class.

FR1.2. The screen must know which context it was opened in (main-menu vs.
pause) for the duration of its lifetime, determined at construction time —
not re-derived per frame from ambient state (e.g. "is a world currently
running") since that could be ambiguous or change while the screen is open.

**FR2 — Pause-context background: blurred world overlay, not the 3D scene**

FR2.1. When opened in pause context, the screen must NOT render
`MainMenuBackgroundRenderer`'s continuous 3D rotating background.

FR2.2. Instead, the pause-context background must match vanilla
`PauseScreen`'s own behavior: a blurred, semi-transparent view of the
frozen game world behind the screen (vanilla's existing panorama/blur
overlay mechanism), not a solid fill and not the 3D character scene.

FR2.3. All other rendering (tab bar, active panel, friends sidebar) is
unchanged between contexts and layers on top of this background exactly as
it does today over the 3D background.

**FR3 — Pause tab replaces Home tab in pause context**

FR3.1. In pause context, the tab that occupies the `HOME` tab's position
in the tab bar is replaced by a `PAUSE` tab (new `MainMenuTab` enum
member, or equivalent — naming/shape is a planning decision, not specified
here beyond "a pause-specific tab exists"). The `HOME` tab itself is not
shown in pause context.

FR3.2. In main-menu (title-screen) context, `HOME` continues to appear
exactly as today; `PAUSE` never appears there.

FR3.3. The Pause tab's content is limited to:

FR3.3.1. A "Return to game" action (button or equivalent) that closes the
pause screen and resumes gameplay, mirroring vanilla `PauseScreen`'s "Back
to Game" button. This is confirmed as the tab's entire content for now —
see Resolved Questions below (Options and "Save and Quit to Title" are
already available elsewhere in the reused screen's persistent sidebar and
do not need to be duplicated onto this tab).

FR3.4. `PAUSE` tab is the default active tab when the pause screen opens
(same role `HOME` plays as default active tab on the title screen today,
per `MainMenuStateMachine`'s construction with an initial tab — see
`features/main-menu/src/main/java/de/lazuli/features/mainmenu/services/MainMenuStateMachine.java:58`).

**FR4 — All other tabs are unchanged and available in both contexts**

FR4.1. `WORLDS, SERVERS, STORE, WARDROBE, ACHIEVEMENTS, STATISTICS, TWEAKS`
render and behave identically whether the screen is in main-menu or pause
context — same panel classes, same content, no context-conditional
behavior inside any of them. Confirmed: fully functional/unrestricted in
pause context, same as on the real main menu — no restrictions of any kind
(see Resolved Questions below).

**FR5 — Pause trigger (Esc while in a world) replaces vanilla `PauseScreen`**

FR5.1. Pressing Esc while a world is running (and no other screen is
already open) must open `MainMenuScreen` in pause context, exactly where
vanilla would have opened `PauseScreen`.

FR5.2. This must be implemented per platform module (three independent
mixin sets: `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`), following the
same "single choke-point mixin redirecting a vanilla screen class" pattern
already established by `GuiTitleScreenRedirectMixin`/
`ClientTitleScreenRedirectMixin` for the title screen (see Current State
above) — reusing that established pattern is a planning-phase decision, not
mandated verbatim here, but the precedent exists and should be considered.
The exact vanilla call site(s) that construct/open `PauseScreen` on each
platform were not `javap`/bytecode-confirmed during this spec pass (unlike
the title-screen redirect, whose call sites are already confirmed in the
existing mixins' own Javadoc) — that confirmation is left to the planning
phase's own spike.

FR5.3. Since `MainMenuScreen` currently hard-codes `shouldCloseOnEsc() ->
false`, Esc-to-close/resume behavior inside an already-open pause-context
screen must be provided some other way consistent with FR3.3.1's "Return to
game" action (e.g. Esc while the pause-context screen is open also resumes
gameplay, same as vanilla `PauseScreen`'s Esc-closes behavior) — whether
this reuses `shouldCloseOnEsc()` (made context-conditional) or a different
mechanism is a planning decision.

**FR6 — World-pause semantics (game-tick pausing) — IN SCOPE, must match vanilla**

FR6.1. Vanilla `PauseScreen` pauses world ticking in singleplayer via
`Screen.isPauseScreen()` (the exact method `MainMenuScreen` itself already
overrides, currently hard-coded `false` — see Current State) returning
`true` only when running an integrated singleplayer server not exposed to
LAN, and `false` for multiplayer/realms/LAN-opened singleplayer — per
vanilla's own long-standing rule. This behavior must be preserved
identically when `MainMenuScreen` is opened in pause context: singleplayer
world ticking pauses; multiplayer/realms/LAN-opened-singleplayer world
ticking does not pause. This is explicitly in scope, not deferred.

FR6.2. Main-menu context must continue to report `isPauseScreen() -> false`
unconditionally, unchanged from today (there is no running world to pause
in that context in the first place).

FR6.3. Opening/closing the pause-context screen must not itself
desync/break gameplay beyond what vanilla's own pause/resume already does
(e.g. resuming must not double-fire input, must not leave the world
permanently paused after closing). This is a correctness bar, not a new
mechanism to invent — vanilla already gets this right and the new screen
must not regress it.

## Non-Functional Requirements

**NFR1 — No duplication across platform modules or across contexts.**
The screen/tab/panel classes must be shared (parameterized or
constructed with context, not forked) both across the two contexts
(main-menu vs. pause) within a single platform module, and consistent in
approach across all three platform modules (each module still owns its own
mapped copy of the classes, per this codebase's existing multi-version
strategy — `common`/business logic shared, `platform/*` version glue — but
the *design* must not introduce a second parallel screen/tab hierarchy per
context).

**NFR2 — Consistent with `.claude/context/architecture.md` layering.**
`MainMenuTab` (api layer, no Minecraft imports) may gain a new member but
must stay free of Minecraft/Fabric imports. Any new pause-trigger mixins
belong in each platform module's own `lazuli.mixin` package and
`lazuli.mixins.json`, per the existing per-platform mixin pattern.

## Resolved Questions

1. **FR4 default — do all non-Home/Pause tabs stay identical in pause
   context?** **Resolved: yes.** User confirmed all other tabs
   (Worlds/Servers/Store/Wardrobe/Achievements/Statistics/Tweaks) are fully
   functional/unrestricted when accessed from the pause context, identical
   to the real main menu — no restrictions needed.
2. **Pause tab content beyond "Return to game."** **Resolved: no
   additional content needed.** Options and "Save and Quit to Title" do
   NOT need to be added to the Pause tab — they already exist in the
   persistent sidebar of `MainMenuScreen`, visible in both main-menu and
   pause contexts. The Pause tab's content stays exactly "Return to Game,"
   as originally requested.
3. **Options/accessibility access while paused.** **Resolved: already
   covered.** Same resolution as #2 — Options is already reachable via
   `MainMenuScreen`'s persistent sidebar in pause context, so no additional
   access point is needed on the Pause tab.

## Open Questions / Assumptions (remaining, for planning phase awareness)

4. **Naming of the new tab/enum member.** This spec calls it `PAUSE`
   throughout for concreteness; exact naming is a planning-phase/
   implementation detail, not load-bearing to this spec.
5. **Determining "context" at construction (FR1.2).** This spec requires
   context to be fixed at construction but does not mandate the mechanism
   (constructor flag, factory method, subclass-free parameter object, etc.)
   — left for planning, per architecture-agnostic conventions for this
   phase.
6. **Blurred-world background mechanism per platform version (FR2.2).**
   Vanilla's blur/panorama-behind-pause-screen rendering mechanism differs
   across the three supported Minecraft versions (matching the pattern
   already documented for the 3D main-menu background in
   `docs/specs/unified-mainmenu-background.md`'s own per-version API
   findings), and was not confirmed via `javap`/bytecode inspection during
   this spec pass. This is a planning-phase spike, not spec content.

## Acceptance Criteria (for later verification phase, not implementation)

- AC1. Esc while in a running world (singleplayer or multiplayer, no other
  screen open) opens `MainMenuScreen` with the Pause tab active, blurred
  world background, no 3D scene.
- AC2. Esc/"Return to game" while that screen is open resumes gameplay and
  removes the screen, restoring normal input.
- AC3. Home tab never appears in pause context; Pause tab never appears in
  main-menu (title) context.
- AC4. Singleplayer world ticking pauses while the pause-context screen is
  open; multiplayer/realms/LAN-opened-singleplayer world ticking does not
  pause. Both resume/continue correctly after closing.
- AC5. All non-Home/Pause tabs render and function in pause context exactly
  as they do in main-menu context.
- AC6. Title-screen (main-menu context) behavior is unchanged from before
  this feature: 3D background, Home tab present, `isPauseScreen() -> false`.
- AC7. Behavior is consistent across all three platform modules
  (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`).
- AC8. Pause tab contains only "Return to Game"; no additional actions are
  required on it (Options / Save-and-Quit-to-Title remain accessible via
  the existing persistent sidebar, not duplicated onto the tab).

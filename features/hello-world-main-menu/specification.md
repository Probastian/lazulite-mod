# Hello World Main Menu — Feature Specification

## Overview
Adds a small, self-contained "Hello World" demonstration to the game's title/main menu screen. This is the first entry under `features/`, and its primary purpose is to validate the boilerplate's end-to-end feature workflow (specification -> plan -> implementation -> verification) and the `Feature -> API -> Version Adapter -> Platform` layering pattern across all three currently supported targets: `platform/fabric-26.2` (Minecraft 26.2), `platform/fabric-26.1` (Minecraft 26.1), and `platform/fabric-1.21.11` (Minecraft 1.21.11).

## Motivation
No feature exists yet; `features/` is empty. Before larger features are attempted, the project needs one deliberately minimal, visually verifiable-in-game reference feature that proves:
- the required feature folder layout (`api/`, `config/`, `events/`, `gui/`, `mixins/`, `resources/`, `services/`, `tests/`, `README.md`) works in practice,
- version-specific differences (Mojang-mapped 26.x vs. Yarn-mapped 1.21.11, and the Screen render-state API change) can be isolated behind a stable `api/` abstraction without `if (MC_VERSION == ...)` branching in shared code, and
- a feature can be added without a mixin, using only officially documented Fabric API extension points.

## Goals
- Display the text "Hello World" (configurable, default `"Hello World"`) on the title screen on all three supported Minecraft/Fabric targets, with identical player-visible behavior.
- Use only officially documented, non-mixin Fabric API entry points (research confirms this is sufficient for this use case).
- Keep the vanilla title screen's existing buttons/functionality fully intact — this is an addition, not a replacement, for v1.
- Provide a simple per-feature config toggle (enable/disable) and text override.
- Establish a reusable "Platform API interface + per-platform Version Adapter" pattern in `api/` that later, larger features can copy.
- Keep all business logic (config parsing, enable/disable decision) unit-testable on a plain JVM, with no Minecraft/Fabric classes on the test classpath.

## Non-goals
- Not a full reimplementation/replacement of `TitleScreen` (no reimplementation of Singleplayer/Multiplayer/Realms/Options/Quit buttons, splash text, logo, language button, etc.). Full replacement is listed under Future Extensions, not built now.
- No networking; this is 100% client-only with no client-server messages.
- No persistence beyond one small local JSON config file; no world/player data, no cloud sync.
- No full localization/translation system for v1; the displayed string is a literal config value, not a translation key. Full i18n is a Future Extension.
- No in-game config-editing screen for v1 (config is a hand-editable JSON file). Mod Menu / config-screen integration is a Future Extension.
- No support for any Minecraft/Fabric target beyond the three platform modules that already exist in this repo.
- No server-side behavior of any kind.

## User Stories
- As a mod developer validating the boilerplate, I want to launch the client on any of the three supported versions and see "Hello World" on the title screen, so I know the feature pipeline and the version-adapter pattern both work end-to-end.
- As a mod user, I want to disable the "Hello World" label or change its text by editing a config file, without recompiling the mod.
- As a future feature author, I want a documented, copyable example under `features/` that demonstrates the required folder layout and the "Fabric API events + Platform API adapter, no mixin" approach, so I can model new features on it.

## Functional Requirements
- **FR1.** After the title screen is shown, the mod displays a text label reading the configured string (default `"Hello World"`) on the title screen, positioned so it does not overlap or disable existing vanilla buttons.
- **FR2.** The label is (re-)added to every subsequent instance of the title screen for the client process's lifetime (the title screen is re-created e.g. after a resize, or on returning from a world/server), not only the first one shown.
- **FR3.** The label is purely decorative in v1 — not clickable, triggers no action (making it a button is a Future Extension).
- **FR4.** At startup the feature reads a config file (see Configuration) controlling: `enabled` (boolean, default `true`) and `text` (string, default `"Hello World"`).
- **FR5.** If the config file is missing, it is created with default values on first run. If it is malformed, the feature logs a warning and falls back to defaults instead of crashing client startup.
- **FR6.** If `enabled` is `false`, no label is added, and no screen-event handler performs any mutation beyond the cheap enabled/disabled check.
- **FR7.** Behavior is identical across `platform/fabric-26.2`, `platform/fabric-26.1`, and `platform/fabric-1.21.11`, achieved through per-platform Version Adapters, not shared code with conditional branching on MC version.
- **FR8.** No code outside `platform/fabric-*` may import any `net.minecraft.*` or `net.fabricmc.fabric.api.client.*` class. Any such import belongs in a platform module's Version Adapter.

## Non-functional Requirements
- **NFR1.** No mixins are introduced by this feature. All hooking uses Fabric API's `ScreenEvents`/`Screens` (module `fabric-screen-api-v1`), optionally `ClientLifecycleEvents` (module `fabric-lifecycle-events-v1`) — both already transitively available via the existing `net.fabricmc.fabric-api:fabric-api:${fabric_api_version}` dependency already declared in every `platform/fabric-*/build.gradle`. No new Gradle dependency is required.
- **NFR2.** Negligible performance impact: a boolean check plus, at most, one widget construction per title-screen `init()`; no per-tick polling, no per-frame allocation.
- **NFR3.** Feature business logic (config model, enable/disable decision) outside `platform/fabric-*` must be unit-testable on a plain JVM with no Minecraft/Fabric on the classpath.
- **NFR4.** Config parsing must never throw an uncaught exception that crashes client startup; any failure degrades to defaults with a logged warning.
- **NFR5.** The feature follows the required folder layout from `.claude/context/feature-guidelines.md` (`api/`, `config/`, `events/`, `gui/`, `mixins/`, `resources/`, `services/`, `tests/`, `README.md`), so it can serve as a template for future features. `mixins/` is expected to remain empty/unused for v1 — kept only for structural consistency.
- **NFR6.** Public classes/interfaces carry JavaDoc with at least one usage example, per `.claude/context/philosophy.md`.

## Public API
Illustrative shapes (final names/signatures are a planning-phase decision); the layering itself is normative.

1. **`api` module** (shared, zero Minecraft imports) — new package, e.g. `de.probastian.boilerplate.api.mainmenu`:
   - `MainMenuHook` (interface) — the "Platform API" referenced by `architecture.md`'s multi-version pattern:
     - `void showLabel(String text);`
     - `void hideLabel();`
     - Only `String` appears in the signature; no Minecraft types leak into `api`.
   - A lookup/registration mechanism so a feature (which cannot depend on Platform) can obtain the platform-supplied implementation. Two viable designs, with a trade-off to resolve during planning (see Architecture — Open Question):
     - (a) `MainMenuHookRegistry` — a small static registry (`register(MainMenuHook)` / `get(): Optional<MainMenuHook>`) that each platform module populates at startup. Simplest, but is a form of global mutable state, which `.claude/context/philosophy.md` lists under "Things to Avoid."
     - (b) A Fabric Loader custom entrypoint (e.g. `"hello-world-main-menu-hook"` declared per-platform in each `fabric.mod.json`), resolved via `net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypoints(...)`. Avoids a mutable static registry at the cost of depending on the Fabric Loader API (not "Minecraft" itself, but still a modding-framework dependency) from `api`.

2. **`features/hello-world-main-menu/api/`** (feature-facing contract):
   - `HelloWorldMainMenuConfig` — immutable data type: `boolean enabled()`, `String text()`, and a documented `DEFAULT` constant.

3. **`features/hello-world-main-menu/services/`**:
   - `HelloWorldMainMenuService` — pure business logic, no Minecraft imports. Loads/saves `HelloWorldMainMenuConfig`, and exposes `void applyToMainMenu()`, which reads the current config and, through the `api` module's lookup mechanism, calls `showLabel(text)` or `hideLabel()` on the registered `MainMenuHook`. This is the only place feature logic touches `api`'s main-menu types; it never touches `net.minecraft.*`.

4. **`platform/fabric-<version>/.../mainmenu/FabricMainMenuHook`** (one per platform module — the Version Adapter):
   - Implements `MainMenuHook` using that version's real `Screen`/widget/text classes and Fabric's `ScreenEvents`/`Screens` API.
   - Registered from a new client-only entrypoint in each platform module (see Architecture) at client startup, after which `HelloWorldMainMenuService.applyToMainMenu()` (or equivalent) is invoked so the label is available before/along with the first title screen if `enabled`.

## Architecture
Layering per `.claude/context/architecture.md`: `API -> Services -> Features -> Platform`; Features never depend on other Features; Platform depends only on API (see Open Question below).

Flow for this feature, matching the documented pattern **Feature -> Platform API -> Version Adapter -> Minecraft**:

```
features/hello-world-main-menu/services/HelloWorldMainMenuService   (business logic, no MC imports)
        |  calls
        v
api/.../mainmenu/MainMenuHook (+ lookup mechanism)                  (stable abstraction, no MC imports)
        |  implemented + registered by
        v
platform/fabric-26.2/.../mainmenu/FabricMainMenuHook   (Mojang-mapped: net.minecraft.client.gui.screens.*, net.minecraft.network.chat.Component)
platform/fabric-26.1/.../mainmenu/FabricMainMenuHook   (Mojang-mapped, mirrors 26.2 unless 26.1 APIs differ)
platform/fabric-1.21.11/.../mainmenu/FabricMainMenuHook (Yarn-mapped: net.minecraft.client.gui.screen.*, net.minecraft.text.Text)
        |  use
        v
Fabric API: ScreenEvents.AFTER_INIT / Screens.getButtons(screen)    (module fabric-screen-api-v1)
Minecraft: TitleScreen, Screen, a label/button widget, Component/Text
```

**Why no mixin is required:** Fabric API's `fabric-screen-api-v1` module (bundled inside the `fabric-api` dependency already present in all three platform build files) exposes `ScreenEvents.AFTER_INIT`, firing `(client, screen, scaledWidth, scaledHeight)` after any screen — including `TitleScreen` — completes its default `init()`. Handlers can then add a widget from outside the screen class via `Screens.getButtons(screen)`, a public accessor around the otherwise-protected widget list. This is the officially documented, stable, non-mixin path (present across many Minecraft versions per Fabric's own API docs) for exactly this use case. A full-screen-replacement alternative (`ClientLifecycleEvents.CLIENT_STARTED` + `Minecraft.getInstance().setScreen(new CustomScreen(...))`) is equally mixin-free but out of scope for v1 (see Non-goals / Future Extensions).

**Feature module wiring gap:** `features/` is not yet included in `settings.gradle`. This is the first feature to require that wiring. Planning must decide whether `features/hello-world-main-menu` becomes its own Gradle subproject (built against `api` + `services`, consumed by each `platform/fabric-*` module as an `implementation` dependency), or whether a single `features` aggregator subproject is introduced. This spec does not prescribe the Gradle mechanics, only that the module boundary (Features depend on API/Services; Platform depends on the feature only to bootstrap it) must be preserved.

**Open question / documented architecture gap (flagging, not resolving):** `.claude/context/architecture.md`'s dependency table states `Platform -> API` only. In practice, something in each platform module (the new client entrypoint, i.e., the composition root) must reference `HelloWorldMainMenuService` (a Feature-layer class) to trigger `applyToMainMenu()` at startup — the current dependency table doesn't account for this "composition root" wiring exception. Two ways to resolve, left to planning/ADR:
  1. Treat "Platform -> API only" as governing business-logic dependencies, and accept that a platform module's client entrypoint (composition root) may reference concrete Feature classes purely for bootstrapping/wiring, as is common practice.
  2. Introduce a formal registration mechanism entirely on the API side (e.g., Feature registers itself into a lifecycle callback exposed by `api`, so Platform never needs to import the Feature at all).
  Given `.claude/context/philosophy.md` requires an ADR for "significant architectural changes," and this genuinely clarifies/extends an existing documented rule, an ADR is recommended before or during planning.

**Version-specific facts affecting the adapters** (confirmed via Fabric documentation for the target versions):
- `platform/fabric-1.21.11` is obfuscated and uses Yarn mappings (`net.fabricmc:yarn:1.21.11+build.6:v2`), consistent with this repo's existing `net.minecraft.util.Identifier` / `Identifier.of(...)` usage in that module's `TemplateMod.java`.
- `platform/fabric-26.1` and `platform/fabric-26.2` use Minecraft's official (Mojang) mappings natively — no obfuscation/remap step (`fabric-loom` plugin rather than `fabric-loom-remap`) — consistent with this repo's existing `net.minecraft.resources.Identifier` / `Identifier.fromNamespaceAndPath(...)` usage.
- Minecraft's `Screen` rendering has moved to a render-state-extraction model (`extractRenderState(...)` populating a state object consumed separately, rather than a screen drawing itself directly inside an overridden `render(...)`); current Fabric documentation for custom screens already reflects this model. Implementation/verification should confirm empirically whether 1.21.11 already uses this model (very likely, since it is one patch release after 1.21.10, where the model was already documented) — this affects how a custom widget renders itself, but does **not** affect the `ScreenEvents.AFTER_INIT` + `Screens.getButtons` add-widget mechanism, which is unaffected by that refactor.
- Because of the above, `FabricMainMenuHook` cannot be one shared source file across 1.21.11 and 26.x: package names (`net.minecraft.client.gui.screen.*` vs. `net.minecraft.client.gui.screens.*`), the text type (`net.minecraft.text.Text` vs. `net.minecraft.network.chat.Component`), and possibly widget-rendering details differ. Each platform module owns its own small adapter file — expected version-glue duplication of a handful of lines, not business-logic duplication.

**Client-only entrypoint:** `TitleScreen` and the relevant Fabric GUI APIs are client-only. Each platform module's `fabric.mod.json` currently declares only a `"main"` entrypoint (`TemplateMod`, a plain `ModInitializer`, `"environment": "*"`, i.e., loads on both client and dedicated server). This feature requires a new `"client"` entrypoint (a class implementing `net.fabricmc.api.ClientModInitializer`) per platform module, where the platform registers its `FabricMainMenuHook` and triggers `applyToMainMenu()`. This is additive to each `fabric.mod.json`; it does not change the existing server-safe `main` entrypoint.

## UI / Rendering
- A single line of text, default `"Hello World"`, rendered on the vanilla `TitleScreen`. Suggested default position: centered horizontally, above the "Singleplayer" button row — exact pixel offsets are an implementation detail to be verified visually across common resolutions/GUI scales so it never overlaps the logo, splash text, or existing buttons.
- No custom textures or fonts; uses vanilla font/rendering utilities only.
- Not interactive in v1 (no click/hover behavior); must not consume mouse/keyboard focus or alter the tab-navigation order of existing widgets.
- Must appear only on the title screen for v1 (not on the pause menu or other screens).
- Must correctly reappear when the title screen is recreated (window resize triggers `init()` again; returning to the title screen after disconnecting), which the `AFTER_INIT`-based approach satisfies naturally provided the config lookup/hook call is cheap and idempotent.

## Commands
None. This feature adds no client or server commands.

## Configuration
- **Location:** a per-feature JSON file, e.g. `config/hello-world-main-menu.json`, under Fabric Loader's per-instance config directory (`FabricLoader.getInstance().getConfigDir()`), consistent with "each feature owns its own configuration" (`feature-guidelines.md`). No shared Config service exists yet in `services/` (currently an empty scaffold module) — this feature is not blocked on one being built first and manages its own minimal file I/O. If a shared Config service is introduced later, this feature's loading can migrate to it without changing the public `HelloWorldMainMenuConfig` shape.
- **Schema (v1):**
  ```json
  {
    "enabled": true,
    "text": "Hello World"
  }
  ```
  - `enabled`: boolean, default `true`.
  - `text`: string, default `"Hello World"`. Edge case to resolve in planning: whether an empty string should be treated as equivalent to `enabled: false` (no zero-length label rendered) even when `enabled` is `true`.
- **Read timing:** once at client startup, from the new `client` entrypoint, before/at the point the title screen first appears. Not hot-reloaded during a running session for v1 (a restart is required to pick up file edits) — hot-reload is a Future Extension.
- No `configVersion`/migration field is needed for v1 given the tiny schema (see Migration for when one should be added).
- No in-game config-editing screen for v1 (see Non-goals).

## Events
- **Consumes** (defines no new engine-level event types): Fabric API's `ScreenEvents.AFTER_INIT` (module `fabric-screen-api-v1`) to add the label widget to `TitleScreen` instances; optionally `ClientLifecycleEvents.CLIENT_STARTED` (module `fabric-lifecycle-events-v1`) if the chosen implementation strategy applies the label once at startup rather than reacting per-screen-init. Planning selects one of these two equivalent, non-mixin strategies.
- Defines no new cross-feature event on the (not-yet-built) shared event bus for v1 — no other feature currently exists to react to this one. The feature's own `events/` folder exists per the required layout but may remain empty/unused for v1.

## Networking
None. No packets, no client-server communication, no server-side component — the new `client`-only entrypoint keeps this feature entirely off the dedicated-server code path.

## Persistence
Only the config file described above (`config/hello-world-main-menu.json`). No world save data, no player data, no NBT, no account-linked or cloud storage.

## Dependencies
- No new external libraries. Uses:
  - Fabric API modules already transitively included via the existing `net.fabricmc.fabric-api:fabric-api:${fabric_api_version}` dependency in each `platform/fabric-*/build.gradle`: `fabric-screen-api-v1` (`ScreenEvents`, `Screens`), optionally `fabric-lifecycle-events-v1` (`ClientLifecycleEvents`).
  - JSON parsing for the config file: Gson, already shipped with Minecraft/Fabric Loader and safe to use from `platform/fabric-*` adapters (or a small feature-owned config-IO helper); if strict "no Minecraft-adjacent dependency" purity is desired for logic outside `platform/fabric-*`, the actual file read/write may need to sit behind a small interface — a planning-level decision.
  - No new Gradle dependency additions are anticipated; if planning finds otherwise, that must be called out explicitly as a deviation from this spec.
- Depends on existing repo modules `api` and (depending on the wiring decision) `services`, plus a `settings.gradle` change to include the new feature module(s) and a dependency edge from each platform module onto it.

## Version Compatibility
| Module | Minecraft version | Mappings | Fabric API version | Notes |
|---|---|---|---|---|
| `platform/fabric-26.2` | 26.2 | Mojang (official), unobfuscated | 0.154.2+26.2 | `fabric-loom` plugin |
| `platform/fabric-26.1` | 26.1 | Mojang (official), unobfuscated | 0.145.1+26.1 | `fabric-loom` plugin |
| `platform/fabric-1.21.11` | 1.21.11 | Yarn `1.21.11+build.6` | 0.141.4+1.21.11 | `fabric-loom-remap` plugin; last obfuscated MC version per repo's own notes |

- Player-visible behavior must be identical across all three.
- Each platform module supplies its own `FabricMainMenuHook` Version Adapter and its own `client` entrypoint registration; no shared GUI source spans the mapping boundary between 1.21.11 and 26.x.
- Adding a fourth platform module later (per this repo's stated pattern for adding version support) should only require a new Version Adapter implementing the existing `MainMenuHook` interface — no changes anticipated to `api`, `services`, or the feature's business logic.

## Performance
- Startup cost: negligible — one small JSON file read, one widget construction per title-screen `init()`.
- Runtime cost: none per-tick; the label is static text with no per-frame recomputation.
- No additional retained memory beyond one small config object and, transiently, one widget instance per open title-screen instance.

## Security
- Local-only feature; no network exposure, no arbitrary code execution, no externally-supplied file paths beyond the mod's own local config file (editable only by the user running the client). No secrets or sensitive data involved. Malformed/adversarial config content (e.g., an excessively long `text` value) should be tolerated (e.g., rendered as-is, potentially running off-screen) rather than crashing the client — verify via tests.

## Migration
- N/A for v1 — first version of this feature, no prior schema.
- Forward-looking: if the config schema changes later (e.g., adding button behavior, translation keys, positioning options), introduce a `configVersion` integer field (starting at `1`) at that time, and add an explicit migration step in the feature's config-loading code that upgrades older files in place. Document the migration in this feature's `README.md` and in a spec update at that time, per this repo's documentation convention (specs explain why, plans explain how).

## Future Extensions
- Full title-screen-replacement variant (`ClientLifecycleEvents.CLIENT_STARTED` + `Minecraft.getInstance().setScreen(new CustomTitleScreen(...))`) reproducing/extending the vanilla button layout, for a fully custom main menu.
- Make the label an interactive button (e.g., opens an "About this mod" screen, or links to the project homepage).
- In-game config screen (e.g., Mod Menu integration) to toggle/edit the text without hand-editing JSON.
- Hot-reload of the config file while the client is running.
- Full localization: replace the raw `text` config string with a translation key resolved through Minecraft's language system.
- Promote the `MainMenuHook` / lookup-mechanism pattern into a generalized shared `services` capability if more features need to extend the title screen or other shared vanilla screens, avoiding each feature reinventing its own registry/lookup.
- Migrate this feature's config loading onto a shared Config service in `services/`, once one exists, per the Configuration section above.
- Resolve the "Platform -> API only" vs. composition-root-wiring tension identified in Architecture via a formal ADR, generalizing the answer for all future features rather than deciding it ad hoc here.

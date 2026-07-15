# Hello World Main Menu

The first feature under `features/`. Displays a small, configurable
"Hello World" text label on the vanilla title screen, on all three currently
supported platform modules (`platform/fabric-26.2`, `platform/fabric-26.1`,
`platform/fabric-1.21.11`). See `specification.md` (why) and
`implementation-plan.md` (how) for the full design and its rationale; this
README is a shorter, copyable-template-oriented summary for anyone modeling
a new feature on this one.

## Folder layout

`feature-guidelines.md`/`architecture.md` list a feature's required layout as
flat folder names: `api/`, `config/`, `events/`, `gui/`, `mixins/`,
`resources/`, `services/`, `tests/`, `README.md`. This module realizes that
list as standard Gradle/Java source layout instead of literal flat
directories -- see the implementation plan's Decision 3 for the full
justification. Concretely:

| Required folder | Realized as |
|---|---|
| `api/` | `src/main/java/.../helloworldmainmenu/api/` -- `HelloWorldMainMenuConfig` |
| `config/` | `src/main/java/.../helloworldmainmenu/config/` -- `HelloWorldMainMenuConfigIO` |
| `services/` | `src/main/java/.../helloworldmainmenu/services/` -- `HelloWorldMainMenuService` |
| `events/` | `src/main/java/.../helloworldmainmenu/events/` -- empty placeholder package |
| `gui/` | `src/main/java/.../helloworldmainmenu/gui/` -- empty placeholder package |
| `mixins/` | `src/main/java/.../helloworldmainmenu/mixins/` -- empty placeholder package |
| `resources/` | `src/main/resources/` -- standard Gradle resources dir, currently unused |
| `tests/` | `src/test/java/...` -- standard Gradle test source set |
| `README.md` | this file |

### Why `events/`, `gui/`, and `mixins/` are empty

- **`events/`** -- no shared cross-feature event bus exists yet in
  `services/`, and no other feature currently exists to talk to this one.
  Kept as a documented placeholder (`package-info.java`) so the folder is
  git-tracked and ready if this feature ever needs to publish an event.
- **`gui/`** -- GUI/widget code necessarily imports `net.minecraft.*`
  classes, which this feature's FR8 forbids anywhere outside
  `platform/fabric-*`. There is no legal place for real GUI source inside a
  feature module under this architecture; the widget-adding code lives
  entirely in each platform module's `FabricMainMenuHook`
  (`platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/FabricMainMenuHook.java`).
- **`mixins/`** -- same reasoning as `gui/`, but permanent rather than
  "empty for now": a `@Mixin` class by definition targets `net.minecraft.*`
  classes, so no feature module can ever legally contain one under this
  architecture. This feature also doesn't need a mixin at all -- see below.

## How the label is added (no mixin)

Each platform's `FabricMainMenuHook` implements the `api` module's
`MainMenuHook` interface using only Fabric API's documented
`fabric-screen-api-v1` module:

1. `ScreenEvents.AFTER_INIT` fires once per screen `init()`, including every
   time a `TitleScreen` is (re-)created (first launch, window resize,
   returning from a disconnected world/server).
2. If the feature's config says the label should be shown, the hook
   constructs a plain vanilla text widget (`StringWidget` on 26.x/Mojang
   mappings, `TextWidget` on 1.21.11/Yarn mappings -- same vanilla concept,
   different mapped name) and adds it to the screen via
   `Screens.getWidgets(screen)` (26.x) / `Screens.getButtons(screen)`
   (1.21.11 -- the method was renamed between these `fabric-api` releases,
   confirmed by compiling against each version's real `fabric-api` jar
   rather than assumed).

No `@Mixin` is introduced anywhere by this feature.

## Config schema

Location: `<Fabric config dir>/hello-world-main-menu.json` (per-instance
config directory, resolved via
`FabricLoader.getInstance().getConfigDir()`), created with default values on
first run if missing.

```json
{
  "enabled": true,
  "text": "Hello World"
}
```

- `enabled` (boolean, default `true`) -- master on/off switch.
- `text` (string, default `"Hello World"`) -- the label's text. A blank or
  whitespace-only value is treated as equivalent to `enabled: false`
  (`HelloWorldMainMenuConfig.shouldDisplayLabel()`), since rendering an
  empty label is never useful.

Parsing is hand-rolled (`HelloWorldMainMenuConfigIO`, no external JSON
library -- see the implementation plan's Decision 6) and never throws: any
malformed file (invalid JSON, wrong types, missing keys, etc.) falls back to
the defaults above and reports a warning through the caller-supplied
`Consumer<String>` logger.

The config is read once at client startup; edits require a restart to take
effect (no hot-reload in v1).

## Adding a fourth platform module

Per this repo's own multi-version runbook
(`.claude/context/minecraft.md`, "Adding a New `platform/fabric-<version>`
Module"), adding support for a new Minecraft version should only require:

1. A new `platform/fabric-<version>` module (see the runbook for the general
   steps: duplicate an existing module on the same side of the obfuscation
   boundary, override `gradle.properties`, add it to `settings.gradle`).
2. A new `mainmenu/FabricMainMenuHook` in that module implementing the
   existing `api`'s `MainMenuHook` interface, using that version's real
   Screen/widget/text classes -- copy the closest existing adapter (same
   mapping side) as a starting point and let real compile errors drive any
   renamed APIs.
3. A new `HelloWorldMainMenuClientInitializer` in that module (copy an
   existing one verbatim -- it contains no version-specific code).
4. `implementation project(':features:hello-world-main-menu')` in the new
   module's `build.gradle`, and a `"client"` entrypoint entry in its
   `fabric.mod.json`.

No changes to `api`, `features/hello-world-main-menu`, or this feature's
business logic should be needed.



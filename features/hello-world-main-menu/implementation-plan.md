# Implementation Plan — Hello World Main Menu

## Summary
Add a decorative "Hello World" label to the vanilla title screen on all three
platform modules (`fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`), driven by a
per-feature JSON config, using only `fabric-screen-api-v1` (`ScreenEvents.AFTER_INIT`
+ `Screens.getButtons`) — no mixins. This is the first module under `features/`,
so this plan also wires `features/` into the Gradle build for the first time and
resolves the open design questions the specification deliberately deferred to
planning. No implementation code is written as part of this plan.

## Existing Implementation
- `settings.gradle` includes `api`, `common`, `services`, `libraries`, and the
  three `platform:fabric-*` modules. It does **not** include anything under
  `features/`, which does not yet exist as a Gradle subproject (confirmed: no
  `build.gradle` anywhere under `features/`, only `features/hello-world-main-menu/specification.md`).
- `api/`, `common/`, `services/`, `libraries/` are empty scaffold modules: each
  is declared in `settings.gradle`, the root `build.gradle`'s `subprojects {}`
  block applies `java-library` + Java 21 `sourceCompatibility`/`release` to all
  of them, but none of them has its own `build.gradle` file, and none has a
  `src/` directory. `services/build.gradle` and `common/build.gradle` exist but
  contain only `dependencies { api project(':api') }`; `api/` and `libraries/`
  have no `build.gradle` at all and rely entirely on the root defaults — this
  is valid Gradle (a subproject without its own build file still gets
  `subprojects {}` configuration) and is the precedent this plan follows for
  `api` (see Files to Create).
- Each `platform/fabric-*` module already has:
  - a `TemplateMod implements ModInitializer` with a `"main"` entrypoint,
    `environment: "*"`, and a static `LOGGER` (SLF4J) — this pattern is
    extended, not replaced, with a new `"client"` entrypoint.
  - `dependencies { api project(':api') ... }` in its `build.gradle`, i.e.
    Platform already depends on API today, confirming the "Platform -> API"
    edge from `architecture.md` is live.
  - `implementation "net.fabricmc.fabric-api:fabric-api:${fabric_api_version}"`
    (26.x) / `modImplementation "..."` (1.21.11) already declared, which
    transitively bundles `fabric-screen-api-v1` and `fabric-lifecycle-events-v1`
    — no new Fabric API Gradle coordinate is needed for NFR1.
  - No test framework (JUnit or otherwise) is configured anywhere in the repo
    today (`grep` for `junit`/`testImplementation` across the whole tree
    returns nothing). NFR3's plain-JVM unit tests are the first tests this
    repo will have.
- `docs/adr/README.md` exists (`"Create ADRs for significant architectural
  decisions."`) but no ADR files exist yet and no ADR template file exists in
  the repo.
- 26.2/26.1 use Mojang mappings (`net.minecraft.resources.Identifier`,
  `Identifier.fromNamespaceAndPath`), unobfuscated, Java 25, `fabric-loom`.
  1.21.11 uses Yarn mappings (`net.minecraft.util.Identifier`, `Identifier.of`),
  obfuscated, Java 21, `fabric-loom-remap` + an explicit `mappings` dependency.
  This confirms the spec's stated mapping/package differences and that no
  single `FabricMainMenuHook` source file can be shared across the boundary.

## Decisions on the Open Questions (resolved during planning)

### 1. Public API lookup mechanism — neither (a) nor (b); constructor injection at the composition root
The spec offered a static `MainMenuHookRegistry` (a) or a Fabric Loader custom
entrypoint (b). Both are rejected in favor of a third option that the spec's
own Architecture section already implicitly permits: **plain constructor
injection, wired by the platform module's new `client` entrypoint (the
composition root)**.

- `api` defines only the `MainMenuHook` interface — no registry, no lookup
  class, no Fabric Loader dependency in `api` at all.
- `features/hello-world-main-menu/services/HelloWorldMainMenuService` takes a
  `MainMenuHook` (an `api` type) as a constructor parameter. It never looks
  the hook up itself.
- Each platform's new `client` entrypoint (`HelloWorldMainMenuClientInitializer`)
  is the composition root: it directly `new`s its own `FabricMainMenuHook` and
  passes it into a `new HelloWorldMainMenuService(hook, ...)`.

Justification against `philosophy.md`'s "avoid global state" and
`coding-style.md`'s "Constructor injection over globals":
- Option (a) is, by the spec's own admission, global mutable state — directly
  against `philosophy.md`'s "Things to Avoid."
- Option (b) avoids a registry but pulls `net.fabricmc.loader.api.FabricLoader`
  into `api`, which (i) requires a new Gradle dependency on `api` (currently
  zero dependencies) — a real, avoidable deviation — and (ii) puts Fabric
  Loader classes on the transitive compile path of anything depending on
  `api`, which weakens NFR3's "no Minecraft/Fabric on the classpath" guarantee
  for anyone who later builds on `api` directly.
- Constructor injection needs neither: `api` stays a single dependency-free
  interface, no registry/global exists anywhere, and the composition root
  (platform, which the spec's Architecture section already accepts may
  reference concrete Feature classes for bootstrapping — see Decision 4) does
  the wiring explicitly and locally. `HelloWorldMainMenuService` remains
  trivially testable with a hand-written fake `MainMenuHook`, with zero
  Fabric/Minecraft classes anywhere near the test classpath.

This still satisfies the spec's literal requirement ("a lookup/registration
mechanism so a feature ... can obtain the platform-supplied implementation")
because the "mechanism" is simply: Platform constructs Feature and hands it
the dependency — no separate lookup indirection is needed once Platform is
already permitted to reference Feature classes at all.

### 2. Gradle wiring — `features/hello-world-main-menu` is its own subproject, not a `features` aggregator
`settings.gradle` gains `include 'features:hello-world-main-menu'`. No
`features` aggregator module is introduced.

Justification:
- Matches the existing precedent of one subproject per unit (`platform/fabric-26.2`,
  `platform/fabric-26.1`, ... — never one aggregated "platform" module).
- `architecture.md` forbids Feature -> Feature dependencies. A single shared
  `features` aggregator module would put every future feature's source in one
  compilation unit, making that boundary a matter of convention/discipline
  instead of something the build graph enforces. Per-feature subprojects make
  a `Feature -> Feature` dependency a Gradle configuration error, not just a
  code-review concern.
- The spec's own stated goal is a **copyable template**: "a documented,
  copyable example under `features/`... so I can model new features on it."
  A dedicated `features/<name>/build.gradle` per feature is directly copyable;
  a shared aggregator module is not (the second feature would need to be
  spliced into someone else's build file).
- `features/hello-world-main-menu/build.gradle`: `dependencies { api project(':api') }`
  (`api`, not `implementation`, because `MainMenuHook` — an `api`-module type —
  appears in `HelloWorldMainMenuService`'s public constructor signature, so it
  must leak to consumers, matching Gradle's own `api`-vs-`implementation`
  semantics). No dependency on `:services` is added: `services` is presently
  an empty scaffold with no Config capability to consume, and the spec
  explicitly says this feature is not blocked on one existing (see Migration
  in the spec for the future move).
- Each `platform/fabric-*/build.gradle` gains
  `implementation project(':features:hello-world-main-menu')` (plain
  `implementation`, not `modImplementation` — the feature module is a regular
  Gradle Java project, not a remappable mod jar, so Loom's mod-aware
  dependency configurations do not apply to it).

### 3. Required per-feature folder layout — realized as Java sub-packages, not literal flat disk folders
`feature-guidelines.md` / `architecture.md`'s diagram list `api/`, `config/`,
`events/`, `gui/`, `mixins/`, `resources/`, `services/`, `tests/` as bare
folder names directly under the feature directory. Read completely literally,
this would require a custom Gradle `sourceSets` block pointing at flat,
non-package-mirrored directories — a pattern that exists nowhere else in this
repo (every other module uses standard `src/main/java/<package>/...`).

Decision: keep the standard, zero-configuration Gradle/Java layout
(`src/main/java`, `src/main/resources`, `src/test/java`) that every other
module in this repo already uses, and realize the required folder names as
Java sub-packages under the feature's base package
`de.probastian.boilerplate.features.helloworldmainmenu`:
- `api/` -> `....helloworldmainmenu.api` package (in `src/main/java`)
- `config/` -> `....helloworldmainmenu.config` package
- `services/` -> `....helloworldmainmenu.services` package
- `events/`, `gui/`, `mixins/` -> empty placeholder sub-packages (see below)
- `resources/` -> standard `src/main/resources/`
- `tests/` -> standard `src/test/java/...` (Gradle's default test source set)
- `README.md` -> `features/hello-world-main-menu/README.md`, sibling to
  `specification.md`/`implementation-plan.md` (not under `src/`), matching
  `documentation.md`'s existing spec/plan/verification-report convention.

Justification: this is 100% consistent with every existing module's layout in
this repo (zero precedent anywhere for flat non-package-mirrored source
dirs), needs no custom `sourceSets` configuration (so IDE/tooling support is
automatic), and still satisfies the letter of the required-layout list — every
named folder exists and is discoverable, just expressed as a package instead
of a raw directory. This should be called out to the user as a deviation from
a maximally-literal reading of `feature-guidelines.md`/`architecture.md`'s
diagrams, in case they intended literal flat folders.

`events/`, `gui/`, and `mixins/` are deliberately empty for v1:
- `events/` — no cross-feature event bus exists yet and no other feature
  exists to talk to (per spec, Events section). Kept as a documented
  placeholder package (`package-info.java`) so the folder exists and is
  git-tracked (git does not track empty directories).
- `gui/` — GUI/widget code necessarily touches `net.minecraft.*` classes,
  which FR8 forbids outside `platform/fabric-*`. There is therefore no legal
  place for real GUI source inside this feature module; the widget-adding
  code lives entirely in each platform's `FabricMainMenuHook`. Placeholder
  package only.
- `mixins/` — same argument as `gui/`, stated explicitly by NFR5 ("expected to
  remain empty/unused for v1 — kept only for structural consistency"). Worth
  noting explicitly: because FR8 bans `net.minecraft.*` imports outside
  `platform/fabric-*`, and a Mixin by definition targets `net.minecraft.*`
  classes, no feature module in this architecture can ever legally contain a
  real `@Mixin` class — any future mixin need must live in `platform/`. This
  folder is permanently structural, not just "empty for now."

### 4. Empty-string `text` — treated as equivalent to `enabled: false`
Decision: `HelloWorldMainMenuConfig` gets a derived method
`boolean shouldDisplayLabel()` returning `enabled && text != null && !text.isBlank()`.
An empty or whitespace-only `text` value, even with `enabled: true`, results
in no label being added (equivalent to FR6's `hideLabel()` path).

Justification: rendering a zero-width/whitespace-only label is pure waste
(NFR2: "at most one widget construction... no per-frame allocation" reads
most naturally as "only construct it when there's something to show"), and a
blank-but-technically-enabled label is more likely a user config mistake than
an intentional deliberately-invisible-but-active state — treating it as
"nothing to show" is the least surprising behavior. This decision is captured
as a testable unit (`HelloWorldMainMenuConfigTest`) and as an acceptance
criterion below.

### 5. Composition-root architecture tension — ADR written now, as part of this feature
Decision: yes, write the ADR now, as a deliverable of this feature, resolving
it per the spec's option 1 ("Platform -> API only" governs business-logic
dependencies; a platform module's client entrypoint/composition root may
reference concrete Feature classes purely for bootstrap wiring).

Justification: `philosophy.md` requires an ADR for "significant architectural
changes"; the spec itself flags this as a genuine clarification of an
existing documented rule (not a net-new decision invented ad hoc here) and
explicitly recommends an ADR "before or during planning." Since this plan
must pick a concrete wiring approach to write file-level tasks at all (Decision
1 depends on it), resolving it now — and writing it down — avoids every future
feature re-litigating the same ambiguity. No repo-specific ADR template file
exists (`docs/adr/README.md` is a one-liner); the new ADR uses a standard
lightweight Context/Decision/Consequences shape.

### 6. Config JSON handling — hand-rolled minimal parser, no new external library
Decision: `HelloWorldMainMenuConfigIO` hand-parses/serializes the exact
2-field schema (`enabled: boolean`, `text: string`) itself, rather than
pulling in Gson (or any other JSON library) as a new Gradle dependency.

Justification: the spec explicitly anticipates this ("if strict... purity is
desired... file read/write may need to sit behind a small interface — a
planning-level decision") and separately states "No new Gradle dependency
additions are anticipated; if planning finds otherwise, that must be called
out explicitly as a deviation." A general-purpose JSON library is not
required for correctness here: the schema is two flat scalar fields, FR5/NFR4
already require defaulting on *any* unexpected content (so the parser only
needs to correctly accept well-formed instances of its own tiny schema and
safely default-and-warn on everything else — a narrow, exhaustively
unit-testable surface), and this avoids a real risk this plan flagged and
rejected: guessing a Gson version without being able to verify it against
this repo's actual resolved dependency graph (this planning pass has no
Gradle-execution tool available; `minecraft.md` explicitly warns against
trusting guessed version coordinates). Net effect: **zero new external
Gradle dependencies** anywhere except the unavoidable JUnit test dependency
(see Dependencies) and the new inter-module `project(...)` edges (internal,
not external libraries).
Trade-off accepted: if the config schema grows later (per the spec's own
Migration section), migrating to Gson or a shared `services` Config
capability at that time is straightforward and already anticipated by the
spec — not a decision this feature needs to future-proof further today.

## Files to Create

**`api` module** (no new `build.gradle` needed — no new dependency; relies on
root `subprojects {}` defaults, matching the existing precedent of `api`/`libraries`):
- `api/src/main/java/de/probastian/boilerplate/api/mainmenu/MainMenuHook.java`
  — interface: `void showLabel(String text);` / `void hideLabel();`. Only
  `java.lang.String` in the signature. JavaDoc with a usage example per NFR6.

**`features/hello-world-main-menu` module (new Gradle subproject):**
- `features/hello-world-main-menu/build.gradle`
- `features/hello-world-main-menu/README.md`
- `features/hello-world-main-menu/src/main/java/de/probastian/boilerplate/features/helloworldmainmenu/api/HelloWorldMainMenuConfig.java`
  — immutable data type (`record`): `boolean enabled()`, `String text()`,
  `static final HelloWorldMainMenuConfig DEFAULT` (`enabled=true`,
  `text="Hello World"`), derived `boolean shouldDisplayLabel()` (Decision 4).
- `features/hello-world-main-menu/src/main/java/de/probastian/boilerplate/features/helloworldmainmenu/config/HelloWorldMainMenuConfigIO.java`
  — pure-ish JSON read/write for the 2-field schema (Decision 6): parse a
  `String` -> `HelloWorldMainMenuConfig` (never throws; returns a small result
  type indicating whether defaults were used and why), serialize a config back
  to `String`, and file-level `load(Path)` (creates the file with serialized
  `DEFAULT` if missing, per FR5) using `java.nio.file.Files` only (plain JDK).
- `features/hello-world-main-menu/src/main/java/de/probastian/boilerplate/features/helloworldmainmenu/services/HelloWorldMainMenuService.java`
  — constructor `(MainMenuHook hook, HelloWorldMainMenuConfigIO configIO, Path configFilePath, Consumer<String> warningLogger)`.
  `applyToMainMenu()`: loads config via `configIO`, reports any warning via
  `warningLogger` (keeps FR5/NFR4's "logs a warning" requirement out of
  `api`/`Fabric`/SLF4J entirely — the platform composition root supplies the
  actual logger, e.g. `TemplateMod.LOGGER::warn`, avoiding a new SLF4J
  dependency on the feature module), then calls `hook.showLabel(text)` or
  `hook.hideLabel()` per `shouldDisplayLabel()`.
- `features/hello-world-main-menu/src/main/java/de/probastian/boilerplate/features/helloworldmainmenu/events/package-info.java` (placeholder, documents why empty)
- `features/hello-world-main-menu/src/main/java/de/probastian/boilerplate/features/helloworldmainmenu/gui/package-info.java` (placeholder, documents why empty — FR8)
- `features/hello-world-main-menu/src/main/java/de/probastian/boilerplate/features/helloworldmainmenu/mixins/package-info.java` (placeholder, documents why permanently empty — FR8/NFR5)
- `features/hello-world-main-menu/src/main/resources/.gitkeep` (placeholder — `resources/` unused for v1, no bundled assets)
- `features/hello-world-main-menu/src/test/java/de/probastian/boilerplate/features/helloworldmainmenu/api/HelloWorldMainMenuConfigTest.java`
- `features/hello-world-main-menu/src/test/java/de/probastian/boilerplate/features/helloworldmainmenu/config/HelloWorldMainMenuConfigIOTest.java`
- `features/hello-world-main-menu/src/test/java/de/probastian/boilerplate/features/helloworldmainmenu/services/HelloWorldMainMenuServiceTest.java`

**Platform modules — one Version Adapter + one client entrypoint per module (x3):**
- `platform/fabric-26.2/src/main/java/de/probastian/boilerplate/mainmenu/FabricMainMenuHook.java`
- `platform/fabric-26.2/src/main/java/de/probastian/boilerplate/HelloWorldMainMenuClientInitializer.java`
  (implements `net.fabricmc.api.ClientModInitializer`; composition root —
  resolves `FabricLoader.getInstance().getConfigDir().resolve("hello-world-main-menu.json")`,
  constructs `FabricMainMenuHook`, `HelloWorldMainMenuConfigIO`,
  `HelloWorldMainMenuService`, calls `applyToMainMenu()`)
- `platform/fabric-26.1/src/main/java/de/probastian/boilerplate/mainmenu/FabricMainMenuHook.java`
- `platform/fabric-26.1/src/main/java/de/probastian/boilerplate/HelloWorldMainMenuClientInitializer.java`
- `platform/fabric-1.21.11/src/main/java/de/probastian/boilerplate/mainmenu/FabricMainMenuHook.java`
- `platform/fabric-1.21.11/src/main/java/de/probastian/boilerplate/HelloWorldMainMenuClientInitializer.java`

**Documentation:**
- `docs/adr/0001-platform-composition-root-may-depend-on-feature-classes.md`
  (Decision 5; Context/Decision/Consequences shape, references this feature
  and `architecture.md`'s dependency table)

## Files to Modify
- `settings.gradle` — add `include 'features:hello-world-main-menu'`
- `build.gradle` (root) — add `test { useJUnitPlatform() }` inside the existing
  `subprojects {}` block (harmless no-op for modules with no tests; enables
  the JUnit 5 pattern for this and every future feature)
- `platform/fabric-26.2/build.gradle` — add
  `implementation project(':features:hello-world-main-menu')` to `dependencies {}`
- `platform/fabric-26.1/build.gradle` — same
- `platform/fabric-1.21.11/build.gradle` — same
- `platform/fabric-26.2/src/main/resources/fabric.mod.json` — add a new
  `"client": ["de.probastian.boilerplate.HelloWorldMainMenuClientInitializer"]`
  array under `"entrypoints"`, alongside the existing `"main"` array; no other
  field changes (existing `"main"`/`"environment": "*"` untouched)
- `platform/fabric-26.1/src/main/resources/fabric.mod.json` — same
- `platform/fabric-1.21.11/src/main/resources/fabric.mod.json` — same

## Interfaces
- `api/.../mainmenu/MainMenuHook` — the only cross-layer abstraction this
  feature introduces. `String`-only signature; no Minecraft types. Implemented
  once per platform module by `FabricMainMenuHook`.
- `HelloWorldMainMenuConfigIO`'s parse/load result type is an internal
  (feature-package-private or small public record) type, not part of the
  cross-module public API surface.

## Services
- `HelloWorldMainMenuService` (feature-owned; not a `services/` module
  capability — see architecture.md's distinction between shared cross-feature
  `Services` and per-feature `services/`). Pure business logic: no
  `net.minecraft.*`, no `net.fabricmc.*` imports anywhere in this class or its
  dependencies (`HelloWorldMainMenuConfig`, `HelloWorldMainMenuConfigIO`).

## Feature Classes
- `HelloWorldMainMenuConfig` (data/value type + one derived predicate)
- `HelloWorldMainMenuConfigIO` (file + string (de)serialization for the config schema)
- `HelloWorldMainMenuService` (orchestration: load config, decide, call hook, report warnings)
- Per platform: `FabricMainMenuHook` (Version Adapter implementing `MainMenuHook`,
  self-registers on `ScreenEvents.AFTER_INIT` for `TitleScreen` instances,
  uses `Screens.getButtons(screen)` to add/remove a text widget — satisfies
  FR1–FR3) and `HelloWorldMainMenuClientInitializer` (composition root)

## Tests

### Test Strategy
Per NFR3, all tests for feature business logic run on a plain JVM with no
Minecraft/Fabric classes on the classpath:
- `features/hello-world-main-menu` is a plain `java-library` Gradle module
  with **no** dependency on any `platform/fabric-*` module, `minecraft`,
  `fabric-loader`, or `fabric-api` — this is structurally guaranteed by the
  module graph decided above (Decision 2), not just convention.
- JUnit 5 (Jupiter) is added as `testImplementation` to
  `features/hello-world-main-menu/build.gradle` only. This is the one new
  external Gradle dependency this plan introduces (see Dependencies) — flagged
  explicitly as a deviation from the spec's "no new dependency anticipated"
  since no test framework exists anywhere in the repo yet and NFR3 cannot be
  exercised without one.
- `HelloWorldMainMenuServiceTest` uses a hand-written fake/spy `MainMenuHook`
  (records whether `showLabel(String)`/`hideLabel()` was called and with what
  argument) — no mocking framework needed given the interface's tiny surface.
- `HelloWorldMainMenuConfigIOTest` uses JUnit's `@TempDir` for real
  `java.nio.file` I/O (plain JDK, not Minecraft/Fabric — permitted under
  NFR3) to verify FR5 (missing file created with defaults) and NFR4
  (malformed file -> defaults + warning, never throws).
- No platform-module test coverage is added by this plan (`ScreenEvents`
  registration and widget placement are not unit-testable on a plain JVM);
  platform behavior (FR1, FR2, FR7, UI/Rendering positioning) is verified
  manually in-game across all three targets during the verification phase,
  per the spec's own framing ("visually verifiable-in-game").

### Test Cases
- `HelloWorldMainMenuConfigTest`:
  - `DEFAULT` has `enabled=true`, `text="Hello World"`.
  - `shouldDisplayLabel()` true for `(true, "Hello World")`.
  - `shouldDisplayLabel()` false for `(false, "Hello World")` (FR6).
  - `shouldDisplayLabel()` false for `(true, "")` and `(true, "   ")` (Decision 4).
- `HelloWorldMainMenuConfigIOTest`:
  - Missing file -> `load()` creates it with serialized `DEFAULT` content and
    returns `DEFAULT` with no warning (FR5).
  - Well-formed file (`{"enabled": false, "text": "Hi"}`) -> parses correctly,
    no warning.
  - Malformed file (invalid JSON, wrong types, missing keys) -> returns
    `DEFAULT` with a non-null/non-empty warning message, never throws (FR5, NFR4).
  - Excessively long `text` value -> parses as-is, tolerated, no crash (Security section).
  - `parse`/`serialize` round-trip for a handful of representative values.
- `HelloWorldMainMenuServiceTest`:
  - `enabled=true`, non-blank `text` -> fake hook's `showLabel(text)` invoked
    with the exact configured string, `hideLabel()` not invoked.
  - `enabled=false` -> `hideLabel()` invoked, `showLabel` not invoked, no
    config file write beyond `load()`'s own default-creation behavior (FR6:
    "no screen-event handler performs any mutation beyond the cheap
    enabled/disabled check" — verified here at the service level since the
    screen-event handler itself is in platform and not unit-testable).
  - Malformed config on disk -> `warningLogger` consumer invoked with a
    message; service still resolves to defaults' `shouldDisplayLabel()`
    behavior afterward instead of throwing.

## Documentation
- `features/hello-world-main-menu/README.md` — per `documentation.md` and the
  spec's stated goal of a copyable template: folder-layout explanation
  (including why `events/`/`gui/`/`mixins/` are placeholders), the config
  schema, and a short "how to add a 4th platform module" pointer back to
  `minecraft.md`'s runbook (spec's Version Compatibility section says a 4th
  adapter should be the only change needed).
- `docs/adr/0001-platform-composition-root-may-depend-on-feature-classes.md` — Decision 5.
- JavaDoc with at least one usage example on every public class/interface
  created (`MainMenuHook`, `HelloWorldMainMenuConfig`, `HelloWorldMainMenuConfigIO`,
  `HelloWorldMainMenuService`, each `FabricMainMenuHook`), per NFR6 /
  `philosophy.md` ("Public APIs require JavaDoc and examples").

## Dependencies
- **No new external Maven/Gradle library dependencies**, except:
  - JUnit 5 (Jupiter) as `testImplementation` on `features/hello-world-main-menu`
    only, plus `test { useJUnitPlatform() }` centralized in the root
    `build.gradle`'s `subprojects {}` block. Exact version must be confirmed
    against Maven Central at implementation time rather than guessed (per
    `minecraft.md`'s general caution against trusting unverified version
    numbers, applied here to a non-Fabric library out of caution since this
    planning pass has no Gradle-execution tool available to confirm a
    resolved version).
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:hello-world-main-menu` -> `api` (`api` configuration; see Decision 2)
  - `platform:fabric-26.2` -> `features:hello-world-main-menu` (`implementation`)
  - `platform:fabric-26.1` -> `features:hello-world-main-menu` (`implementation`)
  - `platform:fabric-1.21.11` -> `features:hello-world-main-menu` (`implementation`)
- Existing dependencies relied on, unchanged: `net.fabricmc.fabric-api:fabric-api:${fabric_api_version}`
  (already declared per platform module; supplies `fabric-screen-api-v1` /
  `fabric-lifecycle-events-v1` transitively) and `net.fabricmc:fabric-loader:${loader_version}`
  (already declared per platform module; supplies `FabricLoader.getInstance().getConfigDir()`
  and `ClientModInitializer`).
- Depends on Decisions 1–6 above being accepted as part of this plan's approval
  — several of them resolve ambiguity the spec explicitly left open, so they
  are substantive parts of what's being approved, not incidental detail.

## Risks
1. **Exact TitleScreen/widget class names and packages are not yet confirmed
   for each mapping.** The spec itself flags this ("exact pixel offsets are
   an implementation detail," render-state-extraction model uncertainty for
   1.21.11). Per `minecraft.md`'s Research Rules ("Always verify official
   documentation... Never invent APIs"), implementation must confirm the real
   `TitleScreen`/text-widget class names and `Screens.getButtons(screen)`
   usage per version by compiling against each version's actual mappings,
   not by guessing from this plan. Mitigation: implement/build one platform
   module at a time, let real compile errors drive the exact API, per
   `minecraft.md`'s runbook.
2. **Widget positioning collisions.** Placing the label "above the
   Singleplayer button row" must be verified visually at multiple resolutions
   and GUI scales per version (vanilla title screen layouts can differ
   slightly across MC versions/branches). Mitigation: manual visual
   verification on all three targets during the verification phase (per
   spec's Non-goals/UI section), not just a single resolution.
3. **Hand-rolled JSON parsing (Decision 6) is narrower than a general JSON
   library.** Risk of subtle parsing bugs (e.g., escaped characters in
   `text`) if the implementation is not kept deliberately conservative.
   Mitigation: the test suite above must include escaped-character and
   malformed-input cases; the parser must fail closed (default + warn) on
   anything it doesn't confidently recognize, never attempt "best-effort"
   partial parsing.
4. **`FabricLoader.getInstance().getConfigDir()` behavior in a dev
   environment vs. a real launch** should be sanity-checked manually per
   platform module during verification (per-instance config dir semantics
   are standard Fabric Loader behavior, but this repo has not exercised
   config-file I/O before).
5. **Client entrypoint ordering.** `ClientModInitializer.onInitializeClient()`
   runs once at client startup, before any `TitleScreen` exists; the
   `FabricMainMenuHook` must register its `ScreenEvents.AFTER_INIT` listener
   at construction time (not lazily), so the very first title screen shown
   already has the label per FR1/FR2. Must be verified in-game, not just
   inferred.
6. **JUnit version drift risk**, since this plan cannot verify an exact
   version number against a live Gradle resolution (see Dependencies) —
   implementation should pick a current stable JUnit 5 release and record the
   chosen version in the eventual verification report.
7. **No ADR template precedent** — `docs/adr/README.md` is a one-line stub, so
   ADR-0001's structure is this plan's own proposal (Context/Decision/Consequences),
   not an established repo convention; the user may want a different shape.

## Acceptance Criteria
Mapped to the specification's functional and non-functional requirements:

- **FR1** — On each of the three platform targets, after `TitleScreen` is
  shown, a label reading the configured `text` (default `"Hello World"`) is
  visible, positioned so it does not overlap/disable the Singleplayer /
  Multiplayer / Realms / Options / Quit buttons, at common resolutions/GUI scales.
- **FR2** — Resizing the window (re-triggers `init()`) and returning to the
  title screen from a world/server both still show the label.
- **FR3** — The label consumes no click/hover/tab-focus interaction (verified
  manually: clicking it does nothing, tabbing through widgets skips it or
  behaves as inert text).
- **FR4/FR5** — `HelloWorldMainMenuConfigIOTest` passes: missing config file
  is created with defaults; malformed file falls back to defaults with a
  logged warning (verified via `HelloWorldMainMenuServiceTest`'s
  `warningLogger` case) and never throws/crashes.
- **FR6** — `HelloWorldMainMenuServiceTest`'s `enabled=false` case passes:
  `hideLabel()` called, `showLabel` not called.
- **FR7** — Manual in-game check confirms identical visible text/position
  behavior across all three targets for the same config file content.
- **FR8** — `features/hello-world-main-menu` and `api` modules contain zero
  `net.minecraft.*` / `net.fabricmc.fabric.api.client.*` imports (spot-checked
  via `grep` across `features/hello-world-main-menu/src` and `api/src` during
  verification); all such imports exist only under `platform/fabric-*/src`.
- **NFR1** — No `.mixins.json` entries or `@Mixin` classes are added by this
  feature; `git diff` for `*.mixins.json` files is empty.
- **NFR2** — Manual/code-review check: `applyToMainMenu()` is called once per
  `ScreenEvents.AFTER_INIT` firing (once per `TitleScreen` construction), no
  per-tick hooks registered, no per-frame allocation in the render path (the
  widget itself is constructed once per screen `init()`, consistent with any
  other vanilla button).
- **NFR3** — `features/hello-world-main-menu`'s `build.gradle` has zero
  dependency (direct or transitive) on any `platform/*`, `minecraft`,
  `fabric-loader`, or `fabric-api` coordinate; `gradlew :features:hello-world-main-menu:test`
  runs and passes without any Minecraft/Fabric jar on the test classpath
  (spot-checked via `--configuration testRuntimeClasspath` during verification).
- **NFR4** — `HelloWorldMainMenuConfigIOTest`'s malformed-file cases all pass
  without an uncaught exception.
- **NFR5** — `features/hello-world-main-menu` contains all required
  sub-packages/files (`api`, `config`, `events`, `gui`, `mixins`, `resources`,
  `services`, `tests` per Decision 3's mapping) and a `README.md`.
- **NFR6** — Every public class/interface listed under Files to Create has a
  JavaDoc comment with at least one `{@code ...}` or `<pre>` usage example.

## Open Questions
- None remaining from the specification — all four items the spec deferred to
  planning are resolved above (Decisions 1–4), plus the two additional
  decisions the spec flagged as planning-adjacent (Decisions 5–6). Any further
  questions should surface during implementation as concrete compile-time
  findings (see Risk 1), not as open design questions.

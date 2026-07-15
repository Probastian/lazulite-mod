# Implementation Plan — Steamworks Bootstrap Service

## Summary
Build the first real capability inside `services/`: a Steamworks native-library
load + `SteamAPI` init/pump/shutdown lifecycle, exposed as a steamworks4j-free
`SteamAvailability` contract in `api/` and a concrete `SteamworksService` (plus
its own `SteamLibraryLoader` implementation) in `services/`, wired from each
`platform/fabric-*` module's `client` entrypoint via `ClientTickEvents`
(pump) and `ClientLifecycleEvents.CLIENT_STOPPING` (shutdown). No
feature-specific Cloud/Friends/Workshop/matchmaking code is added by this plan
(specification's Non-goals). No implementation code is written as part of
this plan.

## Existing Implementation
- `services/build.gradle` is currently `dependencies { api project(':api') }`
  only — no source directory, no other dependency. This plan adds its first
  real dependency (steamworks4j) and its first `src/` tree.
- `api/` has **no `build.gradle` at all** (relies entirely on root
  `subprojects {}` defaults) and currently contains exactly one package,
  `de.lazuli.api.mainmenu` (`MainMenuHook`, a two-method, zero-dependency,
  JavaDoc'd interface with a usage example). This is the direct structural
  precedent this plan follows for the new `de.lazuli.api.steamworks` package:
  no new `api/build.gradle` is needed either.
- Package namespace confirmed as `de.lazuli` throughout `api/`, `services/`,
  `features/hello-world-main-menu/`, and all three `platform/fabric-*`
  modules (verified by reading actual file paths under each module's
  `src/main/java`, not just the spec's citations).
- `settings.gradle` already includes `api`, `common`, `services`,
  `features:hello-world-main-menu`, and the three `platform:fabric-*`
  modules. No change needed there for this plan (no new subproject is added).
- Root `build.gradle`'s `subprojects {}` block already gives every subproject
  (including `services`) Java 21 `sourceCompatibility`/`release`, JUnit 5 +
  AssertJ + Mockito as `test*`, and Lombok/JSpecify as `compileOnly` — with no
  per-module opt-in needed. `services` needs no build.gradle changes for test
  infra, only for the new runtime dependency.
- Each `platform/fabric-*/build.gradle` currently declares `api project(':api')`
  and `implementation project(':features:hello-world-main-menu')`, plus
  `minecraft`/`fabric-loader`/`fabric-api` coordinates (via `implementation`
  for 26.2/26.1 using the plain `net.fabricmc.fabric-loom` plugin, or
  `modImplementation` + an explicit Yarn `mappings` dependency for
  `fabric-1.21.11`, which uses `net.fabricmc.fabric-loom-remap` instead and
  pins its own `loom_version=1.17.14` in a per-module `gradle.properties`
  override — the root `gradle.properties`' `loom_version=1.17-SNAPSHOT`
  governs only the two non-remap modules). None currently declare
  `project(':services')` or any `include` (Jar-in-Jar) dependency — both are
  new to this plan.
- Every `platform/fabric-*` module already has a `"main"` entrypoint
  (`LazuliMod`, `environment: "*"`, static SLF4J `LOGGER`) and a `"client"`
  entrypoint (`HelloWorldMainMenuClientInitializer`, a
  `net.fabricmc.api.ClientModInitializer`) declared in `fabric.mod.json`'s
  `"entrypoints"` object as single-element arrays. This plan adds a second
  `"client"` array entry per module (Fabric Loader invokes a mod's own
  multiple entrypoints of the same type in the array's declared order), not a
  second `"main"` entrypoint — Steam's client-facing API has no
  dedicated-server use here (specification's Compatibility/Composition-root
  sections).
- `docs/adr/0001-platform-composition-root-may-depend-on-feature-classes.md`
  establishes that a platform composition root may construct concrete
  **Feature** classes for bootstrap wiring, as an explicit, narrow exception
  to `architecture.md`'s "Platform -> API only" table. It does not, as
  written, cover a composition root constructing a concrete **Services**
  class (`SteamworksService`) directly — the specification itself calls this
  out as "generalized here from 'Platform may depend on a Feature to wire
  it' to 'Platform wires a shared Service into a Feature.'" This plan treats
  that generalization as itself a significant architectural decision
  requiring its own ADR (Decision 2 below), consistent with
  `philosophy.md`'s "Significant architectural changes require an ADR" and
  the precedent set by ADR-0001 being written as part of its own feature's
  plan rather than assumed.

### Resolved: native-binary bundling question (Architecture section, "the two-binary distinction")
**Resolved empirically: the officially published `com.code-disaster.steamworks4j:steamworks4j` jar on Maven Central bundles *both* binaries; no separate vendoring of Valve's `redistributable_bin` is needed.**

Evidence gathered directly from steamworks4j's own official documentation site
(`https://code-disaster.github.io/steamworks4j/build-instructions.html`) and
changelog (`https://github.com/code-disaster/steamworks4j/blob/master/CHANGES.md`):
- Build instructions state verbatim: *"During the compile phase, the Maven
  build pulls shared libraries out of the `sdk/redistributable_bin/` folder
  and adds them as resources to the `steamworks4j` module."* — i.e. Valve's
  redistributable `steam_api`/`steam_api64` binaries are baked into the
  **published `steamworks4j` jar itself** as classpath resources, by the
  maintainer's own release build, not left for consumers to source
  separately.
- The same page explicitly carves out the *exception*: *"For copyright
  reasons, this is **not** done for `steamworks4j-server` and the
  `sdkencryptedappticket` library"* — confirming the exception is scoped to
  the server/encrypted-ticket artifacts this project explicitly excludes
  (specification's Non-goals: no `steamworks4j-server`), not to the core
  client `steamworks4j` artifact this plan actually depends on.
- CHANGES.md v1.6.0 confirms the JNI bridge is packaged the same way:
  *"Removed `steamworks4j-natives` subproject. Native libraries are now added
  as resources to `steamworks4j.jar` directly."*
- The Getting Started guide's native-loading section corroborates this from
  the consumer side: a custom `SteamLibraryLoader` (the path this plan takes,
  since neither `steamworks4j-gdx` nor `steamworks4j-lwjgl3` applies to a
  Fabric mod) must *"locate and load the shared libraries (`steam_api`,
  `steamworks4j`) yourself"* — both names, both already resolvable from the
  classpath once the plain `steamworks4j` jar is a dependency.

**Consequence:** `services/build.gradle` needs nothing beyond a plain Maven
coordinate dependency for steamworks4j itself; this project does not need to
separately obtain, vendor, or license-track Valve's `redistributable_bin`
files. (Recorded under Risks below is the residual, narrower point that this
plan has not independently re-derived Valve's Steamworks SDK redistribution
license terms — only confirmed that steamworks4j's own build/release process
already bundles the files, which is what determines whether *this* project
needs to do anything extra.)

### Resolved: exact steamworks4j Maven coordinate and version
Confirmed via `https://central.sonatype.com/artifact/com.code-disaster.steamworks4j/steamworks4j`
(latest version shown: **1.10.0**) and cross-checked against
`https://central.sonatype.com/artifact/com.code-disaster.steamworks4j/steamworks4j/1.10.0`
existing as a real, resolvable version page. Coordinate:
`com.code-disaster.steamworks4j:steamworks4j:1.10.0`. (Maven Central's search
API, `search.maven.org`, returned a stale/older index topping out at 1.9.0 at
the time of this check — Sonatype Central's own artifact page is the more
current source and is what this plan relies on; implementation should
re-confirm resolution succeeds against this repo's actual configured
`mavenCentral()` repository, per the standing dependency-verification
policy, since a planning-time web check cannot confirm real Gradle
resolution.)

### Resolved: Fabric Loom `include` (Jar-in-Jar) syntax for Loom `1.17-SNAPSHOT`/`1.17.14`
Confirmed via Fabric's official docs (`https://docs.fabricmc.net/develop/loom/options`,
the current Loom options reference): the `include` configuration's syntax for
a plain (non-mod) library dependency is:
```gradle
dependencies {
    include "com.code-disaster.steamworks4j:steamworks4j:${steamworks4j_version}"
}
```
Per that same page's wording: *"Include a non-mod library jar in the remapped
jar. A dummy mod will be generated. Not transitive."* — this is exactly
steamworks4j's shape (a plain Java library, not itself a Fabric mod), so no
extra Loom configuration is needed beyond this one line per platform module.
The docs also confirm `include`-declared dependencies "are added to
`remapJar` in remapping Loom, or to `jar` in non-remapping Loom respectively"
— i.e. the same `include` syntax applies uniformly to both the plain
`net.fabricmc.fabric-loom` plugin (`fabric-26.2`, `fabric-26.1`, Loom
`1.17-SNAPSHOT`) and the `net.fabricmc.fabric-loom-remap` plugin
(`fabric-1.21.11`, Loom `1.17.14`) used in this repo — Loom's own docs treat
this as one feature with two internal targets, not two different syntaxes to
pick between. `include` is orthogonal to (not a replacement for) the normal
compile/runtime dependency edge: this plan gets steamworks4j onto each
platform module's compile and dev-`runClient` classpath transitively through
`implementation project(':services')` (since `services` declares steamworks4j
as `api`), and separately uses `include` purely so the *shipped, packaged*
mod jar (what a player installs, as opposed to a Gradle project graph)
physically nests the steamworks4j jar inside it — these are two different
problems the current build already conflates for `fabric-api`/`fabric-loader`
(which end-users are expected to already have installed as separate mods) but
must not conflate for steamworks4j (a plain library end-users cannot
separately "install" as a mod).

## Decisions on the Open Questions (resolved during planning)

### 1. `SteamLibraryLoader` implementation: a services-owned `ClasspathSteamLibraryLoader`, with native-file-name resolution factored out as a pure, unit-testable function
steamworks4j's own extension point (`com.codedisaster.steamworks.SteamLibraryLoader`,
confirmed via its actual source: `default void setLibraryPath(String)` /
`default boolean loadLibrary(String libraryName)`, both no-op by default) is
implemented once, in `services/steamworks`, as `ClasspathSteamLibraryLoader`
(not literally named `SteamLibraryLoader`, to avoid colliding with/shadowing
steamworks4j's own interface of that exact simple name in imports and
JavaDoc). It extracts each requested native resource from this project's own
classpath (steamworks4j's jar, already a normal dependency per the resolved
bundling question above) to a writable extraction directory supplied via its
constructor (a `Path`, decided by the platform composition root — see
Decision 3), then `System.load()`s it, returning `loadLibrary`'s `boolean`
per-call result instead of throwing.

The per-OS/arch native file-name mapping (e.g. `steam_api64.dll` /
`libsteam_api.so` / `libsteam_api.dylib` for Valve's binary, and
`libsteamworks4j.{dll,so,dylib}` for steamworks4j's own bridge) is factored
into its own small pure function/class (e.g. `SteamNativeLibraryNames`) that
takes `os.name`/`os.arch`-shaped `String` parameters rather than reading
`System.getProperty` itself — this keeps the OS/arch -> filename mapping
unit-testable across every combination from a single CI machine, with only
the real call site (`ClasspathSteamLibraryLoader`) passing the JVM's actual
`System.getProperty("os.name")`/`"os.arch"` values.

Rejected alternative: `steamworks4j-gdx`/`steamworks4j-lwjgl3`'s bundled
loaders — inapplicable per the specification's own Architecture section
(designed for those frameworks' asset/classloading conventions, not a Fabric
mod's).

### 2. New ADR-0002 generalizing ADR-0001 to Services classes
Write `docs/adr/0002-platform-composition-root-may-construct-services-classes.md`,
extending ADR-0001's "composition-root exception" from Feature classes to
Services classes: a platform module's `client`/`main` entrypoint may
construct a concrete `services/`-layer class (here, `SteamworksService`) for
bootstrap wiring, for the same reasons ADR-0001 already accepted for Feature
classes (something has to `new` the object and drive its lifecycle; a formal
`api`-side registry would just relocate the same "who wires this" question
into `api`, which this repo has already rejected once). Justification for a
*new* ADR rather than silently stretching ADR-0001: ADR-0001's title and
"Consequences" section are written specifically in terms of Feature classes
and `features/<name>` project dependencies; broadening its literal scope
without a recorded decision would leave a future reader unable to tell
whether that broadening was deliberate or an oversight.

### 3. App ID resolution: a services-owned `SteamAppIdResolver`, driven by a JVM system property, decoupled from the separate dev-`steam_appid.txt` Gradle task
Two independent mechanisms, each solving a different part of the
specification's Configuration section, deliberately not conflated:
- **Runtime override, read by `SteamworksService` itself at startup:** a small,
  pure, unit-testable `SteamAppIdResolver` in `services/steamworks` resolves,
  in priority order: (1) a JVM system property (`-Dlazuli.steamAppId=...`,
  read via a `Function<String, String>` parameter so tests can inject a fake
  lookup instead of mutating real system properties), falling back to (2)
  Valve's public test App ID `480`. This is this project's own convenience
  layer on top of Valve's file-based mechanism, not something Valve's native
  API reads directly — documented as such in this class's JavaDoc per the
  specification's explicit warning.
- **Dev-environment `steam_appid.txt` generation, a Gradle build concern, not
  runtime code:** each `platform/fabric-*/build.gradle` gains a small task
  (e.g. `generateSteamAppId`) that writes a `run/steam_appid.txt` file
  (defaulting to `480`, overridable via a Gradle project property, e.g.
  `-PsteamAppId=...`) before `runClient` executes, so `run/` (already
  `.gitignore`'d) always has the file a fresh `git clone` + `runClient` needs,
  without a manually-created, easily-forgotten file. This is deliberately a
  *different* override knob (a Gradle project property, resolved at build
  time) from the runtime JVM system property above (resolved at
  `SteamworksService` construction time) — both exist because they solve
  different problems (what dev-time `steam_appid.txt` content to generate,
  vs. what App ID a real running process should ask `SteamAPI` for), and
  conflating them into one "the same env var means two different things at
  two different times" mechanism is exactly what the specification warns
  against.

### 4. Native-library extraction directory: `FabricLoader.getInstance().getConfigDir()`-relative, decided by the composition root, not by `services` itself
Per NFR5, `services`/`api` must stay buildable/testable with no Minecraft/
Fabric classes on the classpath, so `ClasspathSteamLibraryLoader` cannot call
`FabricLoader` itself. Each platform module's new `SteamworksClientInitializer`
(Decision 5) resolves a directory via
`FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("steamworks-natives")`
(the same `getConfigDir()` API this repo's existing
`HelloWorldMainMenuClientInitializer` already relies on, so no new Fabric
Loader API surface is introduced) and passes it into `ClasspathSteamLibraryLoader`'s
constructor as a plain `java.nio.file.Path`. This sidesteps the antivirus-related
`java.io.tmpdir` extraction issues the specification flags, without requiring
`services` to know Fabric Loader exists. (Whether Fabric Loader additionally
exposes a more purpose-built "cache directory" API in loader `0.19.3` is a
minor, low-risk detail left for implementation to confirm; the config-dir
fallback above is already proven to work in this repo and is sufficient
either way.)

### 5. Composition root: one new `SteamworksClientInitializer` per platform module, separate from `HelloWorldMainMenuClientInitializer`
Each `platform/fabric-*` module gets a new
`de.lazuli.SteamworksClientInitializer implements ClientModInitializer`,
registered as a second entry in `fabric.mod.json`'s `"client"` array
(alongside, not replacing, `HelloWorldMainMenuClientInitializer`). It:
resolves the App ID (Decision 3) and native-library directory (Decision 4),
constructs `SteamworksService`, registers `ClientTickEvents.END_CLIENT_TICK ->
steamworksService::pumpCallbacks` and `ClientLifecycleEvents.CLIENT_STOPPING
-> steamworksService::shutdown` (both `fabric-lifecycle-events-v1`, already
transitively available, no new Gradle coordinate), and logs the resolved
`isSteamAvailable()`/`steamAppId()` once via `LazuliMod.LOGGER`.

Kept as its own entrypoint class (rather than folded into the existing
Hello-World one) because the two features are unrelated bootstrap concerns
with independent lifecycles and this repo's own dependency-rules table
already forbids Feature-to-Feature coupling in spirit; keeping composition-root
classes single-purpose mirrors that same discipline even though
composition roots are technically exempt from the Feature/Platform edge
rule.

**Explicitly out of scope, flagged for a future extension (per specification's
Non-goals/Future Extensions and this plan's own Risks):** this plan does not
solve how a *second*, future client entrypoint (e.g. a future
`features/steam-cloud` feature's own composition-root wiring) obtains the
*same* already-constructed `SteamworksService` instance rather than
constructing a second, competing one (which would double-attempt
`SteamAPI.init()` in one process). No consuming feature exists yet in this
plan's scope, so this hand-off mechanism is deliberately left unresolved
here, consistent with the specification's own "Consumption by future
features" section describing it as future wiring work.

## Files to Create

**`api` module** (no new `build.gradle` — matches existing zero-dependency precedent):
- `api/src/main/java/de/lazuli/api/steamworks/SteamAvailability.java`
  — interface: `boolean isSteamAvailable();` and `long steamAppId();`
  (a raw primitive, not a steamworks4j type). JavaDoc must explicitly call
  out (per specification's Public API section) that, unlike `MainMenuHook`,
  this is *not* a Platform-API/Version-Adapter pair — steamworks4j has no
  Minecraft dependency, so there is exactly one implementation
  (`SteamworksService`), not one per Minecraft version — so a future reader
  doesn't go looking for a per-platform adapter that intentionally doesn't
  exist.

**`services` module:**
- `services/build.gradle` (modified — see Files to Modify)
- `services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java`
  — implements `SteamAvailability`. A static factory method (e.g.
  `SteamworksService.create(long appId, Path nativeLibraryDirectory,
  Consumer<String> warningLogger)`) performs `SteamAPI.loadLibraries(...)` +
  `SteamAPI.init()`/`initEx()`, catching steamworks4j's checked
  `SteamException` and treating any failure identically (unavailable, logged
  via `warningLogger`, never thrown out of `create(...)`); a
  package-private constructor accepting a precomputed availability/App-ID
  state exists separately so tests can exercise `pumpCallbacks()`/`shutdown()`
  idempotency without a real native-library attempt (see Test Strategy).
  `pumpCallbacks()` calls `SteamAPI.runCallbacks()` (no-op if unavailable);
  `shutdown()` calls `SteamAPI.shutdown()` if-and-only-if this instance ever
  successfully initialized, and is safe to call multiple times or when never
  initialized.
- `services/src/main/java/de/lazuli/services/steamworks/ClasspathSteamLibraryLoader.java`
  — implements `com.codedisaster.steamworks.SteamLibraryLoader`; constructor
  takes the extraction directory `Path` (Decision 4); `loadLibrary(String)`
  extracts the named classpath resource to that directory (creating it if
  needed) and `System.load()`s it, returning `false` (never throwing) on any
  I/O or link failure.
- `services/src/main/java/de/lazuli/services/steamworks/SteamNativeLibraryNames.java`
  — pure per-OS/arch native file-name resolution (Decision 1), parameterized
  on `os.name`/`os.arch`-shaped strings for testability.
- `services/src/main/java/de/lazuli/services/steamworks/SteamAppIdResolver.java`
  — pure App ID resolution (Decision 3): JVM-system-property override,
  `Function<String, String>`-injected for testability, defaulting to `480L`.
- `services/src/test/java/de/lazuli/services/steamworks/SteamworksServiceTest.java`
- `services/src/test/java/de/lazuli/services/steamworks/SteamNativeLibraryNamesTest.java`
- `services/src/test/java/de/lazuli/services/steamworks/SteamAppIdResolverTest.java`

**Platform modules — one entrypoint per module (x3):**
- `platform/fabric-26.2/src/main/java/de/lazuli/SteamworksClientInitializer.java`
- `platform/fabric-26.1/src/main/java/de/lazuli/SteamworksClientInitializer.java`
- `platform/fabric-1.21.11/src/main/java/de/lazuli/SteamworksClientInitializer.java`

**Documentation:**
- `docs/adr/0002-platform-composition-root-may-construct-services-classes.md` (Decision 2)

## Files to Modify
- `gradle.properties` (root) — add `steamworks4j_version=1.10.0` alongside the
  existing pinned-dependency properties (`fabric_api_version`,
  `junit_version`, etc.), with a comment citing Maven Central as the source.
- `services/build.gradle` — add
  `api "com.code-disaster.steamworks4j:steamworks4j:${steamworks4j_version}"`
  (`api`, not `implementation`, matching this repo's own already-stated
  rationale in `features/hello-world-main-menu/build.gradle` for exactly this
  choice: `SteamworksService`'s public surface is expected to accept/expose
  steamworks4j-typed values, e.g. via `com.codedisaster.steamworks.SteamException`
  in its factory method's contract, to feature code that will eventually
  construct `SteamFriends`/`SteamRemoteStorage`/etc. objects themselves).
- `platform/fabric-26.2/build.gradle`, `platform/fabric-26.1/build.gradle`,
  `platform/fabric-1.21.11/build.gradle` — each gains:
  - `implementation project(':services')` (new dependency edge; gets
    steamworks4j onto the compile/dev-runtime classpath transitively via
    `services`'s `api` dependency)
  - `include "com.code-disaster.steamworks4j:steamworks4j:${steamworks4j_version}"`
    (Jar-in-Jar; physically bundles steamworks4j — JNI bridge and Valve's
    redistributable binary alike — into each module's shipped mod jar)
  - a `generateSteamAppId` task (Decision 3) writing `run/steam_appid.txt`
    before `runClient`
- `platform/fabric-26.2/src/main/resources/fabric.mod.json`,
  `platform/fabric-26.1/src/main/resources/fabric.mod.json`,
  `platform/fabric-1.21.11/src/main/resources/fabric.mod.json` — each gains
  a second entry in the existing `"client"` array:
  `"de.lazuli.SteamworksClientInitializer"`, alongside the existing
  `"de.lazuli.HelloWorldMainMenuClientInitializer"`. No other field changes
  (`"main"`/`"environment": "*"` untouched, per the specification's
  Composition-root-wiring section: Steam's client API is never touched from
  the shared `"main"` entrypoint).
- `.gitignore` — no change needed; `run/` is already ignored, and
  `steam_appid.txt` only ever lives inside `run/` in this repo's dev setup
  (never at the repo root), so no new ignore rule is required. (Called out
  explicitly so implementation doesn't add a redundant rule.)

## Interfaces
- `api/.../steamworks/SteamAvailability` — the only cross-layer abstraction
  this plan introduces into `api`. Zero steamworks4j imports; `boolean` +
  `long` only. Implemented once by `SteamworksService` (not per-platform —
  see the JavaDoc callout in Files to Create).
- `com.codedisaster.steamworks.SteamLibraryLoader` (steamworks4j's own
  interface, not this repo's) — implemented once by
  `ClasspathSteamLibraryLoader` in `services`.

## Services
- `SteamworksService` (new, `services/steamworks`) — the shared, single
  owner of the Steamworks native-library load / init / per-tick pump /
  shutdown lifecycle for the entire process, per the specification's stated
  rationale for building this directly in `services/` rather than waiting
  for a second consumer (four already-documented planned consumers: Cloud,
  Friends, Workshop, matchmaking).

## Tests

### Test Strategy
- `SteamAppIdResolverTest` and `SteamNativeLibraryNamesTest` are ordinary,
  fully deterministic plain-JVM unit tests (pure functions, fake
  `Function<String,String>`/string inputs) — no native library, no real
  Steam client, no I/O.
- `SteamworksServiceTest` covers two categories, deliberately kept separate
  so the deterministic cases never depend on environment state:
  1. **Deterministic, via the package-private constructor** (Decision on
     `SteamworksService`'s shape above): construct with a precomputed
     "unavailable" state and assert `pumpCallbacks()`/`shutdown()` are
     no-ops and never throw, and that `shutdown()` is idempotent (callable
     multiple times); construct with a precomputed "available" state using a
     fake/no-op steamworks4j interaction seam and assert `pumpCallbacks()`
     delegates as expected without throwing.
  2. **One explicitly-environment-dependent integration-style case** via the
     real `SteamworksService.create(...)` factory path, run on a plain JVM
     with the real steamworks4j jar on the test classpath (already true,
     since `services` depends on it) and *no* `steam_appid.txt` present in
     the test working directory: asserts `create(...)` never throws and
     `isSteamAvailable()` is `false` when Steam is not running in the CI
     environment. This test must be written so it *documents* (via a comment
     and/or an environment-variable-gated skip) that a developer running the
     full suite locally with the real Steam client open and App ID 480
     resolvable might instead observe `isSteamAvailable() == true` — the
     assertion that matters and must always hold is "never throws," not a
     hard-coded `false`, to avoid a flaky test (see Risks).
- No platform-module test coverage is added (Fabric Loader/`ClientTickEvents`
  registration is not unit-testable on a plain JVM); FR1–FR5 are verified
  manually in-game across all three targets during the verification phase
  (running with Steam open + App ID 480, and with Steam closed, per FR4).

## Dependencies
- **New external Maven dependency:** `com.code-disaster.steamworks4j:steamworks4j:1.10.0`,
  confirmed against Sonatype Central
  (`https://central.sonatype.com/artifact/com.code-disaster.steamworks4j/steamworks4j`
  and `.../steamworks4j/1.10.0`) — see "Resolved: exact steamworks4j Maven
  coordinate and version" above. Re-confirm actual resolution against this
  repo's configured `mavenCentral()` repository at implementation time (a
  planning-time web check cannot substitute for a real Gradle resolution).
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `services` -> steamworks4j (`api` Gradle configuration; see Files to Modify)
  - `platform:fabric-26.2` -> `services` (`implementation`)
  - `platform:fabric-26.1` -> `services` (`implementation`)
  - `platform:fabric-1.21.11` -> `services` (`implementation`)
- **New Loom `include` (Jar-in-Jar) edges**, one per platform module, same
  coordinate as above — see "Resolved: Fabric Loom `include` syntax."
- **No new Fabric API Gradle coordinate:** `ClientTickEvents`
  (`fabric-lifecycle-events-v1`) and `ClientLifecycleEvents`
  (`fabric-lifecycle-events-v1`) are both already transitively available via
  each platform module's existing `fabric-api` dependency (same module
  family the hello-world feature already relies on for
  `ClientLifecycleEvents`).
- Depends on Decisions 1–5 above being accepted as part of this plan's
  approval, plus the two empirically-resolved Architecture questions
  (native-binary bundling, Loom `include` syntax) being accepted as settled
  rather than re-litigated during implementation.

## Risks
1. **Exact steamworks4j 1.10.0 method signatures for `SteamAPI.loadLibraries(...)`,
   `SteamAPI.init()`/`initEx()`, and `SteamException`'s exact checked-exception
   shape are not pinned by this plan** (confirmed only at a "this is the
   documented general behavior" level via steamworks4j's GitHub source and
   getting-started docs, not by reading the literal 1.10.0 method signatures
   line-by-line). Per this repo's own Research Rules (verify official
   sources, never invent APIs), implementation must confirm the exact
   signatures against the real 1.10.0 jar/source before writing
   `SteamworksService.create(...)`, and treat any mismatch from what this
   plan describes as a normal implementation-time finding, not a plan defect.
2. **`SteamworksServiceTest`'s real-`create(...)` case is inherently
   environment-dependent** (Test Strategy above): a developer's own machine
   running the full test suite with Steam open could observe
   `isSteamAvailable() == true` instead of `false`. Mitigation: assert
   "never throws" as the hard invariant; treat the specific boolean value as
   informational/environment-dependent, not a hard assertion, in that one
   test only.
3. **No mechanism yet for a second future client entrypoint to reuse the same
   `SteamworksService` instance** (Decision 5's explicit out-of-scope note).
   Building a real Cloud/Friends/Workshop feature later will require solving
   this hand-off (e.g. entrypoint-ordering discipline, or a small
   non-global holder passed between composition roots) — flagged now so it
   is not rediscovered as a surprise; not solved by this plan since no
   consuming feature exists yet, per specification's Non-goals.
4. **`ClientTickEvents`'s exact class/method name and package across all
   three supported `fabric-api` versions is not verified by this plan**,
   per the specification's own explicit instruction that this must be
   confirmed by compiling against each version during implementation (same
   treatment as the `Screens.getButtons` -> `Screens.getWidgets` rename
   already handled by the hello-world feature), not assumed here.
5. **Fabric Loader's exact "safe to `System.load()` a fresh native library
   more than once per process" behavior across repeated dev `runClient`
   invocations, and any interaction with `include`'s generated dummy mod
   wrapper and classloading, is not verified by this plan** — implementation
   should manually verify `runClient` works cleanly on a clean `run/`
   directory and on a second consecutive run (native libraries are typically
   loaded once per JVM process, so this is expected to be a non-issue, but
   is unverified).
6. **This plan does not independently re-derive Valve's Steamworks SDK
   redistribution license terms** — it relies on steamworks4j's own
   maintainer already having resolved that question for their published
   client-side `steamworks4j` artifact (confirmed via their own build docs,
   see "Resolved: native-binary bundling question" above). If that
   understanding is ever found to be wrong, this project would need to
   revisit vendoring Valve's binaries itself, per the specification's own
   Future Extensions section.
7. **Maven Central's `search.maven.org` Solr index returned a stale result
   (topping out at 1.9.0) versus Sonatype Central's own artifact page
   (1.10.0)** during this plan's research — implementation should re-confirm
   1.10.0 resolves via this repo's actual Gradle build before treating the
   version as final, rather than trusting either web source in isolation.

## Acceptance Criteria
Mapped to the specification's functional and non-functional requirements:

- **FR1** — After each platform module's `SteamworksClientInitializer` runs,
  `SteamworksService.isSteamAvailable()` reflects a real init attempt for the
  resolved App ID, with no exception escaping construction, verified both by
  `SteamworksServiceTest` (never throws) and manually in-game (Steam
  running + `steam_appid.txt` present -> `true`; Steam closed -> `false`).
- **FR2** — Manual in-game check: with Steam running, letting the client sit
  at the title screen and in a world for an extended period produces no
  Steamworks-related warning/error spam and no observable stutter
  attributable to `pumpCallbacks()`.
- **FR3** — Manual in-game check: closing the client (via `ClientLifecycleEvents.CLIENT_STOPPING`)
  with Steam available produces no crash and no leaked/hung Steam session
  (verified by being able to immediately relaunch and reconnect via Steam's
  own overlay/friends list state, if visually checkable).
- **FR4** — `SteamworksServiceTest`'s deterministic "unavailable" case plus
  the real-`create(...)` never-throws case, together with a manual in-game
  run with the Steam client fully closed: client reaches the main menu
  normally, no crash, `isSteamAvailable() == false`.
- **FR5** — `SteamAppIdResolverTest` passes: no override -> `480L`; a valid
  override system property -> that value; an invalid/unparseable override ->
  falls back to `480L`, never throws.
- **FR6** — `api/src/main/java/de/lazuli/api/steamworks/SteamAvailability.java`
  contains zero steamworks4j (`com.codedisaster.steamworks.*`) imports
  (spot-checked via `grep` during verification); `services` is the only
  module importing `com.codedisaster.steamworks.*` outside the three
  platform modules' `include`d jar itself.
- **NFR1** — Code review: no blocking I/O inside `pumpCallbacks()` beyond the
  single `SteamAPI.runCallbacks()` call.
- **NFR2** — `SteamworksServiceTest` and `ClasspathSteamLibraryLoaderTest`-equivalent
  coverage (folded into `SteamworksServiceTest`'s deterministic cases) confirm
  no uncaught `SteamException`/native-load failure escapes `create(...)`.
- **NFR3** — Code review: no `static` mutable field holding Steam-session
  state anywhere in `services/steamworks` or the three
  `SteamworksClientInitializer` classes; each `SteamworksService` instance is
  constructed once per composition root and threaded through explicitly.
- **NFR4** — `SteamAvailability`, `SteamworksService`, and
  `ClasspathSteamLibraryLoader` each carry a JavaDoc comment with at least
  one `{@code ...}`/`<pre>` usage example.
- **NFR5** — `gradlew :services:test` runs and passes with no
  `net.minecraft.*`/`net.fabricmc.*` jar on its test classpath (spot-checked
  via `--configuration testRuntimeClasspath` during verification, same
  technique used for the hello-world feature's NFR3).
- **Compatibility** — `gradlew build` succeeds for all three platform
  modules with the new `services`/`include` dependencies in place; each
  module's shipped jar (inspected via `jar tf`) contains the nested
  steamworks4j jar/dummy-mod-wrapper structure `include` produces.

## Open Questions
- None remaining from the specification's two explicitly-flagged
  planning-phase items (native-binary bundling, Loom `include` syntax) — both
  resolved empirically above. Decisions 1–5 resolve the remaining
  planning-level ambiguity the specification left open (illustrative-only
  names/signatures, App-ID resolution mechanism, native-library extraction
  location, composition-root wiring shape). Any further questions should
  surface during implementation as concrete compile-time/API-signature
  findings (Risk 1), not as open design questions.

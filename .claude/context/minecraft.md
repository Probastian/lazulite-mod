# Minecraft Development Guide

## Documentation Priority
1. Official Fabric documentation
2. Fabric API source
3. Fabric API JavaDocs
4. Mojang mappings
5. Parchment
6. Sponge Mixin Wiki
7. Minecraft source
8. Minecraft Wiki
9. Community articles

## Mappings
Prefer Mojang mappings where possible.
Use Parchment for parameter names and documentation.
Avoid exposing Yarn/intermediary names in public APIs.

## Research Rules
Always verify official documentation before implementing:
- Rendering
- Mixins
- Registries
- Networking
- Commands
- Data generation
- World generation
- Entity AI

Never invent APIs.

## Wrappers
Wrap verbose Minecraft APIs.

Instead of:
Registry.register(...)

Prefer:
Registrar.item(...)
PlatformRegistry.registerItem(...)

## Version Compatibility
Business logic belongs in common modules.
Minecraft implementation belongs in platform modules.
No version checks inside features.

## Known Cross-Version API Differences
A living record of concrete API divergences discovered between the supported
Minecraft/Fabric versions, so later features don't re-research something
already found. Append a row whenever implementation work turns up a real
divergence (not just package renames already covered by the Obfuscation
Boundary table below) — include which versions it affects.

| API / Concept | ≤1.21.11 (Yarn) | ≥26.1 (Mojang) | Found while |
|---|---|---|---|
| Adding a widget to an existing screen's widget list | `net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(Screen)` | `Screens.getWidgets(Screen)` — renamed in fabric-api, not just remapped | `hello-world-main-menu` (FabricMainMenuHook) |
| Non-interactive text widget | `net.minecraft.client.gui.widget.TextWidget` | `net.minecraft.client.gui.components.StringWidget` | `hello-world-main-menu` (FabricMainMenuHook) |
| Text/component type | `net.minecraft.text.Text`, `Text.literal(String)` | `net.minecraft.network.chat.Component`, `Component.literal(String)` | `hello-world-main-menu` (FabricMainMenuHook) |
| Client singleton | `net.minecraft.client.MinecraftClient` | `net.minecraft.client.Minecraft` | `hello-world-main-menu` (FabricMainMenuHook) |

## Multi-Version Porting Runbook

### The Obfuscation Boundary
Minecraft/Fabric versions 1.21.11 and older are obfuscated. 26.1 and newer are not.
This single fact drives most of what differs between platform modules — check
which side of the boundary a version falls on before doing anything else.

| | ≤1.21.11 (obfuscated) | ≥26.1 (unobfuscated) |
|---|---|---|
| Loom plugin id | `net.fabricmc.fabric-loom-remap` | `net.fabricmc.fabric-loom` |
| Mappings | Yarn, needs a `mappings` dependency | Mojang, no `mappings` dependency |
| Mod dependency configs | `modImplementation` / `modApi` / `modCompileOnly` | plain `implementation` / `api` / `compileOnly` |
| Java version | 21 | 25 |
| Class naming example | `net.minecraft.util.Identifier`, `Identifier.of(ns, path)` | `net.minecraft.resources.Identifier`, `Identifier.fromNamespaceAndPath(ns, path)` |
| Final jar task | `remapJar` (via `assemble`) | `jar` (via `assemble`) |

Yarn stops being maintained after 1.21.11 — there will be no obfuscated version
newer than that to port to.

### Looking Up Version Coordinates
Do not trust WebFetch/WebSearch summaries for exact version strings — the
summarizer has confused real historical Fabric Loom version numbers with
fictional ones when asked about versions past its training data. Always
confirm coordinates with raw HTTP fetches (`curl` via Bash), which return
unsummarized JSON/XML:

- Game versions: `curl -s https://meta.fabricmc.net/v2/versions/game`
- Loader versions: `curl -s https://meta.fabricmc.net/v2/versions/loader`
- Yarn mappings for a game version: `curl -s https://meta.fabricmc.net/v2/versions/yarn/<version>`
- Loom plugin versions: `curl -s https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/maven-metadata.xml`
  (swap `fabric-loom` for `fabric-loom-remap` for obfuscated versions)
- Fabric API versions for a game version: grep the game version suffix out of
  `curl -s https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml`

Sanity-check freshness via the `lastUpdated` field in the maven-metadata XML —
it should be recent, not stale.

### Adding a New `platform/fabric-<version>` Module
1. Duplicate an existing platform module on the same side of the obfuscation
   boundary as a starting point (same plugin id, same dependency style).
2. Give the new module its own `gradle.properties` overriding
   `minecraft_version`, `fabric_api_version`, and (if obfuscated) `yarn_mappings`
   and `loom_version` — do not touch the root `gradle.properties`, which stays
   pinned to the primary/newest supported version.
3. Add the module to `settings.gradle`.
4. If the new version crosses the obfuscation boundary relative to existing
   modules, check the shared modules' Java level (see below) still covers it.
5. Build it directly (`:platform:fabric-<version>:build`) and let real compile
   errors — not guesses — drive any API renames. Class/method names across the
   Yarn/Mojang mapping boundary cannot be reliably predicted from search
   results alone.
6. Regenerate the run configuration
   (`:platform:fabric-<version>:configureClientLaunch`) and hand-add a
   corresponding entry to `.vscode/launch.json` — Loom's `vscode` task writes a
   fixed `"Minecraft Client"` name into the single shared launch.json and its
   up-to-date check is broken across subprojects, so it silently no-ops for
   every module after the first. Never rely on it alone in a multi-version
   workspace.

### Java Version Floor
`api`/`common`/`services`/`libraries` are depended on by every platform
module, so they must compile at the *lowest* Java version required by any
supported Minecraft version (currently 21, for 1.21.11), set once in the root
`build.gradle`. Individual platform modules raise their own
`sourceCompatibility`/`targetCompatibility`/`release` upward as their
Minecraft version requires (25 for 26.1/26.2). Never raise the shared floor to
match the newest platform module — that breaks the build for every older one.

### Toolchain Note
`javac` can target an older `--release` than the JDK running it, but never a
newer one. Gradle's automatic toolchain provisioning was unreliable at
locating an already-installed JDK on this machine (Windows, JDK found under
`Program Files\Microsoft\...` was not auto-detected). The working setup is:
run the whole Gradle invocation under the newest JDK any supported version
needs (currently 25), and let older platform modules cross-compile down via
`options.release`. Don't reach for `java.toolchain {}` per-subproject to solve
this unless auto-provisioning (a configured download repository) is set up —
it will fail closed instead of falling back.

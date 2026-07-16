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
| Singleplayer world-select screen's internal scrolling list widget (nested inside `SelectWorldScreen`, holds one row per save) — **class name confirmed via `javap -p` against this repo's actual resolved Minecraft jars** (`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged*/**`), superseding the planning-time web-research-only guess | `net.minecraft.client.gui.screen.world.WorldListWidget`, extends `net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget<Entry>` extends `net.minecraft.client.gui.widget.EntryListWidget<E>`. `EntryListWidget.addEntry(E)`/`clearEntries()` are `protected`; `children()` is `public final` and returns the live backing list directly (no defensive copy in the accessor itself). `WorldListWidget.Entry` is `abstract` with a **public** no-arg constructor and a non-abstract `getLevel()` (safe to subclass directly, no constructor-accessibility mixin needed). `SelectWorldScreen.levelList` is a `private` field (must locate the widget instance via the Screen's own `children()`/`Screens.getWidgets` list, filtered by `instanceof`, never a direct field reference). | `net.minecraft.client.gui.screens.worldselection.WorldSelectionList`, extends `net.minecraft.client.gui.components.ObjectSelectionList<Entry>` extends `net.minecraft.client.gui.components.AbstractSelectionList<E>` — **not** `EntryListWidget`; that Yarn-era base-class name does not exist at all under Mojang's official mapping used by 26.1/26.2 (a genuine naming divergence beyond a simple rename, confirmed absent via `javap`/jar-entry listing). `AbstractSelectionList.addEntry(E)`/`clearEntries()` are `protected` (same visibility as the Yarn side); `WorldSelectionList` itself additionally overrides `clearEntries()` (still `protected`). `children()` is `public final`, same live-list behavior as the Yarn side. `WorldSelectionList.Entry` is `abstract` with a **public** no-arg constructor and non-abstract `getLevelSummary()` — same subclass-directly approach works, no constructor mixin needed. `SelectWorldScreen.list` is a `private` field, identical constraint to the Yarn side. Confirmed identical across both `26.2` and `26.1` jars (same class/method set). | `steam-cloud-sync` (implementation, mandatory-first-step `javap` pass) |
| **The base `Entry` type itself (`AbstractSelectionList.Entry` / `EntryListWidget.Entry`) is `protected`, not just its `addEntry`/`clearEntries` methods** — a genuine compile-time blocker the plan's own working assumption did not anticipate (it only flagged the *methods* as protected). Only visible via `javap -v`'s `InnerClasses` attribute (`protected static abstract ... Entry=class ...`); plain, non-verbose `javap` run directly against the nested class file misleadingly reports the class itself as `public` (the standalone `.class` file's own access flag differs from the modifier recorded in the enclosing class's `InnerClasses` table, which is what javac actually enforces at compile time — confirmed the hard way, via a real `javac`/Loom compile failure: `"Entry has protected access in AbstractSelectionList"`) | `EntryListWidget.Entry` is `protected`; the concrete `WorldListWidget.Entry` (a subtype, declared directly on `WorldListWidget`) is `public` | `AbstractSelectionList.Entry` is `protected`; the concrete `WorldSelectionList.Entry` (a subtype, declared directly on `WorldSelectionList`) is `public` | `steam-cloud-sync` (`WorldSelectionListInvokerMixin`/`WorldListWidgetInvokerMixin`, implementation — real Gradle compile) |
| **Fix for the above (javac-level only):** an `@Invoker` method's declared parameter type does not need to exactly match the real (here, inaccessible-to-us) target parameter type at the *source* level — declaring it as a more specific, **accessible** subtype (here, the concrete list's own public `Entry` subclass) compiles cleanly on both sides. On 26.x/26.1 (no remap step — plain `net.fabricmc.fabric-loom`, Mojang-mapped) this is also confirmed sufficient end-to-end: a real `:platform:fabric-26.2:compileJava`/`:fabric-26.1:compileJava` run succeeds with zero warnings. | `int lazuli$invokeAddEntry(WorldListWidget.Entry entry)` (not `EntryListWidget.Entry<?>`) | `int lazuli$invokeAddEntry(WorldSelectionList.Entry entry)` (not `AbstractSelectionList.Entry<?>`) | `steam-cloud-sync` (implementation) |
| **Open item (1.21.11 only, unresolved by this implementation pass):** on the sole platform module using `fabric-loom-remap`'s real obfuscated-Minecraft remap pipeline, a real `:platform:fabric-1.21.11:remapJar` run prints `"Cannot remap addEntry because it does not exist in any of the targets [net/minecraft/client/gui/widget/EntryListWidget] or their parents"` for the `addEntry` invoker specifically — never for `clearEntries` (arity 0, no generic-erased parameter). Confirmed *not* fixable by choice of our own declared parameter type: tried both the concrete public `WorldListWidget.Entry` subtype and plain `Object` — identical warning either way, ruling out a source-level fix. Matches a documented, known class of Fabric Loom/tiny-remapper limitation remapping `@Invoker`/`@Accessor` targets whose real parameter is a generic type variable (see FabricMC/tiny-remapper issues #124/#126) — not specific to this mixin. The overall build still succeeds (warning, not a failure) and the mixin class is confirmed packaged into the built jar; whether Sponge Mixin's own runtime resolution (independent of this build-time pre-remap pass) still correctly applies `addEntry` against a real, launched, obfuscated 1.21.11 client has **not** been confirmed — this is the single highest-priority check for the verification phase to run first for Group 6 on 1.21.11. | `EntryListWidget.addEntry(E)` — generic parameter, remap warning present (see above) | N/A — no remap step exists on this side at all (plain `jar`, not `remapJar`); not applicable | `steam-cloud-sync` (implementation, real `remapJar` run) |
| Screen rendering entry point (a custom `Screen` subclass overriding its own drawing, e.g. a manually-drawn progress bar) -- **class name, method name, and drawing model all diverge, not just a rename**, confirmed via `javap` against this repo's actual resolved jars | `Screen.render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta)`; draw via `context.fill(x1, y1, x2, y2, color)` / `context.drawCenteredTextWithShadow(textRenderer, text, x, y, color)`; button widget class is `net.minecraft.client.gui.widget.ButtonWidget` | `Screen.extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta)` -- Mojang's 26.x mapping renamed the whole immediate-mode `render`/`GuiGraphics` model to an "extract render state" model (`GuiGraphicsExtractor`, `extractRenderState`/`extractWidgetRenderState`/`extractContent` across every widget/list class); draw via the same-shaped `guiGraphics.fill(x1, y1, x2, y2, color)` / `guiGraphics.centeredText(font, component, x, y, color)` methods, still present but on the renamed/refactored type; button widget class is `net.minecraft.client.gui.components.Button` | `steam-cloud-sync` (`WorldRestoreScreen`, implementation) |
| `LevelSummary`'s own accessors for a world's save-folder slug / display name (used to key per-world Cloud sync state) -- same class package/name on both sides (`net.minecraft.world.level.storage.LevelSummary`), but its own method names diverge, confirmed via `javap` | `getName()` (save-folder slug) / `getDisplayName()` (player-facing name) | `getLevelId()` (save-folder slug) / `getLevelName()` (player-facing name) | `steam-cloud-sync` (`FabricWorldSyncToggleInjector`/`FabricCloudOnlyWorldListInjector`, implementation) |
| Client-side singleplayer-vs-multiplayer check (FR4.1's `LastPlayedPointer.type`/FR6.2 world-unload detection) -- confirmed via `javap`, a real divergence beyond a simple rename (different method names, not just remapped) | `MinecraftClient.isIntegratedServerRunning()` / `MinecraftClient.getServer()` returning `net.minecraft.server.integrated.IntegratedServer` (nullable); currently-joined server via `MinecraftClient.getCurrentServerEntry()` returning `net.minecraft.client.network.ServerInfo` | `Minecraft.hasSingleplayerServer()` / `Minecraft.getSingleplayerServer()` returning `net.minecraft.server.MinecraftServer` (its `IntegratedServer` subtype, nullable); currently-joined server via `Minecraft.getCurrentServer()` returning `net.minecraft.client.multiplayer.ServerData` | `steam-cloud-sync` (`SteamCloudSyncClientInitializer`, implementation) |
| Resolving a singleplayer world's own save-folder path from `MinecraftServer` (needed for Group 6's world-unload archive trigger, FR6.2) | `MinecraftServer.getSavePath(net.minecraft.util.WorldSavePath.ROOT)`; display name via `MinecraftServer.getSaveProperties().getLevelName()` | `MinecraftServer.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)`; display name via `MinecraftServer.getWorldData().getLevelName()` | `steam-cloud-sync` (`SteamCloudSyncClientInitializer`, implementation) |
| `ClientPlayConnectionEvents`' connection-handle parameter type (event class/package itself, `net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents` in module `fabric-networking-api-v1`, is the same simple name/package on both sides of the boundary — Fabric API's own classes are not remapped the way Minecraft's own classes are) — **Yarn side now confirmed via `javap` against this repo's actual remapped 1.21.11 `fabric-networking-api-v1` jar** (`.gradle/loom-cache/remapped_mods/remapped/net/fabricmc/fabric-api/fabric-networking-api-v1-*/...jar`), matching the planning-time recollection exactly (no divergence found) | `net.minecraft.client.network.ClientPlayNetworkHandler` | `net.minecraft.client.multiplayer.ClientPacketListener` (confirmed via direct fetch of Fabric API's own GitHub source at tag `0.141.4+1.21.11`, which is itself written against Mojang-mapped names; independently reconfirmed via `javap` against the real resolved 26.2 `fabric-api` jar's nested `fabric-networking-api-v1` module) | `steam-cloud-sync` (implementation) |

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

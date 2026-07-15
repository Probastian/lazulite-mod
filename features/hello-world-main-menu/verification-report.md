# Verification Report - Hello World Main Menu

Verification date: 2026-07-15. Specification and plan are approved;
implementation was reported complete. This report is an independent
re-verification against the actual code in the working tree, not a
restatement of the implementer's self-report (no persisted implementer
report file was found in the repo to compare against - see Remaining
Issues).

## Requirement Matrix

| Req | Status | Evidence |
|---|---|---|
| FR1 (label shown, non-overlapping) | Implemented (static) / unverified visually | platform/fabric-26.2/src/main/java/de/probastian/boilerplate/mainmenu/FabricMainMenuHook.java:78-80 constructs a StringWidget, centers it horizontally, offsets it scaledHeight/4 + LABEL_Y_OFFSET(24) below the logo, adds via Screens.getWidgets(screen). Mirrored in fabric-26.1 (same file, same lines) and fabric-1.21.11 (.../mainmenu/FabricMainMenuHook.java:84-86, TextWidget + Screens.getButtons). Actual non-overlap with vanilla buttons at real resolutions/GUI scales requires manual in-game verification - not verifiable here. |
| FR2 (label re-added on every TitleScreen recreation) | Implemented (static) / unverified visually | FabricMainMenuHook constructor (fabric-26.2/.../FabricMainMenuHook.java:54-56) registers ScreenEvents.AFTER_INIT.register(this::onScreenInit) once; onScreenInit (lines 68-81) re-adds the widget for every TitleScreen instance, using the cached labelText field, not a fresh disk read. Resize/return-from-world behavior requires manual verification. |
| FR3 (purely decorative, no interaction) | Implemented (static) / unverified visually | Vanilla StringWidget/TextWidget are non-interactive display widgets, not Button; no click handler wired anywhere in FabricMainMenuHook. Tab-focus/click behavior should still be confirmed manually per the plan's own framing. |
| FR4 (config: enabled/text, defaults) | Implemented | features/hello-world-main-menu/src/main/java/.../api/HelloWorldMainMenuConfig.java:28,34 - record (boolean enabled, String text), DEFAULT = (true, "Hello World"). Read once at startup: HelloWorldMainMenuClientInitializer.java:39-47 (all 3 platforms) calls service.applyToMainMenu() once from onInitializeClient(). |
| FR5 (missing file created; malformed to defaults + warning, no crash) | Implemented and tested | .../config/HelloWorldMainMenuConfigIO.java:71-88 (load). Tests: HelloWorldMainMenuConfigIOTest.java:22-36 (loadCreatesFileWithDefaultsWhenMissing), :80-89 (malformedFileNeverThrows) - both pass (see Test Results). |
| FR6 (enabled=false results in no label, cheap check only) | Implemented and tested | HelloWorldMainMenuConfig.java:47-49 (shouldDisplayLabel), HelloWorldMainMenuService.java:67-79 (applyToMainMenu), FabricMainMenuHook.java:68-77 (onScreenInit returns immediately on text == null, its only "mutation" being that null check). Test: HelloWorldMainMenuServiceTest.java:64-79 (disabledHidesLabelAndNeverShows) passes. |
| FR7 (identical behavior across 3 targets) | Implemented (static) / unverified visually | All 3 FabricMainMenuHooks share identical logic/constants (LABEL_Y_OFFSET = 24, same centering formula), differing only in mapped class names (StringWidget/Component vs TextWidget/Text, getWidgets vs getButtons). All 3 compile successfully against real version jars (see below). Pixel-identical rendered behavior across the three live clients requires manual verification. |
| FR8 (no net.minecraft.* / net.fabricmc.fabric.api.client.* outside platform/fabric-*) | Implemented, verified | grep for net.minecraft. and net.fabricmc.fabric.api.client. across api/src and features/hello-world-main-menu/src returns 4 hits, all inside JavaDoc {@code ...} text (MainMenuHook.java doc comment; HelloWorldMainMenuService.java:16; gui/package-info.java:4; mixins/package-info.java:6-7) - zero real import statements. |
| NFR1 (no mixins) | Implemented, verified | git diff HEAD for *.mixins.json is empty (no changes). grep for @Mixin under platform only matches the three pre-existing mixin/ExampleMixin.java files (unrelated to this feature, present before this feature per commit d66712a). |
| NFR2 (negligible perf: boolean check + at most 1 widget/init, no per-tick/per-frame) | Implemented (code review) | Event-driven via ScreenEvents.AFTER_INIT; onScreenInit constructs at most one widget per firing; no per-tick registration anywhere in the changed files (confirmed by reading all 3 FabricMainMenuHooks and both client initializers in full). |
| NFR3 (feature logic plain-JVM testable, no MC/Fabric on test classpath) | Implemented, verified | gradlew :features:hello-world-main-menu:dependencies --configuration testRuntimeClasspath resolves to only project :api plus JUnit 5/opentest4j artifacts - no minecraft/fabric-loader/fabric-api coordinate anywhere in the tree. |
| NFR4 (config parsing never throws uncaught) | Implemented and tested | HelloWorldMainMenuConfigIO.load/parse catch IOException/RuntimeException and MalformedConfigException respectively (lines 84-87, 104-106). All 10 HelloWorldMainMenuConfigIOTest cases, including malformed/wrong-type/missing-key/long-text/escaped-character cases, pass. |
| NFR5 (required folder layout as sub-packages) | Implemented, verified | features/hello-world-main-menu/src/main/java/.../{api,config,services,events,gui,mixins} all present; events/gui/mixins are package-info.java-only placeholders with documented rationale; src/main/resources/.gitkeep; README.md at module root. Matches plan Decision 3 and the (now-updated) feature-guidelines.md. |
| NFR6 (public classes/interfaces have JavaDoc with usage example) | Implemented, spot-checked | Every public class/interface created by this feature (MainMenuHook, HelloWorldMainMenuConfig, HelloWorldMainMenuConfigIO plus its ParseResult, HelloWorldMainMenuService, all 3 FabricMainMenuHook, all 3 HelloWorldMainMenuClientInitializer) carries a class-level JavaDoc with a pre{@code ...} usage example. No public class found lacking one. |

## Specification Coverage

All 8 functional and 6 non-functional requirements have corresponding
implementation. Everything checkable statically (imports, file presence,
unit-test behavior, compile success) passes. The parts that are inherently
visual/runtime (FR1, FR2, FR3, FR7, and the plan's Risks 2/4/5) are not
verifiable by this static/automated pass and are explicitly called out below
rather than assumed to pass.

## Plan Acceptance Criteria - status

| Criterion | Verifiable here? | Result |
|---|---|---|
| FR1 - label visible, non-overlapping at common resolutions/scales | No - requires manual in-game check | Not verified (flagged) |
| FR2 - resize / return-to-title still show label | No - requires manual in-game check | Not verified (flagged) |
| FR3 - no click/hover/tab-focus interaction | Partially - widget type choice statically supports it; full behavior needs manual check | Not fully verified (flagged) |
| FR4/FR5 - HelloWorldMainMenuConfigIOTest passes | Yes | PASS (10/10) |
| FR6 - HelloWorldMainMenuServiceTest enabled=false case | Yes | PASS |
| FR7 - manual in-game identical behavior | No - requires manual in-game check | Not verified (flagged) |
| FR8 - zero forbidden imports via grep | Yes | PASS |
| NFR1 - no .mixins.json/@Mixin changes | Yes | PASS |
| NFR2 - code-review: event-driven, no per-tick/per-frame | Yes (code review, not runtime profiling) | PASS (by review) |
| NFR3 - test passes with no MC/Fabric on classpath | Yes | PASS |
| NFR4 - malformed-file cases never throw | Yes | PASS |
| NFR5 - required sub-packages/files present | Yes | PASS |
| NFR6 - JavaDoc plus example on every public class | Yes (spot-checked) | PASS |

## Decisions on the Open Questions - followed as written?

1. Constructor injection, not a registry - Confirmed. api/.../MainMenuHook.java defines only the interface, no registry/lookup type anywhere in api. HelloWorldMainMenuService's constructor (HelloWorldMainMenuService.java:49-58) takes MainMenuHook hook directly; each HelloWorldMainMenuClientInitializer news a FabricMainMenuHook and passes it in (HelloWorldMainMenuClientInitializer.java:42-47, all 3 platforms). No global/static state introduced.
2. features/hello-world-main-menu is its own subproject, not an aggregator - Confirmed. settings.gradle:18 has include 'features:hello-world-main-menu'; no features aggregator module exists; features/hello-world-main-menu/build.gradle declares api project(':api') only, matching the plan's stated api-vs-implementation reasoning.
3. Sub-package layout, not literal flat folders - Confirmed. Standard src/main/java/.../helloworldmainmenu/{api,config,services,events,gui,mixins} layout; no custom sourceSets block. feature-guidelines.md was updated (uncommitted working-tree change) to document this exactly.
4. Blank text implies disabled - Confirmed. HelloWorldMainMenuConfig.shouldDisplayLabel() (HelloWorldMainMenuConfig.java:47-49) returns enabled && text != null && !text.isBlank(); covered by HelloWorldMainMenuConfigTest.shouldDisplayLabelFalseWhenTextIsEmpty / ...WhenTextIsWhitespaceOnly (both pass).
5. ADR written now - Confirmed. docs/adr/0001-platform-composition-root-may-depend-on-feature-classes.md exists, resolves per spec Option 1, and .claude/context/architecture.md's Dependency Rules section (uncommitted working-tree change) now carries a "Composition-root exception" note that explicitly cross-references this ADR.
6. Hand-rolled JSON parser, no Gson - Confirmed. Searching for gson/Gson across the feature module only matches prose in specification.md/implementation-plan.md, not code. HelloWorldMainMenuConfigIO's classpath resolves to project :api plus JUnit only - no JSON library dependency anywhere.

All six decisions were followed faithfully in the actual code.

## Architecture Compliance

- Layering respected: features/hello-world-main-menu depends only on :api (api project(':api') in its build.gradle); each platform/fabric-* depends on :api (api) and :features:hello-world-main-menu (implementation) - matches ADR-0001's stated exception exactly, and matches the plan's Files to Modify list.
- No Feature-to-Feature or Feature-to-Platform edges introduced.
- Version Adapters (FabricMainMenuHook) depend only on api plus Fabric/Minecraft - never on Feature classes; only the composition root (HelloWorldMainMenuClientInitializer) crosses into Feature classes, consistent with the ADR's stated scope ("applies specifically to ... ClientModInitializer entrypoints").
- .claude/context/architecture.md's new "Composition-root exception" note (uncommitted) accurately reflects the ADR's conclusion.
- .claude/context/feature-guidelines.md's updated wording (uncommitted) accurately reflects the actual sub-package layout used in code - no drift between doc and implementation.
- Follow-up JUnit centralization: features/hello-world-main-menu/build.gradle no longer declares any JUnit dependency (only a comment noting it is supplied by root); root build.gradle's subprojects {} block (build.gradle:26-36) adds test { useJUnitPlatform() } plus testImplementation/testRuntimeOnly JUnit 5 coordinates for every subproject, version pinned via junit_version=5.14.4 in gradle.properties:22. Confirmed this did not silently drop coverage: all 18 feature tests are still discovered and pass under this centralized setup (see Test Results).

No architecture violations found.

## Documentation Review

- features/hello-world-main-menu/README.md covers: folder-layout mapping table, rationale for empty events/gui/mixins, the no-mixin mechanism, the config schema, and a "4th platform module" runbook pointer - matches the plan's Documentation section and documentation.md's spec/plan/verification convention.
- docs/adr/0001-platform-composition-root-may-depend-on-feature-classes.md follows a clear Context/Decision/Consequences shape as planned (Decision 5), and is cross-referenced from both architecture.md and every HelloWorldMainMenuClientInitializer's JavaDoc.
- JavaDoc-with-example coverage on public classes: full (see Requirement Matrix, NFR6).
- Minor clarity nit (not a bug): HelloWorldMainMenuService.java:60-66's JavaDoc says applying "does not re-read the file from disk on every call beyond what HelloWorldMainMenuConfigIO#load itself does" - worded ambiguously; in the actual call pattern applyToMainMenu() is invoked exactly once per client session (from onInitializeClient()), so this is not incorrect, but the sentence could be misread as implying an in-memory cache exists across repeated calls, when in fact a second call would re-read the file from disk. Recommend clarifying wording; not functionally significant since nothing in the codebase currently calls applyToMainMenu() more than once.

## Test Results

Command: gradlew.bat :features:hello-world-main-menu:test --rerun-tasks (forced fresh run, no task-avoidance).

- BUILD SUCCESSFUL.
- HelloWorldMainMenuConfigTest: 5/5 passed.
- HelloWorldMainMenuConfigIOTest: 10/10 passed.
- HelloWorldMainMenuServiceTest: 3/3 passed.
- Total: 18/18 passed, 0 failed, 0 skipped, 0 errors.

testRuntimeClasspath dependency tree contains only project :api and JUnit 5/opentest4j - no Minecraft/Fabric/Loom artifact present, confirming NFR3 both by test outcome and by dependency inspection.

### compileJava - all three platform modules

Command: gradlew.bat :platform:fabric-26.2:compileJava :platform:fabric-26.1:compileJava :platform:fabric-1.21.11:compileJava --rerun-tasks (forced fresh compilation against each module's real Minecraft/Fabric API jars, not cached).

- BUILD SUCCESSFUL - all three compileJava tasks executed and succeeded (9/9 actionable tasks executed, 0 up-to-date/skipped, 0 failures). This empirically confirms the version-specific API usage claimed in each FabricMainMenuHook's JavaDoc (Screens.getWidgets vs Screens.getButtons, StringWidget vs TextWidget, Component vs Text) actually compiles against the real mapped jars for 26.2, 26.1, and 1.21.11.

## Performance Notes

Code-review only (no runtime profiling performed, and none is feasible without a manual game launch): the implementation is event-driven off ScreenEvents.AFTER_INIT, constructs at most one widget per screen init(), performs exactly one config file read per client session (at onInitializeClient()), and registers no per-tick callback anywhere. This matches NFR2's stated budget. No per-frame allocation was found in any reviewed file.

## Version Compatibility

| Module | Compiles | Adapter uses |
|---|---|---|
| platform/fabric-26.2 | Yes (verified, fresh) | net.minecraft.client.gui.components.StringWidget, net.minecraft.network.chat.Component, Screens.getWidgets |
| platform/fabric-26.1 | Yes (verified, fresh) | Same as 26.2 (mirrors it, per its own JavaDoc) |
| platform/fabric-1.21.11 | Yes (verified, fresh) | net.minecraft.client.gui.widget.TextWidget, net.minecraft.text.Text, Screens.getButtons |

Each fabric.mod.json gained a "client": ["de.probastian.boilerplate.HelloWorldMainMenuClientInitializer"] entrypoint array alongside the pre-existing "main" array, with no other field changes - confirmed for all three platform modules.

Actual player-visible identical behavior across the three live clients (FR7) is not verifiable by static analysis or compilation and requires a manual launch of each target.

## Technical Debt

- LABEL_Y_OFFSET = 24 (all three adapters) is a guessed constant, explicitly flagged by the plan itself as Risk 2, not yet visually confirmed against real vanilla title-screen layouts across resolutions/GUI scales.
- HelloWorldMainMenuService's JavaDoc wording about not "re-reading the file on every call" is slightly ambiguous (see Documentation Review) - low priority.
- No coverage/verification exists yet for FabricLoader.getInstance().getConfigDir() behavior in a real (non-dev) launch environment (plan Risk 4).

## Remaining Issues

1. Manual in-game verification has not been performed for FR1 (non-overlap at common resolutions/GUI scales), FR2 (resize / return-to-title-screen re-add), FR3 (no click/hover/tab-focus consumption), and FR7 (cross-version visual identity). This is the single largest gap between "implementation complete" and "feature verified," and is explicitly anticipated by both the spec ("visually verifiable-in-game") and the plan (Risks 2, 4, 5) - it is not something a static/build-only verification pass can close.
2. No persisted implementer self-report file was found under features/hello-world-main-menu/ to cross-check against; this report's findings are based entirely on independent reading of the code, plan, and spec, plus fresh gradlew runs, rather than reconciliation against a prior claim. No code found that contradicts what the plan describes.
3. No functional bugs, no architecture violations, and no undocumented deviations from the plan's 6 decisions were found.

## Recommendations

1. Perform the manual in-game pass explicitly called for by the spec/plan across all three platform targets (fabric-26.2, fabric-26.1, fabric-1.21.11) at a few representative resolutions/GUI scales, covering: initial title screen, window resize, and return-to-title after disconnect - before considering this feature fully verified.
2. While doing the manual pass, sanity-check FabricLoader.getInstance().getConfigDir()'s resolved path in a real launch (plan Risk 4), and confirm the config file is created at the expected location.
3. Optionally clarify the HelloWorldMainMenuService JavaDoc sentence about re-reading the config file (cosmetic only).
4. No code changes are recommended based on this static/build verification - the implementation matches the specification and plan faithfully wherever it could be checked without launching the game.

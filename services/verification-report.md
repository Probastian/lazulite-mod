# Verification Report — Steamworks Bootstrap Service

## Overall Assessment
The implementation faithfully realizes the specification and plan. All FR1–FR6 and NFR1–NFR5 acceptance criteria are met by the code, the build succeeds, `:services:test` passes with a clean (Minecraft-free) test classpath, and all five self-reported deviations hold up under independent inspection (including bytecode disassembly of the real `steamworks4j-1.10.0.jar`).

## Implemented Requirements
- **FR1/FR4/NFR2** — `SteamworksService.create(...)` (`services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java:84-112`) never throws: catches `SteamException` from `initEx()` plus a defense-in-depth `RuntimeException | UnsatisfiedLinkError` catch-all, converting every failure into `isSteamAvailable() == false` + a logged warning.
- **FR2/NFR1** — `pumpCallbacks()` (`SteamworksService.java:120-124`) is a guarded, single `SteamAPI.runCallbacks()` call, no blocking work.
- **FR3** — `shutdown()` (`SteamworksService.java:131-136`) is idempotent and only calls the native `SteamAPI.shutdown()` if `available && !shutDown`.
- **FR5** — `SteamAppIdResolver` resolves `lazuli.steamAppId` system property → falls back to `480L`; `SteamAppIdResolverTest` covers all branches (no override, valid, blank, unparseable, null lookup).
- **FR6** — Verified via `grep -rln "com.codedisaster" --include="*.java" .` → only `services/src/main/java/.../SteamworksService.java` and `ClasspathSteamLibraryLoader.java` import steamworks4j; `api/steamworks/SteamAvailability.java` has zero steamworks4j imports.
- **NFR3** — No static Steam-session field anywhere; `SteamworksService` is an ordinary constructed instance, not a registry.
- **NFR4** — `SteamAvailability`, `SteamworksService`, and `ClasspathSteamLibraryLoader` each carry JavaDoc with a `{@code ...}` usage example.
- **NFR5** — `./gradlew :services:dependencies --configuration testRuntimeClasspath` contains only `project :api`, `steamworks4j:1.10.0`, JUnit/AssertJ/Mockito — no `net.minecraft`/`net.fabricmc` anywhere. `:services:test` passes.
- **Composition root / entrypoints** — all three `fabric.mod.json` files correctly list `SteamworksClientInitializer` as a second `"client"` entry, alongside `HelloWorldMainMenuClientInitializer`. `"main"` untouched.
- **Jar-in-Jar packaging** — `jar tf` on the built jars confirms `META-INF/jars/steamworks4j-1.10.0.jar` is physically nested inside the shipped mod jar.
- **Dev App-ID Gradle task** — `generateSteamAppId` wired via `runClient.dependsOn` in all three platform modules; confirmed to generate `run/steam_appid.txt` with content `480`.
- **ADR-0002** is a genuine, well-reasoned generalization of ADR-0001 (Feature classes → Services classes), correctly scoped to composition-root wiring only.

## Self-Reported Deviations — Independently Verified
Disassembled the real `com.code-disaster.steamworks4j:steamworks4j:1.10.0` jar (`javap`) rather than trusting the implementer's self-report:
1. **`loadLibraries` returns boolean, no `throws SteamException`** — confirmed via bytecode; only `init()`/`initEx()` declare the checked exception. The single try/catch around both calls is valid.
2. **`SteamAppIdResolver` made public** — justified: called cross-module from `SteamworksClientInitializer` in all three platform modules, so package-private wouldn't compile. `SteamNativeLibraryNames` correctly stays package-private since it's only used internally. Minimal, deliberate visibility.
3. **`appId` param is informational-only** — properly documented in both `SteamworksService.create`'s and `SteamAppIdResolver`'s JavaDoc; not silently misleading.
4. **Dropped deterministic "available" pumpCallbacks/shutdown test** — verified via bytecode that `SteamAPI.shutdown()`/`runCallbacks()` unconditionally call native methods with no init-guard, confirming a fake "available" instance really would throw `UnsatisfiedLinkError`. The gap is real but reasonable; a legitimate follow-up would be static-mocking `SteamAPI` via Mockito's `mockStatic` (available on the classpath, unused).
5. **FR1–FR6/NFR1–NFR5** — all confirmed directly, including an independently-run NFR5 classpath check.

## Minor Observations / Follow-up Recommendations (not blocking)
- `ClasspathSteamLibraryLoader` is `public` but only ever constructed internally by `SteamworksService.create` in the same package — could reasonably be package-private. Minor, not a real leak.
- No dedicated unit test exercises `ClasspathSteamLibraryLoader`'s own failure branches (unsupported OS/arch, missing classpath resource, I/O failure) in isolation — only indirectly reached through the one non-deterministic integration test.
- `SteamworksServiceTest`'s integration test uses `@TempDir(cleanup = CleanupMode.NEVER)` (necessary due to Windows file-locking of loaded natives), which will leak native-library-containing temp directories across repeated local/CI test runs over time — expected and documented, worth monitoring.
- No platform-module-level test coverage was added, consistent with the plan's call-out that Fabric Loader/`ClientTickEvents` registration isn't unit-testable on a plain JVM. FR1–FR5's in-game behavior (Steam running vs. not) has **not** been manually verified — this environment has no real Steam client available.

## Architecture Compliance Summary
- `api/steamworks`: zero steamworks4j imports — confirmed.
- `services`: Minecraft/Fabric-independent — confirmed via clean `testRuntimeClasspath`.
- No global mutable static Steam-session state — confirmed.
- ADR-0002 reads as a genuine, justified decision, correctly scoped to composition-root wiring only.

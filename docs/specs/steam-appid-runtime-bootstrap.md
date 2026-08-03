# Steam App ID Runtime Bootstrap (Shipped-Jar Fix) — Specification

## Overview
Players who download a GitHub Release mod jar and launch Minecraft normally
(not via the Steam client, not via `runClient`/dev environment) see:

```
Steamworks API failed to initialize (FailedGeneric); Steam features
unavailable. Is Steam running, and is a valid steam_appid.txt present
(App ID 480)?
```

`isSteamAvailable()` reports `false` and every Steam-dependent UI affordance
(e.g. the Steam Cloud world-sync toggle icon,
`features/steam-cloud-sync`) fails to render.

**Confirmed root cause** (already diagnosed prior to this spec; re-verified
against current source during this pass):

- Valve's `SteamAPI_Init`/`SteamAPI.initEx()` resolves the App ID for the
  process in exactly two ways: (a) reading a `steam_appid.txt` file from the
  process's **current working directory** at the moment `initEx()` runs, or
  (b) auto-detecting it when the process was launched directly by the Steam
  client (`steam://run/<appid>` or Steam's own "Play" button). Neither path
  reads any JVM system property or environment variable Valve documents as
  supported.
- `steam_appid.txt` is currently produced **only** by each platform module's
  Gradle `generateSteamAppId` task — `platform/fabric-26.2/build.gradle:82-96`
  (identically duplicated in `platform/fabric-26.1/build.gradle` and
  `platform/fabric-1.21.11/build.gradle`) — writing
  `<platform-module>/run/steam_appid.txt`, wired as a `dependsOn` of
  `runClient` (and, per the following comment block, also hooked into VS
  Code's `configureClientLaunch` preLaunchTask). This only ever executes
  during a Gradle-driven dev launch; it has no equivalent at runtime and is
  never bundled into or executed by the shipped release jar.
- `services/src/main/java/de/lazuli/services/steamworks/SteamAppIdResolver.java:18-30`
  explicitly documents that its `lazuli.steamAppId` JVM system property
  resolution is "entirely this project's own convenience layer, not a
  Steamworks-native mechanism" — it is consumed only for this project's own
  diagnostics/logging (`SteamworksService.steamAppId()`,
  `SteamworksClientInitializer.java:88-89`'s log line) and is never written to
  `steam_appid.txt` or otherwise passed to Valve's `SteamAPI`.
- `SteamworksService.create(long appId, Path nativeLibraryDirectory, Consumer<String> warningLogger)`
  (`services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java:87-115`)
  accepts `appId` purely as a value to echo back via `steamAppId()`/log
  messages (`SteamworksService.java:71-78`'s own Javadoc says as much) — it is
  never written anywhere, and `SteamAPI.initEx()` is called with no App-ID
  argument of its own, relying entirely on `steam_appid.txt`/Steam-launch
  auto-detection.
- Net effect: a normal end-user launch (double-click a launcher, run
  `java -jar`, or use any 3rd-party launcher) has a working directory that
  never contains `steam_appid.txt`, so `SteamAPI.initEx()` always returns a
  failure result and every downstream Steam feature silently/gracefully
  degrades to unavailable — exactly the reported symptom.

This spec covers making Steam features work for that normal end-user launch
path, without requiring the player to hand-create `steam_appid.txt`, while
remaining correct in Steam-launched contexts and forward-compatible with a
future real (Valve-issued) Steamworks App ID.

## Goals
- A player who launches the shipped release jar directly (any launcher,
  working directory unknown/uncontrolled by this project, Steam not
  necessarily running as the parent process) gets the same
  `isSteamAvailable()` outcome they would get if `steam_appid.txt` had been
  present and correct — i.e. Steam initialization succeeds whenever a real
  local Steam client is running and logged in, matching Valve's own
  documented behavior for a process that has a valid `steam_appid.txt`.
- The fix works identically across all three platform modules
  (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`) without re-duplicating
  platform-specific logic three times if avoidable — prefer a single
  `services`-layer mechanism the existing `SteamworksClientInitializer` per
  module already calls into, mirroring how `SteamworksService`/
  `SteamAppIdResolver` are already shared.
- The existing dev-only Gradle `generateSteamAppId` tasks continue to work
  unchanged for `runClient`/IDE dev launches (no regression to the dev
  workflow), and continue to take priority in dev (Gradle already stages the
  file into the Loom `run/` working directory before the JVM starts).
- The App ID value used remains easy to swap from one App ID to another
  (e.g. the placeholder `480`, Valve's public "Spacewar" test app, used
  before a real Steamworks App ID was issued — since resolved, this project
  now uses its real App ID, `1751626`) via the existing `lazuli.steamAppId` /
  `-PsteamAppId` mechanisms already established — this fix must not hardcode
  an App ID in a way that requires touching multiple files to update later.
- Every failure mode of the new write-`steam_appid.txt`-at-runtime step
  (working directory not writable, file already exists with different/same
  content, I/O error) is caught and degrades to the existing "Steam
  unavailable" behavior — never throws, never blocks/crashes client startup,
  consistent with `SteamworksService.create`'s existing "never throw" 
  discipline (`SteamworksService.java:64-70`).

## Non-goals
- Obtaining or provisioning a real Steamworks App ID — out of scope, and
  explicitly deferred in `services/specification.md:106` and
  `SteamAppIdResolver.java:13-15` at the time this spec was written. This
  spec's fix works correctly regardless of the App ID value in effect
  (originally the placeholder `480`, now the real, Valve-issued `1751626`)
  and required no code changes to this fix's own logic (only the
  config-value change already anticipated, per Goals) once the real App ID
  was issued.
- Deciding whether/how this mod is ever distributed *as* a Steam-registered
  product itself (e.g. via Steam Workshop distribution or the game being a
  Steam app in its own right) — flagged as an unresolved product question in
  `services/specification.md:21,139`, unaffected by this fix.
- Detecting or handling the case where Steam is not installed/running at all
  — that is already correctly handled by the existing
  `SteamAPI.InitResult != OK` branch in `SteamworksService.create`
  (`SteamworksService.java:96-102`); this spec only removes the
  `steam_appid.txt`-missing failure mode, not the "Steam isn't running"
  failure mode, which remains a legitimate "Steam unavailable" outcome.
- Changing the warning/log message wording beyond what's needed to reflect
  the new behavior (e.g. no longer suggesting the player manually create
  `steam_appid.txt`) — cosmetic follow-up, not required for the core fix.
- Any change to the JNI/native-library loading path
  (`ClasspathSteamLibraryLoader`) — confirmed unrelated to this bug; native
  libraries already load correctly and Jar-in-Jar bundling of
  `steamworks4j` is already fixed (per recent commit history). Left
  untouched.
- Removing or altering the three platform modules' `generateSteamAppId`
  Gradle tasks — they remain the dev-launch mechanism; this spec adds a
  runtime-level mechanism alongside them, not a replacement.
- Supporting the case where the working directory legitimately cannot be
  written to at all (e.g. process running from a read-only/protected
  location with no fallback writable directory) beyond degrading gracefully
  to "Steam unavailable" with a clear log message — no UI-facing error
  dialog or in-game notification is introduced for this edge case (see Edge
  Cases below for what "gracefully" means here).

## Requirements

- **FR1.** Before `SteamAPI.loadLibraries(...)`/`SteamAPI.initEx()` is
  invoked in `SteamworksService.create(...)`, this project must ensure a
  `steam_appid.txt` file containing the resolved App ID exists in the
  process's current working directory (`System.getProperty("user.dir")`, the
  directory `SteamAPI_Init` actually reads from — confirmed this is what
  Valve's native layer consults, not any project-controlled path such as
  Fabric Loader's config/game directory, which may differ from the JVM's CWD
  depending on launcher).
- **FR2.** If `steam_appid.txt` does not already exist in the working
  directory, this project creates it, writing the resolved App ID (see FR5)
  as its entire contents (a single line, the numeric App ID, matching the
  existing Gradle task's `"${appId}\n"` format at
  `platform/fabric-26.2/build.gradle:90`).
- **FR3.** If `steam_appid.txt` already exists in the working directory
  (e.g. the dev `generateSteamAppId` task already wrote it, or a player
  manually placed one), this project does not overwrite it — an existing
  file's contents are treated as authoritative and left untouched (see Edge
  Cases: differing content).
- **FR4.** This ensure-step runs once, synchronously, before the first (and
  only) `SteamworksService.create(...)` call each client process makes — no
  retry loop, no re-check on later ticks, consistent with
  `SteamworksService`'s existing "resolved once for the process lifetime"
  design (`services/specification.md:131-132`).
- **FR5.** The App ID value written when creating a missing
  `steam_appid.txt` is the same value already resolved by
  `SteamAppIdResolver.resolve(System::getProperty)` — i.e. the
  `lazuli.steamAppId` JVM system property if present/parseable, otherwise
  `SteamAppIdResolver.DEFAULT_APP_ID` (currently `1751626`, this project's
  real Steamworks App ID). This reuses the
  existing resolver rather than introducing a second, parallel App-ID
  resolution mechanism — when a real App ID is issued, updating
  `SteamAppIdResolver.DEFAULT_APP_ID` (or supplying `-Dlazuli.steamAppId=...`
  /an equivalent packaged default) is sufficient for both the diagnostic
  value and the newly-written file to pick it up.
- **FR6.** Any failure to create/write `steam_appid.txt` at runtime
  (`IOException`, `SecurityException`, read-only working directory, etc.) is
  caught, logged as a warning via the same warning-logger channel
  `SteamworksService.create` already uses, and does not prevent the rest of
  `SteamworksService.create` from proceeding — it simply proceeds to attempt
  `SteamAPI.initEx()` as it does today (which will then fail with the
  existing, already-correct `FailedGeneric`-style message if the file
  genuinely could not be created).
- **FR7.** The mechanism is implemented once in a shared location consumed
  by all three platform modules' `SteamworksClientInitializer`s (or inside
  `SteamworksService.create` itself), not copy-pasted per platform module —
  mirroring how `SteamworksService`/`SteamAppIdResolver` are already shared
  `services`-layer code, avoiding the same 3x duplication this bug's root
  cause already exhibits in the Gradle tasks.
- **FR8.** No behavior change for dev (`runClient`)/IDE launches: when
  `generateSteamAppId` has already written `run/steam_appid.txt` before the
  JVM starts, FR3's "do not overwrite an existing file" means this runtime
  step is a harmless no-op in that context.
- **FR9.** No behavior change for a genuine Steam-client-launched process
  (e.g. a future state where this mod's game is itself Steam-distributed and
  launched via Steam, which auto-detects the App ID without needing
  `steam_appid.txt` at all) — writing `steam_appid.txt` in that case is
  harmless (Valve's `SteamAPI_Init` prefers/tolerates a present, correct
  file) and must not be skipped conditionally in a way that adds complexity;
  simplicity (always ensure-if-missing) is preferred over trying to detect
  "was I launched by Steam."

## Public API
No new public-facing player/config-facing API. Internal `services`-layer
surface only:

- New method, added to `services/src/main/java/de/lazuli/services/steamworks/`
  (exact class TBD in planning — either a new small class,
  e.g. `SteamAppIdFileWriter`/`SteamAppIdFileEnsurer`, or a private static
  helper inside `SteamworksService`):

  ```java
  /**
   * Ensures a steam_appid.txt file containing {@code appId} exists in
   * {@code workingDirectory}, creating it if absent and leaving any
   * existing file's contents untouched. Never throws; any I/O failure is
   * reported via {@code warningLogger} and treated as non-fatal.
   *
   * @return true if the file exists (pre-existing or newly written) after
   *         this call returns; false only if it could not be created
   */
  static boolean ensureSteamAppIdFile(long appId, Path workingDirectory, Consumer<String> warningLogger);
  ```

  Signature/placement/naming is illustrative for planning; the essential
  public contract is: pure ensure-if-missing semantics, never throws, takes
  the already-resolved App ID and a working-directory `Path` as explicit
  inputs (not looked up internally) so it stays unit-testable the same way
  `SteamAppIdResolver`/`ClasspathSteamLibraryLoader` already are (injected
  dependencies rather than direct `System`/`Files` static calls sprinkled
  through business logic).
- `SteamworksService.create(...)`'s existing public signature is unchanged.
  This ensure-step is called from inside `create(...)` (preferred, per FR7 —
  keeps all three platform modules' call sites untouched) immediately before
  `SteamAPI.loadLibraries(...)`/`SteamAPI.initEx()`, using
  `Path.of(System.getProperty("user.dir"))` as the working directory and the
  same `appId`/`warningLogger` parameters `create(...)` already receives.

## Architecture
- Add the ensure-step as the first action inside
  `SteamworksService.create(long appId, Path nativeLibraryDirectory, Consumer<String> warningLogger)`
  (`services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java:87`),
  before the existing `ClasspathSteamLibraryLoader`/`SteamAPI.loadLibraries`
  call. This keeps the fix in the one shared chokepoint every platform
  module's `SteamworksClientInitializer` already funnels through
  (`platform/fabric-26.2/.../SteamworksClientInitializer.java:65-67`, and the
  identical call sites in the other two platform modules), satisfying FR7
  without touching any platform module's source.
- The working directory is read via `System.getProperty("user.dir")` inside
  `services` (a plain JVM API, not a Fabric Loader API), so this does not
  introduce a Fabric Loader dependency into `services` and stays consistent
  with `services`' existing "buildable/testable with no Fabric Loader
  dependency" constraint (`ClasspathSteamLibraryLoader.java:30-34`'s stated
  rationale for a similar design choice).
- No new class is strictly required if implemented as a small private
  static helper method inside `SteamworksService`; planning should decide
  between that and a small dedicated class purely based on unit-testability
  needs (a dedicated class makes it trivial to unit-test the ensure/no-op/
  failure branches in isolation using a temp directory, following the
  existing `SteamworksServiceTest`/`ClasspathSteamLibraryLoader` testing
  patterns in this package).
- `SteamAppIdResolver` itself is unchanged — it continues to be the single
  source of truth for "which App ID does this process think it should use,"
  consumed both by the existing diagnostic/log-message use (unchanged) and
  now additionally by this new ensure-step (via the `appId` parameter
  `SteamworksService.create` already receives, itself produced by
  `SteamAppIdResolver.resolve(...)` at each platform module's composition
  root, e.g. `SteamworksClientInitializer.java:59`).

## UI
No UI changes. This is a startup-bootstrap fix; existing UI (Steam Cloud
sync toggle icon, etc.) already correctly reflects `isSteamAvailable()` —
fixing the underlying availability computation is sufficient for that UI to
start rendering correctly with no UI-layer code changes.

## Configuration
- Reuses the existing `lazuli.steamAppId` JVM system property
  (`SteamAppIdResolver.SYSTEM_PROPERTY`) as the sole override mechanism for
  the App ID value written into the runtime-created `steam_appid.txt` — no
  new config file, system property, or CLI flag is introduced.
- Default value: `SteamAppIdResolver.DEFAULT_APP_ID` — originally `480`
  (Valve's public "Spacewar" test app), used before a real Steamworks App ID
  was issued; now `1751626`, this project's real (Lazulite) App ID, updated
  once the real ID was issued. Both the diagnostic log line and the
  newly-written `steam_appid.txt` picked it up automatically with no other
  code change, as this section anticipated, satisfying the "easy to swap
  later" goal.
- The three platform modules' `generateSteamAppId` Gradle tasks and their
  `-PsteamAppId=<id>` override remain a separate, dev-time-only
  configuration axis (unchanged by this spec) — they influence the
  Loom-managed `run/` working directory's pre-staged file, which (per FR3)
  takes precedence over the new runtime ensure-step simply because it
  already exists by the time the JVM starts.

## Events
None. This is a synchronous, one-time startup step within
`SteamworksService.create(...)`; no new event bus/callback surface.

## Networking
None. `SteamAPI.initEx()`'s own behavior is unchanged by this fix; this
spec only ensures the file it reads is present.

## Persistence
- New runtime-written artifact: `steam_appid.txt`, a single line containing
  the resolved numeric App ID (e.g. `480\n`), written into the process's
  current working directory (`user.dir`) the first time
  `SteamworksService.create(...)` runs in a process where that file does not
  already exist.
- This file is intentionally left in place after the process exits (not
  cleaned up on shutdown) — matching Valve's own expected
  `steam_appid.txt` lifecycle (a small marker file conventionally left
  sitting next to the game's working directory across launches), and
  avoiding any risk of deleting a file that may have already existed before
  this mod ran (see Edge Cases: pre-existing file).
- No project-owned config schema, versioning, or migration concerns — this
  file's format is entirely dictated by Valve's own `steam_appid.txt`
  convention (one line, the numeric App ID), not something this project
  designs or evolves independently.

## Compatibility
- All three platform modules (`fabric-1.21.11`, `fabric-26.1`,
  `fabric-26.2`) get the fix identically and simultaneously, since it lives
  in the shared `services` module they all depend on and funnels through
  the one `SteamworksService.create(...)` chokepoint each already calls.
- Dev (`runClient`)/IDE launches are unaffected (FR8) — the Gradle-staged
  file already exists by the time this runtime step runs, so it is a no-op
  there.
- Players who already have Steam running with this game's real App ID
  registered (future state) are unaffected/still correctly handled — this
  fix does not change or interfere with Steam-launch auto-detection, it only
  adds a fallback for the case where auto-detection doesn't apply.
- Backward compatible with any player who has already manually created a
  `steam_appid.txt` themselves as a workaround for this bug prior to the fix
  shipping — FR3 ensures their existing file (whatever its contents) is left
  untouched, so no regression/surprise content change for early adopters of
  the manual workaround.

## Edge Cases
- **Working directory not writable** (e.g. installed under
  `Program Files` without write permission, or launched from a read-only
  mount): the ensure-step's `Files.write`/equivalent throws `IOException` (or
  the JVM raises `SecurityException` under a restrictive
  `SecurityManager`/module boundary) — caught, logged via
  `warningLogger`, and `create(...)` proceeds to attempt
  `SteamAPI.initEx()` regardless, which will then fail exactly as it does
  today (unchanged existing failure message), resulting in
  `isSteamAvailable() == false`. No crash, no blocked startup (FR6).
- **`steam_appid.txt` already present with different content** (e.g. a
  previous run for a different Steam app, or a player's own manual
  workaround with a stale/wrong App ID): left untouched per FR3 — this
  project does not attempt to detect or reconcile a mismatch. If the
  existing file's App ID is wrong, `SteamAPI.initEx()` will fail or
  initialize for the wrong app; this is treated as an acceptable, rare edge
  case outside this fix's scope (a player who has already hand-edited this
  file is assumed to know what they're doing), consistent with Valve's own
  file-based convention offering no built-in "is this the right app"
  validation either.
- **`steam_appid.txt` already present with identical/expected content**:
  common case when `runClient`'s `generateSteamAppId` task already ran — the
  ensure-step observes the file exists and does nothing further; no
  redundant write, no diffing of contents.
- **Multiple client processes launched concurrently from the same working
  directory** (unusual, but possible if a player runs two instances):
  potential benign race between two processes both attempting to create the
  same missing file at roughly the same time. Using an atomic-if-absent
  file creation primitive (e.g. `Files.newOutputStream` with
  `StandardOpenOption.CREATE_NEW`, mirroring FR3's non-clobber intent more
  strictly than the existing Gradle task's plain
  `outputFile.asFile.text = ...` overwrite-style write) avoids one process's
  write clobbering the other's — planning should choose the specific
  file-creation API accordingly. A losing race (the other process created it
  first) is treated identically to "already present," not a failure.
- **Working directory differs from what a player expects** (e.g. some
  third-party launchers `cd` into an unexpected directory before invoking
  `java -jar`, or a portable/relocated install): this fix targets whatever
  `System.getProperty("user.dir")` reports at the moment `create(...)` runs,
  which is by definition the same directory Valve's own `SteamAPI_Init` will
  independently consult — there is no discrepancy possible here, since both
  this project's write and Valve's read use the same OS-level "current
  working directory" concept for the same process.
- **Multi-platform module duplication (the original bug's own shape)**:
  explicitly avoided per FR7/Architecture by placing the fix once inside
  shared `services` code rather than duplicating it three times the way the
  existing dev-only Gradle tasks are today. Planning should treat any
  temptation to instead patch each platform module's
  `SteamworksClientInitializer` individually as the wrong shape, reproducing
  this bug's own root cause.
- **App ID was originally a placeholder (480, not a real Valve-issued ID),
  now the real, Valve-issued `1751626`**: this fix does not change that a
  player without Steam running, or without Steam recognizing the App ID as
  installed/ownable in some edge cases, may still see Steam-side failures
  unrelated to `steam_appid.txt` presence (e.g. Valve's own "app not owned"
  checks). Those are pre-existing, out-of-scope Steam-side behaviors, not
  introduced or fixed by this spec.

## Performance
- One additional file-existence check plus, at most once per process
  lifetime, one small file write (a few bytes) — negligible, happens once
  during client startup before any tick loop begins, no measurable
  overhead.

## Future Extensions
- Done: the real Steamworks App ID (`1751626`, Lazulite) has since been
  issued and `SteamAppIdResolver.DEFAULT_APP_ID` updated accordingly — no
  other code from this fix needed to change, confirming the Configuration
  section's design intent.
- If this mod's game ever becomes Steam-distributed in its own right
  (unresolved product question, `services/specification.md:21,139`), the
  Steam client will auto-detect the App ID at launch without needing
  `steam_appid.txt` at all — this fix remains harmless/no-op-compatible with
  that future state (Edge Cases/FR9), requiring no rework.
- Consider surfacing a clearer end-user-facing message (e.g. in a toast or
  the pause-menu Steam status area, if one exists) explaining that Steam
  features are unavailable and why, when `isSteamAvailable() == false` after
  this fix — currently only a log-level warning. Explicitly deferred (see
  Non-goals); no such UI currently exists to extend.

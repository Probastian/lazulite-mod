# "Keep Cloud" Button Fix — Implementation Plan

Spec: `.claude/specs/keep-cloud-button-fix.md` (approved). This plan
implements FR1–FR4 exactly as scoped there. No re-derivation of root
cause; see spec for line-level citations.

## Scope reminder (non-goals — do not touch)

- Bug #3 ("Game mode: Unknown"): do not touch `readLevelDatBatch`,
  `LevelDatBatch`, or `unpairedRows()`'s game-mode row in any of the 3
  `WorldsPanel.java` / `WorldConflictScreen.java` files. Zero diff lines
  related to this.
- `onKeepLocal()`, Cancel path, `WorldRestoreHook`/`WorldRestoreService`,
  `WorldRestoreScreen`/`downloadAndPlay()` bodies, and any `api/` module
  type: unchanged.
- No Cancel button added to `WorldConflictScreen` (noted as future
  extension only).
- **Do not launch Minecraft / run the game at any point in this task**
  (implementation or verification). Verification is compile + test +
  build/repackage + static decompile inspection only.

## Work items

### 1. `platform/fabric-1.21.11/.../cloudsync/WorldConflictScreen.java`

- Add new constructor parameter `Runnable onKeepCloudCompleted`,
  placed immediately before the existing `Runnable onReturn` param
  (after `levelDatBatch`). Add field
  `private final Runnable onKeepCloudCompleted;` next to the existing
  `onReturn` field (line ~75) and assign it in the constructor body.
- In `render()` (lines 137–141), change the `keepCloudCompleted` branch
  from:
  ```java
  if (keepCloudCompleted) {
      resolutionHook.recordKeepCloudResolution(...);
      onReturn.run();
      return;
  }
  ```
  to call `onKeepCloudCompleted.run()` in place of `onReturn.run()`,
  keeping the `recordKeepCloudResolution` call immediately before it,
  unchanged.
- Update the constructor's Javadoc to document the new parameter
  (mirror the existing `onReturn` doc style at ~118–121), clarifying it
  fires only on successful download, never on failure.

### 2. `platform/fabric-1.21.11/.../mainmenu/WorldsPanel.java`

- In `openConflictScreen(LevelSummary summary)` (line 1279), add the
  new constructor argument before the existing `onReturn` lambda:
  `() -> launchWorld(worldSlug)` — reusing the existing private
  `launchWorld(String)` method (lines 1258–1271) verbatim, no changes
  to `launchWorld`.
- This is the only call site (confirmed by grep: exactly one
  `new WorldConflictScreen(` per platform).

### 3. `platform/fabric-26.1/.../cloudsync/WorldConflictScreen.java`

- Same shape as item 1, but the render-loop branch to change is
  `extractRenderState()` (lines 175–179), and Component/Font APIs are
  Minecraft's 26.x variants — no other differences. Constructor is at
  lines 123–147; insert new param before `onReturn` (line 134),
  matching field declared alongside `onReturn`.

### 4. `platform/fabric-26.1/.../mainmenu/WorldsPanel.java`

- In `openConflictScreen(LevelSummary summary)` (line 1202), add
  `() -> launchWorld(worldSlug)` before the existing `onReturn` lambda
  at the `new WorldConflictScreen(...)` call (lines 1216–1221), reusing
  the existing private one-liner `launchWorld(String)` (lines
  1192–1194) verbatim.

### 5. `platform/fabric-26.2/.../cloudsync/WorldConflictScreen.java`

- Identical to item 3 (26.2's `WorldConflictScreen.java` is a
  line-for-line port of 26.1's per the spec — constructor at same
  line range 123–147, `extractRenderState()` branch at 175–179).

### 6. `platform/fabric-26.2/.../mainmenu/WorldsPanel.java`

- Identical to item 4: `openConflictScreen` at line 1185, `new
  WorldConflictScreen(...)` call ~1199–1204, reuse existing
  `launchWorld(String)` one-liner (lines 1175–1177).

## Ordering / dependency

Each platform's screen + panel file must be changed together (the
constructor signature change and its one call site are coupled). The
three platforms are independent of each other and can be done in any
order, but all three must land before verification since the spec
requires identical behavior across all three.

## Out of scope — explicit non-touch list

- `RestoreProgressListener`, `RestoreHandle`, `WorldConflictResolutionHook`,
  `WorldSyncStatusHook`, `WorldRestoreHook` interfaces/impls (`api/`,
  `features/steam-cloud-sync/`, `services/`) — no changes needed or
  permitted; FR2/FR3 confirm the download half is untouched.
- `WorldRestoreScreen.java` in all 3 platforms.
- Any test file, unless item below in Verification requires a new one.

## Verification plan

### A. Compile touched modules

Only the 3 platform modules are touched (no `api`, `services`,
`features/*`, or `common` changes). Run per platform:

```
./gradlew :platform:fabric-1.21.11:compileJava
./gradlew :platform:fabric-26.1:compileJava
./gradlew :platform:fabric-26.2:compileJava
```

### B. Run full test suite for touched modules

```
./gradlew :platform:fabric-1.21.11:test
./gradlew :platform:fabric-26.1:test
./gradlew :platform:fabric-26.2:test
```

Note: `services` module is not touched by this fix (SteamworksService
changes visible in `git status` are pre-existing/unrelated to this
task — do not re-run or modify). Existing relevant tests to watch:
`platform/fabric-26.2/src/test/java/de/lazuli/cloudsync/WorldConflictScreenValuesMatchTest.java`
and `platform/fabric-26.2/src/test/java/de/lazuli/mainmenu/WorldsPanelStatusTest.java`
(fabric-1.21.11 and fabric-26.1 have no equivalent `WorldConflictScreen`-
specific test today per current glob results — do not add new tests
unless a full suite run reveals a gap; this is a callback-wiring fix,
not new logic, so no new test is required by the spec, but if the
`WorldConflictScreenValuesMatchTest` pattern is easily extensible to
assert the new constructor wiring compiles/behaves, note it as
optional/low-priority, not required for acceptance).

### C. Rebuild / repackage jars (not just compileJava)

Per `gradle.properties` comments and prior-plan precedent,
`processIncludeJars` is this project's confirmed real Loom task name
for jar-in-jar bundling. Run per platform:

```
./gradlew :platform:fabric-1.21.11:processIncludeJars
./gradlew :platform:fabric-26.1:processIncludeJars
./gradlew :platform:fabric-26.2:processIncludeJars
```

Also run each platform's full `:build` task to produce the final
distributable jar:

```
./gradlew :platform:fabric-1.21.11:build
./gradlew :platform:fabric-26.1:build
./gradlew :platform:fabric-26.2:build
```

If `:build` fails or task names differ once run (e.g. Loom renames),
confirm actual task names via `./gradlew :platform:fabric-1.21.11:tasks --all`
(and per-platform equivalents) before falling back to any alternative.

### D. Decompile bundled jar to confirm fix is present at runtime

For each platform, locate the built jar (`platform/fabric-*/build/libs/*.jar`,
excluding `-sources`/`-dev` variants — use the remapped/final jar), extract
`de/lazuli/cloudsync/WorldConflictScreen.class` and
`de/lazuli/mainmenu/WorldsPanel.class`, decompile (e.g. via `javap -c` or
CFR/procyon if available in the environment), and confirm:

- `WorldConflictScreen`'s constructor bytecode shows the additional
  `Runnable` parameter.
- The completion branch invokes the new field's `run()`, not the old
  `onReturn` field, at the point corresponding to the
  `keepCloudCompleted` check.
- `WorldsPanel.openConflictScreen`'s compiled call site passes two
  distinct `Runnable` lambda targets (verify via `javap -p -c` showing
  two separate `invokedynamic`/lambda method references) rather than
  the same one twice.

This step exists specifically to rule out a stale/cached jar — do not
skip it even if compile + test pass.

### E. Explicit non-regression checks (static, no game launch)

- Diff review: confirm zero changed lines in `readLevelDatBatch`,
  `LevelDatBatch`, `unpairedRows()` across all 3 platforms (Bug #3
  stays untouched).
- Diff review: confirm `onKeepLocal()` body is unchanged in all 3
  `WorldConflictScreen.java` files.
- Diff review: confirm `statusHook.markDownloadPending`/
  `markDownloadFinished` call sites are unchanged (same guard, same
  bracketing) in all 3 files.
- Confirm the new constructor parameter is passed as a real lambda
  (never `null`, never omitted) at all 3 `openConflictScreen` call
  sites — grep for `new WorldConflictScreen(` and manually inspect
  each of the 3 matches.

### Do NOT

- Do not launch Minecraft or run the game client at any point during
  implementation or verification (per user's standing instruction).
  Verification is limited to `compileJava`/`test`/`processIncludeJars`/
  `build` Gradle tasks and static decompilation — no manual play-test.

## Risks / open questions

- Exact `:build` / `processIncludeJars` task availability should be
  confirmed by running `./gradlew :platform:fabric-1.21.11:tasks --all`
  once at verification time, since these were inferred from
  `gradle.properties` comments and a prior plan, not direct inspection
  of Loom's generated task graph.
- Decompilation tooling (CFR/procyon/javap) available in this
  environment is unconfirmed — verifier should check what's on PATH
  before relying on a specific tool name.
- Pre-existing uncommitted changes in `git status` (SteamworksService,
  cloud-sync test file, RestoreFailureMessages.java, etc.) are unrelated
  to this fix and must not be touched or folded in silently.

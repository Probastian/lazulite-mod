# Implementation Plan — Closing Sync Conflict Coverage Gaps

Specification: `docs/specs/sync-conflict-coverage-gaps.md`

## Summary of decisions carried in from the spec

- Gap 1: `UNKNOWN`-blocked tooltip reuses the existing `UNKNOWN` copy verbatim ("Sync status unknown -- this world has not been synced yet, or sync status has not loaded.") — no new string.
- Gap 2: `checkConflictFor` on toggle-on runs async via `worker.submitBackgroundWork`; a new transient "checking" per-world state blocks Play/Edit exactly like Syncing until the result lands.
- Gap 3: `blocked` gains a `STALE` arm (sync-enabled only). The existing "Resolve Cloud Conflict" pill/`openConflictScreen` wiring is reused for STALE rows via a new `showResolveButton` boolean kept independent of `ConsolidatedStatus` (which is left unchanged — `STALE` still maps to `UNSYNCED` there). Whether `WorldConflictScreen`/`detailFor` can render meaningfully for a STALE row is an **open question, deferred to Task 6 below** — not resolved by this plan either; Task 6 is scoped as an investigation spike with a decision checkpoint before any screen-rendering code is written.
- Gap 4: confirmed no code change; carried as a non-goal only.

## Task list

### Task 1 — `WorldSyncStatusTracker` / status-tracking: transient "checking" state (Gap 2)
File: `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSyncStatusTracker.java`
- Add per-world-slug state for "conflict check pending" (e.g. `markConflictCheckPending(String worldSlug)` / `clearConflictCheckPending(String worldSlug)` / `isConflictCheckPending(String worldSlug)`), mirroring the existing `isUploadInProgress`/`markDownloadPending`/`markDownloadFinished` shape (RAM-only, no new on-disk cache — cross-cutting constraint 1).
- Ensure the pending flag is cleared on both outcomes: `ConflictStatus.CONFLICT` (transitions into `markConflictPending`) and non-conflict (falls through to normal sync).
- Test file: `WorldSyncStatusTrackerTest.java` — new cases for set/query/clear of the pending-check state, including "clears exactly once, no leak if cleared twice."

### Task 2 — `WorldSaveSyncService` / `WorldSyncPreferenceService.toggleSync` wiring (Gap 2)
Files: `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java` (and wherever `WorldSyncPreferenceService.toggleSync` lives / is called from — locate at implementation time; spec cites `WorldSyncPreferenceService.java:86-91`).
- On the disabled -> enabled transition only: after flipping the preference, call `statusTracker.markConflictCheckPending(worldSlug)` synchronously, then submit `checkConflictFor(worldSlug, worldFolder)` via `worker.submitBackgroundWork` (pattern at `WorldSaveSyncService.java:170,196,221`).
- On async completion: clear the pending-check flag; on `CONFLICT`, call the existing `markConflictPending` path (no change to `checkConflictFor` itself) and do **not** invoke `syncWorldNow`; on non-conflict, proceed with the existing background sync flow unchanged.
- Disable transition unaffected.
- Depends on: Task 1 (tracker API must exist first).
- Test files: `WorldSaveSyncServiceTest.java` — toggle-on with divergent ancestor/fingerprint -> `checkConflictFor` invoked, `markConflictPending` fires, no `syncWorldNow` call. Toggle-on with non-divergent state -> normal upload flow. Toggle off/on twice with no external cloud change -> no spurious conflict. `CloudSyncCoordinatorTest.java` — end-to-end toggle-on -> pending-check set -> async result -> either conflict-routed (no upload) or normal-upload, asserting the pending-check flag is set during the gap and cleared after. `CloudSyncableReconcilerTest.java` — only if implementation finds reconciliation logic actually interacts with the toggle-on path; otherwise no change.

### Task 3 — `WorldsPanel.java` blocking logic, all three platforms (Gaps 1, 2, 3)
Files (edit consistently, one javap-verified pass per module):
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
- `platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
- `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`

Changes (identical logic across all three; only Minecraft-API-surface calls differ per `.claude/context/minecraft.md`'s recorded divergences, e.g. `DrawContext`/`TextRenderer`/`Text` on 1.21.11 vs. `GuiGraphicsExtractor`/`Font`/`Component` on 26.x):
- Extend `blocked` at both the render site and the hit-test site to: `isRowSyncing || isRowConflicted || isRowCheckingConflict || (syncEnabled && freshness == UpToDateStatus.UNKNOWN) || (syncEnabled && freshness == UpToDateStatus.STALE)`, where `isRowCheckingConflict` reads Task 1's new tracker state (Gap 2's transient state) and is treated visually identical to `isRowSyncing`.
- Add `showResolveButton = isConflicted || (syncEnabled && freshness == UpToDateStatus.STALE)`, replacing the current bare `isConflicted` gate on the "Resolve Cloud Conflict" pill (render block ~`:348-386` and hit-test block ~`:677-702` in fabric-26.2; equivalent line ranges in the other two modules) at both `pillBounds(...)` call sites and the click handler.
- Leave `computeConsolidatedStatus`/`ConsolidatedStatus` untouched (Gap 3's precedence decision) — no change to the enum or its precedence function on any platform.
- Add a distinct tooltip for the transient checking-blocked state (e.g. "Checking Steam Cloud sync status..."); exact copy is not fixed by the spec, pick one consistent string reused across all three platforms.
- No new Minecraft-client API is anticipated for this task (pure boolean-logic/tooltip changes to existing render/hit-test blocks) — confirm this assumption per-module before editing by re-reading each module's current `blocked`/`pillBounds`/tooltip call sites; if any new API call is introduced, verify via `javap -p` against that module's resolved `minecraft-client.jar` per `.claude/context/minecraft.md`'s cross-version discipline before relying on it.
- Depends on: Task 1 (for `isRowCheckingConflict`).
- Test file: `WorldsPanelStatusTest.java` — add cases (see spec Test scenarios): sync-enabled+STALE -> blocked; sync-disabled+STALE -> not blocked; STALE+CONFLICT together -> Conflict status precedence unchanged; `showResolveButton` true for CONFLICT rows (regression) and for sync-enabled STALE rows, false for sync-disabled STALE and for plain UNSYNCED/SYNCED rows; blocked-while-checking cases mirroring existing `isRowSyncing` cases. Since `computeConsolidatedStatus` is a shared package-private static function copied per-platform, extend the existing `fabric-26.2` test file (the sole automated test per the file's own doc comment) and add an equivalent new `showResolveButton`-focused test function/class alongside it (or in the same file) — do not duplicate the precedence matrix in the other two modules, per the established "one shared automated test + end-of-implementation three-way diff" convention already documented in `cloud-sync-conflict-ux`'s plan.

### Task 4 — Three-way diff verification (Gaps 1, 2, 3)
- After Task 3 lands on all three platforms, run a full three-way diff of the touched `WorldsPanel.java` blocks (as the existing convention requires per `WorldsPanelStatusTest.java`'s doc comment) to catch drift beyond the already-known Yarn/Mojang naming differences.
- Depends on: Task 3.

### Task 5 — `javap -p` verification pass
- For each of the three platform modules, run `javap -p` against that module's resolved `minecraft-client.jar` to confirm any Minecraft-client API touched by Task 3 (expected: none new, per Task 3's note) truly matches the assumed shape, per `.claude/context/minecraft.md`'s mandatory cross-version discipline. If Task 3's assumption of "no new API" holds, this is a quick confirmation pass, not new research.
- Depends on: Task 3.

### Task 6 — STALE-rendering investigation spike for `WorldConflictScreen` (Gap 3, open question)
Files to read/investigate (no code change unless the spike concludes one is needed):
- `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/cloudsync/WorldConflictScreen.java`
- `WorldConflictResolutionHook.java` (`api/src/main/java/de/lazuli/api/cloudsync/`) and its `ConflictDetail`/`detailFor` implementation in `WorldSaveSyncService.java`

Spike goal: answer the spec's deferred open question — can the existing `detailFor`/`ConflictDetail`/screen rendering produce a sensible view for a STALE-but-not-diverged row?
- If yes (reusable as-is or with a small conditional branch inside the existing screen): implement the minimal conditional (e.g. suppress "Keep Local" or reframe copy when the row's underlying state is STALE rather than a true ancestor conflict), still calling the same `WorldConflictScreen` constructor / `openConflictScreen` entry point — no new screen/class.
- If no (reuse is non-trivial): do not force it. Leave `WorldConflictScreen` unchanged for this pass; the resolve pill still opens the same screen for STALE rows (per the spec's decided direction) but the screen may show placeholder/"Unknown" values for fields that don't have a STALE-meaningful source, consistent with the screen's existing null-safe rendering (F8's already-handled "Conflict detail is no longer available" pattern) — document this as a known limitation, not a blocker.
- This task's outcome must be recorded (in a follow-up spec/plan note or PR description) before Task 7's screen-touching tests are written, since the test scope depends on which branch is taken.
- Depends on: none (can run in parallel with Tasks 1-3), but Task 7's `WorldConflictScreenValuesMatchTest.java` scope depends on this task's outcome.

### Task 7 — Test-only follow-up for `WorldConflictScreenValuesMatchTest.java` (Gap 3, conditional on Task 6)
- If Task 6 concludes a rendering change is needed: add new cases exercising a STALE-sourced `ConflictDetail`/screen render, alongside the existing true-conflict cases (regression, unchanged).
- If Task 6 concludes no change is needed: no new cases required beyond confirming existing cases still pass unchanged.
- Depends on: Task 6.

## Sequencing

1. Task 1 (tracker state) — foundation for Gap 2's UI gating.
2. Task 2 (toggle-on wiring) — depends on Task 1.
3. Task 3 (WorldsPanel blocking/resolve-pill logic, all 3 platforms) — depends on Task 1; independent of Task 2 (panel reads tracker state directly, doesn't need Task 2's toggle wiring to compile/test against, though full manual verification benefits from both being done).
4. Task 6 (STALE-rendering spike) — can start immediately, in parallel with 1-3; must complete before Task 7.
5. Task 4 (three-way diff) — after Task 3.
6. Task 5 (`javap -p` pass) — after Task 3, can run alongside Task 4.
7. Task 7 (conditional screen test follow-up) — after Task 6.
8. Full test suite run (`WorldSaveSyncServiceTest`, `WorldSyncStatusTrackerTest`, `WorldsPanelStatusTest`, `WorldConflictScreenValuesMatchTest`, `CloudSyncCoordinatorTest`, `CloudSyncableReconcilerTest`) as a final gate once all above land.

## Non-goals (unchanged from spec)

- Gap 4 (two OS users sharing one Steam login) requires **no code change** — it is a confirmation only, already documented in the spec, and must not be re-litigated or "fixed" during this implementation pass.
- No changes to `checkFingerprintForConflict` or its existing call site in `syncWorldNow` — Gap 2's new check is additive and earlier in sequence, not a replacement.
- No new on-disk/file-level cache — all new state (Task 1's transient "checking" flag) is RAM-only, reusing the tracker's existing in-memory pattern.
- No auto-download-before-play behavior (Gap 3 Option (b)) — explicitly out of scope.
- No new UI surface for conflict resolution — the existing "Resolve Cloud Conflict" pill and `WorldConflictScreen` are reused as-is (modulo Task 6's possible minimal conditional), never replaced or duplicated.

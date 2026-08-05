# Spec: Closing Sync Conflict Coverage Gaps

## Overview

Three coverage gaps exist between the strict conflict-detection logic already implemented in `WorldSaveSyncService` and what the UI (`WorldsPanel`) and the toggle-sync flow (`WorldSyncPreferenceService`) actually surface/enforce. This is a bug-fix spec: the detection primitives (`checkConflictFor`, `UpToDateStatus`, `WorldFingerprintCache`) already exist and are correct; the gaps are in *when they're called* and *what the UI gates on*. A fourth suspected gap (two OS users sharing one Steam login) is confirmed out of scope.

## Goals

- G1: `WorldsPanel` blocks Play/Edit when freshness is `UNKNOWN`, not just when Syncing/Conflicted.
- G2: Re-enabling sync via `WorldSyncPreferenceService.toggleSync` runs the strict `checkConflictFor` gate before any upload, and a detected conflict routes to `WorldConflictScreen` instead of silently overwriting cloud.
- G3: `WorldsPanel` blocks Play/Edit when freshness is `STALE` (recommended), consistent with existing Syncing/Conflict UX.
- G4 (non-goal, documented): confirm no code change needed for the two-OS-users-one-Steam-login scenario.

## Non-goals

- No changes to `checkFingerprintForConflict` or its existing call sites in the real upload path (`syncWorldNow`). It stays as the informational, non-blocking pre-upload notice; the new toggle-on check is additive and runs *before* it, not instead of it.
- No new on-disk/file-level cache. Any new state introduced by these fixes reuses `WorldFingerprintCache` (RAM-only) or existing `WorldSyncAncestor`/`WorldSyncStatusTracker` state. This is a standing project directive, not a per-feature choice.
- No change to how ancestor/fingerprint cache paths are derived (`FabricLoader.getInstance().getConfigDir()`); see Gap 4 below.
- No implementation code or task breakdown in this document — specification only.

---

## Gap 1: UNKNOWN freshness does not gate Play

### Problem statement

`WorldsPanel` computes `boolean blocked = isRowSyncing(...) || isRowConflicted(...)` (fabric-1.21.11 `WorldsPanel.java:365`, hit-test copy at `:691`; same pattern in fabric-26.1 and fabric-26.2). Freshness (`UpToDateStatus`) is tracked separately (`freshnessCache`, default `UNKNOWN` at `:219`) and only affects tooltip/icon text (`freshnessTooltipFor`, `:548-621`), never the `blocked` gate. When a fingerprint fetch fails (e.g. offline, Steam Cloud unreachable), freshness correctly resolves to `UNKNOWN` rather than a false `UP_TO_DATE` — but the player can still click Play with zero cloud comparison having occurred for that session.

### Desired behavior / acceptance criteria

- `blocked` becomes `isRowSyncing || isRowConflicted || freshness == UpToDateStatus.UNKNOWN`, evaluated identically at the render site (`:365`) and the hit-test site (`:691`) in all three platform copies.
- When blocked solely due to `UNKNOWN`, Play/Edit are greyed out using the existing disabled-button treatment (same visual pattern as Syncing), with a tooltip explaining the reason. **Decided tooltip copy**: reuse `freshnessTooltipFor`'s existing `UNKNOWN` copy verbatim — "Sync status unknown -- this world has not been synced yet, or sync status has not loaded." (`:615`/`:610`) — for the blocked case, with no separate "blocked" variant. Rationale: the existing copy already reads as an explanation of *why* the world can't be trusted yet, which doubles naturally as the reason Play/Edit are disabled; inventing a second string for the same underlying condition (session hasn't confirmed freshness) would fragment tooltip copy without adding clarity. This resolves the Gap 1 open question below in favor of reuse, not differentiation.
- A world with sync disabled entirely must not be blocked by this rule — `UNKNOWN` gating only applies when sync is enabled for that world (mirror the existing `syncEnabled &&` guard pattern seen at `:445`). Confirm this against how `freshnessCache` is populated/defaulted for sync-disabled worlds so disabled worlds aren't spuriously blocked.
- No change to how `UNKNOWN` is computed (`WorldSaveSyncService`/`WorldFingerprintCache` fetch-failure paths) — this gap is UI-gating only.

### Test scenarios

- Extend `WorldsPanelStatusTest.java`'s `ConsolidatedStatus` precedence matrix (currently 9 cases) with cases covering: sync-enabled + `UNKNOWN` -> blocked; sync-disabled + `UNKNOWN` -> not blocked; `UNKNOWN` + simultaneously `isRowSyncing` or `isRowConflicted` -> still blocked (no regression to existing precedence).
- Verify tooltip text/icon selection for the new blocked-by-`UNKNOWN` case matches the existing `UNKNOWN` tooltip copy exactly ("Sync status unknown -- this world has not been synced yet, or sync status has not loaded.") — decided above, no longer an open question.

---

## Gap 2: Toggling sync back on bypasses the strict conflict check

### Problem statement

`WorldSyncPreferenceService.toggleSync` (`WorldSyncPreferenceService.java:86-91`) only flips the boolean preference and persists it; it never touches `WorldSyncAncestor`/`WorldFingerprintCache` state or invokes any conflict check. When a world's sync is later driven through `syncWorldNow` (`WorldSaveSyncService.java:622`), the only conflict-adjacent check on that path is `checkFingerprintForConflict` (`:645`, `:720`) — which posts an informational "this will overwrite cloud" message but always proceeds to upload. The strict, ancestor-aware `checkConflictFor` (`:392-429`) is never invoked as part of re-enabling sync. Net effect: if cloud state diverged while sync was off (e.g. played on another device), re-enabling sync on this device silently overwrites the cloud's diverged state instead of surfacing a real conflict.

### Desired behavior / acceptance criteria

- When `toggleSync` transitions a world from disabled -> enabled, before the world's next upload is allowed to proceed (i.e. before any `syncWorldNow` invocation triggered by re-enabling), the strict `checkConflictFor(worldSlug, worldFolder)` must run.
- Insertion point: this check must happen synchronously as part of, or immediately triggered by, the enable-transition in `toggleSync` — not deferred to whatever incidentally calls `syncWorldNow` next. Concretely: `toggleSync` (or a caller wrapping it, e.g. wherever the UI invokes toggle) must call `checkConflictFor` for that world right after flipping the preference to enabled, before any upload path is scheduled/submitted via `worker.submitBackgroundWork`.
- On `ConflictStatus.CONFLICT`: `statusTracker.markConflictPending(worldSlug)` fires (already `checkConflictFor`'s behavior at `:392-429` — no change needed there), and the toggle-on flow must **not** proceed to call `syncWorldNow`/upload for that world. The existing Conflict UX (row shows Conflict state, "Resolve Cloud Conflict" pill, `WorldConflictScreen` reachable) takes over from there — no new UI surface needed, only the wiring that gets it there.
- On `ConflictStatus.NO_CONFLICT` (or equivalent non-conflict result): proceed with the existing behavior — sync stays enabled, background sync flow (`syncWorldNow` -> `checkFingerprintForConflict` -> upload) runs as today. No change to that downstream path.
- `toggleSync`'s disable-transition (enabled -> disabled) is unaffected; this gate only applies to the enable transition.
- **Confirmed: `checkConflictFor` runs asynchronously on the background worker** (`worker.submitBackgroundWork`, matching the existing pattern at `WorldSaveSyncService.java:170,196,221`) rather than synchronously inline in `toggleSync`. `checkConflictFor` does I/O (fingerprint fetch) and must not block the UI/main-menu thread that calls `toggleSync`.
- **New required transient state: "checking".** While the async `checkConflictFor` triggered by an enable-transition is in flight for a world, that world's Play/Edit must be blocked exactly as if it were Syncing — this is not an optional UX nicety, it is a hard acceptance criterion. The UI must not leave Play/Edit clickable during the gap between "sync toggled on" and "conflict check result received." Concretely: `WorldSyncStatusTracker` (or equivalent) needs a way to mark/query "conflict check pending" per world slug, and `WorldsPanel`'s `blocked` computation (Gap 1) must additionally OR in this transient state — treated the same as `isRowSyncing` for blocking purposes (same visual muted treatment, same disabled-button pattern), not as a new distinct visual state, though its own tooltip copy may differ (e.g. "Checking Steam Cloud sync status…" — exact copy decided at implementation/planning time, not fixed by this spec since it doesn't affect blocking behavior).
- On completion of the async check: on `ConflictStatus.CONFLICT`, the transient "checking" state clears and the row transitions to the existing Conflict UX (see below). On non-conflict, the transient state clears and the normal background sync flow proceeds.

### Test scenarios

- Extend `CloudSyncCoordinatorTest.java` and/or `WorldSaveSyncServiceTest.java` with: toggle-on with a divergent ancestor/fingerprint pair -> asserts `checkConflictFor` is invoked, `markConflictPending` fires, and `syncWorldNow`/upload is never called.
- toggle-on with non-divergent state -> asserts normal upload flow still runs (regression guard that this change is additive, not a replacement).
- toggle-off then toggle-on twice in a row (no external cloud change in between) -> should not spuriously flag conflict.
- Extend `WorldSyncStatusTrackerTest.java` if `markConflictPending` gains a new call site's worth of state transitions to verify, and to cover the new transient "checking" state's set/clear transitions (entered on enable-transition, cleared on either `CONFLICT` or non-conflict result).
- Extend `WorldsPanelStatusTest.java` and/or a hit-test-level test to assert Play/Edit are blocked while the transient "checking" state is set for a world, mirroring the existing `isRowSyncing`-blocks-Play/Edit cases.

---

## Gap 3: STALE freshness does not gate Play

### Problem statement

A `STALE` world (cloud has moved forward — e.g. played on another device — but this device hasn't downloaded that newer state yet) is not blocked by `blocked` (`WorldsPanel.java:365/691`) today; `STALE` only affects tooltip copy (`:564`, `:607`, `:620`). A player can Play/Edit a stale local copy, then save/exit, triggering an upload that either creates a real conflict (missed sooner than necessary) or — in the worst case — races with a delayed fingerprint refresh and gets misclassified.

### Design decision: (a) STALE blocks Play, vs (b) auto-download-before-play

**Option (a): STALE blocks Play/Edit, greyed out with tooltip.**
- Pro: Reuses the exact same mechanism as Gap 1 and the existing Syncing block — one code path, one visual language, no new async orchestration in the panel.
- Pro: Never touches the world folder without explicit user action; no silent overwrite of the local save directory.
- Con: User must take a manual action to resolve staleness before playing; slightly more friction.

**Option (b): auto-download the newer cloud state before allowing Play.**
- Pro: Removes user friction — Play "just works" once download completes.
- Con: Requires new download-and-replace-local-world-folder orchestration, including handling failures mid-download, partial writes, and confirming this doesn't collide with the user's local unsynced changes — meaningfully larger surface than option (a) for what is scoped as a bug fix.

**Recommendation: Option (a)** for the Play/Edit-blocking mechanism itself — this part is unchanged from the original recommendation. **Superseding update to the "manual action" story**: the manual action a STALE row's user takes is no longer left unspecified — it reuses the existing "Resolve Cloud Conflict" pill/`WorldConflictScreen` wiring already built in the `cloud-sync-conflict-ux` round (see below), rather than "sync now" or "re-toggle." Option (b) (auto-download) remains out of scope for this bug-fix pass and may be revisited later as a UX enhancement.

### Desired behavior / acceptance criteria (Option (a), updated)

- `blocked` becomes `isRowSyncing || isRowConflicted || (syncEnabled && freshness == UpToDateStatus.UNKNOWN) || (syncEnabled && freshness == UpToDateStatus.STALE)` — combined with Gap 1's change, both `UNKNOWN` and `STALE` gate identically when sync is enabled.
- Tooltip for STALE-blocked state reuses the existing STALE copy (`:607`/`:620`: "This world has changed since it was last synced to Steam Cloud.") — no new copy invented.
- No auto-download behavior is introduced by this gap's fix.
- **New (supersedes the prior "manual action left unspecified" note): the existing "Resolve Cloud Conflict" pill becomes visible/enabled for STALE rows too**, not only rows with `ConflictStatus.CONFLICT`. Clicking it wires to the same `openConflictScreen(summary)` call already used for true conflicts (`WorldsPanel.java` fabric-26.2 `:727-742` and equivalents) — no new UI surface, no new screen, no new button. This is the decided direction, not an open question.
- **Bucket/precedence design decision.** `WorldsPanel`'s existing `ConsolidatedStatus` enum (`UNSYNCED, SYNCING, SYNCED, CONFLICT`, precedence `computeConsolidatedStatus`: Conflict > Syncing > Synced > Unsynced, covered by `WorldsPanelStatusTest`'s existing 9-case matrix) is **left unchanged** — `STALE` continues to map to `UNSYNCED` for icon/consolidated-status purposes exactly as it does today (`syncEnabledStaleIsUnsynced` stays green with no expected-value change). Resolve-pill visibility is **not** driven by `ConsolidatedStatus` today (it is driven by a separate `isRowConflicted`/`conflictCache == CONFLICT` check, orthogonal to the status enum) and this spec keeps it that way: a new boolean, e.g. `showResolveButton = isConflicted || (syncEnabled && freshness == UpToDateStatus.STALE)`, gates the pill at both the render and hit-test call sites, alongside (not folded into) the existing `isConflicted` check. Rationale: this is the option that "keeps precedence logic cleanest" per the reconciliation requirement — it changes zero existing `computeConsolidatedStatus` behavior or existing `WorldsPanelStatusTest` expected values, avoids overloading a 4-value enum with a fifth "STALE-conflict-like" value that would need its own precedence slot relative to Syncing/Synced, and mirrors the codebase's existing pattern of keeping the resolve-pill's visibility condition independent of the status icon's condition (they already answer different questions: "what icon/color" vs. "is there cloud-resolution action available").
- **Open question deferred to planning (not resolved by this spec): can `WorldConflictScreen`/`WorldConflictResolutionHook.detailFor` render meaningfully for a STALE-but-not-ancestor-diverged row?** The existing conflict-screen data plumbing (`ConflictDetail`'s `LocalDetail`/`CloudDetail` split, per `cloud-sync-conflict-ux`'s plan) was built for a true ancestor-divergence conflict, where both a local and a cloud state genuinely diverged from a common ancestor and need side-by-side comparison. A STALE row has no local divergence — the local copy is simply behind the cloud's newer state, so several `ConflictDetail` fields (e.g. local vs. cloud "which one wins" framing, ancestor-based fields) may not have a meaningful STALE-specific interpretation. Planning must determine whether: (i) `detailFor`/`WorldConflictScreen` can be reused as-is with STALE rows simply presenting as "cloud is newer, keep cloud is the only sensible action" (e.g. suppressing or greying the "Keep Local" option), (ii) a small conditional rendering branch is needed inside the existing screen (still no new screen/class), or (iii) STALE rows should route through the same screen but be constrained to a strict subset of its actions. This spec deliberately does not resolve (i)/(ii)/(iii) — only that the same button/screen is reused, not a new surface.

### Test scenarios

- Extend `WorldsPanelStatusTest.java`'s precedence matrix with: sync-enabled + `STALE` -> blocked (Play/Edit); sync-disabled + `STALE` -> not blocked; `STALE` + `isRowConflicted` simultaneously -> Conflict precedence wins for the status icon (verify existing `computeConsolidatedStatus` precedence ordering/expected values are unchanged by this gap, since STALE continues mapping to `UNSYNCED` there).
- New `WorldsPanelStatusTest.java` (or adjacent) cases asserting the "Resolve Cloud Conflict" pill's visibility condition (`showResolveButton` or equivalent) is `true` for: true `CONFLICT` rows (existing behavior, regression guard), and STALE rows with sync enabled (new). Asserts `false` for STALE rows with sync disabled, and for non-STALE/non-CONFLICT rows.
- Confirm `WorldConflictScreenValuesMatchTest.java` scenarios still pass unchanged for true-conflict rows; if planning resolves the STALE-rendering open question with a code change, add corresponding new cases there for STALE-sourced `ConflictDetail` values — otherwise leave as a planning-phase follow-up.

---

## Gap 4: Two OS users sharing one Steam login (confirmed out of scope, no code change)

Ancestor cache (`WorldSyncAncestor`) and fingerprint cache (`WorldFingerprintCache`) paths are derived from `FabricLoader.getInstance().getConfigDir()`, which is per-instance (per OS user profile / per game install), not a machine-wide or Steam-account-wide path. Two OS users on the same machine sharing one Steam login therefore naturally get independent local caches even though they share the same cloud identity — there is no cross-user cache collision to fix. No code change is required; this is a confirmation, not a fix, and should not be re-litigated in planning.

---

## Cross-cutting constraints (apply to all three gaps)

1. **RAM-only cache directive.** No new on-disk/file-level cache may be introduced by any of these fixes. Reuse `WorldFingerprintCache` (already RAM-only) and existing `WorldSyncAncestor`/tracker state for any new state needed (e.g. tracking "conflict check in flight" during toggle-on).
2. **`checkFingerprintForConflict` is untouched.** Its existing call site inside `syncWorldNow` (`WorldSaveSyncService.java:645`) stays exactly as is. Gap 2's fix adds a new, earlier `checkConflictFor` call in the toggle-on path; it does not replace, wrap, or alter `checkFingerprintForConflict`.
3. **Three-platform consistency for `WorldsPanel.java`.** `WorldsPanel.java` exists independently in `platform/fabric-1.21.11`, `platform/fabric-26.1`, and `platform/fabric-26.2`, with genuine Minecraft API differences between them (see `.claude/context/minecraft.md`, "Known Cross-Version API Differences"). Gaps 1 and 3's `blocked`-gate changes must be applied to all three copies. The planning phase must verify any new/changed API usage per module (e.g. via `javap -p` against each module's resolved `minecraft-client.jar`) rather than assuming identical behavior across copies.

## Test files to extend (do not duplicate)

- `WorldsPanelStatusTest.java` — extend the `ConsolidatedStatus` 9-case precedence matrix with UNKNOWN- and STALE-blocking cases (Gaps 1 and 3), plus new cases for the "Resolve Cloud Conflict" pill's visibility condition covering STALE rows (Gap 3) and the transient "checking" blocked state (Gap 2).
- `WorldSaveSyncServiceTest.java` — conflict-check-on-toggle-on scenarios (Gap 2).
- `WorldSyncStatusTrackerTest.java` — new `markConflictPending` call-site transitions if applicable, plus the new transient "checking" state's set/clear transitions (Gap 2).
- `CloudSyncCoordinatorTest.java` — end-to-end toggle-on -> conflict-check -> no-upload wiring (Gap 2).
- `CloudSyncableReconcilerTest.java` — only if reconciliation logic is found during planning to interact with the toggle-on path; otherwise no change expected.
- `WorldConflictScreenValuesMatchTest.java` — regression check only; no new cases expected unless planning finds STALE/UNKNOWN state bleeds into conflict-screen values.

## Open questions for the planning phase

- Gap 1: resolved — tooltip copy reuses the existing `UNKNOWN` string; see Gap 1's acceptance criteria.
- Gap 2: resolved — confirmed async on the background worker with a required transient "checking" blocked state; see Gap 2's acceptance criteria.
- Gap 3: should a manual "sync now" call-to-action be added to the STALE tooltip, or is greying out with explanatory text sufficient for this pass?
- Gap 3 (new): can `WorldConflictScreen`/`resolutionHook.detailFor` render meaningfully for a STALE-but-not-ancestor-diverged row, given the full local-vs-cloud metadata diff was designed for a true ancestor conflict? **Left open, deferred to planning** — see Gap 3's section below for the exact question.

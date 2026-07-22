# Implementation Plan — Own-Profile "In Game" Status (bug fix) + Context-Menu Regression Test

## Implements
- `features/friends-sidebar/specification-own-profile-ingame-status.md` — v1.7
  amendment, requirements FR-OP1/FR-OP2/FR-OP3 (own-row rendering fix, Piece
  1) and FR-CM1/FR-CM2 (context-menu `isOwnProfile` gate regression test,
  Piece 2 — no code change, test-coverage only).

This plan touches only the three platform `FriendSidebarWidget.java` copies
(`drawRow` and its two call sites) plus one new/updated unit test file for
`FriendContextMenuWidget`. It does not touch `FriendSummary`, `FriendsService`,
`FriendSidebarStateMachine`, `SteamFriendsGateway`, or
`FriendContextMenuWidget`'s production code at all.

## Existing Implementation (research, this plan — re-confirmed against the
current working tree immediately before writing this plan, per the spec's
own Compatibility-section warning about concurrent dropdown-polish drift)

**Re-confirmed line numbers (all match the spec's citations, no drift found):**
- `platform/fabric-26.2/.../FriendSidebarWidget.java`:
  - `own.ifPresent(profile -> drawRow(guiGraphics, profile, getX(), getY(), width, showText));` — line 416.
  - Friend-loop call: `drawRow(guiGraphics, friends.get(i), getX(), rowY, width, showText);` — line 460.
  - `private void drawRow(GuiGraphicsExtractor guiGraphics, FriendSummary friend, int x, int y, int width, boolean showText)` — line 556.
    - `int statusColor = facade.stateMachine().statusColorArgb(friend.personaState(), friend.inGame());` — line 557.
    - Avatar-placeholder-box fallback: `... facade.stateMachine().statusColorArgb(friend.personaState(), friend.inGame()));` — line 572.
    - Status-label: `.orElseGet(() -> facade.stateMachine().statusLabel(friend.personaState(), friend.inGame()));` — line 579.
  - `Optional<FriendSummary> own = steamAvailable ? facade.localProfile() : Optional.empty();` — line 320 — own row is populated (and thus rendered) whenever Steam is available, **including while at the main menu/`TitleScreen`** (confirmed: `FabricFriendsSidebarInjector.isAllowListed` includes `TitleScreen`, `SelectWorldScreen`, `JoinMultiplayerScreen`, `OptionsScreen`, `PauseScreen`, `RealmsMainScreen` — the sidebar, and therefore the own row, renders outside of gameplay too). This is why FR-OP1's session predicate must be evaluated independently of `own`'s own presence — the own row exists before/after/without a session, and must only render the in-game tier while a session is actually active.
- `platform/fabric-26.1/.../FriendSidebarWidget.java`: identical line numbers to fabric-26.2 (416/460/556/557/572/579) — confirmed via direct read, structural twin as the spec states.
- `platform/fabric-1.21.11/.../FriendSidebarWidget.java`: same shape, offset by the pre-existing ~14-line Yarn/Mojang idiom difference — confirmed via direct read: own-row call at line 412, friend-loop call at line 456, `drawRow(DrawContext context, ...)` at line 552, `statusColor` at 553, avatar-placeholder fallback at 563, status-label at 575.
- `FriendContextMenuWidget.java` — re-confirmed identical across all three modules (read fabric-26.2's copy in full, 181 lines): constructor at lines 48-85, `isEnabled(int index)` at lines 87-108 (`isOwnProfile` short-circuit at lines 88-91, `return index == 1;`), `mouseClicked` at lines 141-175 (the `isEnabled(index)` guard at line 146). Matches the spec's citations exactly (spec cites `:87-91` and `:146` on the fabric-26.2 copy — confirmed verbatim, no drift).

**Session-active predicate — no existing helper in this codebase; this plan
introduces one inline, per platform module (mapping-set difference already
established as a norm elsewhere in these three files):**
- No existing per-frame "is a world/session currently active" check exists
  anywhere in `FriendSidebarWidget`/`FriendsSidebarFacade`
  (`FriendsSidebarFacade.isSteamAvailable()` is unrelated — it reflects
  Steamworks initialization, not world/session state, confirmed by reading
  `FriendsSidebarFacade.java:106-157`).
- The repo's only existing "are we in a world" checks live in
  `SteamCloudSyncClientInitializer.java` (all three platform modules), but
  those are **event-driven** (`ClientPlayConnectionEvents.JOIN`/`DISCONNECT`,
  checking `client.isIntegratedServerRunning()` /
  `client.hasSingleplayerServer()` / `client.getCurrentServerEntry()` /
  `client.getCurrentServer()`), not a per-frame poll suitable for a `drawRow`
  call site — not directly reusable here, but confirm the mapping-specific
  client field name they use for the current world is *not* needed: this
  plan uses the simpler `level`/`world`-null check below instead, which is
  cheaper and does not require distinguishing singleplayer vs. remote server
  (FR-OP1 explicitly requires "true for either").
- Recommended predicate, evaluated directly at the `drawRow`/`renderNow`
  call site each frame (negligible cost, matches this method's existing
  per-frame `personaState`/`inGame` reads):
  - **Mojang mappings** (`fabric-26.1`, `fabric-26.2`): `Minecraft.getInstance().level != null` — `Minecraft.level` is the current `ClientLevel` (non-null exactly while a world is loaded, singleplayer-integrated or remote, and remains non-null while `PauseScreen` is open, satisfying FR-OP1's explicit "true... including while paused at the in-game pause menu" clause).
  - **Yarn mappings** (`fabric-1.21.11`): `MinecraftClient.getInstance().world != null` — Yarn's client-side world field is conventionally named `world` (the Yarn analogue of Mojang's `level`); this plan flags verifying the exact field name via a quick compile/`javap` check at implementation time as Risk 3 below, since this codebase has no prior usage of this specific field to point to directly (only server-side `ServerWorld`/event-driven `isIntegratedServerRunning()` usages exist today).
  - Both are `null` at every allow-listed non-gameplay screen (`TitleScreen`, `SelectWorldScreen`, `JoinMultiplayerScreen`, `OptionsScreen`, `RealmsMainScreen`) and non-null at `PauseScreen` and during ordinary gameplay — matching FR-OP1's exact truth table.

## Files to Create
None.

## Files to Modify

1. **`platform/fabric-26.2/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`**
   - `drawRow(...)` (line 556): add a new trailing `boolean inGame` parameter,
     replacing the three inline `friend.inGame()` reads at lines 557, 572,
     579 with the parameter `inGame` (FR-OP3 — the flag is now supplied by
     the caller, independent of `FriendSummary.inGame()`, rather than
     re-derived from `friend` inside the method). New signature:
     `private void drawRow(GuiGraphicsExtractor guiGraphics, FriendSummary friend, int x, int y, int width, boolean showText, boolean inGame)`.
   - Own-row call site (line 416): change to
     `own.ifPresent(profile -> drawRow(guiGraphics, profile, getX(), getY(), width, showText, Minecraft.getInstance().level != null));`
     (FR-OP1) — the own row is always told "in game" exactly when a session
     is active, regardless of `profile.inGame()` (which `FriendsService.localProfile()` always sets `false`, per spec's Piece 1 "Current behavior" citation — unchanged, FR-OP3).
   - Friend-loop call site (line 460): change to
     `drawRow(guiGraphics, friends.get(i), getX(), rowY, width, showText, friends.get(i).inGame());`
     — behaviorally identical to today (each friend's own `inGame()` flag),
     preserving `specification-status-recolor-ingame.md`'s existing FR-P1/
     FR-P2/FR-P3 behavior for every non-own row untouched.
   - `Minecraft` is already imported in this file (used elsewhere, e.g. line
     386/499's `Minecraft.getInstance().font`) — no new import needed.

2. **`platform/fabric-26.1/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`**
   - Identical change to item 1, at the same line numbers (416/460/556/557/572/579), confirmed structural twin.

3. **`platform/fabric-1.21.11/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`**
   - Same shape, Yarn idiom: `drawRow(DrawContext context, ...)` (line 552)
     gains trailing `boolean inGame` parameter, replacing `friend.inGame()`
     at lines 553/563/575. Own-row call site (line 412) becomes
     `own.ifPresent(profile -> drawRow(context, profile, getX(), getY(), width, showText, MinecraftClient.getInstance().world != null));`
     (FR-OP1, Yarn field name — verify exact field at implementation time,
     Risk 3). Friend-loop call site (line 456) becomes
     `drawRow(context, friends.get(i), getX(), rowY, width, showText, friends.get(i).inGame());`.
     `MinecraftClient` is already imported/used in this file (e.g. line 253's
     `MinecraftClient.getInstance().getWindow()`) — no new import needed.

4. **New/updated test file for `FriendContextMenuWidget`** (Piece 2, FR-CM1/
   FR-CM2) — exact test-source location is an implementation-phase decision
   depending on whether a plain-JVM test target already exists for
   platform-module classes with a `net.minecraft.*` import
   (`FriendContextMenuWidget` imports `Minecraft`/`AbstractWidget`/
   `MouseButtonEvent`, unlike the feature-module's own pure-Java
   `FriendSidebarStateMachineTest`). Confirmed at investigation time: no
   existing test file targets `FriendContextMenuWidget` in any of the three
   platform modules (spec's own finding, re-confirmed by this plan's glob
   search turning up zero non-worktree matches for a test file of that
   name). Recommend:
   - Add `platform/fabric-26.2/src/test/java/de/lazuli/friends/FriendContextMenuWidgetTest.java`
     (and, if the repo's existing convention is to run identical unit tests
     per platform module rather than share one, mirrored copies under
     `fabric-26.1`/`fabric-1.21.11` — confirm at implementation time whether
     any sibling widget test in this package is single-copy or triplicated,
     e.g. by checking for an existing `platform/*/src/test/java/de/lazuli/friends/*Test.java`
     precedent; if none exists yet, a single copy under `fabric-26.2` plus a
     short note in the plan/PR is acceptable, since `FriendContextMenuWidget`
     is a structural triplet with no platform-specific branching in
     `isEnabled`/`mouseClicked`).
   - Test content (FR-CM1/FR-CM2, mapped to the spec's Acceptance Criteria
     1-2 verbatim):
     - Construct `new FriendContextMenuWidget(0, 0, friend, facade, onClosed, true, worldJoinRequester, hostingStatusReader, worldInviteSender, toastService)` with `isOwnProfile = true` and **maximally-permissive** stub collaborators: `worldInviteSender.isHosting()` stubbed `true`, `hostingStatusReader.isFriendHosting(anyLong())` stubbed `true`.
     - Reflectively or via a package-private test seam invoke `isEnabled(index)` for `index` in `{0, 1, 2, 3}` (since `isEnabled` is `private` — the test must live in the same package, `de.lazuli.friends`, to call it directly without reflection, consistent with this being a same-package test class) and assert `false, true, false, false` respectively (FR-CM1, Acceptance Criteria item 1).
     - Simulate `mouseClicked` at each of the four option rows' Y coordinates (`OPTION_HEIGHT * index`, offset from the constructed `y`) and assert: clicking row 1 invokes `facade.actions().onShowProfile(friend.steamId64())` exactly once; clicking rows 0/2/3 invoke none of `onOpenChat`, `worldInviteSender.inviteFriend`, `worldJoinRequester.joinHostedWorld` (FR-CM1, Acceptance Criteria item 2) — using a mocking library already in use elsewhere in the test suite (confirm which, e.g. Mockito, by checking an existing test's imports at implementation time) or hand-written stub/fake collaborators if no mocking library is currently a test dependency for platform modules.
   - No production code change — this file is additive only (Files to Create
     would be the more literal category, but it is listed here since it
     directly operationalizes FR-CM1/FR-CM2 as line items).

## Order / Dependencies of Changes
1. The three `FriendSidebarWidget.java` edits (Piece 1) are independent of
   the new test file (Piece 2) — no compile dependency either direction. Any
   order is fine; recommend doing Piece 1 first since it is the actual bug
   fix, then Piece 2 as a pure test-coverage addition.
2. Among the three `FriendSidebarWidget.java` copies, no ordering dependency
   — each module compiles independently (separate Gradle subprojects).

## Risks
1. **`FriendSidebarWidget.java` is an actively-evolving, shared file with
   in-flight, unrelated changes** (dropdown-polish work,
   `features/friends-sidebar/implementation-plan-dropdown-polish.md` —
   confirmed via `git status`: all three platform copies show as modified in
   the working tree at plan-writing time). This plan's own re-read (above)
   found the cited `drawRow`/call-site line numbers to still match the
   spec's citations exactly — but re-run `git status`/`git diff` again
   immediately before editing each of the three files at implementation
   time, since the dropdown-polish branch may continue to evolve
   concurrently, and confirm no further drift before applying the FR-OP1/
   FR-OP3 edits. The dropdown-polish work's own touched region
   (`joinPolicyDropdown`/`renderNow`/`mouseClicked`/dropdown fields) does not
   overlap `drawRow`'s body or either of its two call sites, so no merge
   conflict is expected, but this must be re-verified, not assumed, per this
   repo's established discipline for this specific file.
2. **`drawRow`'s new trailing `boolean inGame` parameter changes a private
   method's signature with exactly two call sites per file (six total across
   three modules)** — low risk (both call sites are updated in the same
   change, in the same file, and the method is `private` so no external
   caller exists), but a partial edit (updating the signature/body without
   updating both call sites) would fail to compile immediately, giving fast
   feedback.
3. **Yarn mapping field name for "current client world" is unconfirmed by
   direct prior usage in this codebase** (`fabric-1.21.11`'s existing code
   only reads world state via `MinecraftClient.isIntegratedServerRunning()`/
   `getCurrentServerEntry()`, event-driven, never a raw `.world`/`.level`
   field read). This plan's recommended `MinecraftClient.getInstance().world != null`
   is the conventional Yarn name, but must be confirmed to actually resolve
   at implementation time (a quick local compile or `javap`/decompiled-jar
   check against the pinned Yarn mappings version for this module,
   consistent with this repo's established "verify against the pinned jar,
   don't guess" discipline for exact API shapes) — if the field is instead
   private/differently named, an equivalent public accessor
   (`MinecraftClient.getInstance().world` vs. some getter) must be
   substituted; this does not change the FR-OP1 predicate's semantics, only
   its exact Java expression.
4. **Own-row "in game" styling must not affect any friend row.** The fix is
   scoped entirely to the own-row call site passing a different boolean than
   `friend.inGame()`; the friend-loop call site is updated only to
   explicitly pass `friends.get(i).inGame()` (previously read directly
   inside `drawRow`) — behaviorally a no-op for every friend row. Mitigated
   by this plan spelling out both call sites' exact before/after text so an
   implementer does not accidentally pass the same session-active boolean to
   both call sites (which would incorrectly force every friend's row green
   too).
5. **No unit test exists or is added for the `drawRow`/rendering fix itself**
   (Piece 1) — consistent with this repo's established convention that
   `FriendSidebarWidget.drawRow` is not unit-tested anywhere today (per
   `implementation-plan-status-recolor-ingame.md`'s own Test Strategy
   section, which relies entirely on manual in-game verification for this
   same method). This plan follows that precedent rather than introducing a
   new rendering-test harness, and instead specifies manual verification
   steps below (Test Strategy) mapped 1:1 to FR-OP1/FR-OP2.
6. **Test file for `FriendContextMenuWidget` may need triplication across
   three platform modules** if this repo's convention (once confirmed at
   implementation time) is one test source set per Gradle subproject rather
   than a shared test module — flagged so the implementer budgets for
   copying the same test file three times (mechanical, no logic differences
   expected since `FriendContextMenuWidget` itself has zero platform-specific
   branching) rather than being surprised partway through.

## Dependencies
No new external (non-Fabric) dependency. If a mocking library (e.g. Mockito)
is needed for the new `FriendContextMenuWidgetTest` and is not already a test
dependency of the relevant platform module's `build.gradle`, this must be
confirmed against that specific module's existing test dependencies at
implementation time before assuming it is available — if genuinely absent,
prefer hand-written stub/fake implementations of `WorldInviteSender`/
`FriendHostingStatusReader`/`WorldJoinRequester`/`ToastService`/
`FriendsSidebarFacade`'s relevant surface over introducing a brand-new test
dependency, since all four are small interfaces easily faked by hand and
this spec's own scope is narrow (test-coverage-only, Piece 2) — avoid
expanding scope into a build-config change unless one is already present
elsewhere in the same module's test source set (check for an existing
Mockito/JUnit import in any sibling test file in the same module first).

## Test Strategy
- **Piece 1 (own-row rendering) — no automated test, per the established
  convention for this method (Risk 5); manual in-game verification across
  all three platform modules:**
  1. At the main menu (`TitleScreen`) and other allow-listed non-gameplay
     screens, the own-profile row still renders (unchanged from today) but
     with the existing plain-persona-state color/label (FR-OP1's `false`
     branch — no session active) — confirms the fix does not force the
     in-game tier outside of gameplay.
  2. Load a singleplayer world: own-profile row immediately switches to the
     green "in game" status bar/avatar-placeholder color and the label
     `"In Game"` (FR-OP1/FR-OP2), regardless of the account's actual Steam
     persona state (test with the Steam client's own status manually set to
     Away/Busy while playing, if feasible, to directly exercise the bug
     being fixed).
  3. Open the in-game pause menu (`PauseScreen`) while the world is loaded:
     own-profile row remains in the green "in game" tier (FR-OP1's explicit
     "true... while paused" clause) — does not revert to the raw persona
     color/label.
  4. Return to the main menu (world unloaded): own-profile row reverts to
     the plain persona-state color/label (FR-OP1's `false` branch resumes).
  5. Connect to a remote server (not singleplayer): own-profile row shows
     the same green "in game" tier as step 2 (FR-OP1's "connected to a
     remote server" clause).
  6. Confirm friend rows are entirely unaffected throughout (still driven by
     each friend's own `personaState()`/`inGame()`/Rich Presence, per
     `specification-status-recolor-ingame.md`'s existing, unchanged
     behavior) — no regression introduced by the call-site signature change.
  7. Repeat steps 1-6 independently on all three platform modules
     (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`).
- **Piece 2 (context-menu regression test) — new automated unit test, the
  actual deliverable of this piece:**
  - `FriendContextMenuWidgetTest` (Files to Modify item 4): run and confirm
    both new test methods pass, exercising `isEnabled` and `mouseClicked`
    exactly per the spec's Acceptance Criteria items 1-2, with the
    maximally-permissive stub collaborators described above (`isHosting()`
    and `isFriendHosting(...)` both `true`) to prove the `isOwnProfile`
    short-circuit truly overrides them.
  - Re-run the module's full existing test suite after adding the new test
    file, as a regression check that no existing test elsewhere in the same
    module's `src/test` broke (low risk — this is a purely additive test
    file with no production-code change).

## Acceptance Criteria
Per `features/friends-sidebar/specification-own-profile-ingame-status.md`:
- FR-OP1: on every platform module, the own-profile row's status color/label
  render via the in-game-aware path (`statusColorArgb(personaState, true)`/
  green, `statusLabel(personaState, true)`/`"In Game"`) whenever
  `Minecraft.getInstance().level != null` (Mojang mappings,
  `fabric-26.1`/`fabric-26.2`) or `MinecraftClient.getInstance().world != null`
  (Yarn mappings, `fabric-1.21.11`) — true during singleplayer, remote-server
  play, and while paused; false at every allow-listed non-gameplay screen.
- FR-OP2: the own row's in-game label is always the exact literal `"In Game"`
  string already returned by `FriendSidebarStateMachine.statusLabel(int, boolean)`
  for `inGame = true` — no new string/constant introduced.
- FR-OP3: `FriendSummary.inGame()`/`gameAppId()` for the own profile remain
  unchanged (`FriendsService.localProfile()` untouched by this plan); the
  own-row's "is in game" signal is supplied entirely via `drawRow`'s new
  trailing `boolean inGame` parameter at its own-row call site, independent
  of `profile.inGame()`.
- FR-CM1: a new unit test on `FriendContextMenuWidget` asserts
  `isEnabled(index)` returns `true` only for `index == 1` when constructed
  with `isOwnProfile = true`, even with maximally-permissive
  `worldInviteSender`/`hostingStatusReader` stubs, and that `mouseClicked`
  only ever invokes `facade.actions().onShowProfile(...)` for such a menu
  (never `onOpenChat`/`inviteFriend`/`joinHostedWorld`).
- FR-CM2: the above test (or its triplicated per-module copies, per Risk 6)
  passes identically for `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`.
- All three platform modules compile after the `drawRow` signature change;
  no other call site of `drawRow` exists per module (confirmed: exactly two
  call sites each, both updated in this plan).
- Manual in-game verification (Test Strategy, Piece 1) passes on all three
  platform modules.

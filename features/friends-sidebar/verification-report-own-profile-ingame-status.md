# Verification Report — Own-Profile "In Game" Status (v1.7 amendment)

## Scope
Verifies `specification-own-profile-ingame-status.md` and
`implementation-plan-own-profile-ingame-status.md` against the actual working-tree
diff across `platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`.

## Implemented Requirements

- **FR-OP1 / FR-OP2 / FR-OP3** — Confirmed implemented identically across all
  three platform modules. `drawRow` gained a trailing `boolean inGame`
  parameter (fabric-26.2/26.1: `FriendSidebarWidget.java:556`; fabric-1.21.11:
  `:552`), replacing all three internal `friend.inGame()` reads
  (statusColor, avatar-placeholder fallback, status-label) with the parameter.
  - Own-row call site now passes a session-active predicate independent of
    `friend.inGame()`: `Minecraft.getInstance().level != null` on
    fabric-26.1/26.2 (`FriendSidebarWidget.java:416`), and
    `MinecraftClient.getInstance().world != null` on fabric-1.21.11
    (`:412`) — matches FR-OP1's Mojang/Yarn mapping split exactly.
  - Friend-loop call site (fabric-26.1/26.2 `:460`, fabric-1.21.11 `:456`)
    unchanged in effect — explicitly passes `friends.get(i).inGame()`, a
    behavioral no-op vs. pre-change code (satisfies the "friend rows
    unaffected" regression guard, plan Risk 4).
  - `FriendsService.localProfile()`
    (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java:137-149`)
    is untouched — still hardcodes `inGame = false, gameAppId = 0L` — confirms
    FR-OP3 (rendering-layer-only fix, no `FriendSummary` semantic change).
  - Label content: since the `inGame` flag now flows through unchanged into
    `facade.stateMachine().statusLabel(personaState, inGame)`, the own row
    reuses the existing generic `"In Game"` fallback with no new string
    introduced (FR-OP2) — confirmed by inspection, no new constant added.

- **FR-CM1 / FR-CM2** — New test file
  `FriendContextMenuWidgetTest.java` added identically to all three modules
  (`platform/fabric-26.2/src/test/java/de/lazuli/friends/`,
  `fabric-26.1/src/test/java/de/lazuli/friends/`,
  `fabric-1.21.11/src/test/java/de/lazuli/friends/` — the 26.1/26.2 copies are
  byte-identical; the 1.21.11 copy differs only in the Yarn-mapped
  `Click`/`MouseInput` classes vs. Mojang's `MouseButtonEvent`/
  `MouseButtonInfo`, as expected). Both tests construct the menu with
  `isOwnProfile = true` and maximally-permissive stubs
  (`worldInviteSender.isHosting()` → `true`,
  `hostingStatusReader.isFriendHosting(anyLong())` → `true`) and assert only
  row 1 ("Show profile") has any effect (`onShowProfile` invoked exactly
  once; `onOpenChat`/`inviteFriend`/`joinHostedWorld` never invoked). Since
  `isEnabled(int)` is `private` (confirmed at
  `FriendContextMenuWidget.java:87`), the tests exercise it indirectly via
  `mouseClicked` at each row's Y coordinate — a reasonable/equivalent
  substitution for the spec's illustrative "construct/exercise isEnabled"
  wording, since `mouseClicked`'s `isEnabled(index)` guard
  (`FriendContextMenuWidget.java:146`) makes the click-level behavior a
  faithful proxy. `OPTION_HEIGHT` confirmed `16` at
  `FriendContextMenuWidget.java:34`, matching the test's row-coordinate math.

## Missing Requirements
None found. All FR-OP and FR-CM items are implemented/tested as specified.

## Test Results
Ran `./gradlew :platform:fabric-26.2:test :platform:fabric-26.1:test
:platform:fabric-1.21.11:test` (normal incremental run, all tasks reported
`UP-TO-DATE`, confirming the already-built/tested state matches current
sources). Test XML reports confirm, for each of the three modules,
`FriendContextMenuWidgetTest`: `tests="2" skipped="0" failures="0"
errors="0"` — both `isEnabled_onlyShowProfile_whenOwnProfile_...` and
`mouseClicked_onOwnProfileMenu_onlyShowProfileRowInvokesAnything()` pass on
all three platforms. No other test in the affected modules' suites failed
(full `:test` task succeeded for each module, not just the new test class).

## Documentation Coverage
- Spec and plan both present and internally consistent; no drift between
  plan's cited line numbers and the actual pre-change code (verified via
  `git diff`, matches plan's "Existing Implementation" citations exactly).
- No README/CHANGELOG update accompanies this change — consistent with this
  directory's existing convention for prior same-directory amendments
  (e.g. `specification-status-recolor-ingame.md`,
  `specification-invite-to-game.md` also have no corresponding README edits).
- Piece 1 (rendering fix) has no automated test, per the plan's explicit,
  spec-consistent Risk 5 rationale (this repo's established precedent of
  manual-only verification for `FriendSidebarWidget.drawRow`). This is a
  known, called-out gap, not an oversight — flagged here for completeness
  but not counted as a missing requirement, since the spec/plan explicitly
  scoped it out and provided a manual Test Strategy checklist instead. No
  evidence in this session that the manual verification steps were executed;
  recommend the user (or a future session) walk through Test Strategy items
  1-7 before considering this fully done end-to-end, since only Piece 2 has
  automated coverage.

## API Compliance
- No public API surface changed: `drawRow` is `private` in all three
  modules, so the new trailing parameter is not a breaking change for any
  external caller (plan's Risk 2 claim confirmed correct).
- `FriendContextMenuWidget`'s public surface unchanged — Piece 2 is
  test-only, as specified.
- `FriendsSidebarFacade`, `FriendsService`, `FriendSummary` unchanged, per
  Non-goals and FR-OP3.

## Architecture Violations
None found. The fix is entirely local to each platform module's
`FriendSidebarWidget.drawRow` and its two call sites, exactly as scoped by
the spec's Architecture section. No new cross-feature bridge, no new class.

## Regression Check — Concurrent Dropdown-Polish Work
- Confirmed via `git diff --stat` that each modified `FriendSidebarWidget.java`
  has exactly 6 changed lines (3 call-site/signature edits × 2 duplicated
  reads each — matches the plan's precise before/after), with no overlap
  with the dropdown-polish work's own touched region
  (`joinPolicyDropdown`/`renderNow`/`mouseClicked`/dropdown fields per the
  plan's Risk 1 note) — both change sets coexist cleanly in the current
  working tree with no half-applied merge or conflicting logic detected.
- `specification-invite-to-game.md`'s "Join game"/"Invite to game" enablement
  wiring is untouched: `FriendContextMenuWidget.java`'s `isEnabled`/
  `mouseClicked` bodies show no diff in git status for that file in this
  change set (only the new test file was added), and the new test's
  maximally-permissive-stub assertions directly re-confirm that wiring is
  still correctly short-circuited by `isOwnProfile` and not otherwise altered.

## Follow-up Recommendations
1. Execute the manual in-game verification checklist (Test Strategy, Piece 1,
   spec/plan Test Strategy items 1-7) across all three platform modules
   before considering this amendment fully validated end-to-end, since no
   automated test covers the rendering fix itself (by established, explicit
   convention/precedent, not an omission of this implementation).
2. No other action needed — implementation matches spec/plan requirements
   and existing tests pass.

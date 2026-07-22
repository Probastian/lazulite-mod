# Implementation Plan — Rich Presence Display & Change Logging

## Implements
- `features/friends-sidebar/specification-richpresence-display-and-logging.md`
  (v1.8 amendment), requirements FR-D1-FR-D5 (Piece 1, own-row display bug fix
  + entrypoint-order landmine fix), FR-D6 (Piece 2, manual smoke test only, no
  code change), FR-D7-FR-D12 (Piece 3, `RichPresencePublisher` change
  logging).
- Closes the exact gap `features/rich-presence/plan.md` "Decision 4" deferred
  (`plan.md:606-608`, Risk 5/`plan.md:643`): sidebar-side consumption of
  `RichPresenceFacade` for the own row.

This plan touches: all three platform modules' `FriendsSidebarClientInitializer`,
`FriendSidebarWidget`, `FabricFriendsSidebarInjector`, and `fabric.mod.json`;
all three platform modules' `RichPresenceClientInitializer`;
`features/rich-presence`'s `RichPresencePublisher` and
`RichPresencePublisherTest`. It does not touch `FriendsService`,
`FriendsSidebarFacade`'s data-source surface, `FriendSummary`,
`FriendContextMenuWidget`, or `RichPresenceFacade`'s existing interface
(unchanged, per spec's Public API item 4).

## Existing Implementation (re-confirmed against the working tree at
plan-writing time)

**Own-row rendering path (all three platform modules, structurally
identical):**
- `FriendSidebarWidget` own-row call site already threads a per-frame
  session-active boolean into `drawRow` (own-profile-ingame-status amendment,
  already landed): `fabric-26.2`/`fabric-26.1` line 416:
  `own.ifPresent(profile -> drawRow(guiGraphics, profile, getX(), getY(), width, showText, Minecraft.getInstance().level != null));`;
  `fabric-1.21.11` line 412 (Yarn idiom):
  `own.ifPresent(profile -> drawRow(context, profile, getX(), getY(), width, showText, MinecraftClient.getInstance().world != null));`.
  Friend-loop call sites (line 460 / 456) pass `friends.get(i).inGame()`,
  unaffected by this plan.
- `drawRow`'s current signature (confirmed, all three modules):
  `fabric-26.2`/`fabric-26.1`: `private void drawRow(GuiGraphicsExtractor guiGraphics, FriendSummary friend, int x, int y, int width, boolean showText, boolean inGame)` at line 556;
  `fabric-1.21.11`: `private void drawRow(DrawContext context, FriendSummary friend, int x, int y, int width, boolean showText, boolean inGame)` at line 552.
  The status-text line (fabric-26.2 line 578-579, others at the same
  structural offset) is:
  ```java
  String status = facade.richPresenceStatus(friend.steamId64())
          .orElseGet(() -> facade.stateMachine().statusLabel(friend.personaState(), inGame));
  ```
  `facade.richPresenceStatus(long)` (`FriendsSidebarFacade.java:164-166`)
  forwards to `FriendsDataSource.richPresenceStatus(long)` ->
  `FriendsService.richPresenceStatus(long)`, which is only ever populated by
  `resolveFriend(...)` for a friend's `steamId64` — never for the local
  player's own `steamId64` (`FriendsService.localProfile()`'s own comment:
  "never resolves Rich Presence (FR1.6 -- friend-relative only)"). This is
  the confirmed root cause the spec's Piece 1 describes: the own row always
  falls through to `statusLabel(...)`, never a live Rich Presence string.
- `FriendSidebarWidget`'s constructor (all three modules, same param list,
  `fabric-26.2`/`fabric-26.1` line 182-183, `fabric-1.21.11` line 179):
  `public FriendSidebarWidget(FriendsSidebarFacade facade, AvatarTextureCache avatarTextureCache, RowClickListener rowClickListener, boolean handleOnly, boolean reserveTopInset)`
  — no `RichPresenceFacade` parameter today.
- `FabricFriendsSidebarInjector`'s constructor (all three modules, identical
  shape) takes `(FriendsSidebarFacade facade, WorldJoinRequester worldJoinRequester, FriendHostingStatusReader hostingStatusReader, WorldInviteSender worldInviteSender, ToastService toastService)`
  and constructs `new FriendSidebarWidget(facade, avatarTextureCache, ..., handleOnly, reserveTopInset)`
  at its `onScreenInit` call site (`fabric-26.2` lines 120-122) — no
  `RichPresenceFacade` threaded through today.
- No `FriendsSidebarClientInitializer` (any of the three modules) calls
  `RichPresenceFacadeHandoff.require()` anywhere today — confirmed via
  direct read of all three; each currently ends its composition root by
  constructing `new FabricFriendsSidebarInjector(facade, worldJoinRequester, hostingStatusReader, worldInviteSender, toastService)`
  (`fabric-26.2` line 105-106).

**`RichPresenceFacadeHandoff`/`RichPresenceFacade` (all three modules,
structural twins, `api/` module for the interface):**
- `RichPresenceFacadeHandoff.require()` (`platform/fabric-26.2/.../RichPresenceFacadeHandoff.java:30-38`)
  throws `IllegalStateException` if called before `RichPresenceClientInitializer`
  has published — confirmed.
- `RichPresenceFacade.localPresenceStatus()` (`api/.../RichPresenceFacade.java:28`)
  is the sole existing method, already returning exactly the FR-D2 value;
  unchanged by this plan.

**`fabric.mod.json` client-entrypoint order — landmine confirmed present in
all three modules (not just `fabric-26.2`):**
- `platform/fabric-26.2/src/main/resources/fabric.mod.json:21-29`,
  `platform/fabric-26.1/src/main/resources/fabric.mod.json:21-29`, and
  `platform/fabric-1.21.11/src/main/resources/fabric.mod.json:21-29` all list,
  verbatim, the same seven-entry `"client"` array with
  `"de.lazuli.FriendsSidebarClientInitializer"` at index 4 and
  `"de.lazuli.RichPresenceClientInitializer"` at index 6 (last) — i.e.
  `FriendsSidebarClientInitializer` runs strictly before
  `RichPresenceClientInitializer` in all three modules today. `SteamWorldHostingClientInitializer`
  (index 2) already runs before `FriendsSidebarClientInitializer` (index 4)
  in the same array — the precedent FR-D5 cites.

**`RichPresenceClientInitializer` (all three modules, structural twins):**
- `fabric-26.2` line 64: `RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway);`
  inside the `active == true` branch only (the `!active` early-return branch,
  lines 55-59, never constructs a `RichPresencePublisher` — FR-D11,
  unaffected by this plan).

**`RichPresencePublisher`/`RichPresencePublisherTest` (single copy in
`features/rich-presence`, not triplicated per platform):**
- `RichPresencePublisher.java` (23 lines of substance): constructor at line
  29 `public RichPresencePublisher(LocalPresenceTracker tracker, SteamFriendsGateway gateway)`;
  `tick()` at lines 35-46, debounced-change branch after the
  `current.equals(lastWritten)` early return (line 37-39) at lines 40-45.
- `RichPresencePublisherTest.java` (4 tests, Mockito already a test
  dependency of this module — confirmed via existing `org.mockito.Mockito`
  imports) constructs `new RichPresencePublisher(tracker, gateway)` at 4 call
  sites (lines 46, 67, 82, 93) with no logger argument.
- `FriendsService`'s precedent constructor-injected sink (the pattern FR-D7
  mirrors): `private final Consumer<String> warnLogger;` field
  (`FriendsService.java:46`), constructor param
  `Consumer<String> warnLogger` (`FriendsService.java:68`), invoked as
  `warnLogger.accept("...")` (`FriendsService.java:87,101`) — never throws,
  no other contract, matching FR-D7's requirement verbatim.

## Files to Create
None.

## Files to Modify

1. **`features/rich-presence/src/main/java/de/lazuli/features/richpresence/services/RichPresencePublisher.java`**
   (FR-D7/FR-D8/FR-D9)
   - Add field `private final java.util.function.Consumer<String> changeLogger;`
     and a new trailing constructor parameter:
     `public RichPresencePublisher(LocalPresenceTracker tracker, SteamFriendsGateway gateway, java.util.function.Consumer<String> changeLogger)`.
   - Inside `tick()`'s existing change branch (after the
     `current.equals(lastWritten)` early return, before/after the existing
     `setLocalRichPresence`/`clearLocalRichPresence` call — either ordering
     satisfies FR-D8's "exactly one log call per actual change" requirement;
     recommend logging *after* the gateway call succeeds, mirroring the
     existing statement order, so the log reflects a call that was actually
     attempted), add exactly one call:
     `changeLogger.accept("Rich Presence changed: " + describe(lastWritten) + " -> " + describe(current));`
     where a small private static helper
     `describe(Optional<String> value)` renders `value.orElse("(none)")` (or
     equivalent) so both the write case (`Optional` populated) and the clear
     case (`Optional.empty()`) produce a readable message without a raw
     `Optional[...]`/`Optional.empty` toString — exact wording is an
     implementation-time free choice per the spec ("exact wording a
     planning-phase decision"), but must include both old and new values
     (FR-D8).
   - No change to the debounce/change-detection logic itself (`current.equals(lastWritten)`
     guard, `lastWritten` update) — purely additive per the spec's Non-goals.

2. **`features/rich-presence/src/test/java/de/lazuli/features/richpresence/services/RichPresencePublisherTest.java`**
   - Update all 4 existing `new RichPresencePublisher(tracker, gateway)` call
     sites (lines 46, 67, 82, 93) to pass a third argument — a no-op
     `Consumer<String>` (e.g. `msg -> { }`) is sufficient for the 3 tests that
     don't assert on logging; introduce one new test asserting the logging
     contract itself (see Test Strategy below) using a capturing
     `List<String>`-backed `Consumer<String>` or a Mockito-mocked
     `Consumer<String>` (Mockito already present in this test's imports).
   - No other existing assertions change — this is purely additive at the
     call-site/constructor-arity level plus one new test method.

3. **`platform/fabric-26.2/src/main/java/de/lazuli/RichPresenceClientInitializer.java`**
   (FR-D10)
   - Line 64: `RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway);`
     becomes
     `RichPresencePublisher publisher = new RichPresencePublisher(tracker, gateway, LazuliMod.LOGGER::info);`.
   - No other change; the `!active` early-return branch (lines 55-59) is
     unaffected (FR-D11 — no `RichPresencePublisher` constructed there at
     all, so no logger to wire).

4. **`platform/fabric-26.1/src/main/java/de/lazuli/RichPresenceClientInitializer.java`**
   - Identical one-line change to item 3, same line number (structural twin,
     confirmed).

5. **`platform/fabric-1.21.11/src/main/java/de/lazuli/RichPresenceClientInitializer.java`**
   - Identical one-line change to item 3 (re-confirm exact line number
     immediately before editing per this repo's established discipline for
     actively-evolving files — expected same line 64 based on structural-twin
     confirmation elsewhere in this feature, but not independently re-read in
     this planning pass; low risk given this file's small, stable size).

6. **`platform/fabric-{26.2,26.1,1.21.11}/src/main/resources/fabric.mod.json`**
   (FR-D5, all 3 modules, same edit)
   - Reorder the `"client"` array's last two entries so
     `"de.lazuli.RichPresenceClientInitializer"` appears immediately before
     `"de.lazuli.FriendsSidebarClientInitializer"` (mirroring the existing
     `SteamWorldHostingClientInitializer`-before-`FriendsSidebarClientInitializer`
     precedent at indices 2/4 of the same array). New order:
     ```json
     "de.lazuli.HelloWorldMainMenuClientInitializer",
     "de.lazuli.SteamworksClientInitializer",
     "de.lazuli.SteamWorldHostingClientInitializer",
     "de.lazuli.SteamCloudSyncClientInitializer",
     "de.lazuli.RichPresenceClientInitializer",
     "de.lazuli.FriendsSidebarClientInitializer",
     "de.lazuli.ServerBrowserClientInitializer"
     ```
     (`ServerBrowserClientInitializer`'s relative position to the other five
     entries is unaffected — only the two touched entries change order, per
     FR-D5's "no other entrypoint's relative order is disturbed" clause.)
   - Re-run `git status`/re-read each file immediately before editing (Risk 1
     below) — this array is confirmed load-bearing and shared across
     concurrently-evolving features in this repo.

7. **`platform/fabric-26.2/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java`**
   (FR-D1)
   - After obtaining `SteamworksService`/`SteamFriendsGateway` (existing lines
     51-52) and before constructing `FabricFriendsSidebarInjector` (existing
     line 105), add:
     `de.lazuli.api.richpresence.RichPresenceFacade richPresenceFacade = RichPresenceFacadeHandoff.require();`
     (same package, `RichPresenceFacadeHandoff` is already in `de.lazuli`, no
     new import needed beyond the `api.richpresence.RichPresenceFacade` type
     itself).
   - Update the final construction call (existing line 105-106) to thread the
     facade through:
     `new FabricFriendsSidebarInjector(facade, worldJoinRequester, hostingStatusReader, worldInviteSender, toastService, richPresenceFacade);`
     — new trailing parameter (see item 9/10 below for the injector/widget
     signature changes this requires).

8. **`platform/fabric-26.1/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java`**
   and **`platform/fabric-1.21.11/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java`**
   - Identical change to item 7 (re-confirm exact line numbers immediately
     before editing — expected structurally identical to `fabric-26.2` based
     on this feature's own established twin-confirmation, but not
     independently re-read line-by-line in this planning pass for these two
     files specifically; low risk, same discipline as item 5).

9. **`platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`**
   (all 3 modules, same edit; FR-D1/FR-D3)
   - Add field `private final de.lazuli.api.richpresence.RichPresenceFacade richPresenceFacade;`
     and a new trailing constructor parameter, updating the constructor
     (`fabric-26.2` lines 76-86) to:
     `public FabricFriendsSidebarInjector(FriendsSidebarFacade facade, WorldJoinRequester worldJoinRequester, FriendHostingStatusReader hostingStatusReader, WorldInviteSender worldInviteSender, ToastService toastService, RichPresenceFacade richPresenceFacade)`,
     assigning `this.richPresenceFacade = richPresenceFacade;` alongside the
     existing field assignments.
   - Update the single `new FriendSidebarWidget(...)` call site
     (`fabric-26.2` lines 120-122) to pass `richPresenceFacade` as a new
     trailing constructor argument (see item 10).

10. **`platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`**
    (all 3 modules, same shape; FR-D1/FR-D2/FR-D3/FR-D4)
    - Add import `de.lazuli.api.richpresence.RichPresenceFacade;`.
    - Add field `private final RichPresenceFacade richPresenceFacade;` and a
      new trailing constructor parameter: constructor becomes
      `public FriendSidebarWidget(FriendsSidebarFacade facade, AvatarTextureCache avatarTextureCache, RowClickListener rowClickListener, boolean handleOnly, boolean reserveTopInset, RichPresenceFacade richPresenceFacade)`
      (`fabric-26.2`/`fabric-26.1` line 182-183, `fabric-1.21.11` line 179),
      assigning the field alongside the existing five assignments.
    - `drawRow`'s signature (line 556 / 552) gains one new trailing
      parameter: `Optional<String> ownRichPresenceOverride` — e.g.
      `private void drawRow(GuiGraphicsExtractor guiGraphics, FriendSummary friend, int x, int y, int width, boolean showText, boolean inGame, Optional<String> ownRichPresenceOverride)`.
      The status-text computation (existing lines 578-579 /
      structurally-equivalent Yarn lines) becomes:
      ```java
      String status = ownRichPresenceOverride
              .or(() -> facade.richPresenceStatus(friend.steamId64()))
              .orElseGet(() -> facade.stateMachine().statusLabel(friend.personaState(), inGame));
      ```
      (FR-D2's exact fallback chain: live override, else existing friend-row
      Rich Presence lookup — a no-op for the own row since
      `facade.richPresenceStatus(ownSteamId64)` is always empty per Existing
      Implementation above — else the existing generic label, never blank).
    - Own-row call site (line 416 / 412) becomes:
      `own.ifPresent(profile -> drawRow(guiGraphics, profile, getX(), getY(), width, showText, Minecraft.getInstance().level != null, richPresenceFacade.localPresenceStatus()));`
      (Mojang mappings; Yarn equivalent for `fabric-1.21.11` substitutes
      `context`/`MinecraftClient.getInstance().world != null` per the
      existing own-profile-ingame-status plan's own precedent for this exact
      line) — FR-D2's "present" branch source.
    - Friend-loop call site (line 460 / 456) becomes:
      `drawRow(guiGraphics, friends.get(i), getX(), rowY, width, showText, friends.get(i).inGame(), Optional.empty());`
      — passes `Optional.empty()` so friend rows fall through to the
      existing, unaffected `facade.richPresenceStatus(friend.steamId64())`
      lookup exactly as today (FR-D3 — friend-row branch of `drawRow`
      unaffected; Piece 2's "no code change" claim holds for the actual
      Rich-Presence-read logic, only the *plumbing* of an unused-for-friend-
      rows parameter is added).
    - `Optional` is already imported in this file (line 18) — no new import
      needed for that type.

## Order / Dependencies of Changes
1. `RichPresencePublisher`'s constructor-arity change (item 1) must land
   together with its 4 call-site updates (item 2's test file, items 3/4/5's
   platform initializers) in the same change, or the module fails to
   compile — no partial-landing option (mirrors Risk framing in the
   precedent plan for `drawRow`'s own signature change).
2. The `fabric.mod.json` reorder (item 6) has no compile-time dependency on
   the Java changes, but is a *runtime* prerequisite for item 7-10's
   `RichPresenceFacadeHandoff.require()` call — land both in the same change
   per module (a `fabric.mod.json` reorder without the Java change is inert;
   the Java change without the reorder throws `IllegalStateException` at
   startup, per FR-D1's landmine). Recommend doing the `fabric.mod.json`
   edit first per module, immediately followed by that module's
   `FriendsSidebarClientInitializer`/`FabricFriendsSidebarInjector`/
   `FriendSidebarWidget` edits, so the module is never left in a
   compiles-but-crashes-at-startup intermediate state within the same
   working session.
3. `FriendSidebarWidget`'s constructor/`drawRow` signature changes (item 10)
   must land together with `FabricFriendsSidebarInjector`'s updated
   construction call (item 9) and `FriendsSidebarClientInitializer`'s updated
   `FabricFriendsSidebarInjector` construction call (item 7/8) within the
   same module, in the same change — a three-link chain, each a compile-time
   dependency on the next.
4. Across the three platform modules, no ordering dependency (separate
   Gradle subprojects, as established by the precedent plan).
5. Items 1-2 (`RichPresencePublisher`/its test) are independent of items
   3-10 (own-row display + entrypoint order) — either piece can be done
   first; recommend Piece 3 (logging) first since it is fully contained
   within `features/rich-presence` and has no cross-module chain, then Piece
   1 (own-row display) per module.

## Risks
1. **`fabric.mod.json`'s `"client"` array and `FriendsSidebarClientInitializer`/
   `FabricFriendsSidebarInjector`/`FriendSidebarWidget` are all confirmed
   actively-evolving, shared files** (per the precedent plan's own Risk 1,
   still applicable: dropdown-polish work touches `FriendSidebarWidget`).
   Re-run `git status`/re-read each file immediately before editing, since
   line numbers cited here reflect a single point-in-time read; the
   `drawRow`/call-site edits in this plan do not overlap the dropdown-polish
   work's own touched region (`joinPolicyDropdown`/`renderNow` dropdown
   fields), but must be re-verified, not assumed, per this repo's
   established discipline for this specific file.
2. **Four-link constructor-parameter threading
   (`FriendsSidebarClientInitializer` -> `FabricFriendsSidebarInjector` ->
   `FriendSidebarWidget`, plus `RichPresencePublisher`'s separate,
   unrelated arity change) means a partial edit in any one file breaks the
   build immediately** — low risk in practice (fast, unambiguous compiler
   feedback pinpointing the exact missing argument), but an implementer must
   update all files in a given chain within the same change, per item 3 of
   Order/Dependencies.
3. **`fabric-1.21.11`'s exact `RichPresenceClientInitializer`/
   `FriendsSidebarClientInitializer` line numbers were not independently
   re-read in this planning pass** (items 5/8 above) — flagged so the
   implementer re-reads that module's two files before editing rather than
   assuming the `fabric-26.2` line numbers transfer verbatim; the *shape* of
   the edit (one-line constructor-arg addition; one new
   `RichPresenceFacadeHandoff.require()` call plus one new trailing
   constructor argument) is confirmed structurally identical regardless of
   exact line number.
4. **Own-row Rich Presence text must never visually leak into a friend
   row.** Mitigated identically to the precedent plan's own Risk 4: the
   friend-loop call site is updated to explicitly pass `Optional.empty()`
   for the new parameter (previously implicit/nonexistent), so an
   implementer cannot accidentally pass `richPresenceFacade.localPresenceStatus()`
   to both call sites — doing so would make every friend row show the local
   player's own Rich Presence text instead of each friend's own.
5. **No unit test exists or is added for the `drawRow`/rendering fix itself**
   (Piece 1), consistent with this repo's established convention (per the
   precedent plan's own Risk 5, restated) that `FriendSidebarWidget.drawRow`
   is not unit-tested anywhere today — this plan follows that precedent and
   relies on manual in-game verification (Test Strategy below) instead of
   introducing a new rendering-test harness.
6. **`RichPresencePublisher`'s constructor-arity change is a breaking change
   to an in-repo-only class** (not part of `api/`) — all 4 call sites (3
   platform initializers + 1 test file) are enumerated in this plan's Files
   to Modify; a missed call site fails to compile immediately (fast
   feedback), not a silent runtime gap.
7. **Log-message exact wording is unconstrained by the spec** ("exact
   wording a planning-phase decision") — this plan recommends
   `"Rich Presence changed: <old> -> <new>"` with an `orElse("(none)")`-style
   rendering of each `Optional<String>` side; if a reviewer prefers different
   wording, that is a low-cost implementation-time adjustment with no
   structural impact (FR-D8 only requires both old and new values be
   present, at `INFO`, once per actual change).
8. **Two-account manual smoke test (FR-D6/Piece 2) requires a second real
   Steam account that is a Steam friend of the test account** — if
   unavailable during the verification phase, this must be explicitly
   flagged as a deferred/incomplete acceptance criterion rather than silently
   marked done via unit-test coverage alone (no automated test can exercise
   real cross-account Steam Rich Presence propagation, per the spec's own
   FR-D6 wording). Cross-reference: `MEMORY.md`'s existing
   "Steam World Hosting: pending live test" note already flags a similar
   live-test gap for this codebase's Steam-dependent features; this plan
   surfaces the same category of gap for Rich Presence specifically.

## Dependencies
No new external (non-Fabric, non-Mockito) dependency. Mockito is already a
test dependency of `features/rich-presence`'s test source set (confirmed via
`RichPresencePublisherTest.java`'s existing `org.mockito.Mockito` imports) —
no build-config change required for the new/updated test in item 2.

## Test Strategy
- **Piece 1 (own-row Rich Presence display) — no automated test, per the
  established convention for `drawRow` (Risk 5); manual in-game verification
  across all three platform modules:**
  1. Load a singleplayer world with `features/rich-presence` enabled and
     Steam available: the own-profile row's status text switches from the
     generic `"In Game"` label to the live Rich Presence string (e.g.
     `"Exploring Plains"`) as soon as `features/rich-presence`'s own sweep
     computes one — confirms FR-D2's "present" branch.
  2. While the own row is showing a live Rich Presence string, disable
     `features/rich-presence` (or otherwise force
     `RichPresenceFacade.localPresenceStatus()` to return empty, e.g. by
     toggling `rich-presence.json`'s `enabled` off and restarting) and
     confirm the own row falls back to the existing generic `"In Game"`
     label — confirms FR-D2's "else" branch, and that the row is never
     blank.
  3. Confirm every friend row's status text is completely unaffected
     throughout (still driven by each friend's own
     `facade.richPresenceStatus(friend.steamId64())`/persona-state fallback,
     unchanged) — confirms FR-D3.
  4. Confirm the mod starts up successfully (no `IllegalStateException` from
     `RichPresenceFacadeHandoff.require()`) after the `fabric.mod.json`
     reorder — confirms FR-D5's landmine fix actually takes effect; this is
     also implicitly covered by simply launching the game at all after this
     change, since a failure here is a hard startup crash, not a subtle
     rendering bug.
  5. Repeat steps 1-4 independently on all three platform modules
     (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`) — confirms FR-D4.
- **Piece 2 (friend-row Rich Presence, confirmed non-issue) — manual
  two-account smoke test only, no automated coverage possible (FR-D6):**
  - Account A runs this mod with an active Rich Presence status (e.g. a
    loaded singleplayer world); Account B (a Steam friend of Account A, also
    running this mod with the sidebar open) must see Account A's row display
    that same status text within one friends-sidebar refresh interval
    (`FriendsSidebarConfig.refreshIntervalSeconds()`) of it changing on
    Account A's side. Must be logged as an explicit manual verification step
    in the verification phase (Risk 8) — not skipped or marked
    "already covered."
- **Piece 3 (Rich Presence change logging) — new automated unit test,
  `features/rich-presence`'s `RichPresencePublisherTest`:**
  - Add a new test method (e.g. `logsExactlyOnceOnActualChange`) reusing the
    existing `ScriptedTracker` fixture with the same
    `writesOnlyOnActualChange` script (`"Exploring Plains"` x3, then
    `"Building in Plains"`), constructing `RichPresencePublisher` with a
    capturing `Consumer<String>` (a `List<String>` sink, or a
    Mockito-mocked `Consumer<String>` verified via `verify(logger, times(1))...`)
    and asserting the sink is invoked exactly once per actual change (twice
    total across the 4-tick script: once for the initial
    empty-to-`"Exploring Plains"` transition, once for
    `"Exploring Plains"`-to-`"Building in Plains"`) — never on the two
    unchanged intermediate ticks (FR-D8's "never every tick" requirement).
  - Add a second new test method covering the clear case, reusing
    `clearsOnlyOncePerPresentToEmptyTransition`'s existing script
    (`"Exploring Plains"`, then `empty` x3), asserting exactly one log call
    for the present-to-empty transition and none afterward.
  - Update all 4 existing test methods' `new RichPresencePublisher(tracker, gateway)`
    construction calls to pass a third, no-op `Consumer<String>` argument
    (e.g. `msg -> { }`) — no change to their existing assertions.
  - Re-run the full `features/rich-presence` test module after these
    changes, as a regression check.

## Acceptance Criteria
Per `features/friends-sidebar/specification-richpresence-display-and-logging.md`:
- FR-D1: each platform module's `FriendsSidebarClientInitializer` calls
  `RichPresenceFacadeHandoff.require()` and threads the resulting
  `RichPresenceFacade` into `FriendSidebarWidget`'s own-row rendering path
  via `FabricFriendsSidebarInjector`.
- FR-D2: the own-profile row's rendered status text is
  `RichPresenceFacade.localPresenceStatus()`'s value when present, else the
  existing generic in-game-aware label — never blank.
- FR-D3: the friend-row branch of `drawRow`, `FriendSummary`'s shape, and
  `friend.inGame()` are unaffected; friend rows continue reading
  `facade.richPresenceStatus(friend.steamId64())` exactly as today.
- FR-D4: identical behavior across `fabric-1.21.11`, `fabric-26.1`,
  `fabric-26.2`.
- FR-D5: all three `fabric.mod.json` files list
  `RichPresenceClientInitializer` before `FriendsSidebarClientInitializer` in
  the `"client"` array; no other entrypoint's relative order changes; the
  mod starts without an `IllegalStateException` from
  `RichPresenceFacadeHandoff.require()`.
- FR-D6: a manual two-account smoke test (Test Strategy, Piece 2) is
  performed and its outcome explicitly recorded in the verification phase
  (not silently skipped).
- FR-D7: `RichPresencePublisher` takes an injected `Consumer<String>`
  logging sink via its constructor, mirroring `FriendsService`'s
  `warnLogger` pattern.
- FR-D8: exactly one log call per actual debounced change (write or clear),
  never on an unchanged tick, including both the previous and new values —
  verified by the new `RichPresencePublisherTest` methods (Test Strategy,
  Piece 3).
- FR-D9: the log level wired at each platform's
  `RichPresenceClientInitializer` is `LazuliMod.LOGGER::info` (`INFO`), not
  `warn`/`debug`.
- FR-D10: all three platform `RichPresenceClientInitializer`s wire
  `LazuliMod.LOGGER::info` when constructing `RichPresencePublisher`.
- FR-D11: the `!active`/Noop branch of `RichPresenceClientInitializer`
  (which never constructs a `RichPresencePublisher`) is confirmed unchanged
  by this plan.
- FR-D12: FR-D7-FR-D11 hold identically across all three platform modules.
- All four modules (`features/rich-presence` plus the three platform
  modules) compile after these changes; `RichPresencePublisherTest` and the
  rest of `features/rich-presence`'s existing test suite pass.
- Manual in-game verification (Test Strategy, Piece 1) passes on all three
  platform modules; the two-account smoke test (Piece 2) outcome is recorded.

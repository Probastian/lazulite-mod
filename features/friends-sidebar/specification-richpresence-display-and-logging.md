# Rich Presence Display & Change Logging — Specification (v1.8 amendment)

## Overview
This is a v1.8 amendment to `features/friends-sidebar/specification.md`, filed
alongside the standalone `specification-own-profile-ingame-status.md` and
`specification-invite-to-game.md` amendments per this directory's established
convention. It closes the gap `features/rich-presence/plan.md` "Decision 4"
explicitly deferred: **"the in-mod sidebar's own-row label continues showing
the generic 'In Game' fallback until a separate, follow-up change wires
`RichPresenceFacadeHandoff` into `FriendSidebarWidget`"** (`plan.md:606-608`,
also Risk 5/`plan.md:643`). Triggered by a direct user report: *"I still dont
see rich presence in the sidebar. It's important that I can see my own rich
presence and also that of friends. Add logs when changing rich presence."*

Three pieces, confirmed by code investigation prior to this document:

1. **Own row never shows Rich Presence text (real bug, fix required).** The
   own-profile row's status label is always the raw persona-state word or the
   generic "In Game" fallback — it never surfaces the local player's own live
   Rich Presence text, because `features/rich-presence` already exposes
   exactly the accessor needed (`RichPresenceFacade.localPresenceStatus()`)
   but nothing in `features/friends-sidebar` has ever consumed it.
2. **Friend rows already show Rich Presence correctly (confirmed non-issue,
   no code change).** `FriendsService.resolveFriend()` already requests and
   reads the same Steam Rich Presence `"status"` key
   `RichPresencePublisher` writes for the local player. This path is fully
   wired; only a manual two-account smoke test is required to close it out.
3. **No logging on Rich Presence change (missing, fix required).**
   `RichPresencePublisher.tick()` writes/clears the local Rich Presence value
   on every debounced change but never logs it.

## Goals
- The own-profile row visibly shows the local player's own live, computed
  Rich Presence status text (the same string written to Steam's `"status"`
  key) whenever one is available, instead of always falling back to the
  generic "In Game" label.
- Friend rows' existing, already-correct Rich Presence display is confirmed
  end-to-end via a real two-account manual test and documented as such — not
  re-implemented.
- Every actual local Rich Presence value change (write or clear) is logged at
  `INFO` level, so it is visible in the default game log without needing a
  debug build.

## Non-goals
- No change to `RichPresencePublisher`'s debounce/change-detection semantics
  (`features/rich-presence/plan.md` Decision 5, `RichPresencePublisherTest`
  coverage) — this amendment only adds a logging side-effect inside the
  existing debounced-change branch, never a new write/clear path or a new
  poll cadence.
- No change to `FriendsService.resolveFriend()`'s friend-side Rich Presence
  read path (`FriendsService.java:106-134`) — confirmed already correct
  (Goals item 2); untouched by this amendment.
- No change to `FriendSummary`'s shape, `FriendsSidebarFacade`'s public
  surface beyond what FR-D3 below requires, or `FriendContextMenuWidget`.
- No new translation/localization infrastructure — the string surfaced on the
  own row is exactly whatever `RichPresenceFacade.localPresenceStatus()`
  already returns (already localized by `features/rich-presence`'s own
  `TierTextFormatter` seam); this feature does not format or truncate it
  beyond what `drawRow` already does for every other row's status text.
- No in-mod settings UI toggle for this logging — always-on at `INFO`,
  matching the user's explicit request and the product decision recorded in
  this document (see Requirements, Piece 3).
- Does not touch `features/rich-presence`'s own signal-gathering, tiering, or
  publishing logic (`PresenceStatusResolver`, `LocalPresenceTrackerImpl`,
  `PresenceSignalGatherer`) beyond adding the logging sink described below.

## Requirements

### Piece 1 — Own-row Rich Presence display (bug fix)

**Current behavior (confirmed via code read, all three platform modules
structurally identical):**
- `FriendsService.localProfile()`
  (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java:136-149`)
  never resolves or attaches Rich Presence text for the local player's own
  row — by design, per its own comment ("never resolves Rich Presence (FR1.6
  -- friend-relative only)").
- `FriendSidebarWidget.drawRow`
  (e.g. `platform/fabric-26.2/src/main/java/de/lazuli/friends/FriendSidebarWidget.java:556-583`,
  own-row call site at line 416, friend-loop call site at line 460; structurally
  identical in `platform/fabric-26.1` and `platform/fabric-1.21.11` at the same
  line numbers) computes the rendered status text as:
  ```java
  String status = facade.richPresenceStatus(friend.steamId64())
          .orElseGet(() -> facade.stateMachine().statusLabel(friend.personaState(), inGame));
  ```
  `facade.richPresenceStatus(...)` resolves through
  `FriendsSidebarFacade -> FriendsDataSource.richPresenceStatus(long) ->
  FriendsService.richPresenceStatus(long)`
  (`FriendsService.java:152-154`), which only ever returns a value for an
  entry previously populated by `resolveFriend` — never for the own
  `steamId64`. The own row therefore always falls through to the generic
  persona-state/"In Game" label, never the live Rich Presence string, even
  though `features/rich-presence` already computes and publishes exactly that
  string via `RichPresenceFacade.localPresenceStatus()`
  (`api/src/main/java/de/lazuli/api/richpresence/RichPresenceFacade.java:20-29`)
  for precisely this purpose (its own Javadoc: "exposed so
  `features/friends-sidebar`'s own-profile row may optionally consume it in
  place of a generic fallback label").
- No `FriendsSidebarClientInitializer` (any platform module) currently calls
  `RichPresenceFacadeHandoff.require()` anywhere — confirmed via search; this
  cross-feature bridge, though built and ready on the publishing side, has
  zero consumers today.

**Required behavior:**
- FR-D1. Each platform module's `FriendsSidebarClientInitializer` must obtain
  the published `RichPresenceFacade` via `RichPresenceFacadeHandoff.require()`
  and make it available to the own-row rendering path (e.g. threading it into
  `FriendsSidebarFacade`'s constructor, or a small dedicated
  own-presence-supplier parameter — exact shape a planning-phase decision).
- FR-D2. The own-profile row's rendered status text must be:
  1. `RichPresenceFacade.localPresenceStatus()`'s value, when present
     (non-empty), rendered exactly as returned (same "never truncate/reformat
     beyond what `drawRow` already does for other rows" rule as friend rows);
     else
  2. fall back to the existing generic in-game-aware label
     (`facade.stateMachine().statusLabel(personaState, inGame)`, per
     `specification-own-profile-ingame-status.md` FR-OP1/FR-OP2), unchanged
     from today — this fallback chain must never render blank.
- FR-D3. This is a **read-only, additive** change to the own-row branch of
  `drawRow` (or an own-row-specific variant/parameter) — `friend.inGame()`,
  `FriendSummary`'s shape, and the friend-row branch of `drawRow` are
  unaffected. Must compose cleanly with the in-game-styled color/fallback
  label logic `specification-own-profile-ingame-status.md` already
  established (FR-OP1-FR-OP3) — FR-D2's item 2 fallback is exactly that
  spec's existing fallback chain, not a new one.
- FR-D4. Must hold identically across all three platform modules
  (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`).

**Landmine — entrypoint ordering (must fix as part of this feature):**
- All three `platform/fabric-{1.21.11,26.1,26.2}/src/main/resources/fabric.mod.json`
  currently list `"de.lazuli.FriendsSidebarClientInitializer"` **before**
  `"de.lazuli.RichPresenceClientInitializer"` in the `"client"` entrypoint
  array (confirmed for `fabric-26.2` at
  `platform/fabric-26.2/src/main/resources/fabric.mod.json:21-29`; the other
  two modules mirror this ordering).
- Because FR-D1 requires `FriendsSidebarClientInitializer` to call
  `RichPresenceFacadeHandoff.require()`, and `require()` throws
  `IllegalStateException` if called before `RichPresenceClientInitializer`
  has published (`RichPresenceFacadeHandoff.java:30-38`), the publisher must
  run first.
- FR-D5. Reorder each `fabric.mod.json`'s `"client"` array so
  `RichPresenceClientInitializer` appears before `FriendsSidebarClientInitializer`
  — mirroring the existing working precedent of `SteamWorldHostingClientInitializer`
  running before `FriendsSidebarClientInitializer` for
  `WorldHostingBridgeHandoff` (same file, existing lines 24/26). No other
  entrypoint's relative order is disturbed by this reordering.

### Piece 2 — Friend rows already show Rich Presence (confirmed non-issue)

**Finding: already correct, no bug, no code change.** Confirmed by reading
`FriendsService.resolveFriend()` (`FriendsService.java:106-134`): every
refresh sweep calls `gateway.requestFriendRichPresence(steamId64)`, then reads
Steam Rich Presence key `"status"`
(`FriendsService.RICH_PRESENCE_STATUS_KEY`, `FriendsService.java:53`) — the
exact same key `RichPresencePublisher` writes for the local player
(`RichPresencePublisher.STATUS_KEY`,
`features/rich-presence/src/main/java/de/lazuli/features/richpresence/services/RichPresencePublisher.java:22`,
write site line 41). `drawRow`'s friend-row branch
(`FriendSidebarWidget.java:578-579`) already renders this value when present.
This path requires no implementation change.

- FR-D6 (acceptance criteria only, no new code). A manual two-account smoke
  test: Account A runs this mod with an active Rich Presence status (e.g. in
  a loaded singleplayer world); Account B, a Steam friend of Account A also
  running this mod with the sidebar open, must see Account A's row display
  that same status text within one friends-sidebar refresh interval
  (`FriendsSidebarConfig.refreshIntervalSeconds()`) of it changing on
  Account A's side. This must be logged as a manual verification step in the
  verification phase, not skipped as "already covered by unit tests" — no
  automated test can exercise real cross-account Steam Rich Presence
  propagation.

### Piece 3 — Log Rich Presence changes at INFO

**Current behavior:** `RichPresencePublisher.tick()`
(`RichPresencePublisher.java:35-46`) calls
`gateway.setLocalRichPresence(STATUS_KEY, current.get())` or
`gateway.clearLocalRichPresence()` exactly once per actual debounced value
change (`current.equals(lastWritten)` guard, line 37) — with zero logging
today.

**Required behavior:**
- FR-D7. `RichPresencePublisher` gains an injected `Consumer<String>` logging
  sink, mirroring `FriendsService`'s existing constructor-injected
  `warnLogger` pattern (`FriendsService.java:38,46,68` — sink accepts a
  pre-formatted message string, never throws, no other contract).
- FR-D8. Exactly one log call per actual change, placed inside the existing
  `tick()` change branch (after the `current.equals(lastWritten)` early
  return, i.e. never on an unchanged tick) — logging the transition, at
  minimum including both the previous (`lastWritten`) and new (`current`)
  values (e.g. `"Rich Presence changed: <old> -> <new>"`, exact wording a
  planning-phase decision), for both the write case (present value) and the
  clear case (present-to-empty transition).
- FR-D9. Log level is `INFO`, not `DEBUG` or `WARN` — an explicit product
  decision per this document (the user explicitly asked to "add logs when
  changing rich presence" and expects them visible in the default game log
  without a debug build enabled).
- FR-D10. Each platform module's `RichPresenceClientInitializer`
  (construction site at
  `platform/fabric-26.2/src/main/java/de/lazuli/RichPresenceClientInitializer.java:64`,
  mirrored in the other two modules) wires this sink as
  `LazuliMod.LOGGER::info` when constructing `RichPresencePublisher`.
- FR-D11. The `Noop`/inactive branch (`active == false`,
  `RichPresenceClientInitializer.java:55-59`) never constructs a
  `RichPresencePublisher` at all today and is unaffected — no logging is
  expected or required when the feature is disabled/Steam unavailable.
- FR-D12. Must hold identically across all three platform modules.

## Public API
Illustrative shapes only; final names/parameter order are a planning-phase
decision.

1. `RichPresencePublisher`'s constructor gains one new parameter,
   `Consumer<String> changeLogger` (FR-D7), alongside its existing
   `(LocalPresenceTracker tracker, SteamFriendsGateway gateway)` — a
   constructor-arity change, all four call sites (three platform
   `RichPresenceClientInitializer`s plus `RichPresencePublisherTest`) must be
   updated.
2. `FriendsSidebarFacade` (or an equivalent small own-presence-supplier
   parameter threaded through it) gains a way to read
   `RichPresenceFacade.localPresenceStatus()` for the own row (FR-D1/FR-D2) —
   no change to `FriendsDataSource`/`FriendsService`'s public surface is
   required, since Piece 1's fix is confined to the *rendering* layer, not
   the data-source layer (consistent with
   `specification-own-profile-ingame-status.md`'s own precedent of keeping
   `FriendSummary`/`localProfile()` unchanged).
3. `FriendSidebarWidget`'s own-row branch of/around `drawRow` gains the
   fallback-chain read described in FR-D2.
4. No change to `RichPresenceFacade`'s existing public interface — its single
   method, `localPresenceStatus()`, already exists and already returns
   exactly what FR-D2 needs.
5. No change to `FriendContextMenuWidget`'s public surface.

## Architecture
```
platform/fabric-<version>/.../RichPresenceClientInitializer
  |-- publishes RichPresenceFacadeHandoff (unchanged, existing)
  |-- constructs RichPresencePublisher(tracker, gateway, LazuliMod.LOGGER::info)  [FR-D7/FR-D10, new]

platform/fabric-<version>/.../FriendsSidebarClientInitializer
  |-- RichPresenceFacadeHandoff.require()  [FR-D1, new consumer]
  |-- threads facade into FriendsSidebarFacade / own-row rendering path

platform/fabric-<version>/.../FriendSidebarWidget
  |-- own-row branch: localPresenceStatus() -> else existing in-game fallback  [FR-D2, new]
  |-- friend-row branch: unchanged (Piece 2, no code change)

fabric.mod.json ("client" array, all 3 modules)
  |-- RichPresenceClientInitializer reordered BEFORE FriendsSidebarClientInitializer  [FR-D5]

features/rich-presence/.../RichPresencePublisher
  |-- tick(): existing debounced-change branch gains one changeLogger.accept(...) call  [FR-D8]
```
No new class is strictly required; this is threading an existing published
facade into an existing consumer, plus one new constructor parameter on an
existing publisher class. Cross-feature dependency direction is unchanged:
`features/friends-sidebar` optionally consumes `api/.../RichPresenceFacade`
(already designed for exactly this); `features/rich-presence` gains no new
outbound dependency.

## UI
- Own-profile row: when a live Rich Presence string is available, the status
  text shown is that string (e.g. "Near a Village", "In the Nether" — whatever
  `features/rich-presence`'s tiering computes), in place of the generic
  "In Game" fallback; color/border behavior is unchanged from
  `specification-own-profile-ingame-status.md` (FR-D3 — this amendment only
  changes the *text*, not the color logic).
- No new screen, no new sidebar chrome; the fallback-chain ordering (live
  status, else generic label, never blank) matches the sidebar's existing
  "never rendered blank" invariant.

## Configuration
None added. Whether the own row shows a live Rich Presence string or the
generic fallback is entirely driven by whether `features/rich-presence`
itself is enabled/active (`RichPresenceConfig.enabled()`,
`SteamworksService.isSteamAvailable()`) and currently computing a non-empty
status — no new toggle in `friends-sidebar.json` or `rich-presence.json`.
Logging (Piece 3) is always-on at `INFO`, no config gate (FR-D9).

## Events
No new event-bus entries (this repo has no generic event bus). Piece 1 is a
pure rendering-layer read at draw time, reusing the sidebar's existing
per-frame render pass — no new poll/sweep cadence. Piece 3 is a log call
inside `RichPresencePublisher`'s existing per-tick `tick()` method — no new
tick registration.

## Networking
None. No new Steamworks calls of any kind. Piece 1 reads an already-computed,
already-published in-process value (`RichPresenceFacade.localPresenceStatus()`);
Piece 3 adds only a log statement alongside the existing
`setLocalRichPresence`/`clearLocalRichPresence` calls, with no new Steamworks
call. Piece 2 requires no networking change (already correctly wired).

## Persistence
None. No new persisted state; the own row's status text is computed at draw
time (Piece 1), and log lines (Piece 3) are ordinary transient log output,
never persisted by this feature.

## Compatibility
- Must land identically across all three platform modules
  (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`)
  — `FriendSidebarWidget`, `FriendsSidebarClientInitializer`,
  `RichPresenceClientInitializer`, and `fabric.mod.json` are confirmed
  structural twins across all three as of this writing.
- `RichPresencePublisher`'s constructor-arity change (FR-D7/Public API item 1)
  is a breaking change to that class's constructor; all in-repo call sites
  (three platform initializers, `RichPresencePublisherTest`) must be updated
  in the same change — no external consumers exist (this class is not part
  of `api/`).
- The `fabric.mod.json` reorder (FR-D5) touches a load-bearing entrypoint
  array across all three modules — re-verify no other module has since added
  an initializer between the two touched entries before editing (re-run
  `git status`/read each file immediately before editing, per this repo's
  established discipline), since `FriendsSidebarClientInitializer` and
  `RichPresenceClientInitializer` are both confirmed actively-evolving files.
- `RichPresenceFacade`'s existing interface (`api/.../RichPresenceFacade.java`)
  is unchanged — this amendment is purely a new consumer, not a contract
  change, so no other existing consumer of that interface is affected.

## Performance
Negligible for both pieces:
- Piece 1: one additional `Optional<String>` read
  (`RichPresenceFacade.localPresenceStatus()`, already computed once per
  client tick by `features/rich-presence`'s own existing sweep) at draw time
  for the own row only — same cost class as the existing
  `facade.richPresenceStatus(...)` read already performed for every friend
  row.
- Piece 3: one `Consumer<String>.accept(...)` call, only on an actual
  debounced value change (not every tick) — matching the existing
  `setLocalRichPresence`/`clearLocalRichPresence` call frequency exactly, no
  new polling.

## Future Extensions
- If a future amendment wants the own row's Rich Presence text to be
  visually distinguished from the generic "In Game" fallback (different
  color/icon), that is out of scope here — FR-D2/FR-D3 keep color logic
  untouched, text-only.
- If a future amendment wants structured (not string-concatenated) log
  output for Rich Presence changes (e.g. for a diagnostics screen), the
  `Consumer<String>` sink introduced here (FR-D7) can be swapped for a richer
  sink type without touching `tick()`'s change-detection logic.

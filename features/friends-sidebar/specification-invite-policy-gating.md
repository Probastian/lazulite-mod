# Friends Sidebar — "Invite to Game" Join-Policy Gating & Live Join-Gate Fix

## Overview
Two related defects, both stemming from the same root cause: the "who can
join" `JoinPolicy` (`features/friends-sidebar`) and the actual hosted-session
join/advertise state (`features/steam-world-hosting`) are wired together
*incompletely*.

1. **Symptom bug.** "Invite to game"
   (`features/friends-sidebar/specification-invite-to-game.md`, shipped) is
   gated only on `WorldInviteSender.isHosting()`
   (`api/src/main/java/de/lazuli/api/worldhosting/WorldInviteSender.java:28`),
   which reads `HostingLifecycle.currentStatus().hosting()`
   (`platform/fabric-1.21.11/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java:103-105`)
   — a plain "is a world loaded" boolean that is **completely blind** to
   `JoinPolicy`. A host can currently select "Nobody" and still see an
   enabled "Invite to game" row and successfully send a real Steam invite
   that the invited friend can act on (see Networking below) — contradicting
   the policy the host just chose.

2. **Root-cause investigation finding (confirmed, not merely suspected).**
   This repo already has a v1.3/v1.4 "amendment" design that links
   `JoinPolicy` to the hosting session in two ways:
   - Rich Presence advertising is suppressed while policy is `NOBODY`
     (`HostingLifecycle.start(boolean advertise)`/`updateAdvertising(boolean)`,
     `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/HostingLifecycle.java:56-84`),
     driven live by `WorldHostingHookHolder.updateJoinPolicy(...)`
     (`platform/fabric-1.21.11/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java:65-72`),
     itself invoked from `FriendsSidebarClientInitializer`'s
     `onJoinPolicyChanged` config-write callback
     (`platform/fabric-1.21.11/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java:76-91`).
   - A `HostGateway`/`JoinGatePolicy` join-gate predicate
     (`features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/HostGateway.java:55-61`)
     rejects every peer outright when the resolved policy is `NOBODY`.

   **However**, tracing the gate predicate's actual plumbing shows it is
   **not** live the way its own Javadoc claims. `WorldHostingHookHolder
   .onWorldLoad()` passes the *current* value of the static `canJoin` field
   into `new SteamSession(handler, group, canJoin)`
   (`platform/fabric-1.21.11/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java:108-111`),
   which threads it down to a `private final LongPredicate canJoin` field on
   `SteamServerChannel`
   (`platform/fabric-1.21.11/src/main/java/de/lazuli/worldhosting/SteamServerChannel.java:36,40-43`),
   tested once per inbound connection attempt in `acceptPeer(...)`
   (`SteamServerChannel.java:94-99`). `WorldHostingHookHolder
   .updateJoinPolicy(...)` (`WorldHostingHookHolder.java:65-72`) reassigns
   the *static* `canJoin` field to a new `HostGateway`-backed predicate
   object, but the already-running `SteamServerChannel` instance still holds
   its own `final` reference to the **old** predicate captured at
   world-load time — reassigning a static field never reaches a field
   already copied by value into an existing object. Concretely: **flipping
   `JoinPolicy` while a world is already loaded changes Rich Presence
   advertising live, but does not actually change whether a new peer's join
   attempt is accepted or rejected** until the world is unloaded and
   reloaded. This directly contradicts `WorldHostingHookHolder
   .updateJoinPolicy`'s own Javadoc ("re-derives the join gate ... without a
   restart") and is a second, more severe bug than the symptom in (1): a
   host who switches from `NOBODY`/`FRIENDS` to `EVERYONE` mid-session does
   not actually open the gate to new joiners, and a host who switches
   *to* `NOBODY` mid-session does not actually close it to new joiners
   either — only the Rich-Presence-driven native "Join Game"
   button/discoverability and (once fixed) the "Invite to game" row change.

This document specifies both fixes. Filed under `features/friends-sidebar/`
(not `features/steam-world-hosting/`) following this repo's existing
convention (`specification-invite-to-game.md` itself lives here despite most
of its backing logic living in `steam-world-hosting`) — the user-visible
defect (bug 1) and the policy source of truth (`JoinPolicy`,
`FriendSidebarStateMachine.nextJoinPolicy`) both belong to this feature, and
the fix's platform-composition-root changes touch `FriendsSidebarClientInitializer`
(this feature's own composition root) at least as much as
`SteamWorldHostingClientInitializer`. Cross-references
`features/steam-world-hosting/specification.md` (`HostingLifecycle`,
`HostGateway`, `HostedWorldStatus`) and
`features/friends-sidebar/specification-invite-to-game.md` (the "Invite to
game" wiring this amends) throughout.

**Three settled decisions** (incorporated below, not open for planning-phase
reconsideration):
1. **No forcible disconnect of already-connected peers on a `NOBODY`
   transition.** Flipping `JoinPolicy` to `NOBODY` mid-session never
   enumerates or kicks existing connections — only new join attempts are
   affected (FR-IPG6).
2. **`WorldInviteSender#isHosting()` itself returns `false` whenever the
   current `JoinPolicy` is `NOBODY`**, even if a hosted session is
   otherwise active — no new/renamed method, no separate `canInvite()`
   (FR-IPG1).
3. **The cross-thread mutable join-gate mechanism is a shared
   `AtomicReference<LongPredicate>`**, written by
   `WorldHostingHookHolder.updateJoinPolicy(...)` (invoked from whatever
   thread the `JoinPolicy` config-write callback runs on) and read by
   `SteamServerChannel.acceptPeer(...)` (invoked from the Steam
   callback/networking thread that dispatches inbound P2P connection
   attempts) — not a `Supplier<LongPredicate>` or any other wrapper
   (FR-IPG4, Public API item 3).

## Goals
- **G1.** "Invite to game" is disabled whenever the local player's current
  `JoinPolicy` is `NOBODY`, regardless of `HostedWorldStatus.hosting()`.
- **G2.** A live `JoinPolicy` change while a world is already loaded actually
  takes effect for **new** incoming join attempts immediately — not only for
  Rich Presence advertising — matching what
  `WorldHostingHookHolder.updateJoinPolicy`'s existing Javadoc already
  (incorrectly) claims happens today.
- **G3.** The already-shipped, already-correct half of this design —
  advertising suppression while `NOBODY`
  (`HostingLifecycle.updateAdvertising`) and never touching
  `hosting`/already-connected peers on a policy change — is preserved
  exactly as-is; this document does not propose tearing down or restarting
  the Steam P2P session/`SteamSession` on a policy change (see Non-goals and
  Architecture's "Decision: no session teardown on policy change").

## Non-goals
- **No full hosting-session stop/restart on `JoinPolicy` transitions.**
  The user's background framing floats "`NOBODY` should actually STOP the
  hosted Steam session entirely" as one option; this spec does **not**
  adopt that shape. Evidence against it:
  - `HostingLifecycle.updateAdvertising`'s own Javadoc is explicit that a
    live advertising toggle must "never touch `hosting`/`localSteamId64`,
    never disconnect a peer" (`HostingLifecycle.java:67-71`) — this is an
    existing, deliberate invariant from the v1.3 amendment, not an
    oversight.
  - Tearing down the actual `SteamSession`/Netty `SteamServerChannel` (the
    only thing that could truly "stop hosting") on every dropdown click
    would forcibly disconnect any already-connected peers even though
    nothing in this repo's shipped behavior does that today, would require
    re-running the world-load Netty-pipeline-capture dance
    (`WorldHostingHookHolder.onWorldLoad`) without an actual world
    load/unload event, and would make the dropdown itself latency- and
    failure-prone (a real Steamworks listener stop/restart) for what is
    conceptually a pure access-control change.
  - Fixing the *actually broken* half (the frozen join-gate predicate, G2)
    already delivers the user's real requirement — "while policy is
    `NOBODY`, nobody new can join, and switching off `NOBODY` immediately
    allows new joins" — without the cost/risk of session teardown.
- **No forcible disconnect of already-connected peers on a `NOBODY`
  transition mid-session (settled decision, see Overview #1 and FR-IPG6).**
  Existing connections are left alone unconditionally; this is not
  reconsidered during planning. A future reversal remains possible (Future
  Extensions) but is out of scope here.
- **No change to `HostGateway`'s three-state policy semantics**
  (`NOBODY`→reject-all, `FRIENDS`→`isDirectFriend`, `EVERYONE`→accept-all,
  `HostGateway.java:55-61`) — reused exactly as-is.
- **No new context-menu row/label.** Reuses the existing "Invite to game"
  slot exactly as `specification-invite-to-game.md` already established.
- **No change to `HostingPresenceScanner`/"Join game" (`FriendHostingStatusReader`)**
  — that slot answers "is *this friend* hosting," an unrelated question to
  this document's "does *my own* policy allow a new join."
- **No new Steamworks call.** Both fixes are pure local-state plumbing.
- **No `Supplier<LongPredicate>` or other wrapper for the live join-gate
  indirection.** The concrete shape is fixed as `AtomicReference<LongPredicate>`
  (settled decision, see Overview #3, FR-IPG4, Public API item 3); this is
  not a planning-phase choice.

## Requirements

**Bug 1: policy-aware "Invite to game" gating**
- **FR-IPG1.** `WorldInviteSender.isHosting()`'s contract changes from "an
  active hosted session exists" to "an active hosted session exists **and**
  the current `JoinPolicy` is not `NOBODY`"
  (`api/src/main/java/de/lazuli/api/worldhosting/WorldInviteSender.java:25-28`,
  Javadoc updated accordingly). **Settled: `isHosting()` itself returns
  `false` whenever `JoinPolicy == NOBODY`**, even while a hosted session is
  otherwise active — no new/renamed method (`canInvite()` or otherwise);
  the existing single-purpose-interface shape (`isHosting()`/
  `inviteFriend(...)`) is kept because it has exactly one call site today
  (`FriendContextMenuWidget.java:100`) and "hosting" and "not gated to
  nobody" are the intended synonym this interface's external contract now
  documents.
- **FR-IPG2.** `inviteFriend(long)`'s existing race-guard (FR-INV5,
  `specification-invite-to-game.md`) is unchanged; it continues to check
  `HostingLifecycle.currentStatus().hosting()` directly for its own
  narrower "was a session ever active" purpose (Public API below) — only
  the externally-visible `isHosting()` used for **enablement** gains the
  policy check.
- **FR-IPG3.** `FriendContextMenuWidget`'s `case 2` enablement
  (`platform/fabric-1.21.11/src/main/java/de/lazuli/friends/FriendContextMenuWidget.java:100`)
  requires no code change — it already delegates to
  `worldInviteSender.isHosting()`, so FR-IPG1 alone fixes the visible bug
  across all three platform modules once `SteamWorldHostingClientInitializer`
  publishes the corrected implementation (Architecture).

**Bug 2: live join-gate predicate (root-cause fix)**
- **FR-IPG4.** A new incoming peer's join attempt
  (`SteamServerChannel.acceptPeer`, `SteamServerChannel.java:94-99`) must
  test the **current** resolved `JoinPolicy`'s gate, not a predicate object
  frozen at world-load time. **Settled shape: `SteamServerChannel`/
  `SteamSession` hold a shared `AtomicReference<LongPredicate>`** —
  `WorldHostingHookHolder.updateJoinPolicy(...)` writes the new
  `HostGateway`-backed predicate into it (from whichever thread the
  `JoinPolicy` config-write callback runs on, e.g. a Steam callback
  thread), and `SteamServerChannel.acceptPeer(...)` reads it fresh on
  every inbound connection attempt (from the connection-accept thread) —
  not a `final LongPredicate` captured once at construction, and not a
  `Supplier<LongPredicate>` (Public API below).
- **FR-IPG5.** `WorldHostingHookHolder.updateJoinPolicy(LongPredicate,
  boolean)` continues to update advertising live exactly as today
  (`updateAdvertising`, unchanged) and additionally writes FR-IPG4's
  `AtomicReference<LongPredicate>` so the live indirection observes the
  new gate predicate immediately — no new Netty/`SteamSession` restart, no
  reconnect of existing peers.
- **FR-IPG6.** Existing connected peers are **not** disconnected/re-validated
  against the new policy when it changes (Non-goals, Overview #1, settled
  decision) — FR-IPG4/FR-IPG5 only affect **new** join attempts arriving
  after the policy change. This matches `HostingPresenceScanner`'s/
  `updateAdvertising`'s existing "never touches already-connected state"
  precedent, made explicit here as a requirement rather than an accidental
  byproduct.
- **FR-IPG7.** No behavior change while a world is *not* yet loaded —
  `WorldHostingHookHolder.onWorldLoad()` continues to resolve whatever
  `JoinPolicy`/gate is current at that moment exactly as today
  (`WorldHostingHookHolder.java:95-112`, unchanged), constructing the
  initial `AtomicReference<LongPredicate>` with that resolved predicate as
  its starting value.

## Public API
Illustrative shapes only; exact field/method names are a planning-phase
decision, but the wrapper type (`AtomicReference<LongPredicate>`) and the
`isHosting()` contract change are settled, not open.

1. **`WorldInviteSender#isHosting()`**
   (`api/src/main/java/de/lazuli/api/worldhosting/WorldInviteSender.java`) —
   Javadoc-only contract change (FR-IPG1); no signature change. New
   contract: `true` iff a hosted session is active **and** `JoinPolicy !=
   NOBODY`.
2. **`SteamWorldHostingClientInitializer`**'s anonymous `WorldInviteSender`
   implementation
   (`platform/fabric-1.21.11/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java:101-115`,
   structurally identical on `fabric-26.1`/`fabric-26.2`) — `isHosting()`
   changes from
   `return lifecycle.currentStatus().hosting();` to something reading both
   `lifecycle.currentStatus().hosting()` and the live advertise/gate flag
   already tracked by `WorldHostingHookHolder` — e.g. a new
   `WorldHostingHookHolder.isAdvertising(): boolean` accessor exposing the
   existing private `advertise` field
   (`WorldHostingHookHolder.java:28`), since "advertise == false" and
   "`JoinPolicy == NOBODY`" are already 1:1 today
   (`gatePolicy != JoinGatePolicy.NOBODY` is the sole input to `advertise`,
   `SteamWorldHostingClientInitializer.java:97` /
   `FriendsSidebarClientInitializer.java:86`) — avoiding a second,
   redundant "current policy" static field.
3. **`WorldHostingHookHolder`** gains:
   - `static boolean isAdvertising()` — trivial accessor for item 2 (Public
     API), returning the existing `advertise` volatile field.
   - **The `canJoin` live-indirection for FR-IPG4 is a shared
     `AtomicReference<LongPredicate>`** (settled, not a
     `Supplier<LongPredicate>`): e.g. a
     `static final AtomicReference<LongPredicate> canJoin` field on
     `WorldHostingHookHolder`, initialized in `onWorldLoad()` (FR-IPG7) and
     `.set(...)` by `updateJoinPolicy(...)` (FR-IPG5) instead of the
     current plain reassignment of a `static LongPredicate` field. The
     same `AtomicReference` instance (or a reference to it) is threaded
     into `SteamSession`/`SteamServerChannel` at construction so
     `acceptPeer(...)` reads through it (FR-IPG4) rather than through a
     `final LongPredicate` copied by value.
4. **`SteamServerChannel`** (`platform/fabric-<version>/.../SteamServerChannel.java`)
   — the `private final LongPredicate canJoin` field
   (`SteamServerChannel.java:36`) becomes
   `private final AtomicReference<LongPredicate> canJoin`; `acceptPeer`'s
   call site (`SteamServerChannel.java:97`) changes from
   `canJoin.test(peerId)` to `canJoin.get().test(peerId)`, otherwise
   unchanged (disconnect protocol, logging, all unchanged).

## Architecture
```
FriendSidebarWidget (dropdown click)
  -> facade.selectJoinPolicy(policy)                       (unchanged)
     -> joinPolicyWriter.accept(policy)                     (unchanged)
        -> FriendsSidebarClientInitializer's onJoinPolicyChanged (unchanged)
           -> WorldHostingHookHolder.updateJoinPolicy(hostGateway::canJoin,
                                                       gatePolicy != NOBODY)
              |-- advertise flag updates live (existing, correct)
              |     -> HostingLifecycle.updateAdvertising(...)  (existing)
              |-- (NEW, FR-IPG4/5) canJoin.set(newPredicate) on the shared
              |     AtomicReference<LongPredicate> so the ALREADY RUNNING
              |     SteamServerChannel.acceptPeer(...) observes the new
              |     gate predicate on the very next join attempt (read via
              |     canJoin.get().test(peerId), from whatever thread
              |     acceptPeer runs on), instead of only at the next world
              |     load

FriendContextMenuWidget.isEnabled(2)
  -> worldInviteSender.isHosting()                          (existing call site,
                                                              unchanged)
     -> (NEW, FR-IPG1) SteamWorldHostingClientInitializer's WorldInviteSender
          impl now reads BOTH lifecycle.currentStatus().hosting() AND
          WorldHostingHookHolder.isAdvertising() (== JoinPolicy != NOBODY),
          returning false whenever either is false
```
**Decision: no session teardown on policy change.** Reaffirms Non-goals:
`WorldHostingHookHolder.onWorldLoad()`/`onWorldStop()`
(`WorldHostingHookHolder.java:95-129`) remain the only two places a
`SteamSession` is constructed/torn down; `updateJoinPolicy` never calls
either. This preserves every existing invariant this feature area's specs
already document (idempotent `start`/`stop`, "never disconnects a peer" on
advertising toggle) while still closing the real gap (FR-IPG4).

**Decision: cross-thread join-gate mechanism is `AtomicReference<LongPredicate>`.**
`JoinPolicy` changes originate from the config-write callback thread
(potentially a Steam callback thread, given `FriendsSidebarClientInitializer`'s
`onJoinPolicyChanged` may be invoked in response to Steamworks-driven UI
events), while `acceptPeer(...)` runs on whatever thread dispatches inbound
P2P connection attempts — a different thread in general. `AtomicReference`
is the conventional, obvious JDK shape for a single mutable reference
published safely across threads without additional locking, and is chosen
over `Supplier<LongPredicate>` specifically for that conventionality: a
`Supplier` would work equivalently but obscures that this is plain mutable
state being handed off between two threads, whereas `AtomicReference` names
that intent directly.

No new Feature→Feature import: `WorldHostingHookHolder`/`SteamServerChannel`/
`SteamWorldHostingClientInitializer` already live in `platform/` and already
import both features' types where needed (`JoinPolicyBridge`,
`platform/fabric-1.21.11/src/main/java/de/lazuli/worldhosting/JoinPolicyBridge.java`);
this amendment adds no new cross-feature coupling beyond what
`JoinPolicyBridge` already established.

## UI
No new UI. "Invite to game" simply renders in its existing disabled
(greyed, `0xFF808080`, no hover highlight) state whenever `JoinPolicy ==
NOBODY`, using the exact same `isEnabled(2)`/`textColor` mechanism
`specification-invite-to-game.md`'s UI section already documents — no new
visual state, no tooltip (same Non-goal that spec already carries).

## Configuration
No new configuration field. `friends-sidebar.json`'s existing `joinPolicy`
field remains the sole source of truth
(`features/friends-sidebar/specification.md` v1.3 amendment); this document
only fixes how faithfully that value's live changes propagate to (a) the
"Invite to game" enablement and (b) the actual join-gate predicate.

## Events
No event bus (repo has none). `WorldHostingHookHolder.updateJoinPolicy(...)`
remains the single call-site notification mechanism for a live policy
change, invoked synchronously from the config-write callback exactly as
today; the only addition is that this call now also performs a
`canJoin.set(...)` on the shared `AtomicReference<LongPredicate>` (FR-IPG5).

## Networking
No new Steamworks call. Both fixes are pure local in-process state/predicate
plumbing:
- FR-IPG1's fix reads an already-in-memory `boolean` (`advertise`), no IPC.
- FR-IPG4's fix changes how a `LongPredicate` is dereferenced at the point
  an inbound P2P connection is accepted/rejected (`SteamServerChannel
  .acceptPeer`, already a local, no-network-I/O decision point) — the
  actual Steam P2P handshake/disconnect protocol
  (`SteamDisconnectProtocol.sendFin`, `SteamServerChannel.java:99`) is
  unchanged.

## Persistence
None. No new config field, no save state.

## Compatibility
- Must land identically across all three platform modules
  (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`) — `WorldHostingHookHolder`,
  `SteamServerChannel`, `SteamSession`, `SteamWorldHostingClientInitializer`,
  and `FriendsSidebarClientInitializer` each have one structural twin per
  module; all five files must change in lockstep on every module, mirroring
  every prior amendment's own three-platform-module discipline in this
  feature area.
- **Actively-evolving shared files**: re-run `git status`/`git diff` before
  editing any of `FriendSidebarWidget.java`, `FabricFriendsSidebarInjector.java`,
  `DropdownWidget.java`, `FriendsSidebarZOrder.java` (all modified in the
  working tree as of this writing, per an in-progress "dropdown polish"/
  "status-recolor" pass — see
  `features/friends-sidebar/implementation-plan-dropdown-polish.md` and
  `features/friends-sidebar/specification-status-recolor-ingame.md`) and
  `FriendContextMenuWidget.java`/`WorldInviteSender`/`HostingLifecycle`/
  `WorldHostingHookHolder`/`SteamServerChannel`/`SteamSession`/
  `SteamWorldHostingClientInitializer` before landing this amendment — none
  of the in-flight sidebar UI work touches the files this document changes,
  but confirm no incidental overlap (e.g. `FriendSidebarWidget`'s dropdown
  click path, which this document does not modify, only reads from) before
  implementation.
- No behavior change to the v1.3/v1.4 amendments' already-shipped, correct
  half (advertising suppression, `HostGateway`'s three-state semantics,
  `updateAdvertising`'s "never touches hosting" invariant) — this is
  additive/corrective, not a redesign.

## Performance
Negligible. FR-IPG1's fix adds one more already-in-memory volatile boolean
read per context-menu render frame (same cost class as the existing
`hosting` field read). FR-IPG4's fix changes a direct field dereference
(`canJoin.test(id)`) to one extra indirection
(`canJoin.get().test(id)` on an `AtomicReference<LongPredicate>`), evaluated
once per inbound join **attempt** (not per-tick, not per-frame) —
negligible; an `AtomicReference.get()` is a plain volatile read, no CAS
involved on the read path.

## Future Extensions
- Forcibly disconnecting already-connected peers when policy flips to
  `NOBODY` mid-session, if a future pass decides the "leave existing peers
  alone" baseline (settled as a non-goal here, see Non-goals and FR-IPG6)
  is wrong for real usage — a reversal remains straightforward later:
  `SteamSession.hasConnectedPeers()`/peer enumeration already exists as a
  seam, `WorldHostingHookHolder.hasConnectedPeers()`,
  `WorldHostingHookHolder.java:131-137`.
- Surfacing *why* "Invite to game" is disabled (e.g. "You've set who-can-join
  to Nobody" vs. "You are not hosting a world") once
  `FriendContextMenuWidget` gains any tooltip mechanism — today both reasons
  collapse into the same generic greyed-out state (same limitation
  `specification-invite-to-game.md`'s own Future Extensions already flags).

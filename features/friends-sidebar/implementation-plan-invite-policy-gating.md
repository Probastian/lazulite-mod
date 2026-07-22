# Implementation Plan — "Invite to Game" Join-Policy Gating & Live Join-Gate Fix

## Implements
- `features/friends-sidebar/specification-invite-policy-gating.md` — all of FR-IPG1 through FR-IPG7, the settled Public API shapes (items 1-4), and the Architecture "no session teardown" / "`AtomicReference<LongPredicate>`" decisions (not open for reconsideration here).

## Existing Implementation (research, this plan)

Re-confirmed directly against the working tree at planning time (not merely
trusted from the spec's own citations), across all three platform modules
(`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`) — all three are structural
twins for every file this plan touches (confirmed via `Grep` for
`canJoin|LongPredicate` returning the same 3-file set — `WorldHostingHookHolder.java`,
`SteamServerChannel.java`, `SteamSession.java` — on every module):

- **`WorldHostingHookHolder.java`** (`platform/<module>/src/main/java/de/lazuli/worldhosting/`):
  - `private static volatile LongPredicate canJoin;` (line 27).
  - `publish(HostingLifecycle, LongPredicate, boolean)` (lines 50-54) assigns
    `canJoin = joinGate;` directly — **not previously called out in the
    spec's line citations**, but it plainly writes the same field
    `updateJoinPolicy` writes, so it must be updated in lockstep (see Risks).
  - `updateJoinPolicy(LongPredicate, boolean)` (lines 65-72) reassigns
    `canJoin = joinGate;` (the frozen-predicate bug, FR-IPG4/spec Overview #2).
  - `isEnabled()` (lines 75-77) reads `canJoin != null` directly as part of
    its enabled-check.
  - `onWorldLoad()` (lines 95-112) passes the field's current value straight
    into `new SteamSession(handler, group, canJoin)` (line 108) — this is
    the exact "copied by value into an existing object" defect FR-IPG4
    documents.
  - No existing `isAdvertising()` accessor; `advertise` (line 28) is a
    private `volatile boolean` with no public getter today.
- **`SteamServerChannel.java`** (same package):
  - `private final LongPredicate canJoin;` (line 36), assigned once in the
    constructor (line 43).
  - `acceptPeer(SteamID)` (lines 94-132) tests it directly at
    `if (!canJoin.test(peerId))` (line 97).
- **`SteamSession.java`** (same package) — **not named in the parent task's
  explicit three-file list, but structurally load-bearing and must change
  too** (flagged as a plan-level finding, see Risks item 1):
  - `private final LongPredicate canJoin;` (line 37), assigned once in the
    constructor (line 55) from the value `WorldHostingHookHolder.onWorldLoad()`
    passes in.
  - `start()` (lines 69-138) constructs
    `serverChannel = new SteamServerChannel(this, networking, canJoin);`
    (line 113) — passes the same field straight through to
    `SteamServerChannel`'s constructor. For `SteamServerChannel.acceptPeer`
    to observe a live-updated gate (FR-IPG4), the *same*
    `AtomicReference<LongPredicate>` instance `WorldHostingHookHolder` holds
    must flow unchanged through `SteamSession`'s constructor and field into
    `SteamServerChannel`'s constructor and field — i.e. `SteamSession`'s
    `canJoin` field type must also become
    `AtomicReference<LongPredicate>`, purely as a pass-through (no new
    logic in `SteamSession` itself).
- **`SteamWorldHostingClientInitializer.java`** (`platform/<module>/src/main/java/de/lazuli/`):
  - `WorldHostingHookHolder.publish(lifecycle, hostGateway::canJoin, gatePolicy != JoinGatePolicy.NOBODY);`
    (line 97) — the call site whose second argument populates the field
    `publish()` assigns (see above finding).
  - The anonymous `WorldInviteSender` (lines 101-115): `isHosting()`
    (lines 102-105) currently `return lifecycle.currentStatus().hosting();`
    — exactly the pre-fix contract FR-IPG1 changes. `inviteFriend(long)`
    (lines 107-114) is unchanged by this plan (FR-IPG2) — it continues to
    check `lifecycle.currentStatus().hosting()` directly for its own
    narrower race-guard purpose, not `isHosting()`.
- **`WorldInviteSender.java`** (`api/src/main/java/de/lazuli/api/worldhosting/`,
  single copy, no per-platform duplication) — `isHosting()`'s Javadoc
  (lines 25-28) currently documents the pre-fix "active hosted session"
  contract only; FR-IPG1 requires a Javadoc-only update, no signature
  change.
- **`FriendContextMenuWidget.java`** (`platform/<module>/src/main/java/de/lazuli/friends/`)
  — confirmed at line 100:
  `case 2 -> worldInviteSender != null && worldInviteSender.isHosting();`
  — delegates to `isHosting()` exactly as the spec's FR-IPG3 states. **No
  change required or planned for this file.**

**Working-tree state, re-confirmed at planning time (`git status`):**
Currently modified/untracked: `api/.../FriendsSidebarZOrder.java`,
`platform/*/.../friends/FabricFriendsSidebarInjector.java`,
`platform/*/.../friends/FriendSidebarWidget.java`,
`platform/*/.../ui/DropdownWidget.java`, plus the untracked
`features/friends-sidebar/implementation-plan-dropdown-polish.md` (the
in-flight "dropdown polish" pass this plan's own spec's Compatibility
section flags). **None of the seven files this plan actually
creates/modifies (`WorldHostingHookHolder.java`, `SteamServerChannel.java`,
`SteamSession.java`, `SteamWorldHostingClientInitializer.java`,
`WorldInviteSender.java`, all times three platform modules where
applicable) appear in that modified/untracked set** — confirmed no
incidental overlap with the in-flight dropdown-polish work, per the spec's
Compatibility section instruction. `FriendContextMenuWidget.java` (also
flagged by the spec as worth re-checking) is likewise not in the modified
set. This should be re-verified with a fresh `git status` immediately
before implementation begins, since the dropdown-polish work may have
progressed between planning and implementation.

**Tests.** No existing test file references `WorldHostingHookHolder`,
`SteamServerChannel`, `SteamSession`, or the `WorldInviteSender` anonymous
impl (`Glob`/`Grep` for `**/*Test*.java` under each platform module's
`worldhosting`/root package finds none) — these classes have zero
JVM-testable coverage today. `features/steam-world-hosting`'s own
`services` layer (`HostGateway`, `HostingLifecycle`) does have tests
(implied by this repo's established `services`-layer convention, per the
`implementation-plan-dropdown-polish.md` precedent for
`FriendSidebarStateMachineTest`/`FriendsSidebarFacadeTest`); this plan does
not touch `HostGateway`/`HostingLifecycle` internals (spec Non-goals:
"No change to `HostGateway`'s three-state policy semantics").

## Files to Create
None. No new classes, packages, or Gradle modules (per spec Non-goals —
`AtomicReference<LongPredicate>` is the settled shape, added as a field
change to existing classes only).

## Files to Modify

Per platform module (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`),
identical logical change on all three:

1. **`platform/<module>/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java`**
   - Add `import java.util.concurrent.atomic.AtomicReference;`.
   - Change `private static volatile LongPredicate canJoin;` (line 27) to
     `private static final AtomicReference<LongPredicate> canJoin = new AtomicReference<>();`
     (a `final` reference to a mutable box, not a reassignable field —
     matches the settled Public API shape, item 3).
   - `publish(...)` (lines 50-54): change `canJoin = joinGate;` to
     `canJoin.set(joinGate);` (this call site was not explicitly named by
     the parent task list but writes the same field and must change in
     lockstep — see Existing Implementation finding above and Risks item 1).
   - `updateJoinPolicy(...)` (lines 65-72): change `canJoin = joinGate;` to
     `canJoin.set(joinGate);` (FR-IPG5) — the rest of the method
     (advertising-toggle logic) is unchanged.
   - `isEnabled()` (lines 75-77): change `canJoin != null` to
     `canJoin.get() != null` (the `AtomicReference` box itself is now never
     null once declared `final`; the check is on its held value).
   - `onWorldLoad()` (line 108): the `new SteamSession(handler, group, canJoin)`
     call now passes the `AtomicReference<LongPredicate>` field itself
     (unchanged syntactically — same identifier — but now carries the
     reference-not-value semantics FR-IPG4/FR-IPG7 require, since
     `SteamSession`'s constructor parameter type changes to match, see item
     3 below).
   - Add new accessor:
     ```java
     /** @return {@code true} if Rich Presence advertising is currently enabled. */
     public static boolean isAdvertising() {
         return advertise;
     }
     ```
     (Public API item 3; trivial accessor over the existing `advertise`
     volatile field, no other change to `advertise`'s own read/write sites).

2. **`platform/<module>/src/main/java/de/lazuli/worldhosting/SteamServerChannel.java`**
   - Add `import java.util.concurrent.atomic.AtomicReference;`.
   - Change `private final LongPredicate canJoin;` (line 36) to
     `private final AtomicReference<LongPredicate> canJoin;`.
   - Change the constructor parameter (line 40) from
     `LongPredicate canJoin` to `AtomicReference<LongPredicate> canJoin`
     (assignment at line 43 unchanged in form: `this.canJoin = canJoin;`).
   - Change `acceptPeer`'s call site (line 97) from
     `if (!canJoin.test(peerId))` to `if (!canJoin.get().test(peerId))`
     (FR-IPG4, Public API item 4) — no other change to `acceptPeer`
     (disconnect protocol, logging, child-channel wiring all unchanged).

3. **`platform/<module>/src/main/java/de/lazuli/worldhosting/SteamSession.java`**
   (pass-through only — required for the above two files to compile
   together and for FR-IPG4 to actually reach `acceptPeer`; not explicitly
   named in the parent task's file list, flagged as a planning-time
   addition, see Risks item 1):
   - Add `import java.util.concurrent.atomic.AtomicReference;`.
   - Change `private final LongPredicate canJoin;` (line 37) to
     `private final AtomicReference<LongPredicate> canJoin;`.
   - Change the constructor parameter (line 52) from `LongPredicate canJoin`
     to `AtomicReference<LongPredicate> canJoin` (assignment at line 55
     unchanged in form).
   - `start()`'s `new SteamServerChannel(this, networking, canJoin)` call
     (line 113) is syntactically unchanged (same identifier), now passing
     the `AtomicReference` through by reference rather than a
     once-captured `LongPredicate` by value — this is the exact mechanism
     that makes FR-IPG4/FR-IPG5 observable at the already-constructed
     `SteamServerChannel` instance.

4. **`platform/<module>/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java`**
   - The anonymous `WorldInviteSender`'s `isHosting()` (lines 102-105):
     change from
     ```java
     return lifecycle.currentStatus().hosting();
     ```
     to
     ```java
     return lifecycle.currentStatus().hosting() && WorldHostingHookHolder.isAdvertising();
     ```
     (FR-IPG1, Public API item 2 — `WorldHostingHookHolder` is already
     imported in this file, line 24, so no new import needed).
   - `inviteFriend(long)` (lines 107-114) and every other line in this
     file: **unchanged** (FR-IPG2 — the race-guard's own
     `lifecycle.currentStatus().hosting()` check is a distinct, narrower
     purpose and is not touched).
   - The `publish(...)` call site (line 97) itself is unchanged — it
     already passes `hostGateway::canJoin` and
     `gatePolicy != JoinGatePolicy.NOBODY`; only what `publish()` does
     internally with those arguments changes (item 1, above).

5. **`api/src/main/java/de/lazuli/api/worldhosting/WorldInviteSender.java`**
   (single copy) — Javadoc-only change (FR-IPG1, no signature change):
   update `isHosting()`'s doc comment (lines 27-28) from
   `@return true if the local player currently has an active hosted session.`
   to something documenting the new two-part contract, e.g.
   `@return true if the local player currently has an active hosted session
   AND the current join policy is not NOBODY.` Also update the class-level
   usage example (lines 16-23) if it implies the old single-condition
   contract (re-check exact wording at implementation time; no functional
   change either way).

## Files Not Modified (explicitly, per spec)
- `FriendContextMenuWidget.java` (all three modules) — FR-IPG3, confirmed
  no code change needed (Existing Implementation, above).
- `HostGateway.java`, `HostingLifecycle.java`, `HostedWorldStatus` — spec
  Non-goals, reused exactly as-is.
- `FriendsSidebarClientInitializer.java`, `FriendSidebarWidget.java`,
  `FabricFriendsSidebarInjector.java`, `DropdownWidget.java`,
  `FriendsSidebarZOrder.java` — no functional dependency on this plan's
  changes (the `updateJoinPolicy` call site inside
  `FriendsSidebarClientInitializer`'s `onJoinPolicyChanged` callback is
  unchanged in signature and call site, only what it internally does
  changes); these are also the in-flight dropdown-polish files flagged by
  the spec's Compatibility section — re-verify with `git status` before
  implementation that they remain untouched by this plan.

## Order / Dependencies of Changes
1. `SteamServerChannel.java` and `SteamSession.java` on a given platform
   module first, together (tightly coupled — `SteamSession` constructs
   `SteamServerChannel` and must pass a matching parameter type; doing
   either alone leaves that module non-compiling).
2. `WorldHostingHookHolder.java` on that same module next (depends on
   `SteamSession`'s new constructor signature from step 1; also introduces
   `isAdvertising()`, needed by step 3).
3. `SteamWorldHostingClientInitializer.java` on that same module last
   (depends on `WorldHostingHookHolder.isAdvertising()` existing from step 2).
4. Repeat steps 1-3 for each of the three platform modules — no
   cross-module compile dependency, but land all three together in the
   same change per the spec's Compatibility "must land identically across
   all three platform modules" requirement.
5. `WorldInviteSender.java` (single `api/` copy) — Javadoc-only, no
   compile dependency on any platform file; can be done at any point in
   the sequence, but logically pairs with step 3 since it documents the
   same contract change.

## Risks
1. **Two files not explicitly named by the parent task's instructions are
   load-bearing and must change anyway.** `WorldHostingHookHolder.publish(...)`
   (not `updateJoinPolicy`) is the *other* writer of the `canJoin` field —
   left as a plain reassignment (`canJoin = joinGate`) after `canJoin`'s
   type changes to `AtomicReference<LongPredicate>`, this would simply fail
   to compile (can't assign a `LongPredicate` to an `AtomicReference`
   field), forcing the fix regardless; calling it out explicitly here so
   implementation doesn't treat it as an oversight to "restore." Similarly,
   `SteamSession.java`'s own `canJoin` field/constructor parameter must
   change from `LongPredicate` to `AtomicReference<LongPredicate>` purely
   as a pass-through — omitting this file would either fail to compile (if
   `WorldHostingHookHolder.onWorldLoad()` tries to pass an
   `AtomicReference` into a `LongPredicate`-typed constructor parameter) or,
   worse, silently compile if someone instead called `.get()` too early
   inside `WorldHostingHookHolder` and passed a plain `LongPredicate`
   through — which would silently resurrect the exact frozen-predicate bug
   FR-IPG4 exists to fix. Both are flagged as required, not optional,
   additions to the file list in this plan.
2. **`isEnabled()`'s null-check semantics shift subtly.** Today
   `canJoin != null` is `false` until `publish()` has run once (field
   starts null). After the change, `canJoin` (the `AtomicReference` box)
   is never null once the class is loaded (initialized at declaration), so
   the check must move to `canJoin.get() != null` — an implementer who
   only skims for "add an `AtomicReference`" without touching `isEnabled()`
   would break the feature-enabled gate (`isEnabled()` would always return
   `true` once `lifecycle != null`, even before `publish()`/`canJoin.set(...)`
   ran, since an empty `AtomicReference<>()` is non-null but holds
   `null`). Explicitly listed in item 1's task above; call out in
   verification.
3. **Three-platform-module lockstep discipline.** Per spec Compatibility,
   all three modules' five affected files (4 platform + 1 shared `api`)
   must change identically; a partial rollout (e.g. only `fabric-26.2`
   fixed) would leave two modules with the pre-existing frozen-predicate
   bug live. Mitigated by doing all three modules' changes in the same
   commit/PR.
4. **Actively-evolving shared files, re-confirmed not touched.** Per the
   spec's own Compatibility section and this plan's Existing Implementation
   section, `git status` was re-run at planning time and confirms none of
   `FriendSidebarWidget.java`, `FabricFriendsSidebarInjector.java`,
   `DropdownWidget.java`, `FriendsSidebarZOrder.java`, or
   `FriendContextMenuWidget.java` are touched by this plan — but since the
   in-flight dropdown-polish work may progress before this plan is
   implemented, re-run `git status`/`git diff` again immediately before
   starting implementation to reconfirm no overlap, per the spec's own
   instruction.
5. **No forcible-disconnect / session-teardown scope creep.** Because this
   plan touches `SteamSession`/`SteamServerChannel` construction plumbing,
   an implementer could be tempted to also add peer re-validation or
   disconnect-on-policy-change logic while already in this code (a natural
   "while I'm here" temptation). Spec Non-goals/FR-IPG6 explicitly
   prohibit this — `SteamSession`'s change in this plan is a pure type
   change on an existing field/parameter, with zero new logic added to
   `stop()`, `start()`, or any children-enumeration code path.

## Dependencies
No new external (non-Fabric) dependency. `java.util.concurrent.atomic.AtomicReference`
is part of the JDK standard library (`java.base` module, available since
Java 5) already on every platform module's classpath — no Maven Central
lookup or `build.gradle` change required, consistent with the spec's own
Networking/Dependencies-adjacent framing ("No new Steamworks call... pure
local in-process state/predicate plumbing").

## Test Strategy
- **New unit tests, JVM-only, no Minecraft/Steamworks classpath required**
  for the plain predicate-dispatch/atomic-reference logic this plan
  introduces, consistent with this repo's existing convention of unit-testing
  its plain-JVM `services`-layer logic (`FriendSidebarStateMachineTest`,
  `FriendsSidebarConfigIOTest`, `FriendsSidebarFacadeTest`) while leaving
  rendering/widget code to manual verification only. Since
  `WorldHostingHookHolder`/`SteamServerChannel`/`SteamSession` live in
  `platform/<module>/src/main/java/de/lazuli/worldhosting/` (not a
  `features/*/services` module) and depend on Steamworks-native types
  (`SteamID`, `SteamNetworking`) that cannot run headless in a unit test,
  a full `SteamServerChannel`/`SteamSession` unit test is not feasible —
  instead, add one small, targeted, dependency-free test that isolates the
  live-indirection mechanism itself:
  - **New test file:** a JVM-only test exercising the exact shape being
    added — an `AtomicReference<LongPredicate>` written from one "thread"
    (a plain sequential call in the test, no real threading needed to
    prove correctness of the dispatch mechanism) and read via
    `.get().test(id)` from another call site — verifying:
    1. Given an initial predicate `p1` set into the reference, `.get().test(id)`
       reflects `p1`'s answer.
    2. After `.set(p2)`, a **subsequent** `.get().test(id)` call
       immediately reflects `p2`'s answer (the exact defect being fixed —
       a `final LongPredicate` captured before the `.set(p2)` would still
       show `p1`'s answer; a correct fix must not).
    3. `isEnabled()`-equivalent semantics: `new AtomicReference<LongPredicate>()`
       (unset) `.get() != null` is `false`; after `.set(anything)` it is
       `true` — directly testing Risk 2's null-check-migration concern in
       isolation.
  - Where to place it: since none of `WorldHostingHookHolder`/
    `SteamServerChannel`/`SteamSession` are structured for JUnit today (no
    existing `src/test/java` tree under any platform module's
    `worldhosting` package — confirm at implementation time via `Glob`), and
    since the actual production classes are not trivially instantiable
    without Steamworks-native handles, this test is most practically
    written as a small, standalone test of the `AtomicReference<LongPredicate>`
    *mechanism* in isolation (as described above) rather than a full
    `WorldHostingHookHolder`/`SteamServerChannel` integration test. If a
    platform module's `build.gradle` has no existing `src/test/java` +
    JUnit dependency wired up (re-check at implementation time — the
    `features/*` modules do, per `FriendSidebarStateMachineTest`; the
    `platform/*` modules' current test wiring is unconfirmed as of
    planning and must be checked before assuming a test can be added there
    without a build-file change), place this test in whichever module
    already has JUnit configured, or flag to the user that a
    `platform/<module>/build.gradle` test-dependency addition is needed
    first — this is an implementation-time build-config check, not a
    planning-time design change.
  - **No new automated test for `WorldHostingHookHolder.isAdvertising()`
    or the `SteamWorldHostingClientInitializer` anonymous `WorldInviteSender.isHosting()`
    change** — both are one-line reads of existing state with no branching
    logic complex enough to warrant a dedicated unit test beyond what
    manual verification (below) already covers, and both depend on
    `HostingLifecycle`/Steamworks-backed collaborators not easily
    constructed in isolation without more test-double infrastructure than
    this narrow fix justifies.
- **Manual, per-platform-module in-game verification required** (rendering/
  live-session behavior, not unit-testable, consistent with this repo's
  established convention per `implementation-plan-dropdown-polish.md`):
  1. Host a world, set `JoinPolicy` to `Nobody` before or after a friend
     opens the context menu — confirm "Invite to game" is disabled
     (greyed, `FriendContextMenuWidget`'s existing disabled-state styling)
     whenever the local policy is `Nobody`, on all three platform modules
     (G1, FR-IPG1).
  2. With a world already loaded and a friend already able to join
     (`Friends` or `Everyone`), flip `JoinPolicy` to `Nobody` mid-session,
     then have a *different*, previously-unconnected friend attempt to
     join — confirm the join attempt is now rejected (FR-IPG4/G2), not
     merely that Rich Presence/"Join Game" visibility changed.
  3. Flip `JoinPolicy` from `Nobody` back to `Friends`/`Everyone`
     mid-session (world still loaded, no reload) — confirm a new friend's
     join attempt is now accepted immediately, without restarting the
     world (FR-IPG4/G2, the core root-cause fix).
  4. Confirm an already-connected peer is never disconnected when policy
     flips to `Nobody` mid-session (FR-IPG6, G3) — the peer's existing
     connection remains open and functional.
  5. Confirm "Invite to game" re-enables the moment `JoinPolicy` moves off
     `Nobody` while a session is still hosting (G1, inverse of check 1).
  6. Repeat checks 1-5 on all three platform modules
     (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`).

## Acceptance Criteria
Per `features/friends-sidebar/specification-invite-policy-gating.md`:
- FR-IPG1: `WorldInviteSender.isHosting()` (Javadoc) documents, and
  `SteamWorldHostingClientInitializer`'s impl (all three modules) returns,
  `false` whenever `JoinPolicy == NOBODY`, even with an otherwise-active
  hosted session.
- FR-IPG2: `inviteFriend(long)`'s existing race-guard behavior is byte-for-byte
  unchanged on all three modules.
- FR-IPG3: `FriendContextMenuWidget.java` has zero diff on all three
  modules.
- FR-IPG4: a `JoinPolicy` change while a world is loaded is observed by the
  very next `SteamServerChannel.acceptPeer(...)` call (manual verification
  checks 2-3), via the shared `AtomicReference<LongPredicate>` mechanism
  (unit test, above).
- FR-IPG5: `WorldHostingHookHolder.updateJoinPolicy(...)` continues to
  update advertising exactly as before, and additionally
  `.set(...)`s the shared `AtomicReference`.
- FR-IPG6: manual verification check 4 passes — no disconnect of existing
  peers on a `Nobody` transition.
- FR-IPG7: `onWorldLoad()`'s initial-value behavior is unchanged when no
  world was previously loaded (no regression to first-load hosting).
- Public API items 1-4: exact shapes as specified —
  `AtomicReference<LongPredicate>` (not `Supplier`), `isAdvertising()`
  accessor, `canJoin.get().test(peerId)` call-site shape.
- Compatibility: all changes land identically across `fabric-1.21.11`,
  `fabric-26.1`, `fabric-26.2`; zero incidental diff to any of the
  in-flight dropdown-polish files (`FriendSidebarWidget.java`,
  `FabricFriendsSidebarInjector.java`, `DropdownWidget.java`,
  `FriendsSidebarZOrder.java`) or to `FriendContextMenuWidget.java`.
- New unit test (Test Strategy, above) passes and demonstrates the exact
  before/after distinction FR-IPG4 fixes (a `.set()` after initial read is
  observed by a subsequent `.get()`, not just the first one).
- Manual in-game verification (Test Strategy checks 1-6) passes on all
  three platform modules.

# Steam Rich Presence — Completion Implementation Plan

Source spec: `docs/specs/steam-rich-presence.md` (approved). Covers FR1-FR4.
Rich Presence itself (`features/rich-presence`) is untouched by this plan.

## Rescoping note (real App ID 5052800 now live)

The project now ships under its real Steam App ID (`5052800`), replacing the
old dev/test App ID `480` (Spacewar). A read-only audit confirmed there is no
`480` dev-fallback or gating logic left in live source — `SteamAppIdResolver`
already defines `DEFAULT_APP_ID = 5052800L`. The only remaining `480`
references are in historical docs (`services/verification-report.md`,
`services/specification.md`, `services/implementation-plan.md`) and an
unrelated `SpacewarAchievementMapping.java` (achievement-icon reference data,
not this project's App ID) — both are out of scope and untouched by this
plan. Consequently, Task 5 below is a **manual verification task**, not an
implementation task: the real App ID is what unblocks live, cross-account
Rich Presence testing (and any Steamworks partner-site configuration it
depends on, e.g. localization tokens / friend-invite settings for
`5052800`), not a code change.

## Planning-phase finding: FR4 (FR-RP6) is already implemented

Before scoping tasks: `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/.../friends/FriendSidebarWidget.java`
already does exactly what FR4 asks for. The own-profile row draw call already
passes `richPresenceFacade.localPresenceStatus()` as `ownRichPresenceOverride`
into `drawRow`, and `drawRow` already resolves the row label as:

```java
String status = ownRichPresenceOverride
        .or(() -> facade.richPresenceStatus(friend.steamId64()))
        .orElseGet(() -> facade.stateMachine().statusLabel(friend.personaState(), inGame));
```

This is present, identical in shape, in all three platform modules
(verified by direct read of all three files, not inferred). It already
satisfies FR4.1-FR4.3: the facade's status takes precedence when present,
falling back to the generic `"In Game"` (`FriendSidebarStateMachine.statusLabel(int, boolean)`)
only when empty.

**Consequence for this plan**: FR4 requires no code change. Task 6 below is
verification-only (confirm the existing wiring against FR4.1-FR4.3's exact
wording and add a short note to `features/rich-presence/verification-report.md`
closing FR-RP6), not implementation. If the verifier phase finds a genuine gap
here, treat it as a bug to report back, not evidence this plan under-scoped —
this plan's author read the current file content directly.

## Task 1 — `RichPresenceLimits` (FR2, Public API item 2)

**Create**: `services/src/main/java/de/lazuli/services/steamworks/RichPresenceLimits.java`

```java
public final class RichPresenceLimits {
    public static final int MAX_KEY_LENGTH = 64;
    public static final int MAX_VALUE_LENGTH = 256;
    public static final int MAX_KEY_COUNT = 20;
    private RichPresenceLimits() {}
    public static boolean keyWithinLimit(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH;
    }
    public static boolean valueWithinLimit(String value) {
        return value != null && value.length() <= MAX_VALUE_LENGTH;
    }
}
```
Use `String.length()` (UTF-16 code units), not UTF-8 byte length — every
existing caller (`RichPresencePublisher`, `HostingLifecycle`) only ever
produces ASCII/localized-but-short strings; spec FR2.1 says "UTF-8 bytes/chars"
loosely, and `String.length()` is the same discipline already used elsewhere
in this class (plain-JVM, no encoding step). Document this choice in the
class Javadoc so it isn't re-litigated later.

**Modify**: `SteamworksSteamFriendsGateway.setLocalRichPresence` (line 211)
to guard before the native call:

```java
@Override
public boolean setLocalRichPresence(String key, String value) {
    if (!RichPresenceLimits.keyWithinLimit(key)) {
        warn("Rich Presence key exceeds " + RichPresenceLimits.MAX_KEY_LENGTH
                + " chars, not set: \"" + key + "\"");
        return false;
    }
    if (!RichPresenceLimits.valueWithinLimit(value)) {
        warn("Rich Presence value for key \"" + key + "\" exceeds "
                + RichPresenceLimits.MAX_VALUE_LENGTH + " chars, not set.");
        return false;
    }
    try {
        return steamFriends.setRichPresence(key, value);
    } catch (RuntimeException e) {
        warn("Failed to set local Rich Presence key \"" + key + "\": " + e.getMessage());
        return false;
    }
}
```
No truncation (FR2.2) — the guard rejects and warns, never mutates the string.

**Modify (Javadoc only)**: `SteamFriendsGateway#setLocalRichPresence` Javadoc
(line ~106-113) gains a `@return` note that `false` also now means "input
exceeded Valve's key/value length limit," and a class-level or method-level
note documenting the 20-distinct-keys ceiling (FR2.3 — comment only, no
enforcement code; this project only ever writes 4 keys after Task 2).

**No interface signature change** — `setLocalRichPresence(String, String)` is
unchanged; only the implementation gains the guard, matching spec Public API
item 1.

### Tests (Task 1)
**Create**: `services/src/test/java/de/lazuli/services/steamworks/RichPresenceLimitsTest.java`
- key at exactly 64 chars → `keyWithinLimit` true; 65 chars → false.
- value at exactly 256 chars → `valueWithinLimit` true; 257 chars → false.
- null key/value → false (defensive; `setLocalRichPresence` callers never
  pass null today, but the helper should not NPE).

**Create**: `services/src/test/java/de/lazuli/services/steamworks/SteamworksSteamFriendsGatewayTest.java`
if no such test file exists yet (check first) — since `SteamworksSteamFriendsGateway`
directly owns real `SteamFriends`/`SteamUtils`/`SteamUser` instances constructed
in its constructor, it is **not** constructible in a plain-JVM test without a
real Steam client running. Two options, pick during implementation:
  (a) extract the guard into a small package-private static method the gateway
      calls, and unit-test *that* static method directly (simplest — no mocking
      needed since `RichPresenceLimitsTest` already covers the boundary logic
      that this class merely calls), or
  (b) skip a dedicated gateway-level test entirely, since the boundary logic
      is fully covered by `RichPresenceLimitsTest` and the gateway method body
      is now a thin two-line guard plus the pre-existing try/catch (already
      untested today, per the existing file — no regression in test
      discipline).
  Recommendation: (b) — matches FR2.4's actual ask ("boundary cases... against
  a fake/mock `SteamFriends`-equivalent seam"; `SteamFriendsGateway` already
  *is* that seam via `NoopSteamFriendsGateway`/a hand-rolled fake in
  `RichPresencePublisherTest`). Confirm `RichPresencePublisherTest`'s existing
  `ScriptedTracker`/fake-gateway pattern and reuse it if a fake
  `SteamFriendsGateway` already exists there; if so, add one boundary
  assertion there instead of a new file.

## Task 2 — Player-group keys, host side (FR3.1/FR3.3/FR3.4)

**Modify**: `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/HostingLifecycle.java`

Add two new Valve-reserved key constants alongside `CONNECT_KEY`:
```java
static final String PLAYER_GROUP_KEY = "steam_player_group";
static final String PLAYER_GROUP_SIZE_KEY = "steam_player_group_size";
```

Grow the API surface (spec Public API item 3 — additive, source-compatible):
- `start(boolean advertise)` gains an overload or the existing method keeps
  its signature and a new `updatePlayerCount(int count)` method is added
  (recommended — avoids touching the two existing call sites in
  `WorldHostingHookHolder.onWorldLoad()` (`lifecycle.start(advertise)`, line
  112) and `WorldHostingHookHolder.updateJoinPolicy` at all). Concretely:
  - `start(boolean advertise)`: when `advertise`, also write
    `PLAYER_GROUP_KEY = String.valueOf(id)` and `PLAYER_GROUP_SIZE_KEY = "1"`
    (host alone counts as size 1 at start, before any peer has joined) in the
    same call that sets `CONNECT_KEY`. When not advertising, clear as today
    (`clearLocalRichPresence()` already clears all keys, including these two,
    since `SteamFriends.clearRichPresence()` clears every key the process has
    set — confirm this in Task 2's test, not assumed).
  - New `public void updatePlayerCount(int count)`: no-op if `!hosting`;
    otherwise re-sets `PLAYER_GROUP_KEY` (unchanged, still `localSteamId64`)
    and `PLAYER_GROUP_SIZE_KEY = String.valueOf(count)`. Idempotent, safe to
    call every time the host's connected-peer count changes (FR3.4). Does
    **not** touch `CONNECT_KEY`.
  - `updateAdvertising(boolean advertise)`: mirror the same group-key
    set/clear alongside the existing `CONNECT_KEY` set/clear, since
    FR3.5 requires both to travel together.
  - `stop()`: unchanged in shape — `clearLocalRichPresence()` already clears
    all four keys in one call; add a code comment noting this now also covers
    the two new keys (FR3.3), so a future reader doesn't assume it's missing.

**Modify**: `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java`

This is the FR3.4 hook: `WorldHostingHookHolder` already owns the host-side
`SteamSession` instance (field `session`, `onWorldLoad()`/`onWorldStop()`) and
`SteamSession.children` (a `CopyOnWriteArrayList<SteamNettyChannel>`) is the
existing peer-connected bookkeeping (`hasConnectedPeers()` at line ~60 already
reads `!children.isEmpty()`). Concretely:
- Add a small poll in the same per-tick cadence this feature already has
  (`SteamWorldHostingClientInitializer`'s `ClientTickEvents.END_CLIENT_TICK.register(client -> scanner.tick())`,
  line 147) — extend that lambda (or add a sibling registration) to also call
  a new `WorldHostingHookHolder.pollPlayerCount()` that compares
  `session.children.size() + 1` (host counts as 1) against a last-seen count
  and calls `lifecycle.updatePlayerCount(newCount)` only on change (avoid a
  redundant Steam API call every tick — mirrors this class's existing
  idempotence discipline, e.g. `updateJoinPolicy`'s `changed` guard at line 68).
- Do this identically in all three platform modules (`WorldHostingHookHolder`
  is duplicated per-module, confirmed by the earlier glob).

### Tests (Task 2)
**Create/extend**: `features/steam-world-hosting/src/test/java/de/lazuli/features/worldhosting/services/HostingLifecycleTest.java`
(check if it exists first; if not, this is a new file — `HostingLifecycle`
only depends on the already-fakeable `SteamFriendsGateway` interface, so a
hand-rolled fake/recording `SteamFriendsGateway` is sufficient, no Steam
client needed):
- `start(true)` sets `connect`, `steam_player_group` (= local SteamID64 as a
  string), and `steam_player_group_size = "1"` in one call.
- `start(false)` clears rich presence, sets none of the three keys.
- `updatePlayerCount(3)` after `start(true)` re-sets `steam_player_group_size = "3"`
  and leaves `steam_player_group` unchanged; no-op (records nothing) if called
  before `start`/after `stop`.
- `updateAdvertising(false)` then `updateAdvertising(true)` round-trips: group
  keys go away then come back with the same group id.
- `stop()` clears all previously-set keys (assert against the fake gateway's
  recorded "currently set keys" map, not just call count).

## Task 3 — Player-group keys, guest side (FR3.2)

This is the one genuinely new piece of behavior — currently, a client that
joins a Steam-World-Hosting session (`SteamAmbientSession.connectToSteamPeer`,
`platform/*/src/main/java/de/lazuli/worldhosting/SteamAmbientSession.java`)
sets **no** Rich Presence keys of its own today (confirmed by reading that
file — no `setLocalRichPresence`/`RichPresence` reference in it).

**Create**: `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/JoinedSessionPresence.java`
— a small plain-JVM collaborator, sibling to `HostingLifecycle`, **not** a
method added to `HostingLifecycle` itself (that class's Javadoc already
frames it as the *host*-side collaborator; a guest is not "hosting" — keeping
these separate avoids overloading `hosting`/`localSteamId64` semantics with a
second, guest meaning). Shape:
```java
public final class JoinedSessionPresence {
    private final SteamFriendsGateway gateway;
    private volatile boolean joined;
    public JoinedSessionPresence(SteamFriendsGateway gateway) { this.gateway = gateway; }
    public void onJoined(long hostSteamId64, int initialPlayerCount) { ... } // sets both group keys, joined=true
    public void updatePlayerCount(int count) { ... } // no-op unless joined
    public void onLeft() { ... } // clears; joined=false
}
```
Reuses `HostingLifecycle.PLAYER_GROUP_KEY`/`PLAYER_GROUP_SIZE_KEY` constant
values (either widen their visibility to package-private-shared via a small
shared constants holder, e.g. move both constants into a new
`PlayerGroupPresenceKeys` tiny constants class both `HostingLifecycle` and
`JoinedSessionPresence` reference, avoiding string-literal duplication across
host/guest — this is a planning-phase-recommended cleanup, not required for
correctness).

**Modify**: `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/worldhosting/SteamAmbientSession.java`
- Construct/hold a `JoinedSessionPresence` (via whichever composition root
  already constructs `SteamAmbientSession.INSTANCE` — confirm exact
  construction site during implementation; it's a singleton-style `INSTANCE`
  per the earlier read) and call `onJoined(hostSteamId64, 2)` once the P2P
  connection actually succeeds (host + this guest = 2 at minimum; refined by
  the count sync below). Call `onLeft()` on disconnect from the Steam session
  (mirror whatever "left this session" signal already exists for cleanup
  there today — inspect the file's disconnect/cleanup path during
  implementation, not assumed here).

**FR3.4 count sync for the guest** (the one open design question the spec
explicitly deferred to planning): recommend **not** inventing a new network
channel/packet for this. Minecraft's vanilla client already receives the
player list (tab-list `PlayerInfo` updates) for every player on the server,
including a Steam-World-Hosting session's peers, since the Steam P2P channel
carries the *entire* vanilla protocol (confirmed by this feature's own
framing — it tunnels Minecraft's real Netty pipeline). So: the guest can
derive its own `steam_player_group_size` from the client's own connected-player
list size (`Minecraft.getInstance().getConnection().getOnlinePlayerIds().size()`
or the version-appropriate equivalent — exact accessor name is a per-platform,
per-Minecraft-version detail to confirm against each module's already-pinned
Yarn/Mojmap during implementation, consistent with this codebase's existing
per-version-API-lookup discipline) polled on the same tick cadence as
`ClientTickEvents.END_CLIENT_TICK`, diffed against last-seen count exactly
like Task 2's host-side poll. This avoids adding a bespoke S2C packet purely
to duplicate information Minecraft already synchronizes, and keeps FR3
strictly additive (FR3.5) with zero networking-layer changes.

### Tests (Task 3)
**Create**: `features/steam-world-hosting/src/test/java/de/lazuli/features/worldhosting/services/JoinedSessionPresenceTest.java`
- `onJoined(hostId, 2)` sets `steam_player_group = hostId` (as string) and
  `steam_player_group_size = "2"`.
- `updatePlayerCount(3)` after `onJoined` updates only the size key.
- `updatePlayerCount` before `onJoined`/after `onLeft` is a no-op (matches
  `HostingLifecycle.updatePlayerCount`'s guard).
- `onLeft()` clears rich presence.
- Symmetric-value test: given the same `hostId`, `HostingLifecycle.start(true)`'s
  recorded `steam_player_group` value and `JoinedSessionPresence.onJoined(hostId, ...)`'s
  recorded value are byte-identical strings (guards FR3.2's "same value"
  requirement against, e.g., accidental `"STEAM_"` prefixing or differing
  radix/formatting between the two call sites).

## Task 4 — FR2 ceiling documentation (FR2.3)

**Modify**: `SteamFriendsGateway.java` Javadoc on `setLocalRichPresence` (or a
new class-level `@apiNote`-style paragraph) — document: "This process sets at
most 4 Rich Presence keys total (`status`, `connect`,
`steam_player_group`, `steam_player_group_size`), well under Valve's
documented 20-key ceiling; a future feature adding a 5th+ key should confirm
the running total stays under 20 (`RichPresenceLimits.MAX_KEY_COUNT`)." No
runtime counter is added (per spec FR2.3 — doc-only).

## Task 5 — FR1 manual live-verification protocol (doc only, no code; manual test task)

This is not a functional gap — it is a manual test now unblocked by the real
App ID `5052800` going live (see Rescoping note above). Nothing in source
needs to change for FR1; the task is to hand the user a concrete checklist
they can execute themselves against real Steam accounts and the real App ID,
now that it exists.

**Create**: `features/rich-presence/live-verification-protocol.md` (a
separate doc from `verification-report.md`, since this one is a *procedure*
the user runs, not a result the assistant produced — matches FR1.3's
"or append to the existing verification-report.md" alternative). Keep "how to
run it" (this protocol doc) and "what happened when it was run" (a results
section appended to `verification-report.md` once actually run) distinct,
consistent with this repo's existing split between `*-plan.md` and
`verification-report.md`.

Contents (planning-phase table of contents — the planner does not write the
final prose, the user/implementer does when this task is executed):
1. **Preconditions** (FR1.2): two real Steam accounts, Steam-friends of each
   other, both running the *shipped jar* (not `runClient`) with the real App
   ID `5052800` and `steam_appid.txt` present per
   `docs/specs/steam-appid-runtime-bootstrap.md` — link that spec, don't
   restate it.
2. **FR1.1(a) — status visibility**: Account A launches, reaches main menu,
   loads a singleplayer world, opens the pause menu. Account B (a Steam
   friend) opens Steam's Friends List / hovers A's entry and records the
   displayed status text at each of those three tiers, comparing against
   what `features/rich-presence/specification.md`'s tier table predicts for
   that exact sequence. A viewing their *own* "View my profile" panel is an
   acceptable substitute per FR1.1(a) if a second machine/account is
   unavailable for a given sub-check, but the plan recommends running the
   real two-account case at least once since it's the only way to exercise
   Steam's actual cross-client render path.
3. **FR1.1(b) — connect key / Join Game button**: Account A starts hosting
   (Steam World Hosting), confirm B's Friends List entry for A shows the
   native "Join Game" affordance; A stops hosting, confirm it disappears
   within one Rich Presence refresh cycle (`requestFriendRichPresence`
   cadence — check `HostingPresenceScanner`'s poll interval for the expected
   latency to document as a "wait up to N seconds" note).
4. **FR1.1(c) — end-to-end join**: B clicks "Join Game" on A's entry, confirm
   `onGameRichPresenceJoinRequested` fires and B actually connects into A's
   world (not just that the callback fires — a real connect).
5. **FR3 spot-check** (new in this spec, worth folding into the same manual
   pass since it needs the same two-account setup): once B has joined A,
   both A's and B's Friends List entries (viewed from a third account, or
   each viewing the other) should show a "playing in a group" indicator.
6. **Recording template**: a plain pass/fail table per sub-case plus a
   screenshot-attachment convention (file naming, where to store them —
   recommend `features/rich-presence/live-verification-screenshots/` or
   inline in the results note, planner defers final choice to whoever runs
   it).
7. **Results section** — once actually run, append a dated entry to
   `features/rich-presence/verification-report.md` (new `## Live
   Verification (manual)` section) with each sub-case's outcome, mirroring
   that file's existing PASS/FAIL convention.

No code changes in this task. The user runs this themselves per the
project's standing "no launching Minecraft during remote control" constraint
— reiterate that note prominently at the top of the protocol doc itself, not
just in this plan, so it's visible to whoever executes it later.

## Task 6 — FR4 verification (doc-only, confirmed already implemented)

FR4/FR-RP6 is already fully implemented — confirmed by direct read of all
three platform `FriendSidebarWidget.java` files (see finding above): the
own-profile row already prefers `richPresenceFacade.localPresenceStatus()`,
falling back to `facade.richPresenceStatus(friend.steamId64())`, falling back
to the generic `statusLabel(personaState, inGame)`, matching FR4.1-FR4.3's
precedence exactly. **No code change, no new tests.**

The only remaining action: update
`features/rich-presence/verification-report.md` with a short new entry
marking FR-RP6/FR4 as done/verified, citing the file:line references from the
finding above, so it doesn't get re-flagged as an open gap in a future spec
pass.

## Files touched (summary)

**Create**
- `services/src/main/java/de/lazuli/services/steamworks/RichPresenceLimits.java`
- `services/src/test/java/de/lazuli/services/steamworks/RichPresenceLimitsTest.java`
- `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/JoinedSessionPresence.java`
- `features/steam-world-hosting/src/test/java/de/lazuli/features/worldhosting/services/JoinedSessionPresenceTest.java`
- `features/steam-world-hosting/src/test/java/de/lazuli/features/worldhosting/services/HostingLifecycleTest.java` (if not already present)
- `features/rich-presence/live-verification-protocol.md`
- (optional cleanup) `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/PlayerGroupPresenceKeys.java`

**Modify**
- `services/src/main/java/de/lazuli/services/steamworks/SteamworksSteamFriendsGateway.java` (Task 1)
- `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java` (Javadoc, Tasks 1 & 4)
- `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/HostingLifecycle.java` (Task 2)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java` (Task 2)
- `platform/fabric-26.1/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java` (Task 2)
- `platform/fabric-26.2/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java` (Task 2)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/worldhosting/SteamAmbientSession.java` (Task 3)
- `platform/fabric-26.1/src/main/java/de/lazuli/worldhosting/SteamAmbientSession.java` (Task 3)
- `platform/fabric-26.2/src/main/java/de/lazuli/worldhosting/SteamAmbientSession.java` (Task 3)
- `platform/*/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java` (Task 2 tick-hook wiring, all three)
- `features/rich-presence/verification-report.md` (Tasks 5 & 6, appended sections)

**Not modified** (explicitly, per spec Non-goals): `ConnectStringCodec`,
`RichPresencePublisher`, `RichPresenceFacade`/`RichPresenceFacadeImpl`,
`FriendSidebarWidget.java` (all three — already correct, Task 6 is
verify-only), any `steam_appid.txt`/App-ID logic, any partner-site config.

## Risks / Dependencies

1. **FR3.4 count-sync mechanism (guest side) is a genuine new design
   decision**, not a copy of existing code — the recommended vanilla-player-list
   approach (Task 3) needs its exact accessor confirmed per-platform-module
   during implementation (Fabric/Yarn mappings differ slightly across
   `fabric-1.21.11`/`fabric-26.1`/`fabric-26.2`, consistent with this
   project's existing per-module API-name risk pattern seen elsewhere, e.g.
   `ServerJoinPresenceClientInitializer`'s own `HostPortSplit` comment about
   "avoiding depending on an unconfirmed-this-pass accessor name"). If that
   accessor doesn't cleanly expose a count (e.g., only exposes full
   `PlayerInfo` objects requiring iteration), fall back to counting
   `session.children.size()` — but note this field lives in `SteamSession`
   which is **host-only**; the guest genuinely has no equivalent object today,
   so the player-list-count path is the only viable option without adding a
   packet. Flag to the implementer: if neither works cleanly, escalate back
   to spec/user rather than inventing a packet unilaterally (spec's FR3.4
   explicitly left this open).
2. **Constant sharing between `HostingLifecycle` and `JoinedSessionPresence`**:
   duplicating the two key-name string literals (`"steam_player_group"`,
   `"steam_player_group_size"`) in two classes is a minor but real risk of
   future drift (a typo in one not caught since nothing cross-checks them
   except the new symmetric-value test in Task 3). The plan recommends the
   optional `PlayerGroupPresenceKeys` shared-constants class; if the
   implementer judges it unnecessary ceremony for two constants, the
   symmetric-value unit test (Task 3) is the minimum acceptable substitute
   safety net — do not skip both.
3. **`clearLocalRichPresence()` clearing behavior is assumed, not yet
   verified against real steamworks4j semantics** — `SteamFriends.clearRichPresence()`
   is documented by Valve to clear *all* keys the process has set, which is
   why `HostingLifecycle.stop()` today only calls it once for `connect` and
   this plan assumes it'll transparently also clear the two new keys once
   Task 2 starts setting them. If real-world/live-test (Task 5) behavior
   differs, that's a live-verification finding requiring a spec amendment,
   not a Task 2 implementation bug — call this out explicitly in
   `live-verification-protocol.md`'s FR3 spot-check (item 5) so the tester
   knows to specifically check that stopping hosting clears the *group*
   indicator too, not just the connect button.
4. **`WorldHostingHookHolder` is triplicated per platform module** (already
   true today) — Task 2's tick-poll addition must be applied identically
   three times; a copy-paste miss in one module is a silent regression only
   caught by that module's own test suite (each platform module's tests, if
   any exist at this layer — confirm during implementation whether
   `WorldHostingHookHolder` itself has any existing test coverage; if none,
   this is integration-level/manual-only risk, consistent with the rest of
   this mixin-adjacent static-holder class which today has no unit tests
   either).
5. **No regression to `features/rich-presence`'s PASS status**: Task 1's
   guard only ever returns `false` for input that would already have silently
   failed via `SteamFriends.setRichPresence` returning `false` — per spec
   Compatibility, the existing tier system has never produced an over-length
   string, so no existing passing test should newly fail. Run the full
   `rich-presence` and `steam-world-hosting` module test suites after Task 1
   and Task 2 respectively to confirm.
6. **FR4 "already implemented" finding could be wrong if this plan
   mis-read the files** — low risk (direct reads of all three
   `FriendSidebarWidget.java` files were performed, all three showed
   identical `ownRichPresenceOverride` wiring), but the verifier phase should
   still independently re-read all three files rather than trusting this
   plan's transcription, per this project's own verification discipline.

## Test Strategy Summary

| Area | Test type | Location |
|---|---|---|
| FR2.1/FR2.4 boundary logic | Plain-JVM unit test | `RichPresenceLimitsTest` |
| FR2.1 gateway integration | Optional; likely covered transitively | fake-gateway pattern in `RichPresencePublisherTest`, reused if present |
| FR3.1/FR3.3/FR3.4 host-side | Plain-JVM unit test against fake `SteamFriendsGateway` | `HostingLifecycleTest` |
| FR3.2/FR3.4 guest-side | Plain-JVM unit test against fake `SteamFriendsGateway` | `JoinedSessionPresenceTest` |
| FR3.2 symmetric-value guard | Plain-JVM unit test (cross-class assertion) | `JoinedSessionPresenceTest` |
| FR1 (all sub-cases) | Manual, live Steam client, two accounts | `features/rich-presence/live-verification-protocol.md` → results appended to `verification-report.md` |
| FR4 | Re-verification of existing code (no new test needed — already exercised implicitly by any existing `FriendSidebarWidget`/facade tests, if present; confirm during verification phase whether any exist) | N/A |

Full existing suites (`rich-presence`, `steam-world-hosting`, `services`)
must stay green after every task — no existing test's expected behavior
changes in this plan.

## Acceptance Criteria

1. `RichPresenceLimits` exists with the three documented constants and two
   pure functions; `SteamworksSteamFriendsGateway.setLocalRichPresence`
   rejects (returns `false`, logs one warning, never throws) any key `>64`
   chars or value `>256` chars, verified by `RichPresenceLimitsTest`'s
   boundary cases (exactly-at-limit passes, one-over fails).
2. While hosting (`HostingLifecycle`, advertising enabled), `steam_player_group`
   equals the host's `SteamID64` as a string and `steam_player_group_size`
   reflects the live connected-player count (host + peers), verified by
   `HostingLifecycleTest`; both keys clear alongside `connect` on `stop()`
   and on advertising toggling off.
3. A client that joins a Steam-World-Hosting session sets the identical
   `steam_player_group` value (host's `SteamID64` string) and a
   `steam_player_group_size` matching the session's live count, verified by
   `JoinedSessionPresenceTest` including the host/guest symmetric-value
   assertion; clears on leaving.
4. Neither FR3 key is ever set during ordinary singleplayer (no hosting/
   joining active) — covered by `HostingLifecycleTest`'s `start(false)`/no-op
   cases and by `JoinedSessionPresence` simply never being constructed/called
   outside a joined session.
5. `features/rich-presence/live-verification-protocol.md` exists, is
   concrete enough for the user to execute without further clarification,
   and explicitly states the two live-Steam preconditions (FR1.2). It is not
   itself "run" as part of this plan's completion — running it is a
   separate, user-driven follow-up; this plan's completion only requires the
   protocol doc to exist.
6. `features/rich-presence/verification-report.md` gains: (a) an entry
   confirming FR-RP6/FR4 closed with file:line references, (b) a template/
   placeholder section for FR1's live-verification results to be filled in
   once run.
7. Every existing test in `rich-presence`, `steam-world-hosting`, and
   `services` modules still passes unmodified.
8. No change to `ConnectStringCodec`, `"connect"`-key semantics, the
   join-request/invite flow, `RichPresenceFacade`'s public shape, or any of
   the three `FriendSidebarWidget.java` files (Task 6 is doc-only).
9. All Task 2/Task 3 code changes are applied identically across all three
   platform modules (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`) —
   verified by the verifier phase diffing the three modules' equivalent files.

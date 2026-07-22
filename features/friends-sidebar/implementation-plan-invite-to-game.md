# Implementation Plan — "Invite to Game"

## Implements
`features/friends-sidebar/specification-invite-to-game.md` (v1.6 amendment),
with the 5 Open Questions resolved by the user as follows (settled, not
reconsidered in this plan):
1. Bridge shape: one combined `WorldInviteSender` (`isHosting()` +
   `inviteFriend(long)`).
2. Enablement wiring: `FriendContextMenuWidget` reads the new bridge
   reference directly (bypasses `FriendSidebarStateMachine.isInviteEnabled`,
   exactly mirroring "Join game"'s existing precedent). `isInviteEnabled`
   stays dead code, same as `isJoinEnabled` today.
3. Failure feedback: a new, generic, reusable toast notification service
   (does not exist anywhere in this codebase today — confirmed, see Existing
   Implementation), sequenced as Phase 0.
4. No in-session suppression heuristic in v1.
5. No online/offline gating; rely solely on `inviteUserToGame`'s return
   value.

## Existing Implementation

**`FriendContextMenuWidget`** (`platform/fabric-{26.2,26.1,1.21.11}/.../friends/FriendContextMenuWidget.java`,
confirmed byte-for-byte structural twin across all three modules):
- Constructor: `FriendContextMenuWidget(int x, int y, FriendSummary friend, FriendsSidebarFacade facade, Runnable onClosed, boolean isOwnProfile, WorldJoinRequester worldJoinRequester, FriendHostingStatusReader hostingStatusReader)`.
- `isEnabled(int index)`: `case 2 -> facade.stateMachine().isInviteEnabled(friend)` (always `false` today); `case 3 -> hostingStatusReader != null && hostingStatusReader.isFriendHosting(friend.steamId64())` — this is the exact precedent to mirror for `case 2`.
- `mouseClicked`'s `switch`: `case 2 -> facade.actions().onInvite(friend.steamId64())`; `case 3 -> { if (worldJoinRequester != null) worldJoinRequester.joinHostedWorld(friend.steamId64()); }` — the exact precedent for `case 2`'s new body.
- `LABELS = {"Open chat", "Show profile", "Invite to game", "Join game"}` — index 2 is "Invite to game," unchanged.

**`FabricFriendsSidebarInjector`** (all three modules, structural twin,
confirmed via grep): holds `worldJoinRequester`/`hostingStatusReader` fields,
constructor parameters threaded in from `FriendsSidebarClientInitializer`;
constructs `new FriendContextMenuWidget(menuX, menuY, friend, facade,
this::closeMenu, isOwnProfile, worldJoinRequester, hostingStatusReader)` at
the one call site (26.2 lines 188-189, structural twins on the other two).

**`WorldHostingBridgeHandoff`** (`platform/fabric-<version>/.../WorldHostingBridgeHandoff.java`,
per-module, not shared): `volatile static` fields for `WorldJoinRequester`/
`FriendHostingStatusReader`, `publish(joinRequester, statusReader)` /
`requireJoinRequester()` / `requireHostingStatusReader()`, each `require(...)`
throwing `IllegalStateException` if called before publish (entrypoint-order
load-bearing, unchanged discipline).

**`SteamWorldHostingClientInitializer`** (per module, structural twin;
26.2 read in full): the feature's own composition root. When
`!(steamworksService.isSteamAvailable() && config.enabled())`, publishes
`new NoopWorldJoinRequester(), new NoopFriendHostingStatusReader()` and
returns. Otherwise constructs `HostingLifecycle lifecycle = new
HostingLifecycle(gateway)`, `HostingPresenceScanner scanner`, publishes
`WorldJoinRequester joinRequester = SteamAmbientSession.INSTANCE::connectToSteamPeer`
and `FriendHostingStatusReader statusReader = scanner` via
`WorldHostingBridgeHandoff.publish(joinRequester, statusReader)`. Both
`lifecycle` and `scanner` are local variables at this point — `lifecycle` is
not currently captured anywhere reachable after this method returns except
via the tick/stop lambdas at the bottom (`scanner.tick()` / `lifecycle.stop()`).
**This plan's new `WorldInviteSender` needs `lifecycle.currentStatus()` and
`gateway`, both already local here** — it must be constructed as a lambda/small
adapter object right here, alongside `joinRequester`/`statusReader`, and
`WorldHostingBridgeHandoff.publish(...)` must gain a third parameter.

**`HostingLifecycle`** (`features/steam-world-hosting/.../services/HostingLifecycle.java`):
`public HostedWorldStatus currentStatus()` returns `new
HostedWorldStatus(hosting, localSteamId64)` from its own `volatile` fields —
exactly the source of truth `WorldInviteSender.isHosting()`/the connect
string need.

**`ConnectStringCodec.encode(long hostSteamId64)`** (pure,
`features/steam-world-hosting/.../services/ConnectStringCodec.java`): returns
`"+lazuli_join " + Long.toUnsignedString(hostSteamId64)` — the exact string
`inviteToGame`'s `connectString` argument must carry, built from
`HostedWorldStatus.localSteamId64()` (the *host's* id, i.e. the local
player's own id while hosting — confirmed by `HostingLifecycle.start()`'s
own `ConnectStringCodec.encode(localSteamId64)` call, referenced in the spec
Overview).

**`SteamFriendsGateway`** (`services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`)
— the shared, plain-typed seam; `SteamworksSteamFriendsGateway` is the sole
class importing `com.codedisaster.steamworks.*` for friends/identity, and
`activateOverlay(...)`'s existing try/catch + `steamUtils.isOverlayEnabled()`
guard pattern is the template `inviteToGame(...)` must follow (fail-closed to
`false`, log via `warn(...)`, never throw). `NoopSteamFriendsGateway` mirrors
every method as a safe-default no-op (`false`/empty/no-op), the template for
`inviteToGame`'s Noop body (`return false;`).

**`FriendActionListener`** (`api/src/main/java/de/lazuli/api/friends/FriendActionListener.java`):
`onInvite(long steamId64)`'s Javadoc currently reads "Disabled placeholder in
v1 (FR3.3) — never reachable from the UI." Must be updated (spec FR-INV4).

**`FriendsService.onInvite(long steamId64)`**
(`features/friends-sidebar/.../services/FriendsService.java:167-169`):
currently an empty body with an FR3.3 comment. Becomes the real call site
per spec FR-INV4/FR-INV5. **`FriendsService` constructor signature** (needs
checking at implementation time — not read this pass beyond the `onInvite`
excerpt) currently takes `(gateway, config, warnLogger)`
(`FriendsSidebarClientInitializer.java:62`) — it has no bridge reference
today; this plan adds one (see Files to Modify).

**`NoopFriendsService.onInvite`**
(`features/friends-sidebar/.../services/NoopFriendsService.java:60-62`):
already a no-op with an FR3.3/FR0.2 comment — stays as-is (spec FR-INV6, no
change needed).

**`FriendSidebarStateMachine.isInviteEnabled(FriendSummary)`**
(`features/friends-sidebar/.../services/FriendSidebarStateMachine.java:65-67`):
`return false;` unconditionally, with an existing `isJoinEnabled` sibling
also `return false;` and confirmed **dead code today** (Decision 2/precedent:
`isEnabled(3)`'s real gate bypasses `isJoinEnabled` entirely, reading
`hostingStatusReader` directly instead). Per settled decision 2, this method
is **not modified** — it stays exactly as-is, permanently dead code, matching
`isJoinEnabled`'s existing precedent. No change to this file.

**Toast/notification mechanism: confirmed absent.** `Grep` for
`Toast|setOverlayMessage|setActionBar` across the repo returns only this
feature's own not-yet-written specification file — no toast, action-bar, or
equivalent lightweight-feedback mechanism exists anywhere in this codebase
today (FR-INV8's own discussion already flags this gap; independently
confirmed here). This plan's Phase 0 builds one from scratch.

**Vanilla toast API** (Minecraft's own `SystemToast`/`ToastManager` family,
both Mojang- and Yarn-mapped sides expose an equivalent) is the natural
backing mechanism — `Minecraft.getInstance().getToasts().addToast(...)` /
`MinecraftClient.getInstance().getToastManager().add(...)`-shaped, per each
module's own mapping. Exact class/method names are **not `javap`-confirmed
this pass** (no `javap` tool available to planning, consistent with this
feature area's own established caveat pattern in `plan.md`'s Risks 1/3/5/6) —
flagged as Risk 1, first concrete implementation step for the new toast
service specifically.

## Decisions

### D1. Toast service lives in `services/` (shared, not per-platform, not per-feature)
A `ToastService`-shaped seam in `services/src/main/java/de/lazuli/services/ui/`
(new sub-package; `services` already hosts cross-feature shared capability
per its own `steamworks` sub-package precedent — "graduate-on-second-use," but
here it's "start-shared-because-explicitly-reusable" per the task's own
instruction, not a second-use graduation). Shape:
```java
// services/src/main/java/de/lazuli/services/ui/ToastService.java
public interface ToastService {
    /** Posts a short, non-blocking, auto-dismissing notification. */
    void post(String title, String message);
}
```
One method only (spec FR-INV8 needs exactly one message-shaped notification;
no severity/duration/icon parameter added speculatively — smallest surface
that's still genuinely reusable by a future feature, consistent with this
repo's "no speculative generality" convention evidenced throughout `plan.md`).

**Why `services/` and not a new `features/` module or `api/`:** it has a
real Minecraft-side rendering implementation (a toast is drawn via vanilla's
own toast-queue API), so it cannot live in `api/` (zero-Minecraft-dependency
rule) or be a single pure-JVM class. It is not owned by any one Feature's
business logic (unlike `FriendsService`), so it does not belong under
`features/friends-sidebar`. It is exactly the same shape as
`SteamFriendsGateway`: an `api`-agnostic-typed interface in `services/`,
backed by one real, per-platform-module implementation and reused across
Features. Concretely:
- `services/src/main/java/de/lazuli/services/ui/ToastService.java` — the
  interface above, zero `net.minecraft.*` import (mirrors `SteamFriendsGateway`'s
  own "plain-typed contract, real impl elsewhere" shape).
- Per-platform-module real implementation,
  `platform/fabric-<version>/src/main/java/de/lazuli/ui/FabricToastService.java`
  — wraps that module's real vanilla toast API (Risk 1). **Not** a
  `services`-module implementation, since only `services/` itself must stay
  Minecraft-free; the concrete implementation lives in `platform/` exactly
  like `SteamworksSteamFriendsGateway` would if it needed a Minecraft-side
  call (it doesn't, hence living in `services/` itself — this new service
  does, hence its impl lives in `platform/`, a smaller-scope precedent than
  `SteamFriendsGateway` but the same "interface up, impl at whichever layer
  actually needs the dependency" rule).
- One `ToastServiceHandoff` per platform module
  (`platform/fabric-<version>/src/main/java/de/lazuli/ToastServiceHandoff.java`),
  identical `volatile static`/`publish`/`require()` shape to
  `SteamworksServiceHandoff`/`WorldHostingBridgeHandoff`. Published by a new,
  minimal composition-root step — **not** a whole new `ClientModInitializer`;
  folded into the very top of each module's existing
  `SteamworksClientInitializer.onInitializeClient()` (the earliest-registered
  entrypoint in every module's `fabric.mod.json` "client" array, confirmed by
  `plan.md`'s own Existing Implementation — "already the third entry," i.e.
  first among feature-owned initializers) — since a toast service has no
  Steam dependency and no config, publishing it needs no gating logic at all,
  just an unconditional `ToastServiceHandoff.publish(new FabricToastService())`
  call before that initializer's existing body.

### D2. `WorldInviteSender` — one combined interface (settled decision 1)
`api/src/main/java/de/lazuli/api/worldhosting/WorldInviteSender.java`:
```java
public interface WorldInviteSender {
    /** @return true if the local player currently has an active hosted session. */
    boolean isHosting();

    /**
     * Sends a real Steam invite for the current hosted session to the given
     * friend. No-ops (returns false) if !isHosting(). Never throws.
     */
    boolean inviteFriend(long friendSteamId64);
}
```
Real implementation is a small adapter constructed inline in
`SteamWorldHostingClientInitializer` (not a new named class — mirrors how
`joinRequester`/`statusReader` are themselves just method-reference/existing-object
assignments at that call site, no new wrapper class needed for those two
either):
```java
WorldInviteSender inviteSender = new WorldInviteSender() {
    @Override
    public boolean isHosting() {
        return lifecycle.currentStatus().hosting();
    }

    @Override
    public boolean inviteFriend(long friendSteamId64) {
        HostedWorldStatus status = lifecycle.currentStatus();
        if (!status.hosting()) {
            return false; // FR-INV5: race guard, never a stale/empty connect string
        }
        return gateway.inviteToGame(friendSteamId64, ConnectStringCodec.encode(status.localSteamId64()));
    }
};
```
A `Noop` counterpart, `NoopWorldInviteSender` (new small class,
`features/steam-world-hosting/.../services/NoopWorldInviteSender.java`,
identical shape/location to the existing `NoopWorldJoinRequester`/
`NoopFriendHostingStatusReader` siblings): `isHosting()` returns `false`,
`inviteFriend(...)` returns `false`, no-op — published when Steam World
Hosting is absent/disabled (mirrors the existing `!active` early-return
branch in `SteamWorldHostingClientInitializer`).

### D3. `WorldHostingBridgeHandoff.publish(...)` gains a third parameter
Per module: `publish(WorldJoinRequester, FriendHostingStatusReader,
WorldInviteSender)`, a third `volatile static WorldInviteSender` field, and
`requireWorldInviteSender()` following the exact `require(...)` null-check
pattern the other two already use. Both call sites in
`SteamWorldHostingClientInitializer` (the `!active` early return and the real
path) must be updated together — this is a breaking signature change to an
existing method, not an additive overload, since both existing call sites
must be updated in the same commit to keep compiling (no call site should be
left calling a 2-arg `publish` that no longer exists).

### D4. `SteamFriendsGateway.inviteToGame(long, String): boolean` — one new method on the existing shared seam
Added to the interface (`services/.../SteamFriendsGateway.java`), the real
implementation (`SteamworksSteamFriendsGateway`), and the no-op
(`NoopSteamFriendsGateway`). Real implementation follows the exact
`activateOverlay(...)` template (private-helper-free here, since this is
only one call, not two overlay variants sharing a helper):
```java
@Override
public boolean inviteToGame(long friendSteamId64, String connectString) {
    try {
        return steamFriends.inviteUserToGame(SteamID.createFromNativeHandle(friendSteamId64), connectString);
    } catch (RuntimeException e) {
        warn("Failed to invite " + friendSteamId64 + " to game: " + e.getMessage());
        return false;
    }
}
```
`inviteUserToGame(SteamID, String)`'s exact signature is already confirmed
present in this repo's pinned `steamworks4j-1.10.0.jar` per `plan.md`'s own
Existing Implementation (`SteamFriends` method table, line 143) — no new
dependency, no new `WebFetch` needed for the coordinate itself, but
implementation must still re-run this repo's own "`javap`-verify before
implementing" step against the real resolved jar before writing the call
(spec Networking, this repo's established discipline), since planning's
citation is confidence-only, not a fresh independent re-fetch this pass.

### D5. `FriendsService.onInvite(long steamId64)` becomes the real call site
`FriendsService` gains a constructor parameter, `WorldInviteSender
worldInviteSender` (nullable — mirrors how `FriendContextMenuWidget` already
accepts nullable `worldJoinRequester`/`hostingStatusReader`; here it's
needed so `FriendsService` itself can implement `onInvite` for completeness
per FR-INV4's literal requirement that `FriendActionListener.onInvite` be a
real call site, even though — per settled decision 2 — the *UI* path never
actually reaches it through `facade.actions().onInvite(...)`, since
`FriendContextMenuWidget` will call the bridge directly, exactly like "Join
game" bypasses `facade.actions().onJoin(...)` today in favor of
`worldJoinRequester.joinHostedWorld(...)` directly at the widget's own click
site). Concretely:
```java
@Override
public void onInvite(long steamId64) {
    // FR-INV4/FR-INV5: kept for FriendActionListener completeness and any
    // future non-context-menu caller; the context-menu click path itself
    // bypasses this and calls WorldInviteSender directly (Decision 2 /
    // mirrors "Join game"'s existing onJoin bypass).
    if (worldInviteSender == null || !worldInviteSender.isHosting()) {
        return;
    }
    if (!worldInviteSender.inviteFriend(steamId64)) {
        toastService.post("Invite failed", "Could not send the Steam invite. Check that the Steam overlay is enabled.");
    }
}
```
This requires `FriendsService` to also receive a `ToastService` reference —
**flagged as a design tension** (Risk 2): `FriendsService` is otherwise a
plain-JVM-free-of-Minecraft-import-except-steamworks4j class per
`plan.md`'s NFR1 discipline; a `ToastService` reference itself is
Minecraft-free (D1's interface is plain-typed), so this does not violate
NFR1, but it does mean `FriendsService`'s constructor grows by two
parameters for a code path this plan's own Decision 2 makes practically
unreachable from the UI. **Recommended resolution, adopted by this plan:**
put the toast-on-failure call at the actual UI call site instead
(`FriendContextMenuWidget`'s `case 2` click branch, which already has direct
access to whatever the composition root threads in), and leave
`FriendsService.onInvite`'s body as the simple
`worldInviteSender == null || !worldInviteSender.isHosting() ? return :
worldInviteSender.inviteFriend(steamId64)` — no toast call inside
`FriendsService` at all, no `ToastService` parameter added to
`FriendsService`. This keeps the toast wiring entirely inside the
"Join game"-precedent bypass path (Decision 2), the one path actually
reachable from a click, and avoids growing `FriendsService`'s constructor
for unreachable-in-practice code. `FriendsService` gains only the one new
`WorldInviteSender worldInviteSender` constructor parameter.

### D6. `FriendContextMenuWidget` — direct bridge read, toast-on-failure at the click site (settled decisions 2, 3)
Constructor gains one new parameter, `WorldInviteSender worldInviteSender`
(nullable, same convention as `worldJoinRequester`/`hostingStatusReader`),
and one new parameter, `ToastService toastService` (nullable — see Risk 3
below for what "nullable" means here in practice).
- `isEnabled(int index)`'s `case 2` changes from
  `facade.stateMachine().isInviteEnabled(friend)` to
  `worldInviteSender != null && worldInviteSender.isHosting()` — the exact
  `case 3`/`hostingStatusReader` precedent, mirrored.
- `mouseClicked`'s `case 2` changes from
  `facade.actions().onInvite(friend.steamId64())` to:
  ```java
  case 2 -> {
      if (worldInviteSender != null && !worldInviteSender.inviteFriend(friend.steamId64())) {
          if (toastService != null) {
              toastService.post("Invite failed", "Could not send the Steam invite. Check that the Steam overlay is enabled.");
          }
      }
  }
  ```
  This directly parallels `case 3`'s existing `if (worldJoinRequester !=
  null) { worldJoinRequester.joinHostedWorld(...); }` shape, adding only the
  return-value check and toast call FR-INV8 requires (join has no
  equivalent failure-surfacing requirement in this spec, so `case 3` itself
  is unchanged).
- `facade.actions().onInvite(...)` is **never called by this widget** —
  matches the settled decision 2 precedent exactly ("Join game" already
  bypasses `facade.actions().onJoin(...)` the same way).

## Files to Create

### Toast service (Phase 0 — prerequisite, land first)
- `services/src/main/java/de/lazuli/services/ui/ToastService.java` — D1's
  interface, one method, plain-typed, JavaDoc + usage example (NFR3
  precedent from `plan.md`).
- `services/src/main/java/de/lazuli/services/ui/package-info.java` — if this
  repo's convention requires one per new sub-package (confirm against an
  existing `services/` sub-package at implementation time; `services/steamworks`
  has no `package-info.java` today per the files read this pass, so this may
  not be needed — implementer's call, not load-bearing).
- `platform/fabric-26.2/src/main/java/de/lazuli/ui/FabricToastService.java`,
  `platform/fabric-26.1/src/main/java/de/lazuli/ui/FabricToastService.java`,
  `platform/fabric-1.21.11/src/main/java/de/lazuli/ui/FabricToastService.java`
  — one real implementation per module (structural twins, Mojang vs. Yarn
  toast API idiom differs — Risk 1), wrapping that module's vanilla toast
  queue.
- `platform/fabric-26.2/src/main/java/de/lazuli/ToastServiceHandoff.java`,
  `platform/fabric-26.1/.../ToastServiceHandoff.java`,
  `platform/fabric-1.21.11/.../ToastServiceHandoff.java` — D1's
  `volatile static`/`publish`/`require()` hand-off, identical shape to
  `SteamworksServiceHandoff`.

### `WorldInviteSender` bridge
- `api/src/main/java/de/lazuli/api/worldhosting/WorldInviteSender.java` — D2's
  interface.
- `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/NoopWorldInviteSender.java`
  — D2's no-op, sibling to the existing `NoopWorldJoinRequester`/
  `NoopFriendHostingStatusReader` (confirm their exact package/location by
  reading one of them at implementation time — not read this pass, only
  their import lines were seen in `SteamWorldHostingClientInitializer`).

## Files to Modify

### Phase 0 — toast service wiring
- `platform/fabric-26.2/src/main/java/de/lazuli/SteamworksClientInitializer.java`,
  `platform/fabric-26.1/.../SteamworksClientInitializer.java`,
  `platform/fabric-1.21.11/.../SteamworksClientInitializer.java` — each gains
  one line near the top of `onInitializeClient()`:
  `ToastServiceHandoff.publish(new FabricToastService());` (unconditional,
  no config gate — confirm this file's exact current shape by reading it
  first, per this feature area's own "re-run git status/git diff before
  editing a shared file" discipline; not read this planning pass since it
  was out of scope for the spec's own findings, but it is definitely
  touched by this plan and must be read before editing).

### Bridge interface + wiring
- `api/src/main/java/de/lazuli/api/friends/FriendActionListener.java` — update
  `onInvite(long)`'s Javadoc off the "unreachable placeholder" framing
  (spec FR-INV4); signature unchanged.
- `platform/fabric-26.2/src/main/java/de/lazuli/WorldHostingBridgeHandoff.java`,
  `platform/fabric-26.1/.../WorldHostingBridgeHandoff.java`,
  `platform/fabric-1.21.11/.../WorldHostingBridgeHandoff.java` — D3: third
  field/parameter/accessor.
- `platform/fabric-26.2/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java`,
  `platform/fabric-26.1/.../SteamWorldHostingClientInitializer.java`,
  `platform/fabric-1.21.11/.../SteamWorldHostingClientInitializer.java` — D2's
  inline `WorldInviteSender` construction (real path) / `NoopWorldInviteSender`
  (disabled path); both `WorldHostingBridgeHandoff.publish(...)` call sites
  updated to the new 3-arg signature (D3).
- `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`,
  `services/.../SteamworksSteamFriendsGateway.java`,
  `services/.../NoopSteamFriendsGateway.java` — D4: one new method each.

### `FriendsService` / `FriendContextMenuWidget` (×3 platform modules for the widget)
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java`
  — D5: new `WorldInviteSender worldInviteSender` constructor parameter
  (nullable), `onInvite(long)` body per D5's final resolved shape (no
  `ToastService` parameter added, per D5's resolution).
- `platform/fabric-26.2/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java`,
  `platform/fabric-26.1/.../FriendsSidebarClientInitializer.java`,
  `platform/fabric-1.21.11/.../FriendsSidebarClientInitializer.java` — each
  gains: `WorldInviteSender worldInviteSender = WorldHostingBridgeHandoff.requireWorldInviteSender();`
  (alongside the two existing `requireJoinRequester()`/
  `requireHostingStatusReader()` calls), `ToastService toastService =
  ToastServiceHandoff.require();`, passes `worldInviteSender` into the
  `FriendsService`/`NoopFriendsService` construction branch (D5 — note
  `NoopFriendsService` needs no new parameter, since its `onInvite` is
  already an unconditional no-op, FR-INV6, unchanged), and passes both
  `worldInviteSender` and `toastService` into the
  `new FabricFriendsSidebarInjector(facade, worldJoinRequester,
  hostingStatusReader, worldInviteSender, toastService)` constructor call
  (widened by two parameters).
- `platform/fabric-26.2/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`,
  `platform/fabric-26.1/.../FabricFriendsSidebarInjector.java`,
  `platform/fabric-1.21.11/.../FabricFriendsSidebarInjector.java` — each
  gains two new constructor parameters/fields (`worldInviteSender`,
  `toastService`), threaded into the one `new FriendContextMenuWidget(...)`
  call site (widened by two trailing arguments, D6).
- `platform/fabric-26.2/src/main/java/de/lazuli/friends/FriendContextMenuWidget.java`,
  `platform/fabric-26.1/.../FriendContextMenuWidget.java`,
  `platform/fabric-1.21.11/.../FriendContextMenuWidget.java` — D6: two new
  constructor parameters/fields, `isEnabled(2)` and `mouseClicked`'s `case 2`
  updated per D6.

## Order / Dependencies of Changes
1. **Phase 0 — Toast service** (fully independent, land and verify first):
   `ToastService.java` (services) -> `FabricToastService.java` (×3,
   independent per module) -> `ToastServiceHandoff.java` (×3) ->
   `SteamworksClientInitializer.java` publish call (×3). Buildable/testable
   in isolation (a manual smoke-test: trigger `toastService.post(...)` from
   anywhere temporarily, confirm a toast renders, per module) before any
   invite-specific code depends on it.
2. **`SteamFriendsGateway.inviteToGame(...)`** (services layer): interface ->
   `SteamworksSteamFriendsGateway` real impl -> `NoopSteamFriendsGateway`
   no-op. Independent of Phase 0 and of the bridge (step 3); can be done in
   parallel with Phase 0.
3. **`WorldInviteSender` bridge**: `api/.../WorldInviteSender.java` ->
   `NoopWorldInviteSender.java` -> `WorldHostingBridgeHandoff.java` (×3,
   third parameter) -> `SteamWorldHostingClientInitializer.java` (×3, both
   `publish(...)` call sites updated together, real adapter construction
   using `inviteToGame` from step 2 — **depends on step 2 being complete
   first**, since the real adapter calls `gateway.inviteToGame(...)`).
4. **`FriendActionListener.java`** Javadoc-only update (`api/`) — no
   compile-order dependency on anything else, can be done anytime.
5. **`FriendsService.java`** — depends on step 3 (`WorldInviteSender` type
   must exist) for its new constructor parameter.
6. **`FriendsSidebarClientInitializer.java`** (×3) — depends on steps 1
   (`ToastServiceHandoff`), 3 (`WorldHostingBridgeHandoff.requireWorldInviteSender()`),
   and 5 (`FriendsService`'s widened constructor) all being complete on that
   same platform module first.
7. **`FabricFriendsSidebarInjector.java`** (×3) — depends on step 6 (its own
   constructor is called from there) being ready to pass the two new
   arguments; can be edited immediately before/alongside step 6 on the same
   module.
8. **`FriendContextMenuWidget.java`** (×3) — depends on step 7 (constructed
   with the two new arguments there) being ready to pass them.

Recommended sequencing per module, once Phase 0/steps 2-3 land once
globally: `FriendsService.java` (shared) -> per module:
`SteamWorldHostingClientInitializer.java` -> `FriendsSidebarClientInitializer.java`
-> `FabricFriendsSidebarInjector.java` -> `FriendContextMenuWidget.java`,
repeated identically across `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`.

## Risks
1. **Vanilla toast API exact class/method names are not `javap`-confirmed
   this pass** (no `javap`/decompile tool available to planning, same
   limitation `plan.md`'s Risks 1/3/5/6 already documented honestly for this
   feature area) — `FabricToastService`'s first concrete implementation step
   on each module must be a real compile/`javap` pass confirming
   `Minecraft.getInstance().getToasts()` (26.x, Mojang) /
   `MinecraftClient.getInstance().getToastManager()` (1.21.11, Yarn) and the
   exact `SystemToast`/`Toast`-shaped constructor or factory method to add a
   short title+message toast (vanilla's own "Tutorial hint"/"World backup"
   toasts are the closest existing precedent to copy the shape of). Log the
   confirmed result in `.claude/context/minecraft.md`'s table per this
   repo's living-record convention once resolved.
2. **`FriendsService`'s constructor grows for a code path this plan's own
   Decision 2 makes practically unreachable from the UI** (D5) — accepted,
   scope-proportionate trade-off (FR-INV4 requires `onInvite` be a real
   implementation regardless of the UI's own bypass, mirroring `onJoin`'s
   equally-real-but-bypassed implementation today). Not a design flaw to "fix"
   during implementation — do not attempt to also route the UI through
   `facade.actions().onInvite(...)` "for consistency"; that would contradict
   the settled decision 2 and this plan's own D6.
3. **`ToastService` nullability at the `FriendContextMenuWidget` call site.**
   Since `ToastServiceHandoff.publish(...)` is unconditional (Phase 0, no
   config gate), `toastService` should never actually be `null` in practice
   by the time `FriendsSidebarClientInitializer` runs (entrypoint order:
   `SteamworksClientInitializer` always runs first in every module's
   `fabric.mod.json`, confirmed by `plan.md`'s own Existing Implementation).
   This plan still threads it as a nullable constructor parameter into
   `FabricFriendsSidebarInjector`/`FriendContextMenuWidget` for defensive
   consistency with the other two (genuinely-nullable) bridge parameters —
   confirm at implementation time whether `FriendsSidebarClientInitializer`
   should instead call `ToastServiceHandoff.require()` (throws if missing,
   matching `SteamworksServiceHandoff.require()`'s own contract) rather than
   a nullable pattern, since a missing toast service by this point would
   indicate an entrypoint-ordering bug, not an expected "feature disabled"
   state the other two bridge types represent. **Recommendation: use
   `require()`** (non-null always), and drop the `toastService != null`
   guard in `FriendContextMenuWidget`'s `case 2` down to an unconditional
   `toastService.post(...)` call — simpler and correctly reflects that this
   dependency, unlike the Steam World Hosting bridge, is never conditionally
   absent.
4. **`FriendContextMenuWidget`, `FabricFriendsSidebarInjector`,
   `FriendsSidebarClientInitializer`, and `SteamWorldHostingClientInitializer`
   are all actively-evolving, shared files** (the current `git status` shows
   several of them already modified in the working tree, and the in-flight
   `implementation-plan-dropdown-polish.md` also touches
   `FriendContextMenuWidget`'s and `FabricFriendsSidebarInjector`'s
   constructor regions, for unrelated parameters) — re-run `git status`/
   `git diff` before editing any of them, per this feature area's own
   established discipline (Risk 6 there, reused verbatim here). Confirm no
   merge conflict with the dropdown-polish work's own `renderDropdownOverlay(...)`
   additions before landing this plan's own constructor-widening changes to
   the same files.
5. **`OverlayToUserDialog`/`inviteUserToGame` steamworks4j signature
   citation is confidence-only, not independently re-fetched this pass** —
   reused from `plan.md`'s own prior `WebFetch` of
   `code-disaster/steamworks4j` tag `1.10.0`
   (`java-wrapper/src/main/java/com/codedisaster/steamworks/SteamFriends.java`,
   confirming `boolean inviteUserToGame(SteamID steamIDFriend, String
   connectString)`). No new external dependency is introduced by this
   plan (steamworks4j stays pinned at `1.10.0`,
   `gradle.properties:41` — same citation `plan.md` already established), so
   no fresh Maven-coordinate verification is required by this task's own
   Dependencies instruction; implementation must still `javap`-verify the
   exact method shape against this repo's actually-resolved jar before
   writing the call (this repo's own established discipline), per D4.
6. **`WorldHostingBridgeHandoff.publish(...)`'s signature change is a
   breaking change to an existing method**, not an additive overload (D3) —
   both call sites in each module's `SteamWorldHostingClientInitializer`
   (the `!active` early-return branch and the real path) must be updated in
   the same commit; a partial edit leaves the module non-compiling, not
   silently wrong, so this is a compile-time-caught risk, not a runtime one.
7. **No per-friend "already invited" or in-session suppression exists in
   v1** (settled decision 4) — a friend can be invited repeatedly with no
   mod-side limit; Steam's own native invite UI on the recipient's side is
   the only implicit rate-limiting surface. Explicitly accepted, not a gap
   to close in this pass.

## Dependencies
- **No new external (non-Fabric) Maven/Gradle dependency.** steamworks4j
  remains pinned at `1.10.0` (`gradle.properties:41`); `inviteUserToGame`'s
  signature is already covered by this repo's existing pinned-version
  citation (`plan.md`'s own prior `WebFetch` of the real GitHub source at
  `https://raw.githubusercontent.com/code-disaster/steamworks4j/1.10.0/java-wrapper/src/main/java/com/codedisaster/steamworks/SteamFriends.java`)
  — no new coordinate to verify against Maven Central or any other registry
  for this task.
- **New internal (inter-module) dependency edges**: none — this plan adds no
  new Gradle subproject and no new `project(...)` edge; `services/`,
  `features/steam-world-hosting`, `features/friends-sidebar`, `api/`, and all
  three `platform/` modules already depend on each other exactly as needed
  (the toast service's `platform` → `services` and `platform` → `api`
  edges already exist for every other bridge/handoff in this codebase).
- **No new Fabric API Gradle coordinate.** Vanilla's own built-in toast
  system is part of the base Minecraft client jar already on every module's
  classpath, not a separate Fabric API module — no `fabric.mod.json`
  dependency-block change, only the existing `"client"` entrypoint array
  gains no new entries at all (Phase 0's toast publish is folded into the
  already-registered `SteamworksClientInitializer`, not a new entrypoint).

## Test Strategy
- **No new automated/unit tests for the toast service, the bridge adapter,
  `FriendContextMenuWidget`, or `FabricFriendsSidebarInjector`** — consistent
  with this feature area's own established convention (`plan.md`'s Test
  Strategy: "not unit-testable... consistent with every other rendering
  concern in this repo's only existing custom widgets," and
  `implementation-plan-dropdown-polish.md`'s identical precedent for
  `DropdownWidget`/`FriendSidebarWidget`). All of the new/changed classes in
  this plan are either real-Minecraft-rendering-coupled (`FabricToastService`,
  `FriendContextMenuWidget`) or thin composition-root wiring
  (`SteamWorldHostingClientInitializer`, `FriendsSidebarClientInitializer`,
  `WorldHostingBridgeHandoff`, `ToastServiceHandoff`) with no meaningfully
  isolable business logic to unit-test beyond what a real compile already
  checks.
- **`SteamFriendsGateway.inviteToGame(...)`** — not unit-tested against a
  real `SteamFriends` (no fake-seam interface for this class exists in this
  codebase today, per `plan.md`'s own Risk 8, "still open," carried forward
  unchanged by this plan) — manual in-game verification only.
- **Manual in-game verification matrix** (requires a live, running Steam
  client session with at least one real Steam friend to observe the invite
  actually being sent — same constraint `plan.md`'s Risk 9 already documents
  for this feature area), run once per platform module (`fabric-26.2`,
  `fabric-26.1`, `fabric-1.21.11`):
  1. **Toast smoke test (Phase 0, before any invite-specific work lands):**
     trigger `toastService.post("Test", "Hello")` from a temporary call site,
     confirm a vanilla-style toast renders and auto-dismisses, on every
     module.
  2. **Not hosting:** open the friend context menu for any friend; "Invite to
     game" renders greyed/disabled (`0xFF808080`, no hover highlight, per the
     existing `FriendContextMenuWidget.renderNow` convention) and does not
     respond to a click.
  3. **Hosting, successful invite:** start hosting (load a singleplayer world,
     per Steam World Hosting FR1.1/FR1.2), open the context menu for a real
     online friend, click "Invite to game" — confirm it renders enabled
     (full-brightness, hover highlight), the menu closes immediately on
     click (no blocking dialog, FR-INV9), and the friend's own Steam
     client/overlay receives a native invite notification (requires a second
     Steam account/machine to fully observe the recipient side — if
     unavailable, verify `SteamFriends::InviteUserToGame`'s return value is
     `true` via a temporary log line instead and mark the recipient-side
     check "not verified, no second account available" per `plan.md`'s Risk
     9 convention).
  4. **Hosting, forced-failure invite:** with the Steam overlay disabled
     (Steam client setting), repeat step 3 — confirm `inviteToGame` returns
     `false`, a toast renders with the invite-failed message (FR-INV8), and
     no exception reaches the render/click thread.
  5. **Race guard (FR-INV5):** open the context menu while hosting, then stop
     hosting (quit the world) before clicking "Invite to game" if the row is
     still enabled from a stale render; confirm `onInvite`/`inviteFriend`
     no-ops rather than sending a stale/empty connect string (best-effort;
     exact repro timing may be hard to hit manually — code review of the
     `!status.hosting()` guard is the primary verification method here).
  6. **Own-profile row (FR-INV3):** confirm "Invite to game" stays forced
     disabled on the pinned own-profile row regardless of hosting state
     (`isOwnProfile` branch in `isEnabled(int)` takes precedence, unchanged).
  7. **Steam unavailable / feature disabled:** confirm `WorldInviteSender` is
     the `Noop` variant (`isHosting()` always `false`), "Invite to game"
     stays disabled identically to today's unconditional placeholder, no
     exception, on every module.

## Acceptance Criteria
Mapped to `specification-invite-to-game.md`'s requirements:
- **FR-INV1/FR-INV2/FR-INV3** — `FriendContextMenuWidget.isEnabled(2)` reads
  `worldInviteSender != null && worldInviteSender.isHosting()` (D6) on all
  three modules; own-profile forced-disable (FR-INV3) unchanged and
  confirmed to take precedence by code review + manual check 6.
- **FR-INV4/FR-INV5** — `FriendsService.onInvite(long)` is a real
  implementation (D5) guarding against `!isHosting()` at click time (race
  guard, manual check 5); `FriendActionListener.onInvite`'s Javadoc no
  longer describes it as an unreachable placeholder.
- **FR-INV6** — `NoopFriendsService.onInvite` unchanged, still an
  unconditional no-op.
- **FR-INV7** — no new `features/steam-world-hosting` ↔
  `features/friends-sidebar` direct import anywhere (grep-checked); the new
  `WorldInviteSender` contract lives in `api/`, wired only at each platform
  module's composition root, mirroring the existing two bridge contracts.
- **FR-INV8** — a failed `inviteToGame` call (`false` return) triggers
  exactly one `toastService.post(...)` call at the `FriendContextMenuWidget`
  click site (D6), verified manually (check 4) on all three modules; no
  toast on success.
- **FR-INV9** — a successful invite send closes the context menu via the
  existing unchanged `onClosed.run()` call, no blocking dialog (manual check
  3).
- **Compatibility** — identical change landed as structural twins across
  `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2` for every per-module file
  listed under Files to Modify/Create; `SteamFriendsGateway`/
  `SteamworksSteamFriendsGateway`/`NoopSteamFriendsGateway` and `ToastService`
  changed exactly once each in their shared `services/` location, not
  duplicated three times; `gradlew build` succeeds on all three platform
  modules with no new external dependency and no new Gradle subproject.
- **NFR1-equivalent (this feature area's plain-JVM discipline)** — grep-spot-check
  confirms `WorldInviteSender`/`ToastService` (the `api`/`services`-layer
  interfaces) carry zero `net.minecraft.*`/`com.codedisaster.steamworks.*`
  import; `FriendsService` remains the sole friends-sidebar-feature class
  importing steamworks4j types (unchanged by this plan — it gains a plain
  `WorldInviteSender` reference, not a new steamworks4j import).

## Open Questions
None remaining — all five items the specification left open were resolved
by the user before this planning pass (see Implements, above). This plan's
own residual items (toast API exact shape, Risk 1; `ToastService`
nullability convention, Risk 3) are flagged as concrete
implementation-time-confirmable risks, not open design questions requiring
further sign-off.

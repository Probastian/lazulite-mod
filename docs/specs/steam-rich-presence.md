# Steam Rich Presence — Completion Specification

## Overview
Rich Presence is **not** a stub in this codebase. `features/rich-presence`
(spec: `features/rich-presence/specification.md`, plan:
`features/rich-presence/plan.md`, verification:
`features/rich-presence/verification-report.md`, status **PASS**) already
computes a localized, per-tick, debounced `"status"` string (dimension,
biome, movement-derived verb, riding, near-village, digging-around tiers)
and writes it via
`SteamFriendsGateway.setLocalRichPresence("status", value)` →
`SteamworksSteamFriendsGateway.java:211` →
`com.codedisaster.steamworks.SteamFriends.setRichPresence(key, value)`
(steamworks4j's binding of Valve's native `ISteamFriends::SetRichPresence`).
`features/steam-world-hosting`'s `HostingLifecycle.java` independently
writes/clears the Valve-reserved `"connect"` key (drives the native
"Join Game" button and `onGameRichPresenceJoinRequested`/invite flow), and
`RichPresencePublisher`/`RichPresencePublisherTest` already regression-test
that the two features never clobber each other's key (FR-RP5).

Per Valve's Enhanced Rich Presence documentation
(https://partner.steamgames.com/doc/features/enhancedrichpresence),
a plain, pre-localized `"status"` key value is displayed as-is in a friend's
Steam Friends List / overlay entry **with no `steam_display` template and no
Steamworks-partner-site localization-string configuration required** — that
machinery (`steam_display`, `#token` templates, the
`rich_presence_localization` file format) is an *optional* enhancement for
studios that want Steam itself to re-localize the string per-viewer's Steam
client language; this project already fully localizes client-side via
Minecraft's own `Text`/lang-file system before the string reaches
`setRichPresence` (`features/rich-presence/specification.md` Non-goals),
which is Valve's documented "simple" path. **No code or partner-site change
is required to satisfy the basic "shows up in the friends list" requirement**
— it is already implemented and unit-tested; the risk still open is that it
has never been confirmed working against a *live* Steam client (no
`features/rich-presence` test exercises the real `SteamFriends` binding, and
Rich Presence is explicitly out of scope for this project's "no launching
Minecraft during remote control" constraint, so a live check has not yet
happened this session).

This spec covers the small set of genuine, confirmed remaining gaps: (1) an
end-to-end live-verification protocol the user can run themselves, (2)
input validation against Valve's documented key/value/count limits so a
malformed write degrades safely instead of silently failing, (3)
`steam_player_group`/`steam_player_group_size` (Valve's party/"playing
together" grouping keys), not currently set anywhere, and (4) closing the one
explicitly-deferred item from the existing spec, FR-RP6 (the friends-sidebar
own-profile row consuming the computed status string instead of its generic
"In Game" fallback).

## Goals
- Confirm, via a documented manual-verification protocol, that the existing
  `"status"`/`"connect"` writes actually render in a real Steam client's
  Friends List and in-game overlay, and record the outcome (pass/fail plus
  screenshots) — closing the "pending live test" item already tracked in
  user memory for this project's Steamworks features generally.
- Make every `SteamFriendsGateway.setLocalRichPresence` call resilient to
  Valve's documented hard limits (key ≤ 64 chars, value ≤ 256 chars, ≤ 20
  distinct keys per app) so a future tier/label that happens to exceed a
  limit degrades to a safe fallback rather than `SteamFriends.setRichPresence`
  silently returning `false` with no visible symptom.
- Add `steam_player_group`/`steam_player_group_size` so that when the local
  player is hosting or connected to a Steam-World-Hosting session with other
  Steam-friend players present, Steam's Friends List visually groups those
  friends together ("Player is in a group with N others"), per Valve's
  documented party-matching convention — this is the one Enhanced Rich
  Presence capability this project has not yet used at all.
- Deliver FR-RP6 from `features/rich-presence/specification.md`: the
  friends-sidebar own-profile row consumes the same computed status string
  Steam receives, instead of a generic `"In Game"` fallback.
- Leave every already-implemented, already-verified-PASS piece of
  `features/rich-presence` and `features/steam-world-hosting`'s `"connect"`
  key handling untouched.

## Non-goals
- Re-implementing or redesigning any status tier, precedence rule, or
  movement-derived label already specified in
  `features/rich-presence/specification.md` and confirmed PASS in
  `features/rich-presence/verification-report.md` — out of scope; this spec
  only adds the four gaps in Goals.
- Adopting Valve's `steam_display`/`#token`/`rich_presence_localization`
  templating mechanism — explicitly still not needed (Overview) and remains
  a Future Extension in the existing spec, unchanged here.
- Any change to `HostingLifecycle`'s existing `"connect"`-key semantics,
  `ConnectStringCodec`, or the join-request/invite flow
  (`features/friends-sidebar/specification-invite-to-game.md`) — unaffected.
- Fixing `steam_appid.txt`/App-ID resolution — already fully addressed by
  `docs/specs/steam-appid-runtime-bootstrap.md` (App ID `5052800`, real,
  Valve-issued, confirmed written into `services/steam_appid.txt` and every
  platform module's Gradle default). This spec assumes that fix is in place
  and does not re-verify it beyond noting it as a live-test precondition.
- Any Steamworks partner-site (partner.steamgames.com) configuration change
  (e.g. registering rich-presence localization tokens) — not required per
  Overview, and out of this project's ability to script/automate regardless.
- Live in-game testing performed *by the assistant* — per the user's
  standing constraint ("no launching Minecraft during remote control"), this
  spec's manual-verification protocol (FR1) is written for **the user** to
  run themselves; no agent in this project's workflow launches the client.

## Requirements

### FR1 — Manual live-verification protocol
- **FR1.1.** Document a concrete, repeatable manual test procedure (see
  Compatibility/Test Strategy below) covering: (a) `"status"` key visibility
  in the local player's own Steam Friends List entry (viewed from a second
  Steam account/friend, or the local Steam client's own "View my profile"
  panel, which also reflects one's own Rich Presence), across at least one
  tier transition (e.g. main menu → in a world → paused); (b) `"connect"`
  key / native "Join Game" button appearing on a friend's entry while
  Steam-World-Hosting is active, and disappearing when hosting stops; (c)
  clicking "Join Game" actually invoking `onGameRichPresenceJoinRequested`
  end-to-end into a real join.
- **FR1.2.** The protocol must explicitly call out the two live-Steam
  preconditions this project cannot verify by unit test alone: a real
  Steam client logged into two distinct test accounts that are Steam friends
  of each other, and both running the shipped jar (real App ID `5052800`,
  `steam_appid.txt` present per the runtime-bootstrap fix) rather than the
  dev `runClient` environment (App ID auto-detection differs, per
  `docs/specs/steam-appid-runtime-bootstrap.md`).
- **FR1.3.** Record the outcome (pass/fail per sub-case, screenshots) in a
  short results note once run — planning/implementation should add a
  `features/rich-presence/live-verification-report.md` (or append to the
  existing `verification-report.md`) capturing the result, mirroring this
  repo's existing verification-report convention.

### FR2 — Input-limit safety net
- **FR2.1.** Before calling `SteamFriends.setRichPresence(key, value)`,
  `SteamworksSteamFriendsGateway.setLocalRichPresence` validates: `key`
  length ≤ 64 UTF-8 bytes/chars and `value` length ≤ 256 UTF-8 bytes/chars
  (Valve's documented limits — see Networking). If either is exceeded, the
  call is **not** attempted; the method returns `false` and logs one warning
  via the existing `warnLogger` channel (never throws), consistent with this
  class's existing "fail closed, never throw" discipline.
- **FR2.2.** No truncation is performed automatically — a caller (e.g.
  `RichPresencePublisher`/`MinecraftTierTextFormatter`) that produces an
  over-length string is a genuine bug to surface via the warning log, not
  silently mask by cutting the string; this keeps the failure visible during
  development/QA rather than shipping a silently-truncated status.
- **FR2.3.** The 20-distinct-keys-per-process limit (Networking) is not
  independently enforced in code — this project only ever writes two keys
  total (`"status"`, `"connect"`; `steam_player_group`/`_size` from FR3 make
  four), far under the limit — but this ceiling is documented in this spec
  and in `SteamFriendsGateway`'s Javadoc so a future feature adding a fifth
  Rich Presence key is aware of the ceiling before assuming it's unlimited.
- **FR2.4.** Unit tests added for FR2.1's two boundary cases (key exactly at
  /over 64 chars, value exactly at/over 256 chars) against a fake/mock
  `SteamFriends`-equivalent seam, consistent with this class's existing
  plain-JVM-testable-via-interface discipline (`SteamFriendsGateway` is
  already an interface for exactly this reason).

### FR3 — `steam_player_group` / `steam_player_group_size`
- **FR3.1.** While `HostingLifecycle` is actively hosting (`hosting == true`,
  advertising enabled per the existing v1.3 join-policy `advertise` flag),
  additionally set `steam_player_group` to a stable per-session identifier
  (the host's own `SteamID64` string is sufficient and matches Valve's own
  example convention of using the party leader's ID as the group key) and
  `steam_player_group_size` to the current count of connected players
  (host + peers), so that the host and any Steam-friend players connected to
  their world are visually grouped together in each other's Friends List
  ("in a group with N others"), per Valve's documented
  `steam_player_group`/`steam_player_group_size` convention.
- **FR3.2.** A joined (non-host) client that connects to a
  Steam-World-Hosting session sets the **same** `steam_player_group` value
  (the host's `SteamID64` string, obtained from the existing join/connect
  flow — `ConnectStringCodec`/`onGameRichPresenceJoinRequested` already
  carries the host's `SteamID64`) and its own `steam_player_group_size`
  matching the same session player count, so Steam groups every member of
  the session symmetrically, not just the host's own friend list view.
- **FR3.3.** `steam_player_group`/`steam_player_group_size` are cleared
  (alongside the existing `"connect"` clear) whenever `HostingLifecycle.stop()`
  fires, the local player disconnects from a joined session, or the session's
  player count reaches zero for the host — same lifecycle points that already
  clear `"connect"` today, extended to clear these two additional keys in the
  same call.
- **FR3.4.** Player-count updates (a peer joins/leaves) update
  `steam_player_group_size` on the existing session-state-change hook this
  feature's platform-layer Netty integration already has (the same place
  that currently would need to detect peer-connect/disconnect for any other
  purpose) — exact hook is a planning-phase decision, since
  `HostingLifecycle` itself explicitly does not own peer-connected
  bookkeeping (`HostingLifecycle.java:14-16`, "lives in the platform's Netty
  layer").
- **FR3.5.** This is a strictly additive capability: no existing `"connect"`/
  `"status"` behavior changes; `steam_player_group`/`_size` are two more keys
  alongside them, all subject to FR2's limit safety net.
- **FR3.6.** Not attempted for ordinary singleplayer (no hosting/joining
  active) — these two keys are only ever set while a Steam-World-Hosting
  session (hosted or joined) is active, mirroring `"connect"`'s own existing
  scope.

### FR4 — Deliver FR-RP6 (sidebar own-row consumption)
- **FR4.1.** `FriendSidebarWidget`'s own-profile row (all three platform
  modules) reads `RichPresenceFacadeHandoff`'s published
  `RichPresenceFacade#localPresenceStatus(): Optional<String>` (already
  implemented and wired per-platform, per
  `features/rich-presence/verification-report.md` §5.3 — "exist and are
  correctly wired per-platform, but nothing in friends-sidebar consumes them
  yet") and displays that string as the own-row's live label whenever
  present, falling back to the existing generic `"In Game"` string only when
  the facade returns empty (no session active, or Rich Presence disabled).
- **FR4.2.** No change to `RichPresenceFacade`'s existing public shape
  (`api/src/main/java/de/lazuli/api/richpresence/RichPresenceFacade.java`)
  — this is purely a new caller in `features/friends-sidebar`, matching the
  original spec's framing of this as "the only coupling point between this
  feature and friends-sidebar" (`features/rich-presence/specification.md`
  FR-RP6).
- **FR4.3.** Ordering/composition rule: if both a specific own-row status
  (from a prior related fix,
  `features/friends-sidebar/specification-own-profile-ingame-status.md`) and
  this facade's string could apply, the Rich Presence facade's computed tier
  string takes precedence when non-empty (it is strictly more specific), per
  the original spec's framing of "in place of that spec's generic `In Game`
  fallback" — the other spec's own logic for detecting "a world is loaded"
  is otherwise unchanged and untouched by this item.

## Public API
Illustrative; final signatures are a planning-phase decision.

1. `SteamFriendsGateway#setLocalRichPresence(String, String)` — signature
   unchanged; `SteamworksSteamFriendsGateway`'s implementation gains the
   FR2.1 length-guard internally (no interface change).
2. New, small pure-JVM helper (exact home a planning decision, e.g.
   `services/src/main/java/de/lazuli/services/steamworks/RichPresenceLimits.java`):
   ```java
   /** Valve's documented ISteamFriends::SetRichPresence limits. */
   public final class RichPresenceLimits {
       public static final int MAX_KEY_LENGTH = 64;
       public static final int MAX_VALUE_LENGTH = 256;
       public static final int MAX_KEY_COUNT = 20;
       public static boolean keyWithinLimit(String key) { ... }
       public static boolean valueWithinLimit(String value) { ... }
   }
   ```
3. `HostingLifecycle` (or a small new collaborator it delegates to) gains the
   `steam_player_group`/`steam_player_group_size` writes (FR3) — exact
   method shape (extending `start(boolean)`/`updateAdvertising`/`stop()` with
   an additional `playerCount` parameter, vs. a new
   `updatePlayerCount(int)` method) is a planning-phase decision; the
   existing three methods' signatures may need to grow a parameter or gain a
   sibling method, not be replaced.
4. `RichPresenceFacade#localPresenceStatus(): Optional<String>` — already
   exists (FR4 is a new *caller* in `features/friends-sidebar`, not a new
   method).

## Architecture
- FR2's limit guard lives entirely inside
  `SteamworksSteamFriendsGateway` (the sole `com.codedisaster.steamworks.*`
  importer for this surface, per that class's own Javadoc) — no new
  cross-feature dependency.
- FR3's group keys are written from the same place `"connect"` already is
  (`HostingLifecycle`, `features/steam-world-hosting`), reusing the same
  `SteamFriendsGateway` seam — no new Feature→Feature edge, consistent with
  `architecture.md`'s dependency rules already followed by
  `features/rich-presence` and `features/steam-world-hosting` today.
- FR4 is the one cross-feature wiring point this spec touches:
  `features/friends-sidebar` (platform-module `FriendSidebarWidget`) reading
  `features/rich-presence`'s already-published `RichPresenceFacadeHandoff` —
  this exact seam was pre-built and left dangling specifically for this
  follow-up (`features/rich-presence/plan.md`, `verification-report.md` §5.3),
  so no new architecture is introduced, only the missing final call site.

## UI
- No new screen. FR4 changes one existing label (the friends-sidebar
  own-profile row) to show the live computed status string instead of a
  static `"In Game"` fallback — same visual slot, different string source.
- No UI surfaces `steam_player_group`/`steam_player_group_size` directly
  (FR3) — their only visible effect is Steam's own native Friends List
  grouping UI, entirely outside this mod's rendering.

## Configuration
- No new config file/flag. FR2's limits are fixed Valve constants, not
  user-configurable. FR3's group keys follow the same
  `SteamCloudSyncConfig`-style "always on whenever the underlying feature
  (Steam World Hosting) is active" pattern already governing `"connect"` —
  no new toggle.

## Events
- FR3.4's player-count change hook reuses whatever existing
  peer-connect/disconnect signal the platform Netty layer already has for
  `features/steam-world-hosting` (exact event/hook a planning decision,
  consistent with `HostingLifecycle`'s existing documented boundary that it
  does not itself own peer bookkeeping).
- No other new event/callback surface.

## Networking
- Valve's `ISteamFriends::SetRichPresence` documented hard limits (used by
  FR2): key ≤ 64 characters, value ≤ 256 characters, ≤ 20 distinct keys set
  per local player at once; setting a key's value to an empty string clears
  that key (already how `clearLocalRichPresence`/`SteamFriends.clearRichPresence()`
  is used, unaffected).
  (https://partner.steamgames.com/doc/api/isteamfriends,
  https://partner.steamgames.com/doc/features/enhancedrichpresence)
- `steam_player_group`/`steam_player_group_size` are two more plain
  key/value pairs through the exact same already-verified
  `SteamFriends.setRichPresence` call — no new Steamworks API surface to
  `javap`-verify beyond what `SteamworksSteamFriendsGateway`/
  `.claude/context/minecraft.md` already confirms present in the pinned
  steamworks4j 1.10.0 jar.
- No new raw network I/O anywhere in this spec — everything routes through
  the existing local Steam-client IPC path (`SteamAPI.runCallbacks()`/the
  existing `SteamFriends`/`SteamUtils` instances), unchanged.

## Persistence
None. All values in this spec (status string, connect string, group id/size)
remain transient, per-session, recomputed values — never written to disk,
matching the existing feature's own "Persistence: none" section.

## Compatibility
- All four requirements apply identically across all three platform modules
  (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`), consistent with how
  `features/rich-presence` and `features/steam-world-hosting` are already
  each implemented three times in structurally-parallel platform code.
- FR1's manual protocol is the only piece of this spec requiring a live
  Steam client and a second real Steam account; every other requirement
  (FR2-FR4) is verifiable by the existing plain-JVM unit-test discipline this
  codebase already uses for `SteamFriendsGateway`/`HostingLifecycle`/
  `RichPresencePublisher`.
- FR3 does not change `HostingLifecycle`'s existing public contract in a
  breaking way — any signature growth (Public API item 3) must remain
  source-compatible with existing call sites in all three platform modules'
  `IntegratedServer`-lifecycle hooks, or those call sites are updated in the
  same change.
- No regression risk to `features/rich-presence`'s existing PASS
  verification — FR2's guard only rejects *already-invalid* input (which the
  existing tier system has never produced, per the verification report's
  passing tests), and FR4 only changes a fallback string in
  `features/friends-sidebar`, a feature `features/rich-presence` has no
  dependency on in the other direction.

## Performance
- FR2's length checks are two `String.length()` comparisons per
  `setLocalRichPresence` call — negligible, same call frequency as today
  (already debounced by `RichPresencePublisher`/`HostingLifecycle`'s
  idempotent start/stop, not per-tick).
- FR3's additional two key writes occur only on session start/stop/
  player-count change (rare events), not per-tick — negligible.
- FR4 replaces one label string read per sidebar-row render with another
  (facade accessor instead of a static constant) — no new per-frame cost.

## Future Extensions
- Adopting `steam_display`/`#token` templating if cross-locale friend-viewing
  fidelity (a friend viewing the status in *their own* Steam client language
  rather than the local player's Minecraft language) is ever requested —
  still deferred, as in the original spec.
- A settings toggle to disable Rich Presence publishing entirely (already
  listed as a Future Extension in `features/rich-presence/specification.md`,
  unchanged here).
- Extending `steam_player_group` semantics to Steam Matchmaking Lobbies if
  this project ever adopts `ISteamMatchmaking` lobbies instead of/alongside
  direct-connect Steam-World-Hosting.

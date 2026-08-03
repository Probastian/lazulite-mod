> **STATUS: Revised per user feedback, ready for approval.** All four items
> flagged after the prior draft's review pass have been resolved and are
> reflected below: (1) the mining/underground playful label is final
> ("Digging around"), (2) the "Staying"/"Building" preposition is final
> ("in", not "at"), (3) the Building block-placement threshold is lowered to
> 20 placements/60s, and (4) the Trading/village-detection design has been
> replaced entirely — the prior `StructureManager`-based design (which only
> worked for the hosting player, not a joined/remote client) is gone, replaced
> by a villager-entity/bell-block proximity check that is ordinary synced
> client-visible state and works identically for the host and for a
> remote-joined client. No blocking gaps remain. The related bug fix
> (own-row generic "In Game" label) is a separate, already-approved spec:
> `features/friends-sidebar/specification-own-profile-ingame-status.md`
> (unaffected by this document, not touched here).

# Rich Presence Publishing — Specification

## Overview
`features/rich-presence` is a standalone feature that detects what the local
player is currently doing (dimension, biome, movement/building behavior,
pause/menu state, vehicle, and a small set of other cheap signals) and
publishes a human-readable, translated status string to Steam's own Rich
Presence `"status"` key via `SteamFriendsGateway.setLocalRichPresence`
(`services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java:113`).
This is visible in the local player's **real Steam friends list**, in Steam's
own client UI, regardless of whether this mod's friends-sidebar screen is
open at all, or even installed as anything more than the shared Steamworks
plumbing — Rich Presence publishing has no dependency on the
friends-sidebar UI code.

This feature was originally drafted as an in-place amendment to
`features/friends-sidebar/specification.md`
(`specification-richpresence-publishing.md`) and is relocated here because it
is not, in fact, part of that feature: friends-sidebar only ever *reads*
other friends' Rich Presence (`FriendsService.resolveFriend`,
`richPresenceById`); this feature *writes* the local player's own Rich
Presence, a Steam-facing capability with its own identity, home directory,
and lifecycle, independent of any sidebar screen being open. It has exactly
one optional integration point with friends-sidebar: the own-profile row in
the sidebar (`features/friends-sidebar/specification-own-profile-ingame-status.md`)
may, once both features have landed, consume this feature's computed status
string as its live label instead of falling back to a generic `"In Game"`
string (FR-RP6) — that is a one-way, optional consumer relationship, not a
shared home for the two features' specs/code.

## Goals
- Publish a local Rich Presence `"status"` (Steam's own `steam_display`/
  plain-string convention) describing what the local player is doing, built
  via Minecraft's own `Text`/`Component` + lang-file translation system so
  the string is naturally localizable and consistent with the rest of this
  mod's UI text.
- Expose the same computed status string for optional consumption by
  friends-sidebar's own-profile row label (single source of truth for "what
  am I doing right now," consumed both by Steam and, optionally, by the
  sidebar) once `features/friends-sidebar/specification-own-profile-ingame-status.md`
  is amended/extended to prefer it over its generic fallback.
- Ship a confirmed, concrete v1 status-tier set (see Requirements, "Status
  tiers") and a reasoned tier-precedence ordering (see "Tier Priority /
  Precedence"), both finalized per the user's approval pass — no remaining
  open scope questions of the kind the prior draft's "confirm or override"
  call-outs represented.

## Non-goals
- No change to how *other* friends' in-game tier / Rich Presence is read
  (`specification-status-recolor-ingame.md`, `FriendsService.resolveFriend`,
  `richPresenceById`) — that is a read path for friends in a different
  feature; this feature adds a *write* path for the local player only. The
  two are related (both eventually flow through Steam's Rich Presence
  key/value store) but are separate code paths in separate features, and
  neither is refactored to share implementation.
- No attempt to resolve a numeric Steam App ID to a display name (irrelevant
  here — the local player always knows what game they're in).
- No population of Steam's `"connect"` Rich Presence key by this feature —
  that remains exclusively `HostingLifecycle`'s responsibility
  (`features/steam-world-hosting/services/HostingLifecycle.java:61,80`,
  `CONNECT_KEY`), unchanged. This feature publishes different keys
  (`"status"`, and — per the Addendum below — `"steam_display"` plus a small
  set of interpolation-variable keys) and must not overwrite or clear
  `"connect"` when it calls `setLocalRichPresence` — see Requirements
  (FR-RP5)/Compatibility.
- No in-mod settings UI to disable/customize Rich Presence publishing in v1
  (see Future Extensions) — always-on whenever Steam is available, matching
  every other always-on Steamworks read/write in this codebase.
- No server-side/dedicated-server Rich Presence (`SteamFriends` is a
  client-only Steamworks interface in this mod's existing usage,
  `.claude/context/minecraft.md` "Client singleton" row) — only the client's
  own local player's activity is observed; a joined-as-client session on
  someone else's hosted world still counts as "local session active" for
  this purpose (dimension/biome/pause detection all work identically whether
  integrated-server or remote-client). **This now also holds for the
  Near-a-Village tier** (see Requirements) — unlike the prior draft's
  `StructureManager`-based design, the villager/bell proximity mechanism
  depends only on ordinary loaded-chunk entity/block-entity sync, which every
  client (host or remote-joined) receives identically, so there is no
  remaining client-type-dependent fidelity gap anywhere in this feature.
- **Fighting is explicitly rejected, not deferred.** A "Fighting `<mob>`"
  tier was considered and is out of scope permanently for this reason:
  combat encounters are typically short-lived (seconds), and publishing a
  status for something that ends before a friend would ever see it in their
  friends list just produces status flicker with no real payoff. This is not
  a "revisit in v2" item — a future contributor proposing it again should
  treat this as a closed question, not a backlog item, unless the underlying
  reasoning (frequency/duration mismatch with Rich Presence's own update
  cadence) changes.
- **Sleeping is explicitly rejected, not deferred**, for the identical
  reasoning class as Fighting: too short-lived, too frequent a status change
  relative to how long a friend would realistically see it.
- **Died/Respawning is explicitly rejected, not deferred**, for the identical
  reasoning class as Fighting/Sleeping: the death/respawn window is a few
  seconds, and it would flicker rapidly against whatever tier follows it with
  essentially no value as a friends-list status string.
- ~~No `"steam_display"` Valve-side localization token is set in v1~~ —
  **superseded by the Addendum below.** The original v1 decision to omit
  `steam_display` is reversed: per Valve's own Enhanced Rich Presence
  documentation, a custom key like `"status"` is inert data the Friends List
  UI never displays on its own — only the value pointed to by the special
  `steam_display` key is rendered, and it must reference a **localization
  token name** (e.g. `"#Status_Exploring"`), not free text. Without also
  setting `steam_display`, this feature's `"status"` writes are invisible in
  the actual Steam Friends List UI. See "Addendum: `steam_display`
  Localization Token Support" below for the full requirement.

## Requirements

**Current state (confirmed via code read):**
- `SteamFriendsGateway.setLocalRichPresence(String key, String value): boolean`
  already exists as a generic key/value setter, implemented for real in
  `SteamworksSteamFriendsGateway.java:211` (wraps
  `SteamFriends.setRichPresence(key, value)`) and as a no-op `false` in
  `NoopSteamFriendsGateway.java:82`.
- The **only** current caller of `setLocalRichPresence` is
  `HostingLifecycle` (`features/steam-world-hosting/.../HostingLifecycle.java:61,80`),
  and the **only** key it ever writes is `CONNECT_KEY` (`"connect"`). This
  mod does not currently publish any `"status"`/human-readable Rich Presence
  key at all — `RICH_PRESENCE_STATUS_KEY` exists only as a *read* constant
  (`FriendsService.java`, used to read *other* friends' status), never as a
  local write target.
- This feature is wholly new: it does not modify `HostingLifecycle`'s
  existing `"connect"` write, and does not need to change
  `SteamFriendsGateway`'s public surface — the generic
  `setLocalRichPresence(key, value)` method already suffices; this is a new
  caller, `key = "status"` (and, per the Addendum below, `"steam_display"`
  plus a small number of interpolation-variable keys).
- **v1 decision (superseded — see Addendum): set only the `"status"` key to
  a plain, already-fully-formed client-locale string**, computed via
  Minecraft's own `Text`/`Component` + lang-file system. This is retained as
  the historical v1 decision for context, but is no longer the final design
  — `RichPresencePublisher` must additionally set `"steam_display"` (and its
  supporting interpolation keys) per the Addendum, because a plain `"status"`
  string alone is never surfaced by Steam's Friends List UI.

**Core requirements:**

- FR-RP1. A new component (name/package a planning-phase decision, e.g.
  `LocalPresenceTracker`/`PresenceStatusResolver`, living in
  `features/rich-presence/services/`) computes, once per tick/sweep (exact
  interval a planning decision), the current status tier (see "Status
  tiers") from the local client's observable state: current dimension,
  biome at the player's position, `Minecraft.getInstance().isPaused()`,
  whether a non-pause `Screen` is open, player vehicle/mob-riding state,
  recent movement/block-placement history (for the dynamic movement-derived
  tier, see below), and whether the player is near a village (see "Near a
  Village").
- FR-RP2. Each tier maps to a translation key under this mod's existing
  `lazuli.*` namespace convention — new keys under `lazuli.presence.*` —
  added to every platform module's own `en_us.json` (three copies,
  `platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`).
- FR-RP3. The resolved tier's `Text`/`Component` is converted to a plain
  `String` via `Text.getString()` (Yarn) / `Component.getString()` (Mojang
  mapping) — the standard Minecraft idiom for "get a fully localized plain
  string from a Component," including nested translatable arguments (biome
  name, entity name). Biome-name and entity-name resolution follow vanilla's
  own registry-key-derived translation-key convention
  (`"biome.<namespace>.<path>"` / `"entity.<namespace>.<path>"`), unchanged
  from the prior draft of this spec.
- FR-RP4. The resulting plain `String` is written via
  `SteamFriendsGateway.setLocalRichPresence("status", computedString)` only
  when the computed tier or its formatted argument actually changes from the
  previous write (debounce, exact granularity a planning-phase decision) —
  not necessarily every tick.
- FR-RP5. **Must not clobber `HostingLifecycle`'s `"connect"` key.** Writing
  `"status"`/`"steam_display"`/interpolation keys does not, by itself, clear
  or overwrite `"connect"` (independent keys in Steam's per-local-player Rich
  Presence store). This feature's caller must **only ever call
  `setLocalRichPresence` with the key set defined by this feature** (`
  "status"`, `"steam_display"`, `"location"` — see Addendum), never
  re-deriving or touching `"connect"`.
- FR-RP6. The friends-sidebar own-profile row
  (`features/friends-sidebar/specification-own-profile-ingame-status.md`
  FR-OP2) may, once this feature ships, optionally consume the **same**
  computed tier/string this component produces — no second, independent
  computation. Concretely: a read accessor (e.g.
  `Optional<String> localPresenceStatus()`) on a small facade this feature
  exposes is consumed by `FriendSidebarWidget`'s own-row branch in place of
  its generic `"In Game"` fallback, as a follow-up amendment to that spec (or
  landed together — a planning-phase sequencing decision). This is the
  **only** coupling point between this feature and friends-sidebar; nothing
  else in this feature depends on friends-sidebar existing.
- FR-RP7. Session-inactive states (main menu, world not loaded) do not
  attempt to write Rich Presence `"status"` (or `"steam_display"`/
  interpolation keys) at all — clear it
  (`SteamFriends.clearRichPresence()`, confirmed present in the pinned
  steamworks4j 1.10.0 jar per `.claude/context/minecraft.md`'s surface
  table, not yet called anywhere in this codebase) so a friend viewing the
  local player's Steam friends-list entry while they're at the main menu
  sees no stale in-game status text.

**Status tiers — v1 scope (resolved per explicit user feedback):**

| Tier | Signal source | v1? | Notes |
|---|---|---|---|
| In Main Menu | `Minecraft.getInstance().level == null` | v1 (or clear the key, FR-RP7) | Unchanged from the prior draft. |
| Paused | `Minecraft.getInstance().isPaused()` | v1 | Unchanged. |
| Spectating | `player.isSpectator()`/`gameMode == GameMode.SPECTATOR` | v1 | Confirmed good as-is by the user — no changes. |
| Movement-derived label + biome (replaces the old flat "Exploring `<biome>`" / "Building" / "Swimming" tiers) | See "Movement-derived status" below | v1 | New unified dynamic system — see below. |
| Nether/End (combined with biome, not a bare dimension label) | `player.level().dimension()` (`Level.NETHER`/`Level.END`) composed as an orthogonal suffix on the movement-derived label | v1 | See "Dimension suffix" below. Explicitly **not** a bare "In the Nether"/"In the End" string anymore. |
| Digging around in the Overworld (mining/underground, Overworld-only) | Player's Y-position relative to sea level / "no sky visible" heuristic, restricted to the Overworld dimension only | v1 | Final playful label: **"Digging around"** (see "Mining/underground label" below). |
| Driving through `<biome>` (minecart) | `player.getVehicle() instanceof <minecart type>` | v1 | Replaces the old generic "Riding `<entity>`" tier — split by vehicle kind. |
| Sailing through `<biome>` (boat) | `player.getVehicle() instanceof <boat type>` | v1 | Same split, boat-specific wording. |
| Near a Village (villager/bell proximity) | Villager-entity count and/or Bell-block presence within a fixed radius of the player — see "Near a Village — concrete detection design" below | v1, works identically for host and remote-joined clients | Replaces the prior draft's `StructureManager`-based "Trading (inside a structure)" tier — see below for why and how. |
| Fighting | — | **Rejected, not deferred** | See Non-goals. Removed from this table entirely; do not re-propose as a v2 item without revisiting the frequency/duration reasoning. |
| Sleeping | — | **Rejected, not deferred** | See Non-goals. |
| Died/Respawning | — | **Rejected, not deferred** | See Non-goals. |

**Movement-derived status (replaces flat "Exploring `<biome>`" / old
Building / old Swimming tiers):**

Rather than "Exploring `<biome>`" always being shown whenever no more
specific tier applies, the verb itself is now derived from the player's
recent movement/building behavior, sampled over a rolling window, and then
combined with the current biome name:

- **"Exploring `<biome>`"** — the player has moved a great distance over
  roughly the last 2-3 minutes. **Proposed default (tunable, for approval):**
  rolling window of **150 seconds**; "great distance" = straight-line
  displacement between the player's position now and their position 150
  seconds ago exceeds **250 blocks** (a simple, cheap start/end delta rather
  than a full path-length integral — cheaper to compute, and straight-line
  displacement is a reasonable proxy for "actually going somewhere" versus
  "wandering in circles," which the "Staying" tier below is meant to catch
  instead). **[CONFIRM OR OVERRIDE — window length and distance threshold
  are both proposed defaults, not fixed.]**
- **"Staying in `<biome>`"** — the player is AFK or has stayed within a
  small/general area over the same rolling window (i.e. did *not* meet the
  Exploring threshold above and did *not* meet the Building threshold
  below). **Proposed default:** the player's position has not left a
  **32-block-radius bounding circle** at any point during the rolling
  150-second window (recompute the window's centroid/anchor once per window
  rather than a continuously-sliding centroid, to keep this cheap).
  **Preposition: "Staying in `<biome>`"** (confirmed final per user
  feedback — "in" was chosen over the prior draft's proposed "at").
- **"Building in `<biome>`"** — the player has been placing many blocks
  recently. **Confirmed threshold: more than 20 block-place events within
  the trailing 60 seconds** (lowered from the prior draft's proposed 40, per
  user feedback — a simple rolling counter, not a full
  placement-position/pattern analysis — cheap and sufficient to distinguish
  "actively building something" from incidental single-block placement while
  exploring, e.g. placing a torch or a single-block bridge). **Preposition:
  "Building in `<biome>`"** (confirmed final, same "in" choice as "Staying,"
  for consistency between the two tiers).
  - Precedence between Building and Staying/Exploring when more than one
    threshold is technically met in the same window: Building takes
    precedence over Staying (a player who is placing many blocks while not
    moving far is "building," not merely "staying") but Exploring takes
    precedence over Building if the distance threshold is *also* met (a
    player who is moving a great distance while occasionally placing blocks,
    e.g. bridging across a ravine, reads more naturally as "Exploring" than
    "Building" — the large-scale movement is the more salient signal). This
    ordering (Exploring > Building > Staying) is itself part of the "Tier
    Priority / Precedence" section below.

**Dimension suffix (Nether/End):** dimension is **not** a separate,
competing tier. It composes as an orthogonal suffix appended to whichever
movement-derived verb + biome label is currently active: "Exploring
`<biome>` in the Nether," "Staying in `<biome>` in the Nether," "Building in
`<biome>` in the Nether," and the End equivalents. This is the recommended
design (see "Tier Priority / Precedence" for restatement in context) — a
bare "In the Nether"/"In the End" string, as the old draft proposed, is
explicitly replaced by this combined form.

**Mining/underground label (Overworld-only) — final:**

Detection: restricted to the Overworld dimension only (not applied in the
Nether/End, where "underground" isn't a meaningful distinct concept relative
to the surface); a simple proxy signal such as "player's Y-position is below
a threshold (e.g. Y ≤ 40, below sea level) and/or no sky light reaches the
player's position" — exact signal a planning-phase decision, this spec only
fixes that it must be Overworld-only and playful in tone, per explicit user
feedback rejecting a flat "Underground"/"Mining" label.

**Confirmed final label: "Digging around."** Two other candidates were
considered and are not chosen: "Down in the dirt" and "Spelunking" (the
latter read slightly more "cave-exploring" than "mining/building," which is
this tier's more common trigger condition). Neither is a live option going
forward.

**Riding-tier wording — grammar/phrasing self-check (per user request):**
"Driving through `<biome>`" (minecart) and "Sailing through `<biome>`"
(boat) were explicitly re-checked for natural, grammatical English phrasing
since the requester is not a native English speaker. Both read naturally to
a native English speaker: "driving through a forest" and "sailing through a
river/ocean" are both common, idiomatic constructions in English (compare
"driving through the countryside," "sailing through calm waters") — no
wording change is needed versus what was requested; this spec confirms the
phrasing as-is.

**Near a Village — concrete detection design (replaces the prior
`StructureManager`-based "Trading (inside a structure)" design):**

The prior draft of this spec detected "inside a village" via
`StructureManager`/`StructureStart` bounding-box containment. That
mechanism was rejected by the user because of a real, confirmed fidelity
gap: `StructureManager` is a `ServerLevel`/`World`-owned component — its
structure-piece/bounding-box data is generated and stored **server-side
only** and is never sent to a remote client over vanilla's own protocol. On
this mod's most common topology (a local player running their own
integrated server, singleplayer or Steam-World-Hosting) that gap is
invisible, because the client and server share one JVM — but a player who
has **joined** someone else's hosted world is a genuine remote client with
no populated `StructureManager` at all, so "Trading" would simply never
fire for them. The user explicitly rejected shipping with that known,
host-only gap.

**Investigation — what village-related client state is actually synced,
regardless of host/join role:**

- **Villager entities** (Yarn: `net.minecraft.entity.passive.VillagerEntity`;
  Mojang: `net.minecraft.world.entity.npc.Villager`) are ordinary living
  entities. Like every other mob, their existence, position, and type are
  synced to **any** client that has the containing chunk loaded and
  entity-tracked, via vanilla's standard entity-tracking/spawn-packet
  mechanism — this has no dependency on `StructureManager`, and no
  dependency on whether the local client is the integrated-server host or a
  remote-joined client. **Confirmed usable.**
- **Bell blocks** (`BellBlock`/`BellBlockEntity`; Yarn:
  `net.minecraft.block.BellBlock` / `net.minecraft.block.entity.BellBlockEntity`;
  Mojang: `net.minecraft.world.level.block.BellBlock` /
  `net.minecraft.world.level.block.entity.BellBlockEntity`) are ordinary
  block state plus an ordinary block entity. Block state and block-entity
  data for any loaded chunk is synced to every client identically — this is
  the same "any client with the chunk loaded sees this" guarantee vanilla
  gives every other block in the game, again independent of
  `StructureManager` and independent of host/join role. A bell is also
  generated at (functionally) the center of every vanilla village, making it
  an unusually strong single-block "there is a village near here" signal.
  **Confirmed usable.**
- **Composters and lecterns** are likewise ordinary synced block state
  (`ComposterBlock`, `LecternBlock`/`LecternBlockEntity` — present in every
  vanilla village variant in meaningful numbers, one per villager
  house/farm). These are real, viable client-visible signals too, but this
  spec does not use them in v1: they are far more numerous and spatially
  diffuse per village than the single, centrally-placed bell, so they add
  detection complexity (need a *count* of nearby composters/lecterns rather
  than a single presence check) without a clear accuracy gain over
  bell-plus-villager-count. Left as a Future Extension refinement, not
  needed for a correct v1.
- **Jigsaw blocks are ruled out.** Jigsaw blocks (`JigsawBlock`) are a
  structure-generation-time mechanism only — vanilla's structure-processing
  pipeline consumes/replaces jigsaw blocks as part of piece assembly, so a
  fully generated village does not reliably retain jigsaw blocks as
  persistent, player-visible world state the way bells/composters/lecterns
  do. Not a usable post-generation signal; ruled out.

**Chosen v1 mechanism:** the tier fires when **either** of the following is
true, evaluated against the local client's own loaded-chunk entity/block
state only (no `StructureManager`, no server-only data, no custom sync
packet):

1. **A Bell block exists within 24 blocks of the player's position**
   (`BellBlockEntity` lookup restricted to already-loaded chunks in that
   radius — implementation should prefer scanning the level's own loaded
   block-entity list per nearby chunk over a raw per-block scan, cheaper).
   24 blocks is chosen because vanilla village generation places the bell at
   the settlement's structural center and most village houses/paths fall
   within roughly that radius of it — this is intentionally a slightly
   generous radius (a player walking the outer edge of a small village
   should still register as "near" it) balanced against not triggering from
   a bell in a neighboring, visually-distinct structure far away (bells are
   rare enough outside villages, e.g. a player-built bell, that this doesn't
   meaningfully change the semantics of the label if it does fire).
2. **At least 3 `Villager` entities are within 32 blocks of the player's
   position** (`Level.getEntitiesOfClass(Villager.class, aabb)`/Yarn
   equivalent, centered on the player). A **count** threshold (not "any
   villager") is deliberately used rather than a single-villager presence
   check, because a single wandering/escaped villager (or a player's own
   isolated villager trading post, a legitimate but different scenario) is a
   much weaker signal of "this is a village" than a cluster of three or
   more, which realistically only occurs at or very near an actual village
   or a player-built village-equivalent (which is an acceptable, intentional
   inclusion — a player's own substantial villager farm/trading hall reading
   as "Near a Village" is correct behavior, not a false positive). 32 blocks
   (slightly wider than the bell radius) compensates for villagers wandering
   during their daily schedule rather than staying fixed at the settlement
   center the way a bell does.

Both checks are cheap, bounded-radius, ordinary client-visible-state reads
— no server-only API, no new network protocol, and (critically) **no
difference in behavior between the integrated-server host and a
remote-joined client**, since both simply read the same standard
entity-tracking/block-entity sync every vanilla client already receives for
any loaded chunk. This fully resolves the fidelity gap the user rejected in
the prior draft.

**Naming:** the tier is renamed from "Trading (inside a structure)" to
**"Near a Village"** (label text, e.g. "Near a Village in Plains" composed
with the biome/dimension suffix per the existing composition rules) — this
spec's judgment call, on the reasoning that "Trading" implied active
merchant-screen interaction (which the user separately rejected detecting
directly) and "inside a structure" implied the removed bounding-box
containment mechanism; "Near a Village" more accurately describes what a
proximity-based villager/bell check is actually detecting, and reads
naturally whether the player is on foot in the middle of a village, at its
edge, or just outside it. The `lazuli.presence.*` translation key is
proposed as `lazuli.presence.near_village` (exact key name a planning-phase
detail, consistent with this spec's other `lazuli.presence.*` keys).

**Version divergence:** `Villager`/`VillagerEntity` and
`BellBlock`/`BellBlockEntity` diverge across the Yarn/Mojang mapping
boundary only in package name (see class paths cited above), not in method
shape — both sides expose the same `Level`/`World`-scoped
"entities of a class within an AABB" query and the same "block entity at a
position" lookup already used elsewhere in this codebase's cross-version
code. Exact method names/signatures must still be `javap`-confirmed against
each of the three platform modules' own resolved jars during implementation
(`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`),
per this repo's mandatory pre-implementation discipline — this spec's class
names above are a planning-time-researched starting point, not a substitute
for that verification.

## Tier Priority / Precedence

The following is a concrete, reasoned ordering (highest to lowest
precedence), presented as a recommendation for the user's approval, not a
unilateral final decision:

1. **In Main Menu.** Short-circuits everything below it — there is no
   loaded world, dimension, biome, or player entity to evaluate any other
   signal against; this is not even an "in a world" state, so nothing else
   in this list is meaningful until a world is loaded.
2. **Paused.** Also short-circuits everything below it, for the same class
   of reason as Main Menu (though a world *is* loaded, the integrated server
   itself is suspended — nothing changes about the player's state while
   paused, so continuing to evaluate movement/biome/village signals
   underneath it would be meaningless busywork and risks showing a stale
   label the instant the pause menu closes).
3. **Spectating.** Takes priority over every remaining tier below it because
   it is a distinct *game mode*, not a momentary activity — a spectating
   player is not "exploring"/"building"/"near a village" in the normal
   survival/creative sense (they may be flying through blocks, invisible to
   other players, etc.), so none of the movement-derived/village/vehicle
   signals below carry the same meaning for a spectator and should not
   override this label.
4. **Riding (Driving through `<biome>` / Sailing through `<biome>`).**
   Placed above "Near a Village" and above the plain movement-derived label,
   because being in a vehicle is the more physically salient, unambiguous
   fact about the player's current activity — a boat sailing past/through a
   village's edge (e.g. a river cutting through or near a village) reads
   more naturally as "Sailing through `<biome>`" than "Near a Village,"
   since the player is demonstrably not stopped to interact with the
   village at all, they are passing through it. This is the concrete
   tie-break the user's feedback asked for explicitly: **Riding wins over
   Near a Village.**
5. **Near a Village (villager/bell proximity).** Below Riding (see above),
   but above the plain movement-derived label — if the player is on foot
   and near a village per the proximity check above, that is a more
   specific, more informative fact than a generic "Exploring `<biome>`"/
   "Staying in `<biome>`," so it overrides the movement-derived label rather
   than being combined with it (i.e. v1 does not attempt "Near a Village
   while Exploring" as a combined string — Near a Village is a standalone
   override at this precedence level).
6. **Movement-derived label (Exploring `<biome>` / Staying in `<biome>` /
   Building in `<biome>`).** The default/fallback tier once nothing above it
   applies — this is deliberately last among the "activity" tiers since it
   is the coarsest, always-computable signal (some movement-derived verb is
   always resolvable once a world is loaded and the player isn't paused,
   spectating, riding, or near a village), matching its role as the base
   case (see Overview's replacement of the old flat "Exploring `<biome>`"
   default).
7. **Digging around (Overworld-only mining/underground label).** This spec
   places it as a *further specialization* of the movement-derived label
   rather than a fully separate precedence level — i.e. it only applies when
   level 6 would otherwise show "Staying in `<biome>`" or "Building in
   `<biome>`" (an underground player is, almost by definition, in a small
   area or actively placing/breaking blocks, not covering "great distance"
   in the Exploring sense) and the player is underground per its own signal
   (Overworld + below the Y/sky-light threshold). If the player is
   underground *and* also meets the Exploring distance threshold (e.g.
   tunneling in a long straight line for a long distance), Exploring's
   wording is used instead of the playful mining label, since "great
   distance covered" is judged the more salient fact in that combination.
   **[CONFIRM OR OVERRIDE — this sub-ordering between Digging-around and
   Exploring-while-underground is this spec's own judgment call, not
   explicitly specified by the user's feedback; flagging for approval.]**
- **Dimension suffix (Nether/End) composition, restated:** the dimension
  suffix is **not** its own precedence level in this list — it is an
  orthogonal modifier applied on top of whichever label wins at levels 3-7
  above (e.g. "Spectating" itself has no biome/dimension text today per its
  existing simple boolean signal, so the suffix in practice only visibly
  composes with levels 4/5/6/7's biome-bearing labels — "Sailing through
  `<biome>` in the Nether," "Staying in `<biome>` in the Nether," etc.). This
  restates, in the context of the full precedence ordering, the resolution
  already established above: dimension is a suffix, never a competing tier.

This ordering, and each numbered justification, is a recommendation for the
user to confirm or amend, exactly like the status-tier table above — it is
not a committed final design.

## Public API
Illustrative shapes only; final names are a planning-phase decision.

1. **`features/rich-presence/services/LocalPresenceTracker`** (new):
   ```java
   public interface LocalPresenceTracker {
       /** Recomputes and returns the current tier's plain, localized string, or empty if no session is active. */
       Optional<String> currentStatus();
   }
   ```
2. **`features/rich-presence/api/RichPresenceFacade#localPresenceStatus(): Optional<String>`**
   (or an equivalent small facade type in `api/src/main/java/de/lazuli/api/richpresence/`)
   — new read accessor (FR-RP6), backed by (1), to be consumed by
   `features/friends-sidebar`'s own-row branch as a follow-up amendment to
   that spec, or landed together (planning-phase sequencing decision). This
   is the sole cross-feature surface this feature exposes.
3. **`SteamFriendsGateway#setLocalRichPresence(String, String)`** — no
   signature change; this feature is a new caller (`key = "status"`), not a
   new method.

See "Addendum: `steam_display` Localization Token Support" below for the
additional `LocalPresenceTracker` surface (`currentTier()`) and new
`RichPresenceTokenMap` component this feature also requires.

## Architecture
```
features/rich-presence/services/LocalPresenceTracker  (new)
  |-- reads: Minecraft.getInstance() client-side world/player/pause state
  |-- reads: player's current biome -> Component.translatable("biome....")/.getString()
  |-- reads: rolling movement/block-placement history (Exploring/Staying/Building)
  |-- reads: nearby Villager entities + nearby Bell block/block-entity state,
  |          ordinary client-visible loaded-chunk sync, host or remote client
  |          alike (Near a Village, see Requirements)
  |-- writes: SteamFriendsGateway.setLocalRichPresence("status", computedString)
              (services/ -- same shared gateway HostingLifecycle already uses
              for the unrelated "connect" key)
  |-- exposed via: features/rich-presence/api/RichPresenceFacade#localPresenceStatus()

platform/fabric-<version>/.../assets/lazuli/lang/en_us.json  (three copies)
  |-- gains new lazuli.presence.* keys (FR-RP2)
```
No dependency on `features/friends-sidebar` or `features/steam-world-hosting`
in either direction beyond the one optional read accessor in Public API item
2, which friends-sidebar may choose to consume — `features/rich-presence`
itself only depends on `services/SteamFriendsGateway` (already a shared
dependency any feature may use) plus ordinary client-side Minecraft state.
This mirrors the layering discipline `features/server-browser/specification.md`
follows (`architecture.md`'s Dependency Rules: a feature depends on `api`/
`services` only, never another feature).

## UI
- No new screen of its own. The only UI-adjacent surface is the *optional*
  own-profile row label change in `features/friends-sidebar` (once that
  spec is amended per FR-RP6) — e.g. "Exploring Plains," "Sailing through
  Ocean in the Nether," "Digging around" — in place of that spec's generic
  `"In Game"` fallback.

## Configuration
None planned for v1 — Rich Presence publishing is always-on whenever Steam
is available, matching every other always-on Steamworks integration in this
codebase (see Non-goals). A future opt-out toggle is a Future Extension.

## Events
No new event-bus entries (this repo has no generic event bus). The
tracker's recompute is a per-tick/per-sweep poll, not push-driven.

## Networking
- The one (already-existing, unchanged-signature) Steamworks call this
  feature newly exercises with a new key is
  `SteamFriends.setRichPresence("status", value)` — already confirmed
  present and already in use (for a different key) by
  `SteamworksSteamFriendsGateway.java:211`/`HostingLifecycle.java:61,80`; no
  new steamworks4j surface to verify for that call. Per the Addendum below,
  the same call is also used, unchanged in signature, for `"steam_display"`
  and the interpolation-variable keys.
- Local, low-latency Steam-client IPC only — no raw network I/O, no new
  per-frame cost beyond the existing debounced write (FR-RP4).
- `SteamFriends.clearRichPresence()` (FR-RP7) is confirmed present in the
  pinned steamworks4j 1.10.0 jar per `.claude/context/minecraft.md`'s
  existing verification row, though this mod has not yet called it anywhere
  — implementers should still `javap`-verify against the actual resolved jar
  before writing the call.
- No new client-server packet for the Near-a-Village detection signal — it
  is deliberately built entirely from state vanilla already syncs to every
  client (entities, block entities), so no custom sync protocol is needed at
  all, for either the host or a remote-joined client.

## Persistence
None. Presence status is a transient, per-tick-recomputed value, never
written to any config/save file.

## Compatibility
- Must land identically across all three platform modules
  (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`)
  — the `Text`/`Component` translatable API divergence and the biome/
  dimension/`Villager`/`BellBlock` registry-key/class-path accessor
  divergence (exact names must be `javap`-confirmed per this repo's
  mandatory pre-implementation discipline, not assumed from this spec's
  illustrative naming) mean the tier-detection/string-resolution code is
  necessarily three separate, structurally-parallel implementations, not a
  single shared one.
- Must not regress `HostingLifecycle`'s existing `"connect"` Rich Presence
  write (FR-RP5) — both features share one gateway method
  (`setLocalRichPresence`) with different keys; no shared mutable state
  between them beyond that method call itself.
- New `en_us.json` lang keys are purely additive — no existing key is
  renamed/removed, no compatibility risk to any existing translation
  consumer.
- **The prior draft's known fidelity gap on remote-multiplayer clients is
  resolved.** The Near-a-Village tier (villager-entity/bell-block proximity)
  depends only on ordinary loaded-chunk entity/block-entity sync, which
  every client receives identically whether it is the integrated-server
  host or a remote-joined client — there is no remaining client-type
  dependent gap for this feature to document. Manual-verification notes
  should still confirm this in practice (spawn/approach a village on both a
  hosting session and a joined-as-guest session and confirm the label
  appears in both), but this is a verification step, not a documented,
  accepted limitation.
- Optionally depends on
  `features/friends-sidebar/specification-own-profile-ingame-status.md`
  having landed (or landing together) for its own-row consumption point
  (FR-RP6) to have somewhere to plug in — but this feature's Rich Presence
  publishing itself (the Steam-facing write) has no hard dependency on that
  spec and can ship independently if only the Steam friends-list string is
  desired without a sidebar-label change.

## Performance
- The tracker's recompute is O(1) per tick for the direct-signal tiers
  (dimension key, biome lookup, pause/spectator/vehicle booleans) and O(1)
  amortized for the rolling-window tiers (a small ring buffer/rolling
  aggregate of recent positions and block-place counts, not a full history
  rescan every tick) — negligible per-tick cost.
- The debounced Steamworks write (FR-RP4, only on actual tier/argument
  change) keeps the `setRichPresence` IPC call infrequent.
- The Near-a-Village check (bounded-radius villager-entity AABB query +
  bounded-radius block-entity lookup, both scoped to already-loaded chunks
  only) is O(nearby entity/block-entity count), not a full-world or
  full-chunk scan, and only evaluated as often as the tracker's own
  recompute cadence, not per-frame.
- No new per-frame render cost beyond a label string swap on the
  friends-sidebar own row, once optionally wired up.

## Future Extensions
- Using composter/lectern counts as an additional or alternative
  Near-a-Village signal, if the bell/villager-count check ever proves
  insufficiently accurate in practice (see Requirements, "Near a Village").
- ~~A `"steam_display"` Valve-side localization token~~ — **implemented, see
  Addendum below** (no longer a Future Extension).
- A settings toggle to disable Rich Presence publishing entirely.
- Extending the "Near a Village" style of proximity-based detection to other
  notable player-visible structure types (e.g. a nether fortress, an ocean
  monument) using their own characteristic synced blocks/entities, once the
  village mechanism is proven out.
- Reusing the tracker's computed tier for a possible future taskbar/overlay
  indicator, or exposing it to other features via the
  `RichPresenceFacade` accessor already scoped in Public API item 2.
- Revisiting Fighting/Sleeping/Died-Respawning only if the underlying
  reasoning (frequency/duration mismatch with Rich Presence's update
  cadence) is itself invalidated by some future design change — not a
  standing backlog item (Non-goals).

## Open Questions (confirm-or-override items for this approval pass; none of
these block planning outright, they are flagged so the user's picks are
visible rather than silently finalized)
1. **Exact rolling-window thresholds** (150s window / 250-block Exploring
   distance / 32-block Staying radius / 20-placements-per-60s Building
   threshold, plus the 24-block bell radius / 32-block, 3-villager Near-a-
   Village thresholds) — all proposed, tunable defaults, not fixed
   (Requirements, "Movement-derived status" and "Near a Village").
2. **Digging-around vs Exploring-while-underground sub-ordering** — this
   spec's own judgment call (Tier Priority / Precedence, level 7); confirm
   or override.
3. **Exact "session active" predicate** — assumes the same predicate as
   `features/friends-sidebar/specification-own-profile-ingame-status.md`
   FR-OP1 ("world loaded, including while at the pause menu"); confirm this
   matches intent, including that a remote-multiplayer client on someone
   else's server also publishes Rich Presence the same way singleplayer
   does (this spec assumes yes — no exception remains for any tier, now
   that Near a Village's remote-client gap is resolved).
4. **Whether this feature should ship together with
   `features/friends-sidebar/specification-own-profile-ingame-status.md`'s
   own-row consumption (FR-RP6), or independently as a Steam-only Rich
   Presence write with no sidebar-label change yet** — both are valid
   phasing choices; flagging for planning-phase sequencing.

---

# Addendum: `steam_display` Localization Token Support

> **STATUS: New addendum, ready for approval.** This section supersedes the
> base spec's original v1 decision to omit `steam_display` (Overview,
> Requirements "Current state", Future Extensions) — that decision is struck
> through above and replaced by the requirements below. Everything else in
> the base spec (tier set, precedence, detection design, non-goals) is
> unchanged and still governs.

## Overview
Steam's Friends List UI does not display arbitrary custom Rich Presence keys
— per Valve's [Enhanced Rich Presence](https://partner.steamgames.com/doc/features/enhancedrichpresence)
documentation, custom keys (like this feature's existing `"status"`) are
just data. The UI only ever renders whatever the special `steam_display` key
points to, and `steam_display`'s value must be a **localization token name**
(e.g. `"#Status_Exploring"`), never free text — Steam looks that token name
up in per-app localization data configured on the Steamworks partner site
(App Admin → Rich Presence Localization, for this app's App ID `5052800`)
and performs `%variable%` substitution against the game's other currently-set
custom keys at render time, in the *viewer's* own Steam client language.

`RichPresencePublisher` currently sets only `"status"` (a plain,
already-Minecraft-localized string) and never sets `steam_display` — so
today, nothing this feature writes is ever actually visible in a friend's
real Steam Friends List. This addendum closes that gap: `steam_display` is
set to the token name matching the current tier, alongside a small,
fixed set of interpolation-variable custom keys the token's localized text
references. `"status"` itself is retained, unchanged, for FR-RP6's
own-profile-row consumption and any other machine-readable consumer.

## Goals
- Make Rich Presence tiers actually render in the real Steam Friends List UI
  by setting `steam_display` to a valid, partner-site-configured
  localization token name whenever `"status"` is set.
- Reuse the existing `SteamFriendsGateway.setLocalRichPresence(String,
  String)` method for every new key — no gateway interface change.
- Route every new key through the same length-guard mechanism `"status"`
  already goes through, if one exists (see "Requirements", `RichPresenceLimits`).
- Ship a companion `.vdf` localization data file
  (`features/rich-presence/steamworks-localization-tokens.vdf`) enumerating
  one token per tier, to be pasted into the Steamworks partner site.

## Non-goals
- No change to `ConnectStringCodec`, the join/invite flow, friends-sidebar,
  or `features/steam-world-hosting` code — strictly scoped to
  `RichPresencePublisher`, the Steam friends gateway's
  `setLocalRichPresence` calls, `RichPresenceLimits` (if present), and the
  new `.vdf` localization file.
- No new Steamworks gateway method — `steam_display` and the interpolation
  keys are written via the same `setLocalRichPresence(key, value)` call
  `"status"` already uses.
- No attempt to have Steam re-localize the biome name or dimension suffix
  per-viewer-language — those substrings are still produced by *this mod's*
  own Minecraft `Text`/`Component` localization (in the local player's own
  client language) and handed to Steam as plain interpolation-variable
  values (see "Design" below); only the token's surrounding sentence
  structure (e.g. "Exploring %biome%%dimensionSuffix%") is Valve-localized
  per viewer. Full per-viewer re-localization of the biome/dimension text
  itself is a Future Extension, not attempted here.
- Does not change the existing tier set, precedence, or detection logic
  defined in the base specification above — purely an additional publish
  step layered on the already-resolved tier.

## Requirements

**FR-RPD1 — `steam_display` is set alongside `"status"`.** Every time
`RichPresencePublisher` writes (or would write) the `"status"` key (FR-RP4's
existing debounce/change-detection), it must also write `"steam_display"` =
the token name for the current tier (see "Tier-to-token mapping" below),
plus that token's supporting interpolation-variable keys (`"biome"`,
`"dimensionSuffix"`). All keys for a given tick are written together, using
the same debounce trigger as `"status"` — not evaluated/debounced
independently per key, to avoid Steam ever showing a mismatched
`steam_display`/interpolation-variable combination mid-transition.

**FR-RPD2 — `LocalPresenceTracker` must expose the raw tier, not just the
formatted string.** `RichPresencePublisher` cannot select the correct token
or format the interpolation variables from a pre-baked `String` alone (the
current `currentStatus(): Optional<String>` contract). `LocalPresenceTracker`
gains a second accessor exposing the currently-resolved tier's kind
(`TierKind`) plus its already-localized biome name and dimension flags, e.g.:

```java
public interface LocalPresenceTracker {
    Optional<String> currentStatus();
    Optional<LocalPresenceTierSnapshot> currentTier(); // new
}

public record LocalPresenceTierSnapshot(
        TierKind kind,
        String localizedBiome,   // "" if this tier carries no biome argument
        boolean nether,
        boolean end) { }
```

Exact type/method naming is a planning-phase decision (e.g. folding this
into a single richer return type from `currentStatus()` instead of a second
method is an equally valid shape) — the requirement is only that
`RichPresencePublisher` (or an equivalent caller) has access to, at minimum,
the resolved `TierKind`, a pre-localized biome string, and the nether/end
flags for the currently-active tier, every tick it also has
`currentStatus()`'s formatted string. `LocalPresenceTrackerImpl` must
compute `localizedBiome` via the same `TierTextFormatter`/biome-translation
seam it already uses internally to build the full formatted string (no new
`net.minecraft.*`-importing code in `RichPresencePublisher` itself — it
stays platform-agnostic, per the base spec's Architecture section).

**FR-RPD3 — Tier-to-token mapping (pure, testable).** A small, pure
function/class (e.g. `RichPresenceTokenMap`, living alongside
`RichPresencePublisher` in `features/rich-presence/services/`) maps each
non-`MAIN_MENU` `TierKind` to its fixed Steamworks localization token name.
This mapping (final, one entry per `TierKind` enumerated in
`TierKind.java:11-20`) is:

| `TierKind` | `steam_display` token | Interpolation keys used by the token's localized text |
|---|---|---|
| `PAUSED` | `#Status_Paused` | none |
| `SPECTATING` | `#Status_Spectating` | none |
| `RIDING_MINECART` | `#Status_RidingMinecart` | `%location%` |
| `RIDING_BOAT` | `#Status_RidingBoat` | `%location%` |
| `NEAR_VILLAGE` | `#Status_NearVillage` | `%location%` |
| `EXPLORING` | `#Status_Exploring` | `%location%` |
| `STAYING` | `#Status_Staying` | `%location%` |
| `BUILDING` | `#Status_Building` | `%location%` |
| `DIGGING_AROUND` | `#Status_DiggingAround` | none (Overworld-only tier — no dimension suffix is ever needed, and this tier carries no biome argument in `PresenceTier`/`TierTextFormatter` today) |
| `MAIN_MENU` | `#Status_MainMenu` | none (literal text "In main menu" — no interpolation variables; see below) |

**Combined `%location%` key (revised from an earlier two-key `%biome%` +
`%dimensionSuffix%` design — see FR-RPD4 for the full "lesson learned"
writeup):** every biome-bearing tier's token references exactly one
interpolation variable, `%location%`, computed fully in code as the
localized biome name plus the dimension suffix (e.g. `"Forest"`, `"Forest in
the Nether"`, `"Forest in the End"`). This key is always present whenever a
tier's token references it — never split into two keys where one could be
omitted while the token still references it.

**`MAIN_MENU` is a real tier with its own token, not "n/a" (resolved per user
feedback — supersedes the table row and FR-RPD3 note above in the base
spec/earlier addendum draft that treated `MAIN_MENU` as having no token).**
`RichPresenceTokenMap.tokenFor(TierKind.MAIN_MENU)` returns
`Optional.of("#Status_MainMenu")`, whose partner-site-configured text is the
literal string `"In main menu"` (no `%biome%`/`%dimensionSuffix%`
interpolation — this tier never carries either). This does **not** change
FR-RP7's clearing behavior: session-inactive/main-menu state still calls
`SteamFriends.clearRichPresence()` and never calls `setLocalRichPresence` at
all (no session is active, so there is nothing to publish a tier for in the
first place) — `#Status_MainMenu` exists in the token map and the `.vdf` file
for completeness/future use (e.g. if a future revision chooses to publish a
"main menu" status instead of clearing), but is not wired to an actual
`setLocalRichPresence("steam_display", "#Status_MainMenu")` call by this
addendum's `RichPresencePublisher` changes, since FR-RP7's clear-on-main-menu
behavior is unchanged and takes precedence.

`RichPresenceTokenMap` must throw/fail closed (return `Optional.empty()` or
equivalent, logged, non-throwing per this codebase's `SteamFriendsGateway`
non-throwing convention) rather than write a malformed `steam_display` value
if it is ever asked to map `MAIN_MENU` or an unrecognized `TierKind` — this
should not happen given `LocalPresenceTrackerImpl`'s existing FR-RP7
short-circuit, but the mapper itself must not assume that invariant silently.

**FR-RPD4 — Interpolation-variable key (revised — single `location` key,
superseding the original two-key `"biome"`/`"dimensionSuffix"` design below).**
One new custom Rich Presence key is written, only for tiers whose token
references it (`RIDING_MINECART`, `RIDING_BOAT`, `NEAR_VILLAGE`, `EXPLORING`,
`STAYING`, `BUILDING` — every biome-bearing tier per
`PresenceTier.biomeBearing`):
- `"location"` — the tier's already-localized biome display name combined
  with the dimension suffix into a single string, fully composed in code:
  `"Plains"` (Overworld), `"Plains in the Nether"`, `"Plains in the End"`.
  Sourced from `LocalPresenceTierSnapshot.localizedBiome()` (FR-RPD2) plus
  the existing dimension-suffix logic (`" in the Nether"` for `nether`,
  `" in the End"` for `end`, `""` otherwise) — the dimension-suffix logic
  itself is unchanged, it is simply concatenated into `location` in code
  rather than emitted as its own separate key. This composition belongs in
  `RichPresencePublisher` (plain string formatting, no `net.minecraft.*`
  dependency) — not in `LocalPresenceTrackerImpl`, which only needs to
  expose the raw `nether`/`end` booleans and the localized biome name
  (FR-RPD2, unchanged).

~~The original design used two separate keys, `"biome"` and
`"dimensionSuffix"`, with token text referencing both `%biome%` and
`%dimensionSuffix%` (e.g. `"Staying in %biome%%dimensionSuffix%"`).~~ **This
was a confirmed live bug and is superseded.** Root cause: Steam's Rich
Presence `%variable%` interpolation only substitutes a variable if that
exact key was set via `SetRichPresence` in the *same* call/session. The
"never send empty string" rule below correctly omitted `"dimensionSuffix"`
entirely for Overworld tiers — but the token text still referenced
`%dimensionSuffix%` unconditionally, so Steam had no value to substitute and
rendered the literal, un-substituted text to real friends (e.g. "Staying in
Forest%dimensionSuffix%" instead of "Staying in Forest"). **Lesson learned:**
never design a Rich Presence token referencing a `%variable%` that could be
absent for the tier that token is active for — a single combined `location`
key, always present whenever its token is active, closes this class of bug
structurally rather than requiring every future tier addition to remember to
keep two keys in lockstep.

**Empty/absent-value keys are never written as `""` — resolved, no longer
open (supersedes addendum Open Question 2 below, which is now closed).** For
any tier and any of the three keys this feature ever writes
(`"status"`, `"steam_display"`, `"location"`), if the value that would be
written is empty or logically absent — a non-biome-bearing tier's
`"location"` (`PAUSED`, `SPECTATING`, `DIGGING_AROUND`, `MAIN_MENU`) — that
key is **omitted from the `setLocalRichPresence` call(s) for that tick
entirely**, never sent as `setLocalRichPresence(key, "")`. This applies
uniformly across every tier, not case-by-case: `RichPresencePublisher` must
check for empty/absent before each individual key write and skip the call
rather than passing through an empty string. `RichPresenceTokenMap`'s
tier-to-token table and FR-RPD6's test coverage are both written against
this single rule — there is no separate "clear with empty string" path for
these keys; a tier transition that drops a previously-populated key (e.g.
`EXPLORING` in the Nether transitioning to `PAUSED`) is handled the same way
every other absent value is: the key is simply not part of that tick's write
set. Critically, this omission rule is now safe precisely because no
non-biome-bearing tier's token ever references `%location%` in the first
place (see the `.vdf` file) — the earlier bug was the combination of "omit
when absent" plus "token references it anyway"; this revision removes the
second half of that combination for every tier, not just some.

**FR-RPD5 — Length-guard reuse, if present.** `RichPresenceLimits`
(`services/src/main/java/de/lazuli/services/steamworks/RichPresenceLimits.java`)
does **not currently exist** in this codebase as of this writing (confirmed
via repo search; it appears only as a *planned* class in
`docs/plans/steam-rich-presence.md`/`docs/specs/steam-rich-presence.md`, a
separate, not-yet-implemented plan for `steam-world-hosting`'s
`steam_player_group`/`steam_player_group_size` keys). Two cases:
  - **If `RichPresenceLimits` (or an equivalent guard) has landed by the
    time this addendum is implemented**, every new key this addendum
    introduces (`steam_display`, `location`) must go through
    the identical guard `"status"` uses — whether that guard lives inside
    `SteamworksSteamFriendsGateway.setLocalRichPresence` itself (in which
    case it already applies transparently to every key, including these new
    ones, with no extra call site needed) or is invoked explicitly by
    `RichPresencePublisher` before calling `setLocalRichPresence`. No new
    key introduced by this addendum may bypass that guard.
  - **If no such guard exists yet**, this addendum does not introduce one
    (out of scope — the guard is a separate, already-planned piece of work
    tracked in `docs/plans/steam-rich-presence.md`); it only requires that
    *if and when* that guard lands, these new keys are wired through it
    identically to `"status"`, per the bullet above. Either way, token names
    (max ~20 chars) and interpolation values (biome names, dimension
    suffixes — all well under 256 chars) are comfortably within Valve's
    documented `SetRichPresence` limits (64-char key / 256-char value / 20
    keys total) even without an explicit runtime guard.

**FR-RPD6 — Unit test coverage.** `RichPresencePublisherTest.java` (or a new
sibling test file for `RichPresenceTokenMap` if split out) must assert, per
tier, using the existing fake/mocked `SteamFriendsGateway` pattern already
established in that file (`Mockito.mock(SteamFriendsGateway.class)`,
`verify(gateway, times(1)).setLocalRichPresence(eq(key), eq(value))`):
- For each biome-bearing tier (`RIDING_MINECART`, `RIDING_BOAT`,
  `NEAR_VILLAGE`, `EXPLORING`, `STAYING`, `BUILDING`): `steam_display` is set
  to that tier's token name, and `"location"` is set to the expected
  combined biome+suffix string (e.g. `"Plains"`, `"Plains in the Nether"`,
  `"Plains in the End"`) for at least one Overworld and one Nether-or-End
  case.
- For non-biome-bearing tiers (`PAUSED`, `SPECTATING`, `DIGGING_AROUND`):
  `steam_display` is set to that tier's token name; `"location"` is never
  written (omitted entirely, per FR-RPD4).
- `MAIN_MENU`/no-session-active: no `steam_display`/`location` write occurs,
  and the existing `clearLocalRichPresence()` behavior (FR-RP7) is
  unaffected/unchanged.
- The existing debounce tests (`writesOnlyOnActualChange`,
  `logsExactlyOnceOnActualChange`, `logsExactlyOnceOnClear`,
  `clearsOnlyOncePerPresentToEmptyTransition`,
  `neverCallsSetLocalRichPresenceWithConnectKey`,
  `logsWarningAndDoesNotThrowWhenSteamRejectsWrite`,
  `doesNotClearOnFirstTickWhenAlreadyEmpty`) must continue to pass essentially
  unchanged in spirit — they may need updating for the `ScriptedTracker` fake
  to also supply `currentTier()` values, but their debounce/logging/clearing
  assertions are not weakened by this addendum.
- `neverCallsSetLocalRichPresenceWithConnectKey` is extended in spirit: the
  publisher must never call `setLocalRichPresence` with `key = "connect"`
  regardless of how many keys it now writes per tick (FR-RP5 unchanged).

## Public API
1. **`LocalPresenceTracker#currentTier(): Optional<LocalPresenceTierSnapshot>`**
   (new) — see FR-RPD2. Illustrative shape only; final naming/whether this
   is folded into `currentStatus()`'s return type instead is a
   planning-phase decision.
2. **`RichPresenceTokenMap`** (new, `features/rich-presence/services/`) —
   pure function, illustrative shape:
   ```java
   public final class RichPresenceTokenMap {
       /** @return the steam_display token name for the given tier, or empty for MAIN_MENU/unrecognized. */
       public Optional<String> tokenFor(TierKind kind) { ... }
   }
   ```
3. **`SteamFriendsGateway#setLocalRichPresence(String, String)`** — no
   signature change; this addendum is additional callers (`key =
   "steam_display"`, `"biome"`, `"dimensionSuffix"`), exactly as the base
   spec's `"status"` key already is.
4. **`RichPresenceLimits`** (if/when it exists) — no signature change
   assumed; this addendum's new keys are expected to satisfy its existing
   guard shape (`keyWithinLimit(String)`/`valueWithinLimit(String)` per
   `docs/specs/steam-rich-presence.md:226-229`) without modification.

## Architecture
```
features/rich-presence/services/RichPresencePublisher
  |-- reads: LocalPresenceTracker.currentStatus()      (existing, "status" key)
  |-- reads: LocalPresenceTracker.currentTier()         (new, FR-RPD2)
  |-- uses:  RichPresenceTokenMap.tokenFor(TierKind)     (new, FR-RPD3)
  |-- formats: "" / " in the Nether" / " in the End"    (dimensionSuffix, FR-RPD4)
  |-- writes (same debounce trigger as "status"):
  |     SteamFriendsGateway.setLocalRichPresence("status", ...)          (unchanged)
  |     SteamFriendsGateway.setLocalRichPresence("steam_display", ...)   (new)
  |     SteamFriendsGateway.setLocalRichPresence("biome", ...)           (new, biome-bearing tiers only)
  |     SteamFriendsGateway.setLocalRichPresence("dimensionSuffix", ...) (new, biome-bearing tiers only)
  |-- (if present) each write passes through RichPresenceLimits, same as "status" today

features/rich-presence/steamworks-localization-tokens.vdf  (new, data file, not code)
  |-- pasted manually into Steamworks partner site, App Admin > Rich Presence
      Localization, App ID 5052800
```
No new dependency edges beyond the base spec's existing
`services/SteamFriendsGateway` usage — `RichPresenceTokenMap` is a new,
plain-JVM pure-function class living in the same package as
`RichPresencePublisher`, with zero `net.minecraft.*`/`com.codedisaster.*`
import, matching this feature's existing layering.

## UI
No UI change. The rendering surface this addendum actually fixes is Steam's
own client-native Friends List UI (out of process, not this mod's own UI) —
this is the entire point of the addendum: without it, nothing this feature
writes is visible there at all.

## Configuration
None. `steam_display`/interpolation-key publishing is not separately
configurable — it is written whenever `"status"` is written, per FR-RPD1,
matching the base spec's always-on design.

## Events
No new event-bus entries — folds into the same per-tick `tick()` debounce
path `RichPresencePublisher` already has.

## Networking
- Same `SteamFriends.setRichPresence(key, value)` steamworks4j call already
  confirmed present and in use — no new native surface to verify. This
  addendum is purely additional key/value pairs through the identical call.
- No Steam-side network round trip is required to *set* these keys (local
  IPC to the running Steam client only, per the base spec's Networking
  section) — Steam's own re-localization/lookup of the `steam_display`
  token against the Steamworks partner site's configured localization data
  happens on Valve's backend when a *friend* views the Friends List, not at
  write time; this mod never calls out to that lookup itself.

## Persistence
- The new `features/rich-presence/steamworks-localization-tokens.vdf` file
  is checked into the repo as data (not loaded/parsed by any runtime code —
  the mod itself never reads this file; it exists solely to be manually
  copy-pasted into the Steamworks partner site's Rich Presence Localization
  admin page). No runtime persistence implications.
- No other change to this feature's "no persistence" stance (base spec's
  Persistence section) — `steam_display`/interpolation values remain
  transient, per-tick-recomputed, never written to any save/config file.

## Compatibility
- Additive only: `"status"` continues to be written exactly as before
  (FR-RP4 unchanged) — FR-RP6's own-profile-row consumption and any other
  existing reader of the `"status"` key is unaffected.
- Must not regress FR-RP5 (never touching `"connect"`) — the new keys this
  addendum introduces (`steam_display`, `location`) are additional, distinct
  keys, not a replacement for or interference with `"connect"`.
- **Manual, one-time, out-of-band setup requirement:** the token text in
  `steamworks-localization-tokens.vdf` must be pasted into the Steamworks
  partner site (App Admin → Rich Presence Localization, App ID `5052800`)
  before `steam_display` will actually render anything for real players —
  this is not something the mod's own build/release process can automate
  (no public Steamworks Web API for this data as of Valve's current
  documentation); this must be tracked as a manual release-checklist step,
  not assumed to happen automatically alongside a code deploy.
- **Keep-in-sync requirement:** any future tier added to `TierKind` (and
  `RichPresenceTokenMap`) needs a matching entry added to
  `steamworks-localization-tokens.vdf` *and* uploaded to the partner site —
  the `.vdf` file's header comment (see "Persistence"/file contents below)
  must say this explicitly, since the two are otherwise silently divergent
  (a new `TierKind` with no corresponding partner-site token would simply
  render as a raw, unresolved token name, e.g. `"#Status_NewTier"`, to
  friends viewing it, rather than failing loudly).

## Performance
No measurable change — the addendum adds at most 2 additional
`setRichPresence` IPC calls per debounced status change (same trigger,
same infrequency as the existing single `"status"` call, FR-RP4); no new
per-tick computation beyond a `TierKind`-keyed map lookup and a 3-way string
switch plus a concatenation for the combined `location` value, both O(1).

## Future Extensions
- Per-viewer re-localization of the biome name itself (rather than only the
  surrounding sentence structure) — would require either shipping every
  biome's name as its own token-referenced variable text per language on
  the partner site (a large, high-maintenance token set), or accepting that
  friends viewing in a different Steam client language still see the biome
  name in the *local player's* Minecraft client language. Not attempted in
  this addendum; the current design already achieves the primary goal
  (something renders at all) with a bounded, one-time token set.
- Composter/lectern-based Near-a-Village refinement (base spec, unchanged
  Future Extension) is orthogonal to this addendum and unaffected by it.

## New file: `features/rich-presence/steamworks-localization-tokens.vdf`

Required contents (illustrative — exact English wording of each token's
localized text is a planning/implementation-phase wording decision, but the
file's structure, one token per `TierKind` per FR-RPD3's table, and the
required header comment, are fixed by this addendum):

```
// steamworks-localization-tokens.vdf
//
// NOT loaded or parsed by this mod at runtime. This file exists solely as
// data to be manually copy-pasted into the Steamworks partner site:
// App Admin -> Rich Presence Localization, App ID 5052800.
//
// KEEP IN SYNC: every TierKind in
// features/rich-presence/src/main/java/de/lazuli/features/richpresence/services/TierKind.java
// must have exactly one corresponding token here (mapping defined in
// RichPresenceTokenMap / features/rich-presence/specification.md's
// "Addendum: steam_display Localization Token Support", FR-RPD3). Adding a
// new tier without adding + uploading its token here means steam_display
// will render as a raw, unresolved token name to friends viewing it.
//
// CRITICAL RULE (lesson learned from a confirmed live bug): Steam's Rich
// Presence %variable% interpolation ONLY substitutes a variable if that
// exact key was set via SetRichPresence in the SAME call/session. If a
// token's text below references a %variable%, the game code MUST set that
// key every single time this token is the active steam_display value --
// never for some tiers/cases and not others. A previous version of this
// file referenced both %biome% and %dimensionSuffix% separately; the
// dimensionSuffix key was (correctly, per the "never send empty string"
// rule) omitted entirely for Overworld tiers, but the token text still said
// "...%biome%%dimensionSuffix%", so Steam had nothing to substitute for
// %dimensionSuffix% and showed the literal text "Staying in
// Forest%dimensionSuffix%" to real friends. The fix: every biome-bearing
// token below now references a single %location% variable (biome name +
// dimension suffix, fully composed in code, e.g. "Forest in the Nether")
// that is always present whenever its token is active -- never reference a
// variable in a token that could be absent for that tier.
"lang" "english"
"Tokens"
{
    "#Status_Paused"          "Paused"
    "#Status_Spectating"      "Spectating"
    "#Status_RidingMinecart"  "Driving through %location%"
    "#Status_RidingBoat"      "Sailing through %location%"
    "#Status_NearVillage"     "Near a Village in %location%"
    "#Status_Exploring"       "Exploring %location%"
    "#Status_Staying"         "Staying in %location%"
    "#Status_Building"        "Building in %location%"
    "#Status_DiggingAround"   "Digging around"
    "#Status_MainMenu"        "In main menu"
}
```

## Open Questions (addendum-specific) — resolved per user approval pass

All four items below have been answered by the user and are no longer open;
each entry states the resolution and where it is now reflected in this
addendum.

1. **Where `RichPresenceLimits` guard invocation happens, if/when it lands**
   — approved as-is (FR-RPD5's existing "either shape is acceptable,
   whichever is chosen must apply uniformly" language stands unchanged; no
   guard exists yet, so this is not blocking).
2. **Whether `"biome"`/`"dimensionSuffix"` are omitted entirely or written as
   `""` for non-biome-bearing/Overworld tiers** — **resolved: always omit,
   never write an empty string.** See the "Empty/absent-value keys are never
   written as `""`" requirement above (under FR-RPD4), which is now the
   binding rule and supersedes this question.
3. **Whether `LocalPresenceTracker` gains a second method (`currentTier()`)
   or folds tier/biome/dimension data into a single richer return type** —
   approved as-is; FR-RPD2's illustrative two-method shape stands, exact
   shape remains a planning-phase decision within that constraint.
4. **Exact English wording of each token's localized text in the `.vdf`
   file** — approved as-is for the eight pre-existing tiers (the wording
   already proposed in the "New file" section above is final). The one new
   addition, `MAIN_MENU` -> `#Status_MainMenu` -> `"In main menu"` (literal
   text, no interpolation variables), is confirmed final per the user's
   explicit request that `MAIN_MENU` be a real tier with its own token
   rather than "n/a" — see the `MAIN_MENU` row/note in the "Tier-to-token
   mapping" table above (FR-RPD3) and the `.vdf` contents above.
</content>

# Verification Report -- Rich Presence Publishing

Verified against features/rich-presence/specification.md and
features/rich-presence/plan.md. Verification relied on gradlew
build/test output (incremental, no --rerun-tasks), direct reads of every
new/modified source file, and cross-referencing against
.claude/context/minecraft.md's new rows for the claimed javap findings.
No live in-game testing was performed (manual-only per Test Strategy).

## 1. Build and test -- confirmed GREEN

- gradlew :features:rich-presence:test :api:compileJava (incremental):
  BUILD SUCCESSFUL, all tasks up-to-date/passing.
- gradlew compileJava for all three platform:fabric-* modules:
  BUILD SUCCESSFUL.
- settings.gradle registers include features:rich-presence.

## 2. Client entrypoint registration -- confirmed on all three platforms

Every platform/fabric-* module depending on features:rich-presence
(implementation project(':features:rich-presence'), confirmed in all
three build.gradle files) also lists
de.lazuli.RichPresenceClientInitializer in its fabric.mod.json client
entrypoints array (fabric-1.21.11, fabric-26.1, fabric-26.2, all line 28,
positioned after SteamworksClientInitializer as the plan intended). No
silent-inert gap.

## 3. Requirements cross-check against code

- Dimension suffix as orthogonal modifier, not a competing tier --
  confirmed. PresenceStatusResolver never branches on nether/end
  directly; PresenceTier carries the flags through unchanged, and
  MinecraftTierTextFormatter.format() wraps the base label in
  lazuli.presence.dimension_suffix.nether/.end ("%s in the Nether" /
  "%s in the End") only after the base label is resolved.
  PresenceStatusResolverTest.dimensionSuffixFlagsCarryThroughOnBiomeBearingTiers
  exercises this directly.
- Movement-derived tiers + preposition + thresholds -- confirmed.
  en_us.json (identical across all three platform modules): "Exploring
  %s", "Staying in %s", "Building in %s" -- in, not at. Constants:
  EXPLORING_DISTANCE_THRESHOLD_BLOCKS = 250.0,
  BUILDING_PLACEMENT_THRESHOLD = 20 (confirmed lowered from 40, matches
  the spec correction). 150s/60s rolling windows implemented in each
  platform module (ROLLING_WINDOW_MILLIS = 150000,
  BUILDING_WINDOW_MILLIS = 60000). Note: stayRadius150s is populated but
  never actually consulted as a gating condition by the resolver --
  Staying is purely the fallback once Exploring/Building do not fire,
  which matches the spec text but leaves the 32-block radius unenforced
  as a hard bound (minor, flagged below).
- Riding split (Driving/Sailing) with biome -- confirmed. TierKind
  RIDING_MINECART/RIDING_BOAT, VehicleKind enum, platform vehicleKind()
  using AbstractMinecartEntity/AbstractBoatEntity (Yarn) and
  AbstractMinecart/AbstractBoat (Mojang, correct sub-package per the
  documented javap findings). Lang keys match spec wording exactly.
- Mining label Digging around, Overworld-only -- confirmed. underground
  = not nether, not end, and Y <= 40 in PresenceSignalGatherer (Y-only
  heuristic, an accepted simplification documented in the plan's
  Implementation Notes vs the spec's Y-and/or-sky-light allowance). Lang
  key matches the confirmed final wording exactly.
- Fighting/Sleeping/Died-Respawning correctly excluded -- confirmed. No
  such TierKind members and no reference anywhere in this feature's code.
- Near-a-Village via Villager/Bell proximity, not StructureManager --
  confirmed. scanNearVillage uses World.getChunk(int,int) plus
  WorldChunk.getBlockEntities() (bells) and
  World.getEntitiesByType(TypeFilter, Box, predicate) (villagers, Yarn
  side) / the Mojang-mapped equivalents -- both ordinary client-visible,
  loaded-chunk-scoped reads with no StructureManager/server-only
  dependency, so this works identically for host and remote-joined
  clients. Thresholds match (24-block bell radius, 32-block/3-villager
  threshold, NEAR_VILLAGE_VILLAGER_THRESHOLD = 3 in the resolver).
- Village-scan performance: bounded, throttled, and genuinely documented
  -- confirmed, not just an unverified comment. Scan is gated by
  tickCounter modulo NEAR_VILLAGE_SCAN_INTERVAL_TICKS == 0 with the
  interval set to 20 (once per about 1s, not per tick). The bell scan
  iterates a fixed 5x5 chunk neighborhood (radius 2 chunks) and filters
  each chunk's own small, already-materialized getBlockEntities() map --
  no raw per-block-position iteration. .claude/context/minecraft.md
  gained a real row recording the actual javap-confirmed method shapes
  on both mapping sides, and the platform-module Javadoc
  cross-references this reasoning -- mechanism and documentation
  genuinely match.
- Tier-priority resolver is a genuine, justified ladder -- confirmed.
  resolve() implements Main Menu greater than Paused greater than
  Spectating greater than Riding greater than Near Village greater than
  movement-derived (Exploring greater than Building greater than
  Staying) greater than Digging-around-as-specialization, matching the
  spec's Tier Priority section level for level, each branch justified by
  an inline comment. Not an arbitrary if-chain -- unit-tested at every
  documented precedence tie-break and boundary value.
- FR-RP5 (no connect-key clobbering) -- confirmed structurally and by
  test. RichPresencePublisher's STATUS_KEY constant is "status" and is
  the only key literal referenced anywhere in the class; no
  import/reference to ConnectStringCodec/HostingLifecycle exists in this
  feature's source tree. A dedicated test directly asserts
  setLocalRichPresence is never invoked with key "connect".

## 4. Architecture / dependency-direction check

- features/rich-presence/build.gradle: api project(':api') plus
  implementation project(':services') only -- no dependency on
  features:friends-sidebar or features:steam-world-hosting, matching the
  spec's Architecture section and the plan's Dependencies section.
- FriendSidebarWidget.java (all three platform modules) is modified in
  the working tree, but that change has no reference to
  RichPresenceFacade/RichPresenceFacadeHandoff at all -- it is unrelated
  in-flight work for the separate, already-approved
  specification-own-profile-ingame-status.md spec (a generic
  world-not-null "showText" plumbing change), not FR-RP6 wiring. The
  plan's own "Not modified by this plan" note (FR-RP6 consumption
  deferred) is honored.

## 5. Minor observations / follow-ups (not blocking)

1. PresenceSignals.stayRadius150s is populated by every platform
   gatherer but never consulted by PresenceStatusResolver -- the
   32-block Staying-radius threshold is not actually enforced as an
   upper bound anywhere; Staying is purely the fallback once
   Exploring/Building do not fire. Reasonable simplification consistent
   with the spec's wording, but the field is otherwise dead weight in
   the resolver's decision logic.
2. Underground heuristic is Y-only (Y <= 40), not Y-and/or-sky-light as
   the spec allowed -- explicitly documented as an accepted
   simplification in the plan's Implementation Notes, not a silent gap.
3. FR-RP6's real payoff (sidebar label consuming the computed status) is
   not delivered by this plan, as explicitly scoped in Decision 4/Risk 5
   -- RichPresenceFacade/RichPresenceFacadeImpl/RichPresenceFacadeHandoff
   exist and are correctly wired per-platform, but nothing in
   friends-sidebar consumes them yet. Scoped-out, not missing.

## Summary

PASS. All FR-RP1 through FR-RP7 requirements are implemented as
specified: dimension composes as a suffix (not a competing tier),
movement-derived tiers use "in" wording with the confirmed
250-block/20-placement thresholds, Riding is split into Driving/Sailing,
mining is "Digging around" and Overworld-only, Fighting/Sleeping/
Died-Respawning are correctly absent, Near-a-Village uses the
Villager/Bell proximity mechanism (not StructureManager) and works
identically for host/remote-joined clients, the village scan is
genuinely throttled (20-tick interval) and bounded (5x5 chunk
neighborhood), matching its documentation. The tier resolver is a real,
justified precedence ladder, thoroughly unit-tested including exact
boundary values. FR-RP5's "never touch connect" guarantee is both
structurally true and directly regression-tested. The new
features/rich-presence Gradle module compiles, is registered in
settings.gradle, and its plain-JVM unit tests pass. All three
platform/fabric-* modules that depend on this feature list
RichPresenceClientInitializer in their fabric.mod.json client
entrypoints -- no silently-inert platform.

package de.lazuli.crossworldstats;

import de.lazuli.api.crossworldstats.TrackedStat;
import de.lazuli.features.crossworldstats.services.CrossWorldStatsService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Version Adapter for the Cross-World Stats feature on this platform
 * module (mandatory-first-step {@code javap} pass confirmed:
 * {@code net.minecraft.stats.Stats}/{@code StatType}/{@code StatsCounter}
 * shape, {@code LocalPlayer.getStats()}, {@code Minecraft.player} field,
 * {@code MinecraftServer.getWorldPath(LevelResource.ROOT)} -- see
 * {@code .claude/context/minecraft.md}'s Known Cross-Version API Differences
 * table).
 *
 * <p>Registered against {@code ClientTickEvents.END_CLIENT_TICK} by
 * {@code CrossWorldStatsClientInitializer} (FR2.1 Decision 2: a rate-limited
 * tick poll, not {@code ServerLifecycleEvents.BEFORE_SAVE}); the actual
 * interval gating lives in {@link CrossWorldStatsService#tick(String,
 * Supplier)} itself (plain-JVM, shared by every platform), so this class only
 * ever supplies "what world is this" (FR3.1) and "what are its current
 * tracked-stat values right now" (FR2.2) -- both cheap, since the interval
 * gate means the actual {@link StatsCounter} read only happens once per
 * merge interval, not every tick.
 *
 * <p>World identifier resolution (Decision 3): this feature has no
 * compile-time dependency on {@code features:steam-cloud-sync}, and no
 * composition-root handoff publishing that feature's own
 * {@code WorldFingerprint}-equivalent resolution was found at this
 * platform's own wiring layer (implementation-plan Risk 2) -- so this class
 * always uses the accepted fallback identifier directly: the current
 * singleplayer world's own save-folder name, or {@code "remote:" + serverIp}
 * for a real multiplayer session. A world rename would then be read as "a
 * different world" (resetting that one world's baseline, spec's own
 * documented, bounded degradation), never corrupting the global total.
 */
public final class CrossWorldStatsMergeHook {

    /**
     * Every vanilla "distance traveled" custom stat this feature sums into
     * one {@link TrackedStat#DISTANCE_TRAVELED_CM} aggregate (FR4.1: "not
     * attempting per-category breakdown").
     */
    private static final Identifier[] DISTANCE_STAT_IDS = {
            Stats.WALK_ONE_CM, Stats.CROUCH_ONE_CM, Stats.SPRINT_ONE_CM, Stats.WALK_ON_WATER_ONE_CM,
            Stats.FALL_ONE_CM, Stats.CLIMB_ONE_CM, Stats.FLY_ONE_CM, Stats.WALK_UNDER_WATER_ONE_CM,
            Stats.MINECART_ONE_CM, Stats.BOAT_ONE_CM, Stats.PIG_ONE_CM, Stats.HAPPY_GHAST_ONE_CM,
            Stats.HORSE_ONE_CM, Stats.AVIATE_ONE_CM, Stats.SWIM_ONE_CM, Stats.STRIDER_ONE_CM, Stats.NAUTILUS_ONE_CM
    };

    private final CrossWorldStatsService service;

    /**
     * The world id resolved at {@code ClientPlayConnectionEvents.JOIN} time
     * (and opportunistically refreshed by every live {@link #tick}), used as
     * a fallback by {@link #flush} for the disconnect/shutdown path -- by the
     * time {@code DISCONNECT}/{@code CLIENT_STOPPING} actually fires, the
     * integrated server may already be torn down (its connection close is
     * what triggers those very events), so {@link #resolveWorldId} freshly
     * called at that moment can no longer see {@code hasSingleplayerServer()}
     * as {@code true} and would otherwise resolve {@code null} -- silently
     * losing the just-played session's final merge (never recorded into
     * {@code AccountStats.worldBaselines}, so it would never show up as a
     * scanned save in the Statistics tab either).
     */
    private volatile String cachedWorldId;

    public CrossWorldStatsMergeHook(CrossWorldStatsService service) {
        this.service = service;
    }

    /** Called once a play session's network handler is ready (world-join time), while the world/server state is still fully valid. */
    public void onJoin(Minecraft client) {
        cachedWorldId = resolveWorldId(client);
    }

    /** Called once per client tick (FR2.1); cheap even when the merge interval hasn't elapsed. */
    public void tick(Minecraft client) {
        String worldId = resolveWorldId(client);
        if (worldId != null) {
            cachedWorldId = worldId;
        }
        service.tick(worldId, () -> readCurrentValues(client));
    }

    /**
     * Forces an immediate flush regardless of the interval gate (client
     * shutdown/disconnect). Prefers the id cached at join/tick time over a
     * fresh {@link #resolveWorldId} call, since the latter can legitimately
     * resolve {@code null} once the integrated server has already begun
     * tearing down by the time this fires.
     */
    public void flush(Minecraft client) {
        String worldId = resolveWorldId(client);
        if (worldId == null) {
            worldId = cachedWorldId;
        }
        service.flush(worldId, () -> readCurrentValues(client));
        cachedWorldId = null;
    }

    private static String resolveWorldId(Minecraft client) {
        boolean hasSingleplayerServer = client.hasSingleplayerServer();
        if (hasSingleplayerServer) {
            var server = client.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            Path savePath = server.getWorldPath(LevelResource.ROOT).normalize();
            Path fileName = savePath.getFileName();
            return "local:" + (fileName != null ? fileName.toString() : savePath.toString());
        }
        ServerData current = client.getCurrentServer();
        if (current != null && current.ip != null && !current.ip.isBlank()) {
            return "remote:" + current.ip;
        }
        return null;
    }

    private static Map<TrackedStat, Long> readCurrentValues(Minecraft client) {
        if (client.player == null) {
            return Map.of();
        }
        StatsCounter stats = client.player.getStats();

        Map<TrackedStat, Long> values = new EnumMap<>(TrackedStat.class);
        values.put(TrackedStat.PLAY_TIME_TICKS, (long) stats.getValue(Stats.CUSTOM, Stats.PLAY_TIME));
        values.put(TrackedStat.DEATHS, (long) stats.getValue(Stats.CUSTOM, Stats.DEATHS));

        long mined = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            mined += stats.getValue(Stats.BLOCK_MINED, block);
        }
        values.put(TrackedStat.BLOCKS_MINED, mined);

        long killed = 0;
        for (var entityType : BuiltInRegistries.ENTITY_TYPE) {
            killed += stats.getValue(Stats.ENTITY_KILLED, entityType);
        }
        values.put(TrackedStat.MOB_KILLS, killed);

        long crafted = 0;
        for (var item : BuiltInRegistries.ITEM) {
            crafted += stats.getValue(Stats.ITEM_CRAFTED, item);
        }
        values.put(TrackedStat.ITEMS_CRAFTED, crafted);

        long distance = 0;
        for (Identifier distanceStatId : DISTANCE_STAT_IDS) {
            distance += stats.getValue(Stats.CUSTOM, distanceStatId);
        }
        values.put(TrackedStat.DISTANCE_TRAVELED_CM, distance);

        return values;
    }
}

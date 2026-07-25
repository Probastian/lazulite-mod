package de.lazuli.crossworldstats;

import de.lazuli.api.crossworldstats.TrackedStat;
import de.lazuli.features.crossworldstats.services.CrossWorldStatsService;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.registry.Registries;
import net.minecraft.stat.StatHandler;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Version Adapter for the Cross-World Stats feature on this platform
 * module (mandatory-first-step {@code javap} pass confirmed:
 * {@code net.minecraft.stat.Stats}/{@code StatType}/{@code StatHandler}
 * shape, {@code ClientPlayerEntity.getStatHandler()},
 * {@code MinecraftClient.player} field, {@code MinecraftServer.getSavePath
 * (WorldSavePath.ROOT)} -- see {@code .claude/context/minecraft.md}'s Known
 * Cross-Version API Differences table).
 *
 * <p>See the 26.x sibling class's own Javadoc for the shared rate-limiting
 * (Decision 2) and world-identifier-fallback (Decision 3) rationale -- both
 * identical here, only the vanilla API surface itself differs (Yarn mapping,
 * package {@code net.minecraft.stat} not {@code net.minecraft.stats}).
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
     * called at that moment can no longer see {@code isIntegratedServerRunning()}
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
    public void onJoin(MinecraftClient client) {
        cachedWorldId = resolveWorldId(client);
    }

    /** Called once per client tick (FR2.1); cheap even when the merge interval hasn't elapsed. */
    public void tick(MinecraftClient client) {
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
    public void flush(MinecraftClient client) {
        String worldId = resolveWorldId(client);
        if (worldId == null) {
            worldId = cachedWorldId;
        }
        service.flush(worldId, () -> readCurrentValues(client));
        cachedWorldId = null;
    }

    private static String resolveWorldId(MinecraftClient client) {
        boolean isIntegratedServerRunning = client.isIntegratedServerRunning();
        if (isIntegratedServerRunning) {
            var server = client.getServer();
            if (server == null) {
                return null;
            }
            Path savePath = server.getSavePath(WorldSavePath.ROOT).normalize();
            Path fileName = savePath.getFileName();
            return "local:" + (fileName != null ? fileName.toString() : savePath.toString());
        }
        ServerInfo current = client.getCurrentServerEntry();
        if (current != null && current.address != null && !current.address.isBlank()) {
            return "remote:" + current.address;
        }
        return null;
    }

    private static Map<TrackedStat, Long> readCurrentValues(MinecraftClient client) {
        if (client.player == null) {
            return Map.of();
        }
        StatHandler stats = client.player.getStatHandler();

        Map<TrackedStat, Long> values = new EnumMap<>(TrackedStat.class);
        values.put(TrackedStat.PLAY_TIME_TICKS, (long) stats.getStat(Stats.CUSTOM, Stats.PLAY_TIME));
        values.put(TrackedStat.DEATHS, (long) stats.getStat(Stats.CUSTOM, Stats.DEATHS));

        long mined = 0;
        for (var block : Registries.BLOCK) {
            mined += stats.getStat(Stats.MINED, block);
        }
        values.put(TrackedStat.BLOCKS_MINED, mined);

        long killed = 0;
        for (var entityType : Registries.ENTITY_TYPE) {
            killed += stats.getStat(Stats.KILLED, entityType);
        }
        values.put(TrackedStat.MOB_KILLS, killed);

        long crafted = 0;
        for (var item : Registries.ITEM) {
            crafted += stats.getStat(Stats.CRAFTED, item);
        }
        values.put(TrackedStat.ITEMS_CRAFTED, crafted);

        long distance = 0;
        for (Identifier distanceStatId : DISTANCE_STAT_IDS) {
            distance += stats.getStat(Stats.CUSTOM, distanceStatId);
        }
        values.put(TrackedStat.DISTANCE_TRAVELED_CM, distance);

        return values;
    }
}

package de.lazuli.richpresence;

import de.lazuli.features.richpresence.services.PresenceSignals;
import de.lazuli.features.richpresence.services.VehicleKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The sole {@code net.minecraft.*}-touching signal-gathering class for this
 * platform module (plan Decision 7). Owns the movement/placement rolling
 * window and the throttled Near-a-Village scan; produces one
 * {@link PresenceSignals} per client tick, read by
 * {@code LocalPresenceTrackerImpl} via {@link #current()}.
 *
 * <h2>Near-a-Village performance note (task's mandatory performance constraint)</h2>
 * The bell/villager proximity check is the one signal in this feature that
 * could plausibly cost real per-tick time if run naively (a full-radius
 * per-block scan or a whole-world entity iteration). Two cheaper mechanisms
 * were confirmed to exist on this Minecraft version's own client-side API
 * (via a real {@code javap -p} pass against this module's resolved
 * Minecraft jar) and both are used here instead:
 * <ul>
 *     <li><strong>Bells</strong>: {@code Level.getChunk(int, int)} returns
 *     the already-loaded {@link LevelChunk}, and
 *     {@code LevelChunk.getBlockEntities()} returns that chunk's own small,
 *     already-materialized {@code Map<BlockPos, BlockEntity>} -- no
 *     per-block-position scan at all, just a handful of map lookups per
 *     nearby chunk (typically 0-3 block entities per chunk in a real world,
 *     village chunks included). A 24-block bell radius needs at most a 5x5
 *     chunk neighborhood (radius in chunks = {@code ceil(24/16) + 1 = 2}),
 *     i.e. <strong>25 chunk lookups, each a cheap map value-scan</strong> of
 *     a handful of entries -- nowhere near a full-chunk block scan (which
 *     would be up to 16*16*(world height) positions per chunk).</li>
 *     <li><strong>Villagers</strong>: {@code Level.getEntities(EntityTypeTest,
 *     AABB, Predicate)} is the vanilla entity-tracking system's own
 *     spatial-partition query (backed by the level's per-chunk entity
 *     section storage) -- it does not walk every loaded entity in the world,
 *     only those whose sections the given {@link AABB} overlaps. A 32-block
 *     radius box only ever overlaps a handful of loaded chunk sections.</li>
 * </ul>
 * <strong>Throttling:</strong> even with both mechanisms being cheap per
 * call, this scan is additionally throttled to once every
 * {@value #NEAR_VILLAGE_SCAN_INTERVAL_TICKS} ticks (~1 second at 20 TPS),
 * matching this codebase's existing cadence for comparable per-tick sweeps
 * ({@code HostingPresenceScanner}'s own interval-gating). Rough cost
 * estimate at this throttle: 25 chunk-map lookups + 1 bounded entity query,
 * once per second -- negligible relative to a single client tick's overall
 * budget (a client tick has a ~50ms budget; this sweep's own cost is well
 * under a millisecond in typical play, since neither mechanism scales with
 * world size, only with the small, fixed 5x5-chunk/32-block neighborhood).
 * 20 ticks was chosen (rather than a shorter interval) because Near-a-Village
 * is a coarse "is the player generally near this feature" signal, not
 * something that needs sub-second responsiveness -- a village doesn't move,
 * and villagers wander slowly, so a 1-second staleness window is invisible
 * to the player-facing Rich Presence string, which itself is only
 * debounced-written on change (FR-RP4).
 */
public final class PresenceSignalGatherer {

    /** Near-a-Village scan cadence (ticks); see the class-level performance note. */
    public static final int NEAR_VILLAGE_SCAN_INTERVAL_TICKS = 20;

    private static final double BELL_RADIUS_BLOCKS = 24.0;
    private static final double VILLAGER_RADIUS_BLOCKS = 32.0;
    private static final int NEAR_VILLAGE_CHUNK_RADIUS = 2; // covers a 24-block radius (ceil(24/16)+1)

    private static final long ROLLING_WINDOW_MILLIS = 150_000L; // Exploring/Staying window
    private static final long BUILDING_WINDOW_MILLIS = 60_000L;
    private static final int UNDERGROUND_Y_THRESHOLD = 40;

    private record PositionSample(long timeMillis, double x, double z) {
    }

    private final Deque<PositionSample> positionSamples = new ArrayDeque<>();
    private final Deque<Long> placementTimestamps = new ArrayDeque<>();

    private int tickCounter;
    private boolean nearVillageBellCached;
    private int nearVillagerCountCached;

    private volatile PresenceSignals current = PresenceSignals.INACTIVE;

    /** @return the most recently gathered signals; safe to call from any thread. */
    public PresenceSignals current() {
        return current;
    }

    /** Called by a {@code UseBlockCallback} registration whenever the player uses a block-placing item on a block. */
    public void onBlockPlacementAttempt() {
        placementTimestamps.addLast(System.currentTimeMillis());
    }

    /** Called once per client tick. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            current = PresenceSignals.INACTIVE;
            return;
        }

        boolean paused = mc.isPaused();
        boolean spectating = player.isSpectator();
        boolean nether = level.dimension() == Level.NETHER;
        boolean end = level.dimension() == Level.END;

        BlockPos pos = player.blockPosition();
        String biomeKey = biomeTranslationKey(level, pos);
        VehicleKind vehicleKind = vehicleKind(player);
        boolean underground = !nether && !end && pos.getY() <= UNDERGROUND_Y_THRESHOLD;

        long now = System.currentTimeMillis();
        tickCounter++;
        if (tickCounter % NEAR_VILLAGE_SCAN_INTERVAL_TICKS == 0) {
            positionSamples.addLast(new PositionSample(now, player.getX(), player.getZ()));
            scanNearVillage(level, pos);
        }
        trimOlderThan(positionSamples, now, ROLLING_WINDOW_MILLIS);
        trimPlacements(now);

        double displacement = displacementFromAnchor(player.getX(), player.getZ());
        double stayRadius = maxDistanceFromAnchor(player.getX(), player.getZ());

        current = new PresenceSignals(true, paused, spectating, nether, end, biomeKey, vehicleKind, underground,
                displacement, stayRadius, placementTimestamps.size(), nearVillageBellCached, nearVillagerCountCached);
    }

    private static String biomeTranslationKey(Level level, BlockPos pos) {
        Holder<Biome> holder = level.getBiome(pos);
        return holder.unwrapKey()
                .map(key -> {
                    Identifier id = key.identifier();
                    return "biome." + id.getNamespace() + "." + id.getPath();
                })
                .orElse("");
    }

    private static VehicleKind vehicleKind(LocalPlayer player) {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof AbstractMinecart) {
            return VehicleKind.MINECART;
        }
        if (vehicle instanceof AbstractBoat) {
            return VehicleKind.BOAT;
        }
        return VehicleKind.NONE;
    }

    private void scanNearVillage(Level level, BlockPos center) {
        boolean bellFound = false;
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        outer:
        for (int cx = centerChunkX - NEAR_VILLAGE_CHUNK_RADIUS; cx <= centerChunkX + NEAR_VILLAGE_CHUNK_RADIUS; cx++) {
            for (int cz = centerChunkZ - NEAR_VILLAGE_CHUNK_RADIUS; cz <= centerChunkZ + NEAR_VILLAGE_CHUNK_RADIUS; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof BellBlockEntity
                            && blockEntity.getBlockPos().distSqr(center) <= BELL_RADIUS_BLOCKS * BELL_RADIUS_BLOCKS) {
                        bellFound = true;
                        break outer;
                    }
                }
            }
        }
        this.nearVillageBellCached = bellFound;

        AABB box = new AABB(center.getX() - VILLAGER_RADIUS_BLOCKS, center.getY() - VILLAGER_RADIUS_BLOCKS,
                center.getZ() - VILLAGER_RADIUS_BLOCKS, center.getX() + VILLAGER_RADIUS_BLOCKS,
                center.getY() + VILLAGER_RADIUS_BLOCKS, center.getZ() + VILLAGER_RADIUS_BLOCKS);
        this.nearVillagerCountCached =
                level.getEntities(EntityTypeTest.forClass(Villager.class), box, villager -> true).size();
    }

    private void trimPlacements(long now) {
        while (!placementTimestamps.isEmpty() && now - placementTimestamps.peekFirst() > BUILDING_WINDOW_MILLIS) {
            placementTimestamps.pollFirst();
        }
    }

    private static void trimOlderThan(Deque<PositionSample> samples, long now, long windowMillis) {
        while (samples.size() > 1 && now - samples.peekFirst().timeMillis() > windowMillis) {
            samples.pollFirst();
        }
    }

    private double displacementFromAnchor(double x, double z) {
        if (positionSamples.isEmpty()) {
            return 0.0;
        }
        PositionSample anchor = positionSamples.peekFirst();
        return distance(anchor.x(), anchor.z(), x, z);
    }

    private double maxDistanceFromAnchor(double x, double z) {
        if (positionSamples.isEmpty()) {
            return 0.0;
        }
        PositionSample anchor = positionSamples.peekFirst();
        double max = distance(anchor.x(), anchor.z(), x, z);
        for (PositionSample sample : positionSamples) {
            max = Math.max(max, distance(anchor.x(), anchor.z(), sample.x(), sample.z()));
        }
        return max;
    }

    private static double distance(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }
}

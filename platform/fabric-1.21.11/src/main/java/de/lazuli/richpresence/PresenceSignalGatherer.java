package de.lazuli.richpresence;

import de.lazuli.features.richpresence.services.PresenceSignals;
import de.lazuli.features.richpresence.services.VehicleKind;

import net.minecraft.block.entity.BellBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The sole {@code net.minecraft.*}-touching signal-gathering class for this
 * platform module (plan Decision 7). Owns the movement/placement rolling
 * window and the throttled Near-a-Village scan; produces one
 * {@link PresenceSignals} per client tick, read by
 * {@code LocalPresenceTrackerImpl} via {@link #current()}.
 *
 * <p>See {@code platform/fabric-26.2}'s copy of this class for the full
 * Near-a-Village performance-mechanism writeup (identical reasoning and
 * throttle interval on this side -- {@code World.getChunk(int, int)} +
 * {@code WorldChunk.getBlockEntities()} for bells, {@code World.getEntitiesByType}
 * for villagers, both confirmed present via {@code javap -p} against this
 * module's own resolved (Yarn-mapped) Minecraft jar).
 */
public final class PresenceSignalGatherer {

    /** Near-a-Village scan cadence (ticks); see the Mojang-side copy of this class for the full performance note. */
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
        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;
        ClientPlayerEntity player = mc.player;
        if (world == null || player == null) {
            current = PresenceSignals.INACTIVE;
            return;
        }

        boolean paused = mc.isPaused();
        boolean spectating = player.isSpectator();
        boolean nether = world.getRegistryKey() == World.NETHER;
        boolean end = world.getRegistryKey() == World.END;

        BlockPos pos = player.getBlockPos();
        String biomeKey = biomeTranslationKey(world, pos);
        VehicleKind vehicleKind = vehicleKind(player);
        boolean underground = !nether && !end && pos.getY() <= UNDERGROUND_Y_THRESHOLD;

        long now = System.currentTimeMillis();
        tickCounter++;
        if (tickCounter % NEAR_VILLAGE_SCAN_INTERVAL_TICKS == 0) {
            positionSamples.addLast(new PositionSample(now, player.getX(), player.getZ()));
            scanNearVillage(world, pos);
        }
        trimOlderThan(positionSamples, now, ROLLING_WINDOW_MILLIS);
        trimPlacements(now);

        double displacement = displacementFromAnchor(player.getX(), player.getZ());
        double stayRadius = maxDistanceFromAnchor(player.getX(), player.getZ());

        current = new PresenceSignals(true, paused, spectating, nether, end, biomeKey, vehicleKind, underground,
                displacement, stayRadius, placementTimestamps.size(), nearVillageBellCached, nearVillagerCountCached);
    }

    private static String biomeTranslationKey(World world, BlockPos pos) {
        RegistryEntry<Biome> entry = world.getBiome(pos);
        return entry.getKey()
                .map(key -> {
                    Identifier id = key.getValue();
                    return "biome." + id.getNamespace() + "." + id.getPath();
                })
                .orElse("");
    }

    private static VehicleKind vehicleKind(ClientPlayerEntity player) {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof AbstractMinecartEntity) {
            return VehicleKind.MINECART;
        }
        if (vehicle instanceof AbstractBoatEntity) {
            return VehicleKind.BOAT;
        }
        return VehicleKind.NONE;
    }

    private void scanNearVillage(World world, BlockPos center) {
        boolean bellFound = false;
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        outer:
        for (int cx = centerChunkX - NEAR_VILLAGE_CHUNK_RADIUS; cx <= centerChunkX + NEAR_VILLAGE_CHUNK_RADIUS; cx++) {
            for (int cz = centerChunkZ - NEAR_VILLAGE_CHUNK_RADIUS; cz <= centerChunkZ + NEAR_VILLAGE_CHUNK_RADIUS; cz++) {
                WorldChunk chunk = world.getChunk(cx, cz);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof BellBlockEntity
                            && blockEntity.getPos().getSquaredDistance(center) <= BELL_RADIUS_BLOCKS * BELL_RADIUS_BLOCKS) {
                        bellFound = true;
                        break outer;
                    }
                }
            }
        }
        this.nearVillageBellCached = bellFound;

        Box box = new Box(center.getX() - VILLAGER_RADIUS_BLOCKS, center.getY() - VILLAGER_RADIUS_BLOCKS,
                center.getZ() - VILLAGER_RADIUS_BLOCKS, center.getX() + VILLAGER_RADIUS_BLOCKS,
                center.getY() + VILLAGER_RADIUS_BLOCKS, center.getZ() + VILLAGER_RADIUS_BLOCKS);
        this.nearVillagerCountCached =
                world.getEntitiesByType(TypeFilter.instanceOf(VillagerEntity.class), box, villager -> true).size();
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

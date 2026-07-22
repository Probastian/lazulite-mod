package de.lazuli.features.richpresence.services;

/**
 * A plain, immutable snapshot of every raw signal
 * {@link PresenceStatusResolver} needs, gathered once per client tick by
 * platform-module code that touches {@code net.minecraft.*}. Holds only
 * primitive/plain types -- zero Minecraft import -- the seam between
 * "gather" (platform) and "resolve" (this plain-JVM module), mirroring
 * {@code HostingPresenceScanner}'s own raw-string input shape (plan
 * "Existing Implementation").
 *
 * @param sessionActive     {@code true} once a world is loaded (including
 *                          while paused) -- FR-RP7 / Decision 3. When
 *                          {@code false}, every other field is meaningless
 *                          and ignored by the resolver.
 * @param paused            {@code Minecraft.getInstance().isPaused()} /
 *                          {@code MinecraftClient.getInstance().isPaused()}.
 * @param spectating        {@code player.isSpectator()}.
 * @param nether            {@code true} if the player's current dimension is
 *                          the Nether.
 * @param end               {@code true} if the player's current dimension is
 *                          the End.
 * @param biomeTranslationKey the player's current biome's registry-key-derived
 *                          translation key (e.g. {@code "biome.minecraft.plains"}),
 *                          or {@code ""} if unresolved.
 * @param vehicleKind       the player's current vehicle classification.
 * @param underground       Overworld-only mining/underground heuristic,
 *                          already computed by platform code (always
 *                          {@code false} outside the Overworld).
 * @param displacement150s  straight-line distance (blocks) between the
 *                          player's position now and their position ~150
 *                          seconds ago (Exploring signal).
 * @param stayRadius150s    the maximum distance (blocks) the player has
 *                          strayed from the rolling window's anchor position
 *                          -- currently informational only; the resolver's
 *                          "Staying" tier is the default once Exploring/
 *                          Building don't apply, per the specification's own
 *                          "did not meet the Exploring/Building thresholds"
 *                          framing, so this field is not itself consulted by
 *                          {@link PresenceStatusResolver} today.
 * @param blockPlacements60s count of block-place events in the trailing 60
 *                          seconds (Building signal).
 * @param nearVillageBell   {@code true} if a Bell block exists within 24
 *                          blocks of the player.
 * @param nearVillagerCount count of {@code Villager} entities within 32
 *                          blocks of the player.
 */
public record PresenceSignals(
        boolean sessionActive,
        boolean paused,
        boolean spectating,
        boolean nether,
        boolean end,
        String biomeTranslationKey,
        VehicleKind vehicleKind,
        boolean underground,
        double displacement150s,
        double stayRadius150s,
        int blockPlacements60s,
        boolean nearVillageBell,
        int nearVillagerCount) {

    /** A fixed "no session active" snapshot, used before any real tick has run. */
    public static final PresenceSignals INACTIVE =
            new PresenceSignals(false, false, false, false, false, "", VehicleKind.NONE, false, 0, 0, 0, false, 0);
}

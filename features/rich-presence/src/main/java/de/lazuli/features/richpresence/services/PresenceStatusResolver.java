package de.lazuli.features.richpresence.services;

/**
 * Pure, plain-JVM tier-precedence resolver (specification "Tier Priority /
 * Precedence") -- the single most important unit-test target in this
 * feature (plan Decision 5). Zero {@code net.minecraft.*} import: every
 * platform-specific reading has already happened by the time a
 * {@link PresenceSignals} reaches this class.
 *
 * <p>Precedence (highest to lowest), per the specification:
 * <ol>
 *     <li>Main Menu (no session active)</li>
 *     <li>Paused</li>
 *     <li>Spectating</li>
 *     <li>Riding (minecart/boat)</li>
 *     <li>Near a Village</li>
 *     <li>Movement-derived (Exploring &gt; Building &gt; Staying)</li>
 *     <li>Digging Around -- a specialization of level 6, not a separate
 *         level: only applies when level 6 would otherwise resolve to
 *         Staying or Building (i.e. Exploring did not also fire) and the
 *         player is underground (plan Decision 2).</li>
 * </ol>
 */
public final class PresenceStatusResolver {

    /** Exploring distance threshold, blocks, over the rolling window (spec proposed default). */
    public static final double EXPLORING_DISTANCE_THRESHOLD_BLOCKS = 250.0;

    /** Building placement-count threshold, trailing 60s (spec confirmed final, lowered from 40 to 20). */
    public static final int BUILDING_PLACEMENT_THRESHOLD = 20;

    /** Near-a-Village villager-count threshold (spec confirmed final). */
    public static final int NEAR_VILLAGE_VILLAGER_THRESHOLD = 3;

    /**
     * @param signals this tick's gathered raw signals
     * @return the resolved tier, per the precedence ladder above
     */
    public PresenceTier resolve(PresenceSignals signals) {
        if (!signals.sessionActive()) {
            return PresenceTier.of(TierKind.MAIN_MENU);
        }
        if (signals.paused()) {
            return PresenceTier.of(TierKind.PAUSED);
        }
        if (signals.spectating()) {
            return PresenceTier.of(TierKind.SPECTATING);
        }
        if (signals.vehicleKind() == VehicleKind.MINECART) {
            return PresenceTier.biomeBearing(TierKind.RIDING_MINECART, signals);
        }
        if (signals.vehicleKind() == VehicleKind.BOAT) {
            return PresenceTier.biomeBearing(TierKind.RIDING_BOAT, signals);
        }

        boolean nearVillage = signals.nearVillageBell() || signals.nearVillagerCount() >= NEAR_VILLAGE_VILLAGER_THRESHOLD;
        if (nearVillage) {
            return PresenceTier.biomeBearing(TierKind.NEAR_VILLAGE, signals);
        }

        boolean exploring = signals.displacement150s() > EXPLORING_DISTANCE_THRESHOLD_BLOCKS;
        boolean building = signals.blockPlacements60s() > BUILDING_PLACEMENT_THRESHOLD;

        if (!exploring && signals.underground()) {
            // Decision 2: Exploring wins over Digging-around when both fire;
            // otherwise an underground player who isn't covering great
            // distance gets the playful mining label instead of the plain
            // Staying/Building wording.
            return PresenceTier.of(TierKind.DIGGING_AROUND);
        }

        TierKind movementKind;
        if (exploring) {
            movementKind = TierKind.EXPLORING;
        } else if (building) {
            movementKind = TierKind.BUILDING;
        } else {
            movementKind = TierKind.STAYING;
        }
        return PresenceTier.biomeBearing(movementKind, signals);
    }
}

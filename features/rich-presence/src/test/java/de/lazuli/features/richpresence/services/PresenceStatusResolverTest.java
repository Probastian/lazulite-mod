package de.lazuli.features.richpresence.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The primary unit-test target for this feature (plan Test Strategy): the
 * entire tier-precedence ladder, including every documented threshold's
 * exact boundary value.
 */
class PresenceStatusResolverTest {

    private final PresenceStatusResolver resolver = new PresenceStatusResolver();

    /** Base "in a normal Overworld, on foot, doing nothing notable" signal set. */
    private static PresenceSignals base() {
        return new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 0, 0, 0, false, 0);
    }

    @Test
    void mainMenuShortCircuitsEverything() {
        PresenceSignals signals = new PresenceSignals(false, true, true, true, true, "biome.minecraft.plains",
                VehicleKind.MINECART, true, 999, 999, 999, true, 99);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.MAIN_MENU);
    }

    @Test
    void pausedTakesPriorityOverEverythingBelow() {
        PresenceSignals signals = new PresenceSignals(true, true, true, false, false, "biome.minecraft.plains",
                VehicleKind.MINECART, true, 999, 999, 999, true, 99);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.PAUSED);
    }

    @Test
    void spectatingTakesPriorityOverRidingAndBelow() {
        PresenceSignals signals = new PresenceSignals(true, false, true, false, false, "biome.minecraft.plains",
                VehicleKind.BOAT, false, 0, 0, 0, true, 5);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.SPECTATING);
    }

    @Test
    void ridingMinecartTakesPriorityOverNearVillage() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.MINECART, false, 0, 0, 0, true, 5);
        var tier = resolver.resolve(signals);
        assertThat(tier.kind()).isEqualTo(TierKind.RIDING_MINECART);
        assertThat(tier.biomeTranslationKey()).contains("biome.minecraft.plains");
    }

    @Test
    void ridingBoatTakesPriorityOverNearVillage() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.BOAT, false, 0, 0, 0, true, 5);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.RIDING_BOAT);
    }

    @Test
    void nearVillageByBellTakesPriorityOverMovementLabel() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 300, 0, 0, true, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.NEAR_VILLAGE);
    }

    @Test
    void nearVillageByThreeVillagersFires() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 0, 0, 0, false, 3);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.NEAR_VILLAGE);
    }

    @Test
    void nearVillageDoesNotFireWithOnlyTwoVillagers() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 0, 0, 0, false, 2);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.STAYING);
    }

    @Test
    void exploringFiresJustOverTheDistanceThreshold() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 250.0001, 0, 0, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.EXPLORING);
    }

    @Test
    void exactlyAtDistanceThresholdDoesNotFireExploring() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 250.0, 0, 0, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.STAYING);
    }

    @Test
    void buildingFiresJustOverThePlacementThreshold() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 0, 0, 21, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.BUILDING);
    }

    @Test
    void exactlyAtPlacementThresholdDoesNotFireBuilding() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 0, 0, 20, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.STAYING);
    }

    @Test
    void exploringTakesPriorityOverBuildingWhenBothThresholdsMet() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 300, 0, 25, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.EXPLORING);
    }

    @Test
    void buildingTakesPriorityOverStayingWhenOnlyBuildingThresholdMet() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, false, 0, 0, 25, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.BUILDING);
    }

    @Test
    void diggingAroundAppliesWhenUndergroundAndNotExploring() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, true, 0, 0, 0, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.DIGGING_AROUND);
    }

    @Test
    void diggingAroundAppliesWhenUndergroundAndBuilding() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, true, 0, 0, 25, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.DIGGING_AROUND);
    }

    @Test
    void exploringWinsOverDiggingAroundWhenBothApply() {
        PresenceSignals signals = new PresenceSignals(true, false, false, false, false, "biome.minecraft.plains",
                VehicleKind.NONE, true, 300, 0, 0, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.EXPLORING);
    }

    @Test
    void diggingAroundNeverAppliesOutsideOverworldSinceUndergroundIsFalseThere() {
        // Platform code is contractually responsible for never setting
        // underground=true outside the Overworld; this test documents that
        // the resolver itself has no separate dimension gate for it.
        PresenceSignals signals = new PresenceSignals(true, false, false, true, false, "biome.minecraft.nether_wastes",
                VehicleKind.NONE, false, 0, 0, 0, false, 0);
        assertThat(resolver.resolve(signals).kind()).isEqualTo(TierKind.STAYING);
    }

    @Test
    void dimensionSuffixFlagsCarryThroughOnBiomeBearingTiers() {
        PresenceSignals signals = new PresenceSignals(true, false, false, true, false, "biome.minecraft.nether_wastes",
                VehicleKind.NONE, false, 0, 0, 0, false, 0);
        var tier = resolver.resolve(signals);
        assertThat(tier.kind()).isEqualTo(TierKind.STAYING);
        assertThat(tier.nether()).isTrue();
        assertThat(tier.end()).isFalse();
    }

    @Test
    void defaultStayingWhenNothingElseApplies() {
        assertThat(resolver.resolve(base()).kind()).isEqualTo(TierKind.STAYING);
    }
}

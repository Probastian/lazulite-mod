package de.lazuli.features.richpresence.services;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RichPresenceTokenMapTest {

    private final RichPresenceTokenMap tokenMap = new RichPresenceTokenMap();

    @Test
    void mapsEveryTierKindToItsExpectedToken() {
        assertEquals(Optional.of("#Status_MainMenu"), tokenMap.tokenFor(TierKind.MAIN_MENU));
        assertEquals(Optional.of("#Status_Paused"), tokenMap.tokenFor(TierKind.PAUSED));
        assertEquals(Optional.of("#Status_Spectating"), tokenMap.tokenFor(TierKind.SPECTATING));
        assertEquals(Optional.of("#Status_RidingMinecart"), tokenMap.tokenFor(TierKind.RIDING_MINECART));
        assertEquals(Optional.of("#Status_RidingBoat"), tokenMap.tokenFor(TierKind.RIDING_BOAT));
        assertEquals(Optional.of("#Status_NearVillage"), tokenMap.tokenFor(TierKind.NEAR_VILLAGE));
        assertEquals(Optional.of("#Status_Exploring"), tokenMap.tokenFor(TierKind.EXPLORING));
        assertEquals(Optional.of("#Status_Staying"), tokenMap.tokenFor(TierKind.STAYING));
        assertEquals(Optional.of("#Status_Building"), tokenMap.tokenFor(TierKind.BUILDING));
        assertEquals(Optional.of("#Status_DiggingAround"), tokenMap.tokenFor(TierKind.DIGGING_AROUND));
    }
}

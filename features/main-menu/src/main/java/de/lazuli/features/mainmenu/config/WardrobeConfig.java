package de.lazuli.features.mainmenu.config;

import de.lazuli.api.mainmenu.WardrobeSlot;

import java.util.EnumMap;
import java.util.Map;

/**
 * Simple data holder for the per-player wardrobe equip-map (spec FR6.3):
 * which catalog item id (if any) is equipped in each {@link WardrobeSlot}.
 * An absent/{@code null} value for a slot means that slot is unequipped.
 */
public record WardrobeConfig(Map<WardrobeSlot, String> equipped) {

    /** Every slot unequipped -- the expected first-run/fail-closed default. */
    public static final WardrobeConfig DEFAULT = new WardrobeConfig(Map.of());

    public WardrobeConfig {
        Map<WardrobeSlot, String> defensive = new EnumMap<>(WardrobeSlot.class);
        equipped.forEach((slot, itemId) -> {
            if (itemId != null) {
                defensive.put(slot, itemId);
            }
        });
        equipped = Map.copyOf(defensive);
    }
}

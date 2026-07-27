package de.lazuli.features.tweaks.services;

/**
 * Minecraft-agnostic hook interface for T10 Disable Cosmetics (spec
 * Requirements T10). The one tweak whose effect is entirely internal to this
 * mod (gates this mod's own Wardrobe renderer, not a vanilla one).
 */
public interface DisableCosmeticsHook {

    /**
     * @param wardrobeSlotName {@link de.lazuli.api.mainmenu.WardrobeSlot#name()}
     * @return {@code true} if cosmetics equipped in this slot should be suppressed from rendering
     */
    boolean isSlotDisabled(String wardrobeSlotName);
}

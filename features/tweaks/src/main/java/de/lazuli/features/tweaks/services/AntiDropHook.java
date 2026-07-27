package de.lazuli.features.tweaks.services;

/**
 * Minecraft-agnostic hook interface for T1 Anti-Drop (spec Requirements T1).
 * Implemented once per platform module, against that platform's own
 * item-drop input call site (Architecture Decision 2 in the implementation
 * plan: hook interfaces live here, concrete implementations live
 * platform-side since they touch {@code net.minecraft.*}/mixin types).
 */
public interface AntiDropHook {

    /**
     * @param itemId    the dropped item's registry id, e.g. {@code "minecraft:diamond"}
     * @param shiftHeld whether Shift was held for this drop attempt
     * @return {@code true} if the drop should be cancelled
     */
    boolean shouldCancelDrop(String itemId, boolean shiftHeld);
}

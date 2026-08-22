package de.lazuli.features.tweaks.services;

/**
 * Minecraft-agnostic hook interface for the Loot Bin UI tweak
 * (docs/specs/tweaks-loot-bin-ui.md Public API), mirroring
 * {@link CompassHook}'s exact shape: the master enable/disable gets its own
 * dedicated method, while the remaining configurables ({@code
 * applyToChestFamily}/{@code applyToShulkerBox}/{@code groupOrder}/
 * {@code sortWithinGroup}/{@code showSearchBar}/{@code countTextStyle}) are
 * read via a single generic string-keyed accessor.
 *
 * <p>Everything else this tweak needs (grouping index, per-frame
 * aggregation, click-slot resolution, the replacement {@code Screen} class
 * itself) is Minecraft-typed and therefore lives entirely platform-side
 * ({@code LootBinScreen}/{@code LootBinGrouping}/{@code
 * LootBinScreenRegistration}, package {@code de.lazuli.tweaks}), not behind
 * this interface -- same "hook is state-only, behavior is platform-side"
 * split {@code FreecamHook}/{@code FreecamCameraEntity}/{@code FreecamTicker}
 * already established.
 */
public interface LootBinHook {

    boolean isLootBinActive();

    Object lootBinConfigurable(String key);
}

package de.lazuli.tweaks;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;

/**
 * Loot Bin UI (docs/specs/tweaks-loot-bin-ui.md Architecture "Screen-
 * replacement mechanism") -- registers {@link LootBinScreen} as the
 * alternative screen for the chest-family ({@code GENERIC_9x1}-{@code
 * GENERIC_9x6}, spec R2) and shulker-box container types via {@code
 * MenuScreens.register}, called once from {@code
 * TweaksClientInitializer.onInitializeClient()} (Framework Fit), matching
 * the {@code FreecamTicker.register(...)} composition-root call-site
 * convention.
 *
 * <p>Each factory re-checks {@link TweakHooksImpl#isLootBinActive()} AND-ed
 * with that family's own configurable at <strong>every individual
 * container-open call</strong> (not cached at registration time), falling
 * back to vanilla's own {@link ContainerScreen}/{@link ShulkerBoxScreen}
 * when either is off -- this is what makes toggling the tweak or a family's
 * configurable apply the very next time that container type is opened (spec
 * Architecture; already-open screens don't retroactively swap class, R15's
 * secondary hotkey is the live alternative).
 *
 * <p>Goal 8 ("architecturally incapable of appearing for a non-storage
 * container") holds by construction here: a factory is registered only for
 * these two vanilla {@link MenuType} families, so any other container type
 * (furnace, anvil, etc.) never even calls into this class's logic.
 */
public final class LootBinScreenRegistration {

    private static TweaksKeyBindings keyBindingsRef;
    private static TweakHooksImpl hooksRef;

    private LootBinScreenRegistration() {
    }

    public static void register(TweaksKeyBindings keyBindings, TweakHooksImpl hooks) {
        keyBindingsRef = keyBindings;
        hooksRef = hooks;

        MenuScreens.register(MenuType.GENERIC_9x1, LootBinScreenRegistration::chestScreen);
        MenuScreens.register(MenuType.GENERIC_9x2, LootBinScreenRegistration::chestScreen);
        MenuScreens.register(MenuType.GENERIC_9x3, LootBinScreenRegistration::chestScreen);
        MenuScreens.register(MenuType.GENERIC_9x4, LootBinScreenRegistration::chestScreen);
        MenuScreens.register(MenuType.GENERIC_9x5, LootBinScreenRegistration::chestScreen);
        MenuScreens.register(MenuType.GENERIC_9x6, LootBinScreenRegistration::chestScreen);
        MenuScreens.register(MenuType.SHULKER_BOX, LootBinScreenRegistration::shulkerScreen);
    }

    private static AbstractContainerScreen<ChestMenu> chestScreen(ChestMenu menu, Inventory inventory, Component title) {
        if (eligible("applyToChestFamily")) {
            return new LootBinScreen<>(menu, inventory, title);
        }
        return new ContainerScreen(menu, inventory, title);
    }

    private static AbstractContainerScreen<ShulkerBoxMenu> shulkerScreen(ShulkerBoxMenu menu, Inventory inventory, Component title) {
        if (eligible("applyToShulkerBox")) {
            return new LootBinScreen<>(menu, inventory, title);
        }
        return new ShulkerBoxScreen(menu, inventory, title);
    }

    private static boolean eligible(String familyConfigKey) {
        if (hooksRef == null || !hooksRef.isLootBinActive()) {
            return false;
        }
        Object raw = hooksRef.lootBinConfigurable(familyConfigKey);
        return !Boolean.FALSE.equals(raw);
    }

    static TweaksKeyBindings keyBindings() {
        return keyBindingsRef;
    }

    static TweakHooksImpl hooks() {
        return hooksRef;
    }
}

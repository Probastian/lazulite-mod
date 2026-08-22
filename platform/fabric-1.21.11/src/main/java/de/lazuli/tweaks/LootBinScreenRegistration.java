package de.lazuli.tweaks;

import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;

/**
 * Loot Bin UI (docs/specs/tweaks-loot-bin-ui.md Architecture "Screen-
 * replacement mechanism") -- Yarn (1.21.11) port of {@code fabric-26.1}/
 * {@code fabric-26.2}'s copy of this file (see those copies for the full
 * rationale; only {@code HandledScreens.register}/{@code ScreenHandlerType}/
 * {@code GenericContainerScreen}/{@code ShulkerBoxScreen} type names differ
 * from the Mojmap {@code MenuScreens}/{@code MenuType}/{@code
 * ContainerScreen}/{@code ShulkerBoxScreen} equivalents).
 */
public final class LootBinScreenRegistration {

    private static TweaksKeyBindings keyBindingsRef;
    private static TweakHooksImpl hooksRef;

    private LootBinScreenRegistration() {
    }

    public static void register(TweaksKeyBindings keyBindings, TweakHooksImpl hooks) {
        keyBindingsRef = keyBindings;
        hooksRef = hooks;

        HandledScreens.register(ScreenHandlerType.GENERIC_9X1, LootBinScreenRegistration::chestScreen);
        HandledScreens.register(ScreenHandlerType.GENERIC_9X2, LootBinScreenRegistration::chestScreen);
        HandledScreens.register(ScreenHandlerType.GENERIC_9X3, LootBinScreenRegistration::chestScreen);
        HandledScreens.register(ScreenHandlerType.GENERIC_9X4, LootBinScreenRegistration::chestScreen);
        HandledScreens.register(ScreenHandlerType.GENERIC_9X5, LootBinScreenRegistration::chestScreen);
        HandledScreens.register(ScreenHandlerType.GENERIC_9X6, LootBinScreenRegistration::chestScreen);
        HandledScreens.register(ScreenHandlerType.SHULKER_BOX, LootBinScreenRegistration::shulkerScreen);
    }

    private static HandledScreen<GenericContainerScreenHandler> chestScreen(
            GenericContainerScreenHandler handler, PlayerInventory inventory, Text title) {
        if (eligible("applyToChestFamily")) {
            return new LootBinScreen<>(handler, inventory, title);
        }
        return new GenericContainerScreen(handler, inventory, title);
    }

    private static HandledScreen<ShulkerBoxScreenHandler> shulkerScreen(
            ShulkerBoxScreenHandler handler, PlayerInventory inventory, Text title) {
        if (eligible("applyToShulkerBox")) {
            return new LootBinScreen<>(handler, inventory, title);
        }
        return new ShulkerBoxScreen(handler, inventory, title);
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

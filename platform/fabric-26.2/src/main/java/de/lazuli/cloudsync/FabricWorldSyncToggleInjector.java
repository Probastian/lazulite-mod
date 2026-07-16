package de.lazuli.cloudsync;

import de.lazuli.api.cloudsync.WorldSyncToggleHook;
import de.lazuli.api.steamworks.SteamAvailability;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Version Adapter for Group 6's per-world sync-toggle icon (FR6.1) on
 * Minecraft 26.2 (Mojang-mapped, unobfuscated).
 *
 * <p>A Pattern 1 injection ({@code ui-guidelines.md}): a footer-style toggle
 * button added via {@link ScreenEvents#AFTER_INIT} +
 * {@link Screens#getWidgets(Screen)}, reflecting whichever <em>local</em>
 * world row ({@link WorldSelectionList.WorldListEntry}) is currently
 * selected -- found via {@link WorldSelectionList}'s own public
 * {@code getSelectedOpt()} accessor, no mixin needed. Deliberately never
 * acts on a cloud-only synthetic row (FR6.8/FR6.9): those have no local save
 * folder for this toggle to mean anything for yet.
 *
 * <p>The icon is only added at all when this feature's master switch is
 * enabled and Steam is available (FR6.1/Configuration) -- gated by
 * {@code enabledSupplier}, resolved once per screen init.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * new FabricWorldSyncToggleInjector(worldSyncPreferenceService,
 *         () -> config.enabled() && steamAvailability.isSteamAvailable());
 * }</pre>
 */
public final class FabricWorldSyncToggleInjector {

    private final WorldSyncToggleHook hook;
    private final Supplier<Boolean> enabledSupplier;

    /**
     * @param hook            the feature-side sync-toggle hook (typically
     *                        {@code WorldSyncPreferenceService} itself)
     * @param enabledSupplier returns whether the icon should currently be
     *                        shown (master switch AND {@link SteamAvailability})
     */
    public FabricWorldSyncToggleInjector(WorldSyncToggleHook hook, Supplier<Boolean> enabledSupplier) {
        this.hook = hook;
        this.enabledSupplier = enabledSupplier;
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    private void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof SelectWorldScreen selectWorldScreen) || !Boolean.TRUE.equals(enabledSupplier.get())) {
            return;
        }

        Button toggleButton = Button.builder(Component.literal("Cloud Sync"), button -> toggleSelected(selectWorldScreen, button))
                .bounds(6, 6, 100, 20)
                .build();
        updateLabel(selectWorldScreen, toggleButton);
        Screens.getWidgets(screen).add(toggleButton);
    }

    private void toggleSelected(SelectWorldScreen screen, Button button) {
        findSelectedWorldSlug(screen).ifPresent(worldSlug -> {
            hook.toggleSync(worldSlug);
            updateLabel(screen, button);
        });
    }

    private void updateLabel(SelectWorldScreen screen, Button button) {
        Optional<String> selected = findSelectedWorldSlug(screen);
        if (selected.isEmpty()) {
            button.setMessage(Component.literal("Cloud Sync"));
            return;
        }
        boolean enabled = hook.isSyncEnabled(selected.get());
        button.setMessage(Component.literal(enabled ? "Cloud Sync: On" : "Cloud Sync: Off"));
    }

    private Optional<String> findSelectedWorldSlug(SelectWorldScreen screen) {
        for (Object child : screen.children()) {
            if (child instanceof WorldSelectionList list) {
                return list.getSelectedOpt().map(entry -> entry.getLevelSummary().getLevelId());
            }
        }
        return Optional.empty();
    }
}

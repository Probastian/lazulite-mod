package de.lazuli.cloudsync;

import de.lazuli.api.cloudsync.WorldSyncToggleHook;
import de.lazuli.api.steamworks.SteamAvailability;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Version Adapter for Group 6's per-world sync-toggle icon (FR6.1) on
 * Minecraft 1.21.11 (Yarn-mapped, obfuscated).
 *
 * <p>A Pattern 1 injection ({@code ui-guidelines.md}): a footer-style toggle
 * button added via {@link ScreenEvents#AFTER_INIT} +
 * {@link Screens#getButtons(Screen)}, reflecting whichever <em>local</em>
 * world row ({@link WorldListWidget.WorldEntry}) is currently selected --
 * found via {@link WorldListWidget}'s own public
 * {@code getSelectedAsOptional()} accessor, no mixin needed. Deliberately
 * never acts on a cloud-only synthetic row (FR6.8/FR6.9).
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

    private void onScreenInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof SelectWorldScreen selectWorldScreen) || !Boolean.TRUE.equals(enabledSupplier.get())) {
            return;
        }

        ButtonWidget toggleButton = ButtonWidget.builder(Text.literal("Cloud Sync"), button -> toggleSelected(selectWorldScreen, button))
                .dimensions(6, 6, 100, 20)
                .build();
        updateLabel(selectWorldScreen, toggleButton);
        Screens.getButtons(screen).add(toggleButton);
    }

    private void toggleSelected(SelectWorldScreen screen, ButtonWidget button) {
        findSelectedWorldSlug(screen).ifPresent(worldSlug -> {
            hook.toggleSync(worldSlug);
            updateLabel(screen, button);
        });
    }

    private void updateLabel(SelectWorldScreen screen, ButtonWidget button) {
        Optional<String> selected = findSelectedWorldSlug(screen);
        if (selected.isEmpty()) {
            button.setMessage(Text.literal("Cloud Sync"));
            return;
        }
        boolean enabled = hook.isSyncEnabled(selected.get());
        button.setMessage(Text.literal(enabled ? "Cloud Sync: On" : "Cloud Sync: Off"));
    }

    private Optional<String> findSelectedWorldSlug(SelectWorldScreen screen) {
        for (Object child : screen.children()) {
            if (child instanceof WorldListWidget list) {
                return list.getSelectedAsOptional().map(entry -> entry.getLevel().getName());
            }
        }
        return Optional.empty();
    }
}

package de.lazuli.cloudsync;

import de.lazuli.api.cloudsync.BookmarkSyncHook;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.util.Optional;

/**
 * Version Adapter for Group 3's bookmark-toggle affordance (FR3.3) on
 * Minecraft 1.21.11 (Yarn-mapped, obfuscated).
 *
 * <p>A Pattern 1 (non-mixin overlay widget, {@code ui-guidelines.md})
 * injection, mirroring the 26.x/26.1 adapter exactly, just under this
 * version's mapped names: {@link ScreenEvents#AFTER_INIT} adds one
 * footer-style toggle button to {@link MultiplayerScreen} via
 * {@link Screens#getButtons(Screen)} (this version's name for the same
 * mechanism 26.x calls {@code getWidgets}, per the already-logged
 * cross-version rename), reflecting whichever server is currently selected
 * in {@link MultiplayerServerListWidget}, found via that list's own public
 * (inherited) {@code getSelectedOrNull()} accessor -- no mixin needed.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * new FabricBookmarkToggleInjector(bookmarkedServersService);
 * }</pre>
 */
public final class FabricBookmarkToggleInjector {

    private final BookmarkSyncHook hook;

    /**
     * Registers this injector's {@link ScreenEvents#AFTER_INIT} listener.
     *
     * @param hook the feature-side bookmark hook (typically
     *             {@code BookmarkedServersService} itself)
     */
    public FabricBookmarkToggleInjector(BookmarkSyncHook hook) {
        this.hook = hook;
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    private void onScreenInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof MultiplayerScreen multiplayerScreen)) {
            return;
        }

        ButtonWidget toggleButton = ButtonWidget.builder(Text.literal("Bookmark"), button -> toggleSelected(multiplayerScreen, button))
                .dimensions(scaledWidth - 100, 6, 90, 20)
                .build();
        updateLabel(multiplayerScreen, toggleButton);
        Screens.getButtons(screen).add(toggleButton);
    }

    private void toggleSelected(MultiplayerScreen screen, ButtonWidget button) {
        findSelectedServer(screen).ifPresent(server -> {
            hook.toggleBookmark(server.address, server.name);
            updateLabel(screen, button);
        });
    }

    private void updateLabel(MultiplayerScreen screen, ButtonWidget button) {
        Optional<ServerInfo> selected = findSelectedServer(screen);
        if (selected.isEmpty()) {
            button.setMessage(Text.literal("Bookmark"));
            return;
        }
        boolean bookmarked = hook.isBookmarked(selected.get().address);
        button.setMessage(Text.literal(bookmarked ? "Bookmarked" : "Bookmark"));
    }

    private Optional<ServerInfo> findSelectedServer(MultiplayerScreen screen) {
        for (Object child : screen.children()) {
            if (child instanceof MultiplayerServerListWidget list
                    && list.getSelectedOrNull() instanceof MultiplayerServerListWidget.ServerEntry serverEntry) {
                return Optional.of(serverEntry.getServer());
            }
        }
        return Optional.empty();
    }
}

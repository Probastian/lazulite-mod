package de.lazuli.cloudsync;

import de.lazuli.api.cloudsync.BookmarkSyncHook;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Version Adapter for Group 3's bookmark-toggle affordance (FR3.3) on
 * Minecraft 26.2 (Mojang-mapped, unobfuscated).
 *
 * <p>A Pattern 1 (non-mixin overlay widget, {@code ui-guidelines.md})
 * injection: {@link ScreenEvents#AFTER_INIT} adds one footer-style toggle
 * button to {@link JoinMultiplayerScreen} via
 * {@link Screens#getWidgets(Screen)}, mirroring vanilla's own existing
 * Edit/Delete footer buttons that already act on "the currently selected
 * server-list entry" rather than needing a widget embedded inside the
 * scrolling {@link ServerSelectionList} itself (which would need a mixin,
 * per Pattern 2). The button's label/state reflects whichever server is
 * currently selected, found via {@link ServerSelectionList}'s own public
 * {@code getSelected()} accessor (no mixin needed here) each render pass.
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

    private void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof JoinMultiplayerScreen multiplayerScreen)) {
            return;
        }

        Button toggleButton = Button.builder(Component.literal("Bookmark"), button -> toggleSelected(multiplayerScreen, button))
                .bounds(scaledWidth - 100, 6, 90, 20)
                .build();
        updateLabel(multiplayerScreen, toggleButton);
        Screens.getWidgets(screen).add(toggleButton);
    }

    private void toggleSelected(JoinMultiplayerScreen screen, Button button) {
        findSelectedServer(screen).ifPresent(server -> {
            hook.toggleBookmark(server.ip, server.name);
            updateLabel(screen, button);
        });
    }

    private void updateLabel(JoinMultiplayerScreen screen, Button button) {
        Optional<ServerData> selected = findSelectedServer(screen);
        if (selected.isEmpty()) {
            button.setMessage(Component.literal("Bookmark"));
            return;
        }
        boolean bookmarked = hook.isBookmarked(selected.get().ip);
        button.setMessage(Component.literal(bookmarked ? "Bookmarked" : "Bookmark"));
    }

    private Optional<ServerData> findSelectedServer(JoinMultiplayerScreen screen) {
        for (Object child : screen.children()) {
            if (child instanceof ServerSelectionList list
                    && list.getSelected() instanceof ServerSelectionList.OnlineServerEntry onlineEntry) {
                return Optional.of(onlineEntry.getServerData());
            }
        }
        return Optional.empty();
    }
}

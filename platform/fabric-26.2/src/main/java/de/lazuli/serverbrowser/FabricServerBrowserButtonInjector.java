package de.lazuli.serverbrowser;

import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.api.steamworks.SteamAvailability;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Version Adapter for the Server Browser entry point on Minecraft 26.2
 * (Mojang-mapped, unobfuscated) -- Pattern 1 (spec Public API item 5): one
 * top-left {@code Button} injected into {@link JoinMultiplayerScreen} via
 * {@link ScreenEvents#AFTER_INIT}/{@link Screens#getWidgets(Screen)}, no
 * mixin (same shape as {@code FabricBookmarkToggleInjector}, the confirmed
 * precedent this feature's spec/plan both cite).
 *
 * <p>Deliberately top-LEFT, not top-right: {@code FabricBookmarkToggleInjector}
 * already occupies the top-right corner (bounds {@code scaledWidth - 100, 6,
 * 90, 20}), nearly identical to this button's original bounds. Both are
 * injected via independent {@code ScreenEvents.AFTER_INIT} listeners on the
 * same screen, so whichever widget lands earlier in the shared widget list
 * wins click priority regardless of paint order -- the bookmark button,
 * added first, silently absorbed every click meant for this button (it
 * rendered visually on top but never received input). Occupying a disjoint
 * screen region sidesteps the ordering-dependent collision entirely rather
 * than relying on registration order.
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * new FabricServerBrowserButtonInjector(sessionFactory, steamworksService);
 * }</pre>
 */
public final class FabricServerBrowserButtonInjector {

    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MARGIN = 6;

    private final ServerBrowserSessionFactory sessionFactory;
    private final SteamAvailability steamAvailability;

    // Tracks the button most recently added per screen instance so a
    // re-fire of AFTER_INIT (e.g. window resize re-initializing the same
    // JoinMultiplayerScreen) removes the stale, wrongly-positioned button
    // instead of accumulating duplicates. WeakHashMap so closed screens
    // don't leak.
    private final Map<Screen, Button> injectedButtons = new WeakHashMap<>();

    public FabricServerBrowserButtonInjector(ServerBrowserSessionFactory sessionFactory, SteamAvailability steamAvailability) {
        this.sessionFactory = sessionFactory;
        this.steamAvailability = steamAvailability;
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    private void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof JoinMultiplayerScreen multiplayerScreen)) {
            return;
        }

        Button previous = injectedButtons.remove(screen);
        if (previous != null) {
            Screens.getWidgets(screen).remove(previous);
        }

        Button serverBrowserButton = Button.builder(Component.literal("Server Browser"), button -> openServerBrowser(multiplayerScreen))
                .bounds(MARGIN, MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        Screens.getWidgets(screen).add(serverBrowserButton);
        injectedButtons.put(screen, serverBrowserButton);
    }

    private void openServerBrowser(Screen parent) {
        ServerBrowserScreen screen = new ServerBrowserScreen(parent, sessionFactory.newSession(), steamAvailability.isSteamAvailable());
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}

package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.MainMenuHook;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Version Adapter implementing {@link MainMenuHook} for Minecraft 26.2
 * (Mojang-mapped, unobfuscated).
 *
 * <p>Uses only Fabric API's documented, non-mixin {@code fabric-screen-api-v1}
 * module: {@link ScreenEvents#AFTER_INIT} to react to every {@link TitleScreen}
 * being (re-)created, and {@link Screens#getWidgets(Screen)} to add/remove a
 * plain {@link StringWidget} text label without needing a mixin (see NFR1 of
 * this feature's specification).
 *
 * <p>The listener is registered once, at construction time, so the very
 * first {@link TitleScreen} shown after client startup already reflects
 * whatever {@link #showLabel(String)}/{@link #hideLabel()} state was set
 * before that screen's {@code init()} runs (see the implementation plan's
 * Risk 5).
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * FabricMainMenuHook hook = new FabricMainMenuHook();
 * hook.showLabel("Hello World");
 * }</pre>
 */
public final class FabricMainMenuHook implements MainMenuHook {

    /**
     * Suggested default vertical offset (in scaled GUI pixels) below the
     * vanilla logo and above the vanilla button row, matching the spec's
     * "centered horizontally, above the Singleplayer button row" suggestion.
     * Exact placement is verified visually in-game (see the feature's
     * implementation plan, Risk 2) rather than computed from vanilla layout
     * internals, to avoid coupling this adapter to unstable private layout
     * constants.
     */
    private static final int LABEL_Y_OFFSET = 24;

    private volatile String labelText;

    /**
     * Registers this hook's {@link ScreenEvents#AFTER_INIT} listener.
     */
    public FabricMainMenuHook() {
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    @Override
    public void showLabel(String text) {
        this.labelText = text;
    }

    @Override
    public void hideLabel() {
        this.labelText = null;
    }

    private void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof TitleScreen)) {
            return;
        }

        String text = labelText;
        if (text == null) {
            return;
        }

        StringWidget widget = new StringWidget(Component.literal(text), screen.getFont());
        widget.setPosition((scaledWidth - widget.getWidth()) / 2, scaledHeight / 4 + LABEL_Y_OFFSET);
        Screens.getWidgets(screen).add(widget);
    }
}



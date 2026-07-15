package de.probastian.boilerplate.mainmenu;

import de.probastian.boilerplate.api.mainmenu.MainMenuHook;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

/**
 * Version Adapter implementing {@link MainMenuHook} for Minecraft 1.21.11
 * (Yarn-mapped, obfuscated -- the last obfuscated version this repo
 * supports).
 *
 * <p>Uses only Fabric API's documented, non-mixin {@code fabric-screen-api-v1}
 * module: {@link ScreenEvents#AFTER_INIT} to react to every {@link TitleScreen}
 * being (re-)created, and {@link Screens#getButtons(Screen)} to add/remove a
 * plain {@link TextWidget} text label without needing a mixin (see NFR1 of
 * this feature's specification). Note the method is named
 * {@code getButtons} on this version's {@code fabric-api} release, whereas
 * 26.x's {@code fabric-api} renamed the equivalent method to
 * {@code getWidgets} -- confirmed by compiling against each version's real
 * {@code fabric-api} jar rather than assumed, per this feature's
 * implementation plan (Risk 1).
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

    private void onScreenInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof TitleScreen)) {
            return;
        }

        String text = labelText;
        if (text == null) {
            return;
        }

        TextWidget widget = new TextWidget(Text.literal(text), screen.getTextRenderer());
        widget.setPosition((scaledWidth - widget.getWidth()) / 2, scaledHeight / 4 + LABEL_Y_OFFSET);
        Screens.getButtons(screen).add(widget);
    }
}

package de.lazuli.mainmenu;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Pause tab panel (main-menu-pause-integration spec FR3.3): the only content
 * shown in {@code MainMenuTab.PAUSE}, exactly one "Return to Game" action
 * (FR3.3.1/AC8) -- no Options/Save-and-Quit-to-Title here, both already
 * reachable via {@code MainMenuScreen}'s persistent sidebar in both contexts
 * (spec Resolved Questions #2/#3). {@code fabric-1.21.11} (Yarn-mapped) port
 * of the {@code fabric-26.1}/{@code fabric-26.2} class of the same name.
 *
 * <p>Mirrors {@link StatisticsPanel}'s minimal shape ({@code render}/
 * {@code mouseClicked} only, no {@code init}) since this panel owns no
 * scrollable/stateful content.
 */
public final class PausePanel {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 24;

    private final Runnable onReturnToGame;

    /**
     * @param onReturnToGame invoked when the button is clicked -- the host
     *                       screen passes {@code this::close} (plan FR5.3:
     *                       button and Esc must both go through the exact
     *                       same resume path, not a raw
     *                       {@code MinecraftClient.getInstance().setScreen(null)}).
     */
    public PausePanel(Runnable onReturnToGame) {
        this.onReturnToGame = onReturnToGame;
    }

    public void render(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int buttonX = x + (width - BUTTON_WIDTH) / 2;
        int buttonY = y + 24;
        boolean hovered = mouseX >= buttonX && mouseX <= buttonX + BUTTON_WIDTH && mouseY >= buttonY && mouseY <= buttonY + BUTTON_HEIGHT;
        int bg = hovered ? 0x99528A54 : 0x8C312E22;
        context.fill(buttonX, buttonY, buttonX + BUTTON_WIDTH, buttonY + BUTTON_HEIGHT, bg);
        context.drawCenteredTextWithShadow(font, Text.literal("Return to Game"), buttonX + BUTTON_WIDTH / 2, buttonY + BUTTON_HEIGHT / 2 - 4, 0xFFEAE8E1);
    }

    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int buttonX = x + (width - BUTTON_WIDTH) / 2;
        int buttonY = y + 24;
        if (mouseX >= buttonX && mouseX <= buttonX + BUTTON_WIDTH && mouseY >= buttonY && mouseY <= buttonY + BUTTON_HEIGHT) {
            MainMenuScreen.playClickSound();
            onReturnToGame.run();
            return true;
        }
        return false;
    }
}

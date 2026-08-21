package de.lazuli.mainmenu;

import de.lazuli.waypoints.WaypointsBundle;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Pause tab panel (main-menu-pause-integration spec FR3.3): originally just
 * "Return to Game" (FR3.3.1/AC8); the Waypoints feature (spec R19) adds a
 * second "Waypoints" button below it, following exactly the sub-view-swap
 * pattern {@code TweaksPanel} already established for its own per-tweak
 * config screen -- a nullable "which sub-view is active" field ({@link
 * #managingWaypoints}) gates {@code render}/{@code mouseClicked} dispatch
 * between the normal Pause content and the embedded {@link
 * WaypointManagerPanel}, plus a Back button returning to the former.
 * <strong>Not</strong> a new {@code MainMenuTab} enum member, per the spec's
 * explicit requirement.
 */
public final class PausePanel {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 24;
    private static final int BUTTON_GAP = 6;

    private final Runnable onReturnToGame;
    private final WaypointManagerPanel waypointManagerPanel;

    private boolean managingWaypoints;
    private net.minecraft.client.gui.components.Button backButton;
    private Consumer<AbstractWidget> addWidget;
    private Consumer<AbstractWidget> removeWidget;

    /**
     * @param onReturnToGame invoked when the button is clicked -- the host
     *                       screen passes {@code this::onClose} (plan FR5.3:
     *                       button and Esc must both go through the exact
     *                       same resume path, not a raw
     *                       {@code Minecraft.getInstance().setScreen(null)}).
     * @param waypointsBundle the Waypoints feature's registry/scope-resolver
     *                        pair (Waypoints spec R19), threaded into the
     *                        embedded {@link WaypointManagerPanel}
     */
    public PausePanel(Runnable onReturnToGame, WaypointsBundle waypointsBundle) {
        this.onReturnToGame = onReturnToGame;
        this.waypointManagerPanel = new WaypointManagerPanel(waypointsBundle.registry(), waypointsBundle.scopeResolver());
    }

    public void init(Consumer<AbstractWidget> addWidget, Consumer<AbstractWidget> removeWidget, int x, int y, int width) {
        this.addWidget = addWidget;
        this.removeWidget = removeWidget;
        waypointManagerPanel.init(addWidget, removeWidget);
    }

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        if (managingWaypoints) {
            ensureBackButton(x, y);
            waypointManagerPanel.render(guiGraphics, font, x, y + 20, width, height - 20, mouseX, mouseY);
            return;
        }

        int buttonX = x + (width - BUTTON_WIDTH) / 2;
        int buttonY = y + 24;
        boolean hovered = mouseX >= buttonX && mouseX <= buttonX + BUTTON_WIDTH && mouseY >= buttonY && mouseY <= buttonY + BUTTON_HEIGHT;
        int bg = hovered ? 0x99528A54 : 0x8C312E22;
        guiGraphics.fill(buttonX, buttonY, buttonX + BUTTON_WIDTH, buttonY + BUTTON_HEIGHT, bg);
        guiGraphics.centeredText(font, Component.literal("Return to Game"), buttonX + BUTTON_WIDTH / 2, buttonY + BUTTON_HEIGHT / 2 - 4, 0xFFEAE8E1);

        int waypointsButtonY = buttonY + BUTTON_HEIGHT + BUTTON_GAP;
        boolean waypointsHovered = mouseX >= buttonX && mouseX <= buttonX + BUTTON_WIDTH
                && mouseY >= waypointsButtonY && mouseY <= waypointsButtonY + BUTTON_HEIGHT;
        int waypointsBg = waypointsHovered ? 0x99528A54 : 0x8C312E22;
        guiGraphics.fill(buttonX, waypointsButtonY, buttonX + BUTTON_WIDTH, waypointsButtonY + BUTTON_HEIGHT, waypointsBg);
        guiGraphics.centeredText(font, Component.literal("Waypoints"), buttonX + BUTTON_WIDTH / 2, waypointsButtonY + BUTTON_HEIGHT / 2 - 4, 0xFFEAE8E1);
    }

    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (managingWaypoints) {
            return waypointManagerPanel.mouseClicked(x, y + 20, width, height - 20, mouseX, mouseY);
        }

        int buttonX = x + (width - BUTTON_WIDTH) / 2;
        int buttonY = y + 24;
        if (mouseX >= buttonX && mouseX <= buttonX + BUTTON_WIDTH && mouseY >= buttonY && mouseY <= buttonY + BUTTON_HEIGHT) {
            MainMenuScreen.playClickSound();
            onReturnToGame.run();
            return true;
        }

        int waypointsButtonY = buttonY + BUTTON_HEIGHT + BUTTON_GAP;
        if (mouseX >= buttonX && mouseX <= buttonX + BUTTON_WIDTH && mouseY >= waypointsButtonY && mouseY <= waypointsButtonY + BUTTON_HEIGHT) {
            MainMenuScreen.playClickSound();
            managingWaypoints = true;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(int x, int y, int width, int height, double mouseX, double mouseY, double scrollDelta) {
        if (managingWaypoints) {
            return waypointManagerPanel.mouseScrolled(x, y + 20, width, height - 20, mouseX, mouseY, scrollDelta);
        }
        return false;
    }

    private void ensureBackButton(int x, int y) {
        if (backButton == null && addWidget != null) {
            backButton = net.minecraft.client.gui.components.Button.builder(Component.literal("< Back"), b -> {
                        MainMenuScreen.playClickSound();
                        leaveWaypointManager();
                    })
                    .bounds(x + 8, y, 60, 16).build();
            addWidget.accept(backButton);
        }
    }

    /**
     * Returns to the normal Pause content, tearing down the Back button and
     * any live Waypoint Manager sub-view widgets. Also called externally by
     * {@code MainMenuScreen} when switching tabs away from Pause or on
     * screen close (same widget-leak-prevention rationale {@code
     * TweaksPanel.leaveConfigScreen()} already established).
     */
    public void leaveWaypointManager() {
        if (!managingWaypoints) {
            return;
        }
        waypointManagerPanel.leave();
        if (backButton != null && removeWidget != null) {
            removeWidget.accept(backButton);
        }
        backButton = null;
        managingWaypoints = false;
    }
}

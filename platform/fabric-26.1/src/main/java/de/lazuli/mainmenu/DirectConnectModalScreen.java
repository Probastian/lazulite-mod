package de.lazuli.mainmenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Servers panel's Direct Connect modal (spec FR4.3) -- a small,
 * feature-owned overlay screen (no vanilla equivalent confirmed reusable for
 * this version, plan's own "confirm before building a new one" caveat).
 * Centered overlay on a dark scrim; Cancel/clicking the scrim closes it
 * without side effects.
 */
public final class DirectConnectModalScreen extends Screen {

    private final Screen previousScreen;
    private EditBox addressField;

    public DirectConnectModalScreen(Screen previousScreen) {
        super(Component.literal("Direct Connect"));
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        addressField = new EditBox(minecraft.font, width / 2 - 100, height / 2 - 10, 200, 20, Component.literal("Server Address"));
        addRenderableWidget(addressField);

        addRenderableWidget(Button.builder(Component.literal("Connect"), b -> onConnect())
                .bounds(width / 2 - 105, height / 2 + 20, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onCancel())
                .bounds(width / 2 + 5, height / 2 + 20, 100, 20).build());
    }

    private void onConnect() {
        String text = addressField.getValue();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            ServerAddress address = ServerAddress.parseString(text);
            ServerData serverData = new ServerData(text, text, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(previousScreen, Minecraft.getInstance(), address, serverData, false, null);
        } catch (Exception e) {
            de.lazuli.LazuliMod.LOGGER.warn("Failed to parse direct-connect address \"" + text + "\": " + e);
        }
    }

    private void onCancel() {
        Minecraft.getInstance().setScreenAndShow(previousScreen);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        guiGraphics.fill(0, 0, width, height, 0x99000000);
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);
        guiGraphics.centeredText(minecraft.font, Component.literal("Enter Server Address"), width / 2, height / 2 - 30, 0xFFEAE8E1);
    }

    @Override
    public void onClose() {
        onCancel();
    }
}

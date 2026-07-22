package de.lazuli.mainmenu;

import de.lazuli.LazuliMod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

/**
 * Servers panel's Direct Connect modal (spec FR4.3) -- {@code fabric-1.21.11}
 * (Yarn-mapped, obfuscated) port of the {@code fabric-26.1}/{@code fabric-26.2}
 * class of the same name. No vanilla equivalent confirmed reusable for this
 * version (plan's own "confirm before building a new one" caveat) -- vanilla
 * 1.21.11 has no standalone "Direct Connect" screen either.
 */
public final class DirectConnectModalScreen extends Screen {

    private final Screen previousScreen;
    private TextFieldWidget addressField;

    public DirectConnectModalScreen(Screen previousScreen) {
        super(Text.literal("Direct Connect"));
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        addressField = new TextFieldWidget(client.textRenderer, width / 2 - 100, height / 2 - 10, 200, 20, Text.literal("Server Address"));
        addDrawableChild(addressField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Connect"), b -> onConnect())
                .dimensions(width / 2 - 105, height / 2 + 20, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> onCancel())
                .dimensions(width / 2 + 5, height / 2 + 20, 100, 20).build());
    }

    private void onConnect() {
        String text = addressField.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            ServerAddress address = ServerAddress.parse(text);
            ServerInfo serverInfo = new ServerInfo(text, text, ServerInfo.ServerType.OTHER);
            ConnectScreen.connect(previousScreen, MinecraftClient.getInstance(), address, serverInfo, false, null);
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to parse direct-connect address \"" + text + "\": " + e);
        }
    }

    private void onCancel() {
        MinecraftClient.getInstance().setScreen(previousScreen);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x99000000);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(client.textRenderer, "Enter Server Address", width / 2, height / 2 - 30, 0xFFEAE8E1);
    }

    @Override
    public void close() {
        onCancel();
    }
}

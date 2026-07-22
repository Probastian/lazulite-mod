package de.lazuli.mainmenu;

import de.lazuli.LazuliMod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.Text;

/**
 * Servers panel's Add Server modal (spec FR4.3) -- {@code fabric-1.21.11}
 * (Yarn-mapped, obfuscated) port of the {@code fabric-26.1}/{@code fabric-26.2}
 * class of the same name. No vanilla equivalent confirmed reusable for this
 * version (plan's own "confirm before building a new one" caveat). Appends a
 * new {@link ServerInfo} entry to the same vanilla {@link ServerList} the
 * Saved sub-view reads.
 */
public final class AddServerModalScreen extends Screen {

    private final Screen previousScreen;
    private final ServerList serverList;

    private TextFieldWidget nameField;
    private TextFieldWidget addressField;

    public AddServerModalScreen(Screen previousScreen, ServerList serverList) {
        super(Text.literal("Add Server"));
        this.previousScreen = previousScreen;
        this.serverList = serverList;
    }

    @Override
    protected void init() {
        nameField = new TextFieldWidget(client.textRenderer, width / 2 - 100, height / 2 - 34, 200, 20, Text.literal("Server Name"));
        addDrawableChild(nameField);

        addressField = new TextFieldWidget(client.textRenderer, width / 2 - 100, height / 2 - 10, 200, 20, Text.literal("Server Address"));
        addDrawableChild(addressField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> onAdd())
                .dimensions(width / 2 - 105, height / 2 + 20, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> onCancel())
                .dimensions(width / 2 + 5, height / 2 + 20, 100, 20).build());
    }

    private void onAdd() {
        String name = nameField.getText();
        String address = addressField.getText();
        if (address == null || address.isBlank()) {
            return;
        }
        try {
            ServerInfo entry = new ServerInfo(name == null || name.isBlank() ? address : name, address, ServerInfo.ServerType.OTHER);
            serverList.add(entry, false);
            serverList.saveFile();
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to add saved server \"" + address + "\": " + e);
        }
        onCancel();
    }

    private void onCancel() {
        MinecraftClient.getInstance().setScreen(previousScreen);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x99000000);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(client.textRenderer, "Add Server", width / 2, height / 2 - 54, 0xFFEAE8E1);
    }

    @Override
    public void close() {
        onCancel();
    }
}

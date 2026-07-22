package de.lazuli.mainmenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

/**
 * Servers panel's Add Server modal (spec FR4.3) -- a small, feature-owned
 * overlay screen (no vanilla equivalent confirmed reusable for this version,
 * plan's own "confirm before building a new one" caveat). Appends a new
 * {@link ServerData} entry to the same vanilla {@link ServerList} the Saved
 * sub-view reads.
 */
public final class AddServerModalScreen extends Screen {

    private final Screen previousScreen;
    private final ServerList serverList;

    private EditBox nameField;
    private EditBox addressField;

    public AddServerModalScreen(Screen previousScreen, ServerList serverList) {
        super(Component.literal("Add Server"));
        this.previousScreen = previousScreen;
        this.serverList = serverList;
    }

    @Override
    protected void init() {
        nameField = new EditBox(minecraft.font, width / 2 - 100, height / 2 - 34, 200, 20, Component.literal("Server Name"));
        addRenderableWidget(nameField);

        addressField = new EditBox(minecraft.font, width / 2 - 100, height / 2 - 10, 200, 20, Component.literal("Server Address"));
        addRenderableWidget(addressField);

        addRenderableWidget(Button.builder(Component.literal("Add"), b -> onAdd())
                .bounds(width / 2 - 105, height / 2 + 20, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onCancel())
                .bounds(width / 2 + 5, height / 2 + 20, 100, 20).build());
    }

    private void onAdd() {
        String name = nameField.getValue();
        String address = addressField.getValue();
        if (address == null || address.isBlank()) {
            return;
        }
        try {
            ServerData entry = new ServerData(name == null || name.isBlank() ? address : name, address, ServerData.Type.OTHER);
            serverList.add(entry, false);
            serverList.save();
        } catch (Exception e) {
            de.lazuli.LazuliMod.LOGGER.warn("Failed to add saved server \"" + address + "\": " + e);
        }
        onCancel();
    }

    private void onCancel() {
        Minecraft.getInstance().setScreenAndShow(previousScreen);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        guiGraphics.fill(0, 0, width, height, 0x99000000);
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);
        guiGraphics.centeredText(minecraft.font, Component.literal("Add Server"), width / 2, height / 2 - 54, 0xFFEAE8E1);
    }

    @Override
    public void onClose() {
        onCancel();
    }
}

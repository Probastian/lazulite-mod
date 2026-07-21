package de.lazuli.serverbrowser;

import de.lazuli.api.serverbrowser.ServerBrowserRow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * This feature's own new subclass of {@link ObjectSelectionList} (spec
 * Decision 3) -- our own, freshly-populated list, never an insertion of
 * synthetic rows into a foreign, already-constructed vanilla list (contrast
 * {@code steam-cloud-sync}'s Group 6), so {@code addEntry}/{@code clearEntries}
 * are reachable via ordinary Java subclassing, no mixin needed.
 *
 * <p>Usage example:
 * <pre>{@code
 * ServerBrowserListWidget list = new ServerBrowserListWidget(minecraft, width, height, top, 24, this::onRowSelected);
 * list.replaceRows(session.currentRows());
 * }</pre>
 */
public final class ServerBrowserListWidget extends ObjectSelectionList<ServerBrowserListWidget.Row> {

    private final Consumer<ServerBrowserRow> onRowSelected;

    public ServerBrowserListWidget(Minecraft minecraft, int width, int height, int y0, int itemHeight,
                                    Consumer<ServerBrowserRow> onRowSelected) {
        super(minecraft, width, height, y0, itemHeight);
        this.onRowSelected = onRowSelected;
    }

    /** Replaces every row in this list with {@code rows} (already sorted/filtered, FR2.3/FR3.6). */
    public void replaceRows(List<ServerBrowserRow> rows) {
        clearEntries();
        for (ServerBrowserRow row : rows) {
            addEntry(new Row(row));
        }
    }

    @Override
    public int getRowWidth() {
        return width - 20;
    }

    /**
     * One synthetic row rendering the six FR2.1 columns, plus a muted
     * treatment for a {@code !respondedSuccessfully} row (FR4.2).
     */
    public final class Row extends ObjectSelectionList.Entry<Row> {

        private final ServerBrowserRow data;

        public Row(ServerBrowserRow data) {
            this.data = data;
        }

        public ServerBrowserRow data() {
            return data;
        }

        @Override
        public Component getNarration() {
            return Component.literal(data.serverName());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int textColor = data.respondedSuccessfully() ? 0xFFFFFFFF : 0xFF808080;
            int x = getContentX();
            int y = getContentY() + 2;

            guiGraphics.text(minecraft.font, data.serverName(), x, y, textColor);
            guiGraphics.text(minecraft.font, data.map(), x + 160, y, textColor);
            guiGraphics.text(minecraft.font, data.players() + "/" + data.maxPlayers(), x + 300, y, textColor);
            guiGraphics.text(minecraft.font, String.valueOf(data.ping()), x + 360, y, textColor);
            guiGraphics.text(minecraft.font, data.hasPassword() ? "[Locked]" : "", x + 410, y, textColor);
            guiGraphics.text(minecraft.font, data.isSecure() ? "[VAC]" : "", x + 470, y, textColor);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!data.respondedSuccessfully()) {
                return false;
            }
            setSelected(this);
            if (doubleClick && onRowSelected != null) {
                onRowSelected.accept(data);
            }
            return true;
        }
    }
}

package de.lazuli.serverbrowser;

import de.lazuli.api.serverbrowser.ServerBrowserRow;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;

/**
 * This feature's own new subclass of {@link AlwaysSelectedEntryListWidget}
 * (spec Decision 3, Yarn-side equivalent of 26.x's {@code ObjectSelectionList})
 * -- our own, freshly-populated list, never an insertion of synthetic rows
 * into a foreign, already-constructed vanilla list, so {@code addEntry}/
 * {@code clearEntries} are reachable via ordinary Java subclassing, no mixin
 * needed.
 *
 * <p>Usage example:
 * <pre>{@code
 * ServerBrowserListWidget list = new ServerBrowserListWidget(client, width, height, top, 14, this::onRowSelected);
 * list.replaceRows(session.currentRows());
 * }</pre>
 */
public final class ServerBrowserListWidget extends AlwaysSelectedEntryListWidget<ServerBrowserListWidget.Row> {

    private final Consumer<ServerBrowserRow> onRowSelected;

    public ServerBrowserListWidget(MinecraftClient client, int width, int height, int y0, int itemHeight,
                                    Consumer<ServerBrowserRow> onRowSelected) {
        super(client, width, height, y0, itemHeight);
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
    public final class Row extends AlwaysSelectedEntryListWidget.Entry<Row> {

        private final ServerBrowserRow data;

        public Row(ServerBrowserRow data) {
            this.data = data;
        }

        public ServerBrowserRow data() {
            return data;
        }

        @Override
        public Text getNarration() {
            return Text.literal(data.serverName());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int textColor = data.respondedSuccessfully() ? 0xFFFFFFFF : 0xFF808080;
            int x = getContentX();
            int y = getContentY() + 2;

            context.drawText(client.textRenderer, data.serverName(), x, y, textColor, false);
            context.drawText(client.textRenderer, data.map(), x + 160, y, textColor, false);
            context.drawText(client.textRenderer, data.players() + "/" + data.maxPlayers(), x + 300, y, textColor, false);
            context.drawText(client.textRenderer, String.valueOf(data.ping()), x + 360, y, textColor, false);
            context.drawText(client.textRenderer, data.hasPassword() ? "[Locked]" : "", x + 410, y, textColor, false);
            context.drawText(client.textRenderer, data.isSecure() ? "[VAC]" : "", x + 470, y, textColor, false);
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubleClick) {
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

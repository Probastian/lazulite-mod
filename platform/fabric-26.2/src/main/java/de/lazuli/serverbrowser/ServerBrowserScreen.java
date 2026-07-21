package de.lazuli.serverbrowser;

import de.lazuli.api.serverbrowser.ServerBrowserColumn;
import de.lazuli.api.serverbrowser.ServerBrowserFilterState;
import de.lazuli.api.serverbrowser.ServerBrowserRow;
import de.lazuli.api.serverbrowser.ServerBrowserSession;
import de.lazuli.api.serverbrowser.ServerBrowserSource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * Version Adapter (spec Public API item 6) -- a genuinely new {@code Screen}
 * subclass (not an overlay) implementing the table/filter UI on Minecraft
 * 26.2, backed by {@link ServerBrowserSession} (which itself composes
 * {@code ServerBrowserQuery} + {@code ServerBrowserTableModel}).
 *
 * <p>Renders a single status message in place of the table/toolbar when
 * Steam is unavailable (FR5.1); the Multiplayer-screen button that opens
 * this screen stays visible/clickable regardless (FR5.2, handled by
 * {@link FabricServerBrowserButtonInjector}, not this class).
 *
 * <p>Usage example (from {@link FabricServerBrowserButtonInjector}):
 * <pre>{@code
 * Minecraft.getInstance().setScreenAndShow(new ServerBrowserScreen(parent, sessionFactory.newSession(), steamAvailable));
 * }</pre>
 */
public final class ServerBrowserScreen extends Screen {

    private static final int TOOLBAR_Y = 6;
    private static final int TOOLBAR_Y2 = 30;
    private static final int HEADER_Y = 74;
    private static final int LIST_TOP = 86;
    private static final int FOOTER_HEIGHT = 26;

    private final Screen parent;
    private final ServerBrowserSession session;
    private final boolean steamAvailable;

    private ServerBrowserListWidget listWidget;
    private EditBox searchBox;
    private EditBox maxPingBox;
    private Button hideFullToggle;
    private Button hidePasswordToggle;
    private Button hideEmptyToggle;
    private Button sourceToggle;
    private Button refreshButton;
    private Button joinButton;

    private ServerBrowserSource currentSource = ServerBrowserSource.INTERNET;
    private ServerBrowserFilterState filter = ServerBrowserFilterState.DEFAULT;
    private boolean refreshEverCompleted;
    private boolean cursorIsHand;

    // Lazily created, process-lifetime GLFW standard cursors -- Minecraft has
    // no per-widget hover-cursor API, so this talks to GLFW directly via the
    // window's raw handle. Never destroyed (glfwCreateStandardCursor handles
    // are cheap and shared across every ServerBrowserScreen open).
    private static long handCursor;
    private static long arrowCursor;

    private static long handCursor() {
        if (handCursor == 0L) {
            handCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        }
        return handCursor;
    }

    private static long arrowCursor() {
        if (arrowCursor == 0L) {
            arrowCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_ARROW_CURSOR);
        }
        return arrowCursor;
    }

    public ServerBrowserScreen(Screen parent, ServerBrowserSession session, boolean steamAvailable) {
        super(Component.literal("Server Browser"));
        this.parent = parent;
        this.session = session;
        this.steamAvailable = steamAvailable;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width / 2 - 50, height - FOOTER_HEIGHT, 100, 20)
                .build());

        if (!steamAvailable) {
            return; // FR5.1 -- status-only, no toolbar/table/query
        }

        buildToolbar();

        listWidget = new ServerBrowserListWidget(minecraft, width, height - LIST_TOP - FOOTER_HEIGHT, LIST_TOP, 14, this::joinRow);
        listWidget.setY(LIST_TOP);
        addRenderableWidget(listWidget);

        session.start(currentSource, this::onRowsChanged, this::onRefreshComplete);
    }

    private void buildToolbar() {
        searchBox = new EditBox(minecraft.font, 6, TOOLBAR_Y, 140, 20, Component.literal("Search"));
        searchBox.setResponder(text -> {
            filter = filter.withSearchText(text);
            session.setFilter(filter);
        });
        addRenderableWidget(searchBox);

        hideFullToggle = Button.builder(Component.literal("Hide Full: Off"), button -> toggleHideFull())
                .bounds(152, TOOLBAR_Y, 110, 20).build();
        addRenderableWidget(hideFullToggle);

        hidePasswordToggle = Button.builder(Component.literal("Hide Locked: Off"), button -> toggleHidePassword())
                .bounds(266, TOOLBAR_Y, 120, 20).build();
        addRenderableWidget(hidePasswordToggle);

        hideEmptyToggle = Button.builder(Component.literal("Hide Empty: Off"), button -> toggleHideEmpty())
                .bounds(390, TOOLBAR_Y, 110, 20).build();
        addRenderableWidget(hideEmptyToggle);

        sourceToggle = Button.builder(Component.literal("Source: Internet"), button -> toggleSource())
                .bounds(6, TOOLBAR_Y2, 130, 20).build();
        addRenderableWidget(sourceToggle);

        maxPingBox = new EditBox(minecraft.font, 142, TOOLBAR_Y2, 60, 20, Component.literal("Max Ping"));
        maxPingBox.setMaxLength(6);
        maxPingBox.setResponder(text -> {
            filter = filter.withMaxPing(parseMaxPing(text));
            session.setFilter(filter);
        });
        addRenderableWidget(maxPingBox);

        joinButton = Button.builder(Component.literal("Join Server"), button -> joinSelected())
                .bounds(width - 184, TOOLBAR_Y2, 90, 20).build();
        joinButton.active = false;
        addRenderableWidget(joinButton);

        refreshButton = Button.builder(Component.literal("Refresh"), button -> {
                    refreshEverCompleted = false;
                    session.refresh();
                })
                .bounds(width - 90, TOOLBAR_Y2, 84, 20).build();
        addRenderableWidget(refreshButton);
    }

    private static int parseMaxPing(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ServerBrowserRow selectedRow() {
        ServerBrowserListWidget.Row row = listWidget != null ? listWidget.getSelected() : null;
        return row != null ? row.data() : null;
    }

    private void joinSelected() {
        ServerBrowserRow selected = selectedRow();
        if (selected != null) {
            joinRow(selected);
        }
    }

    private void toggleHideFull() {
        filter = filter.withHideFull(!filter.hideFull());
        hideFullToggle.setMessage(Component.literal("Hide Full: " + (filter.hideFull() ? "On" : "Off")));
        session.setFilter(filter);
    }

    private void toggleHidePassword() {
        filter = filter.withHidePasswordProtected(!filter.hidePasswordProtected());
        hidePasswordToggle.setMessage(Component.literal("Hide Locked: " + (filter.hidePasswordProtected() ? "On" : "Off")));
        session.setFilter(filter);
    }

    private void toggleHideEmpty() {
        filter = filter.withHideEmpty(!filter.hideEmpty());
        hideEmptyToggle.setMessage(Component.literal("Hide Empty: " + (filter.hideEmpty() ? "On" : "Off")));
        session.setFilter(filter);
    }

    private void toggleSource() {
        currentSource = currentSource == ServerBrowserSource.INTERNET ? ServerBrowserSource.LAN : ServerBrowserSource.INTERNET;
        sourceToggle.setMessage(Component.literal("Source: " + (currentSource == ServerBrowserSource.INTERNET ? "Internet" : "LAN")));
        refreshEverCompleted = false;
        session.start(currentSource, this::onRowsChanged, this::onRefreshComplete);
    }

    private void onRowsChanged(java.util.List<ServerBrowserRow> rows) {
        if (listWidget != null) {
            listWidget.replaceRows(rows);
        }
    }

    private void onRefreshComplete() {
        refreshEverCompleted = true;
    }

    private void sortBy(ServerBrowserColumn column) {
        session.setSortColumn(column);
    }

    private void joinRow(ServerBrowserRow row) {
        if (row.hasPassword()) {
            Minecraft.getInstance().setScreenAndShow(new ServerBrowserPasswordPromptScreen(this, row));
        } else {
            ServerBrowserConnector.connect(this, row);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);

        if (joinButton != null) {
            joinButton.active = selectedRow() != null;
        }

        if (!steamAvailable) {
            guiGraphics.centeredText(minecraft.font,
                    Component.literal("Steam not available - make sure Steam is running and try again."),
                    width / 2, height / 2, 0xFFFFFFFF);
            return;
        }

        drawHeader(guiGraphics, "Name", 6, ServerBrowserColumn.NAME);
        drawHeader(guiGraphics, "Map", 166, ServerBrowserColumn.MAP);
        drawHeader(guiGraphics, "Players", 306, ServerBrowserColumn.PLAYERS);
        drawHeader(guiGraphics, "Ping", 366, ServerBrowserColumn.PING);
        drawHeader(guiGraphics, "Pwd", 416, ServerBrowserColumn.PASSWORD);
        drawHeader(guiGraphics, "VAC", 476, ServerBrowserColumn.SECURE);

        updateHeaderCursor(mouseX, mouseY);

        if (session.isRefreshing()) {
            guiGraphics.text(minecraft.font, "Refreshing...", width - 180, HEADER_Y - 12, 0xFFAAAAAA);
        } else if (refreshEverCompleted && session.currentRows().isEmpty()) {
            guiGraphics.centeredText(minecraft.font, Component.literal("No servers found."), width / 2, LIST_TOP + 20, 0xFFAAAAAA);
        }
    }

    private static final int HEADER_MAX_X = 520;

    private void drawHeader(GuiGraphicsExtractor guiGraphics, String label, int x, ServerBrowserColumn column) {
        boolean sorted = session.sortColumn() == column;
        String text = label;
        if (sorted) {
            text = "§l" + label + (session.sortAscending() ? " ▲" : " ▼");
        }
        guiGraphics.text(minecraft.font, text, x, HEADER_Y - 12, sorted ? 0xFFFFFF55 : 0xFFFFFFFF);
    }

    /** Hand cursor while hovering a sortable header label, arrow otherwise (no built-in per-widget cursor API, so this talks to GLFW directly). */
    private void updateHeaderCursor(int mouseX, int mouseY) {
        boolean overHeader = steamAvailable && mouseY >= HEADER_Y - 14 && mouseY < HEADER_Y
                && mouseX >= 6 && mouseX < HEADER_MAX_X;
        if (overHeader != cursorIsHand) {
            cursorIsHand = overHeader;
            GLFW.glfwSetCursor(minecraft.getWindow().handle(), overHeader ? handCursor() : arrowCursor());
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (steamAvailable && event.y() >= HEADER_Y - 14 && event.y() < HEADER_Y
                && event.x() >= 6 && event.x() < HEADER_MAX_X) {
            ServerBrowserColumn clicked = columnAt((int) event.x());
            if (clicked != null) {
                sortBy(clicked);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private ServerBrowserColumn columnAt(int x) {
        if (x < 160) {
            return ServerBrowserColumn.NAME;
        } else if (x < 300) {
            return ServerBrowserColumn.MAP;
        } else if (x < 360) {
            return ServerBrowserColumn.PLAYERS;
        } else if (x < 410) {
            return ServerBrowserColumn.PING;
        } else if (x < 470) {
            return ServerBrowserColumn.PASSWORD;
        } else {
            return ServerBrowserColumn.SECURE;
        }
    }

    @Override
    public void onClose() {
        session.close();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        session.close();
        if (cursorIsHand) {
            cursorIsHand = false;
            GLFW.glfwSetCursor(minecraft.getWindow().handle(), arrowCursor());
        }
    }
}

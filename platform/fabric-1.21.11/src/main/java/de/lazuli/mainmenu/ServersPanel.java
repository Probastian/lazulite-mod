package de.lazuli.mainmenu;

import de.lazuli.LazuliMod;
import de.lazuli.api.serverbrowser.ServerBrowserColumn;
import de.lazuli.api.serverbrowser.ServerBrowserFilterState;
import de.lazuli.api.serverbrowser.ServerBrowserRow;
import de.lazuli.api.serverbrowser.ServerBrowserSession;
import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.api.serverbrowser.ServerBrowserSource;
import de.lazuli.features.mainmenu.services.MainMenuStateMachine;
import de.lazuli.serverbrowser.ServerBrowserPasswordPromptScreen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;

/**
 * Servers tab panel (specification FR4) -- {@code fabric-1.21.11} (Yarn-mapped,
 * obfuscated) port of the {@code fabric-26.1}/{@code fabric-26.2} class of the
 * same name.
 *
 * <p>Session lifecycle (plan Existing Implementation): a fresh
 * {@link ServerBrowserSession} is obtained via {@link ServerBrowserSessionFactory#newSession()}
 * when the Browser sub-view becomes active ({@link #activateBrowser()}) and
 * closed when the sub-view is left or the screen closes ({@link #deactivateBrowser()}).
 */
public final class ServersPanel {

    private static final int ROW_HEIGHT_COMPACT = 24;
    private static final int ROW_HEIGHT_EXPANDED = 54;

    private final MainMenuStateMachine state;
    private final MainMenuScreen owner;
    private final ServerBrowserSessionFactory sessionFactory;
    private final boolean steamAvailable;

    private final ServerList savedServers = new ServerList(MinecraftClient.getInstance());

    private ServerBrowserSession browserSession;
    private List<ServerBrowserRow> browserRows = List.of();
    private ServerBrowserFilterState filter = ServerBrowserFilterState.DEFAULT;
    private boolean browserRefreshEverCompleted;
    private float refreshSpinDegrees;

    private ButtonWidget subViewToggle;
    private ButtonWidget refreshButton;
    private ButtonWidget directConnectButton;
    private ButtonWidget addServerButton;
    private TextFieldWidget searchBox;
    private ButtonWidget hideFullToggle;
    private ButtonWidget hidePasswordToggle;

    public ServersPanel(MainMenuStateMachine state, MainMenuScreen owner, ServerBrowserSessionFactory sessionFactory, boolean steamAvailable) {
        this.state = state;
        this.owner = owner;
        this.sessionFactory = sessionFactory;
        this.steamAvailable = steamAvailable;
        try {
            savedServers.loadFile();
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to load saved server list: " + e);
        }
    }

    /** Called once, when the tab bar/screen constructs the panel's own buttons. */
    public void init(Consumer<ClickableWidget> addWidget, int x, int y, int width) {
        subViewToggle = ButtonWidget.builder(Text.literal(subViewLabel()), b -> toggleSubView())
                .dimensions(x, y - 24, 110, 20).build();
        addWidget.accept(subViewToggle);

        refreshButton = ButtonWidget.builder(Text.literal("Refresh"), b -> onRefreshClicked())
                .dimensions(x + width - 76, y - 24, 76, 20).build();
        addWidget.accept(refreshButton);

        directConnectButton = ButtonWidget.builder(Text.literal("Direct Connect"), b ->
                        MinecraftClient.getInstance().setScreen(new DirectConnectModalScreen(owner)))
                .dimensions(x + width - 250, y - 24, 110, 20).build();
        addWidget.accept(directConnectButton);

        addServerButton = ButtonWidget.builder(Text.literal("+ Add Server"), b ->
                        MinecraftClient.getInstance().setScreen(new AddServerModalScreen(owner, savedServers)))
                .dimensions(x + width - 364, y - 24, 100, 20).build();
        addWidget.accept(addServerButton);

        searchBox = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, x, y, 160, 18, Text.literal("Search"));
        searchBox.setChangedListener(text -> {
            filter = filter.withSearchText(text);
            if (browserSession != null) {
                browserSession.setFilter(filter);
            }
        });
        addWidget.accept(searchBox);

        hideFullToggle = ButtonWidget.builder(Text.literal("Hide Full"), b -> toggleHideFull())
                .dimensions(x + 168, y, 100, 18).build();
        addWidget.accept(hideFullToggle);

        hidePasswordToggle = ButtonWidget.builder(Text.literal("Hide Locked"), b -> toggleHidePassword())
                .dimensions(x + 272, y, 110, 18).build();
        addWidget.accept(hidePasswordToggle);

        applyVisibility();
    }

    private String subViewLabel() {
        return state.serversSubView() == MainMenuStateMachine.ServersSubView.SAVED ? "Sub-view: Saved" : "Sub-view: Browser";
    }

    private void toggleSubView() {
        if (state.serversSubView() == MainMenuStateMachine.ServersSubView.SAVED) {
            state.setServersSubView(MainMenuStateMachine.ServersSubView.BROWSER);
            activateBrowser();
        } else {
            state.setServersSubView(MainMenuStateMachine.ServersSubView.SAVED);
            deactivateBrowser();
        }
        subViewToggle.setMessage(Text.literal(subViewLabel()));
        applyVisibility();
    }

    private boolean tabActive;

    /** Called by {@code MainMenuScreen} whenever the Servers tab's active/inactive state changes. */
    public void setTabActive(boolean active) {
        this.tabActive = active;
        if (active && state.serversSubView() == MainMenuStateMachine.ServersSubView.BROWSER) {
            activateBrowser();
        } else if (!active) {
            deactivateBrowser();
        }
        applyVisibility();
    }

    private void applyVisibility() {
        boolean browser = tabActive && state.serversSubView() == MainMenuStateMachine.ServersSubView.BROWSER && steamAvailable;
        subViewToggle.visible = tabActive;
        refreshButton.visible = browser;
        directConnectButton.visible = browser;
        addServerButton.visible = browser;
        searchBox.visible = browser;
        hideFullToggle.visible = browser;
        hidePasswordToggle.visible = browser;
    }

    /** Called when the Browser sub-view becomes active. Idempotent. */
    public void activateBrowser() {
        if (!steamAvailable || browserSession != null) {
            return;
        }
        browserSession = sessionFactory.newSession();
        browserRefreshEverCompleted = false;
        browserSession.start(ServerBrowserSource.INTERNET, rows -> browserRows = rows, () -> browserRefreshEverCompleted = true);
    }

    /** Called when the Browser sub-view is left, or the screen closes. Idempotent. */
    public void deactivateBrowser() {
        if (browserSession != null) {
            browserSession.close();
            browserSession = null;
            browserRows = List.of();
        }
    }

    private void onRefreshClicked() {
        if (browserSession != null) {
            browserRefreshEverCompleted = false;
            browserSession.refresh();
        }
        refreshSpinDegrees += 360f; // cosmetic-only, independent of actual refresh completion
    }

    private void toggleHideFull() {
        filter = filter.withHideFull(!filter.hideFull());
        hideFullToggle.setMessage(Text.literal("Hide Full: " + (filter.hideFull() ? "On" : "Off")));
        if (browserSession != null) {
            browserSession.setFilter(filter);
        }
    }

    private void toggleHidePassword() {
        filter = filter.withHidePasswordProtected(!filter.hidePasswordProtected());
        hidePasswordToggle.setMessage(Text.literal("Hide Locked: " + (filter.hidePasswordProtected() ? "On" : "Off")));
        if (browserSession != null) {
            browserSession.setFilter(filter);
        }
    }

    public void render(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        if (refreshSpinDegrees > 0) {
            refreshSpinDegrees = Math.max(0, refreshSpinDegrees - 24f); // decays back to 0 over ~15 frames
        }

        if (state.serversSubView() == MainMenuStateMachine.ServersSubView.SAVED) {
            renderSaved(context, font, x, y + 24, width, height - 24, mouseX, mouseY);
        } else {
            renderBrowser(context, font, x, y + 24, width, height - 24, mouseX, mouseY);
        }
    }

    private void renderSaved(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int count = savedServers.size();
        if (count == 0) {
            context.drawText(font, Text.literal("No saved servers yet."), x, y, 0xFF908C7F, false);
            return;
        }
        int rowY = y;
        for (int i = 0; i < count; i++) {
            ServerInfo server = savedServers.get(i);
            String rowId = "saved:" + i;
            boolean expanded = rowId.equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight;
            context.fill(x, rowY, x + width, rowY + rowHeight, hovered ? 0xFF2A2820 : 0xFF201E17);

            int pingColor = pingStatusColor(server.ping);
            context.fill(x + 6, rowY + 8, x + 10, rowY + 12, pingColor);
            context.drawText(font, Text.literal(server.name), x + 16, rowY + 4, 0xFFEAE8E1, false);
            String playersText = server.players != null ? server.players.online() + "/" + server.players.max() + " players" : "";
            context.drawText(font, Text.literal(playersText), x + 16, rowY + 15, 0xFF908C7F, false);

            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                boolean connectHover = mouseX >= x + width - 100 && mouseX <= x + width - 8 && mouseY >= buttonY && mouseY <= buttonY + 18;
                context.fill(x + width - 100, buttonY, x + width - 8, buttonY + 18, connectHover ? 0xFF64A066 : 0xFF528A54);
                context.drawCenteredTextWithShadow(font, "Connect", x + width - 54, buttonY + 5, 0xFFFFFFFF);
            }
            rowY += rowHeight + 4;
        }
    }

    private static int pingStatusColor(long ping) {
        if (ping < 0) {
            return 0xFF908C7F;
        } else if (ping < 150) {
            return 0xFF528A54;
        } else if (ping < 400) {
            return 0xFFC9A227;
        } else {
            return 0xFFB54848;
        }
    }

    private void renderBrowser(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        if (!steamAvailable) {
            context.drawCenteredTextWithShadow(font,
                    "Steam not available - make sure Steam is running and try again.",
                    x + width / 2, y + height / 2, 0xFFFFFFFF);
            return;
        }

        int headerY = y + 24;
        drawColumnHeader(context, font, "Name", x + 24, headerY, ServerBrowserColumn.NAME);
        drawColumnHeader(context, font, "Players", x + width - 180, headerY, ServerBrowserColumn.PLAYERS);
        drawColumnHeader(context, font, "Latency", x + width - 100, headerY, ServerBrowserColumn.PING);

        int rowY = headerY + 16;
        for (ServerBrowserRow row : browserRows) {
            if (rowY + ROW_HEIGHT_COMPACT > y + height) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT_COMPACT;
            context.fill(x, rowY, x + width, rowY + ROW_HEIGHT_COMPACT, hovered ? 0xFF2A2820 : 0xFF201E17);
            if (row.hasPassword()) {
                context.drawText(font, Text.literal("*"), x + 4, rowY + 7, 0xFF908C7F, false);
            }
            context.drawText(font, Text.literal(row.serverName()), x + 24, rowY + 7, row.respondedSuccessfully() ? 0xFFEAE8E1 : 0xFF605C50, false);
            context.drawText(font, Text.literal(row.players() + "/" + row.maxPlayers()), x + width - 180, rowY + 7, 0xFF908C7F, false);
            context.drawText(font, Text.literal(row.ping() + "ms"), x + width - 100, rowY + 7, pingStatusColor(row.ping()), false);
            rowY += ROW_HEIGHT_COMPACT + 2;
        }

        if (browserSession != null && browserSession.isRefreshing()) {
            context.drawText(font, Text.literal("Refreshing..."), x + width - 260, headerY, 0xFF908C7F, false);
        } else if (browserRefreshEverCompleted && browserRows.isEmpty()) {
            context.drawCenteredTextWithShadow(font, "No servers found.", x + width / 2, rowY + 12, 0xFF908C7F);
        }
    }

    private void drawColumnHeader(DrawContext context, TextRenderer font, String label, int x, int y, ServerBrowserColumn column) {
        boolean sorted = browserSession != null && browserSession.sortColumn() == column;
        String text = sorted ? label + (browserSession.sortAscending() ? " ^" : " v") : label;
        context.drawText(font, Text.literal(text), x, y, sorted ? 0xFFC9A227 : 0xFFEAE8E1, false);
    }

    /** @return true if this click was consumed by a row/header in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (state.serversSubView() == MainMenuStateMachine.ServersSubView.SAVED) {
            return savedMouseClicked(x, y + 24, width, height - 24, mouseX, mouseY);
        }
        return browserMouseClicked(x, y + 24, width, height - 24, mouseX, mouseY);
    }

    private boolean savedMouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int count = savedServers.size();
        int rowY = y;
        for (int i = 0; i < count; i++) {
            ServerInfo server = savedServers.get(i);
            String rowId = "saved:" + i;
            boolean expanded = rowId.equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                if (mouseX >= x + width - 100 && mouseX <= x + width - 8 && mouseY >= buttonY && mouseY <= buttonY + 18) {
                    connect(server);
                    return true;
                }
            }
            if (mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                state.toggleRowExpanded(rowId);
                return true;
            }
            rowY += rowHeight + 4;
        }
        return false;
    }

    private boolean browserMouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (browserSession == null) {
            return false;
        }
        int headerY = y + 24;
        if (mouseY >= headerY - 12 && mouseY < headerY + 4) {
            if (mouseX >= x + 24 && mouseX < x + width - 200) {
                browserSession.setSortColumn(ServerBrowserColumn.NAME);
                return true;
            } else if (mouseX >= x + width - 180 && mouseX < x + width - 110) {
                browserSession.setSortColumn(ServerBrowserColumn.PLAYERS);
                return true;
            } else if (mouseX >= x + width - 100 && mouseX < x + width) {
                browserSession.setSortColumn(ServerBrowserColumn.PING);
                return true;
            }
        }

        int rowY = headerY + 16;
        for (ServerBrowserRow row : browserRows) {
            if (rowY + ROW_HEIGHT_COMPACT > y + height) {
                break;
            }
            if (mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT_COMPACT) {
                joinBrowserRow(row);
                return true;
            }
            rowY += ROW_HEIGHT_COMPACT + 2;
        }
        return false;
    }

    private void joinBrowserRow(ServerBrowserRow row) {
        if (!row.respondedSuccessfully()) {
            return;
        }
        if (row.hasPassword()) {
            MinecraftClient.getInstance().setScreen(new ServerBrowserPasswordPromptScreen(owner, row));
        } else {
            ServerAddress address = ServerAddress.parse(row.address());
            ServerInfo serverInfo = new ServerInfo(row.serverName(), row.address(), ServerInfo.ServerType.OTHER);
            ConnectScreen.connect(owner, MinecraftClient.getInstance(), address, serverInfo, false, null);
        }
    }

    private void connect(ServerInfo server) {
        ServerAddress address = ServerAddress.parse(server.address);
        ConnectScreen.connect(owner, MinecraftClient.getInstance(), address, server, false, null);
    }
}

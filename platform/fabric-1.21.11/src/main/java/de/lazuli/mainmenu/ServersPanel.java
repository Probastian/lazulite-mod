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
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.network.NetworkingBackend;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private static final int ROW_HEIGHT_COMPACT = 32;
    private static final int ROW_HEIGHT_EXPANDED = 72;
    private static final int ICON_TEX_SIZE = 64;
    private static final int IMAGE_MARGIN = 2;
    private static final int BORDER_THICKNESS = 2;
    private static final int SCROLL_STEP = 16;

    private final MainMenuStateMachine state;
    private final MainMenuScreen owner;
    private final ServerBrowserSessionFactory sessionFactory;
    private final boolean steamAvailable;

    private final ServerList savedServers = new ServerList(MinecraftClient.getInstance());
    private final MultiplayerServerListPinger savedServerPinger = new MultiplayerServerListPinger();
    private final IconTextureCache iconCache = new IconTextureCache(LazuliMod.LOGGER::warn);
    private final Set<Integer> pingingSavedIndices = new HashSet<>();
    private boolean savedPingedOnce;

    private ServerBrowserSession browserSession;
    private List<ServerBrowserRow> browserRows = List.of();
    private ServerBrowserFilterState filter = ServerBrowserFilterState.DEFAULT;
    private boolean browserRefreshEverCompleted;
    private float refreshSpinDegrees;

    // FX10: Browse sub-view scroll offset, in pixels, clamped in renderBrowser
    // to [0, maxScroll] each frame once total content height is known.
    private int browseScrollOffset;

    private ButtonWidget subViewToggle;
    private ButtonWidget refreshButton;
    private ButtonWidget savedRefreshButton;
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

        // FX4.2: a separate, Saved-view-specific refresh control -- distinct
        // from the Browser-only refreshButton above, since it re-pings every
        // saved server via MultiplayerServerListPinger rather than
        // ServerBrowserSession.refresh() (Browser-only).
        savedRefreshButton = ButtonWidget.builder(Text.literal("Refresh"), b -> pingAllSavedServers())
                .dimensions(x + width - 76, y - 24, 76, 20).build();
        addWidget.accept(savedRefreshButton);

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
            maybePingSavedOnce();
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
        } else if (active && state.serversSubView() == MainMenuStateMachine.ServersSubView.SAVED) {
            maybePingSavedOnce();
        } else if (!active) {
            deactivateBrowser();
        }
        applyVisibility();
    }

    private void applyVisibility() {
        boolean browser = tabActive && state.serversSubView() == MainMenuStateMachine.ServersSubView.BROWSER && steamAvailable;
        boolean saved = tabActive && state.serversSubView() == MainMenuStateMachine.ServersSubView.SAVED;
        subViewToggle.visible = tabActive;
        refreshButton.visible = browser;
        savedRefreshButton.visible = saved;
        directConnectButton.visible = browser;
        addServerButton.visible = browser;
        searchBox.visible = browser;
        hideFullToggle.visible = browser;
        hidePasswordToggle.visible = browser;
    }

    /** FX4.5: saved servers are pinged once automatically the first time the Saved sub-view becomes visible. */
    private void maybePingSavedOnce() {
        if (savedPingedOnce) {
            return;
        }
        savedPingedOnce = true;
        pingAllSavedServers();
    }

    /** FX4.2/FX4.5: re-pings every saved server via vanilla's own {@link MultiplayerServerListPinger}. */
    private void pingAllSavedServers() {
        for (int i = 0; i < savedServers.size(); i++) {
            pingSavedServer(i);
        }
    }

    private void pingSavedServer(int index) {
        if (!pingingSavedIndices.add(index)) {
            return;
        }
        ServerInfo server = savedServers.get(index);
        try {
            savedServerPinger.add(server, () -> pingingSavedIndices.remove(index), () -> pingingSavedIndices.remove(index),
                    NetworkingBackend.remote(MinecraftClient.getInstance().options.shouldUseNativeTransport()));
        } catch (Exception e) {
            pingingSavedIndices.remove(index);
            server.setStatus(ServerInfo.Status.UNREACHABLE);
        }
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

            // FX12.1/FX12.4: 2/3-row-height 1:1 image (full-height read as
            // oversized against the row's text) with a ping-status colored
            // border replacing the old separate status dot, vertically
            // centered in the leftover space.
            int imageSize = (rowHeight - IMAGE_MARGIN * 2) * 2 / 3;
            int imageX = x + IMAGE_MARGIN;
            int imageY = rowY + (rowHeight - imageSize) / 2;
            int pingColor = pingStatusColor(server.ping);
            context.fill(imageX - BORDER_THICKNESS, imageY - BORDER_THICKNESS,
                    imageX + imageSize + BORDER_THICKNESS, imageY + imageSize + BORDER_THICKNESS, pingColor);
            Identifier iconId = iconCache.forServer(rowId, server.getFavicon());
            context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, imageX, imageY, 0f, 0f,
                    imageSize, imageSize, ICON_TEX_SIZE, ICON_TEX_SIZE);

            int textX = imageX + imageSize + BORDER_THICKNESS + 6;
            int textAvailableWidth = Math.max(20, x + width - textX - 4);
            context.drawText(font, Text.literal(server.name), textX, rowY + 4, 0xFFEAE8E1, false);

            // FX4.3: pending-state placeholder instead of a blank string.
            String playersText;
            if (server.players != null) {
                playersText = server.players.online() + "/" + server.players.max() + " players";
            } else if (pingingSavedIndices.contains(i) || server.getStatus() == ServerInfo.Status.PINGING) {
                playersText = "Pinging...";
            } else {
                playersText = "—";
            }
            context.drawText(font, Text.literal(playersText), textX, rowY + 15, 0xFF908C7F, false);

            // FX11: MOTD always shown (not gated on expanded) once available,
            // clipped/truncated to the row's own available width so it never
            // overflows into the tab bar (R5: reuse vanilla's own
            // TextRenderer#trimToWidth truncation helper rather than
            // inventing manual pixel-width math). This Yarn mapping's own
            // cross-version divergence: the server's MOTD field is named
            // "label" here, not "motd" -- see .claude/context/minecraft.md's
            // Known Cross-Version API Differences table.
            if (server.label != null) {
                String motdPlain = font.trimToWidth(server.label.getString(), textAvailableWidth);
                context.drawText(font, Text.literal(motdPlain), textX, rowY + 26, 0xFF908C7F, false);
            }

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

        // FX10: the visible row viewport sits below the header; scroll offset
        // is clamped here (FX10.3) once total content height is known, then
        // clipped (FX10.4) so no partially-visible row bleeds outside it.
        int viewportTop = headerY + 16;
        int viewportBottom = y + height;
        int rowStride = ROW_HEIGHT_COMPACT + 2;
        int contentHeight = browserRows.size() * rowStride;
        int viewportHeight = Math.max(0, viewportBottom - viewportTop);
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        browseScrollOffset = Math.max(0, Math.min(browseScrollOffset, maxScroll));

        context.enableScissor(x, viewportTop, x + width, viewportBottom);
        int rowY = viewportTop - browseScrollOffset;
        for (ServerBrowserRow row : browserRows) {
            if (rowY + ROW_HEIGHT_COMPACT > viewportTop && rowY < viewportBottom) {
                boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT_COMPACT
                        && mouseY >= viewportTop && mouseY <= viewportBottom;
                context.fill(x, rowY, x + width, rowY + ROW_HEIGHT_COMPACT, hovered ? 0xFF2A2820 : 0xFF201E17);
                if (row.hasPassword()) {
                    context.drawText(font, Text.literal("*"), x + 4, rowY + 7, 0xFF908C7F, false);
                }
                context.drawText(font, Text.literal(row.serverName()), x + 24, rowY + 7, row.respondedSuccessfully() ? 0xFFEAE8E1 : 0xFF605C50, false);
                context.drawText(font, Text.literal(row.players() + "/" + row.maxPlayers()), x + width - 180, rowY + 7, 0xFF908C7F, false);
                context.drawText(font, Text.literal(row.ping() + "ms"), x + width - 100, rowY + 7, pingStatusColor(row.ping()), false);
            }
            rowY += rowStride;
        }
        context.disableScissor();

        if (browserSession != null && browserSession.isRefreshing()) {
            context.drawText(font, Text.literal("Refreshing..."), x + width - 260, headerY, 0xFF908C7F, false);
        } else if (browserRefreshEverCompleted && browserRows.isEmpty()) {
            context.drawCenteredTextWithShadow(font, "No servers found.", x + width / 2, viewportTop + 12, 0xFF908C7F);
        }
    }

    /**
     * FX10.2: mouse-wheel forwarding from {@code MainMenuScreen} while the
     * Browse sub-view is active and the mouse is over this panel. Clamping to
     * [0, maxScroll] happens per-frame in {@link #renderBrowser}, so here we
     * only need to accumulate the raw delta.
     */
    public boolean mouseScrolled(int x, int y, int width, int height, double mouseX, double mouseY, double scrollDelta) {
        if (state.serversSubView() != MainMenuStateMachine.ServersSubView.BROWSER) {
            return false;
        }
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
            return false;
        }
        browseScrollOffset -= (int) Math.round(scrollDelta * SCROLL_STEP);
        if (browseScrollOffset < 0) {
            browseScrollOffset = 0;
        }
        return true;
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
                    MainMenuScreen.playClickSound();
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
                MainMenuScreen.playClickSound();
                browserSession.setSortColumn(ServerBrowserColumn.NAME);
                return true;
            } else if (mouseX >= x + width - 180 && mouseX < x + width - 110) {
                MainMenuScreen.playClickSound();
                browserSession.setSortColumn(ServerBrowserColumn.PLAYERS);
                return true;
            } else if (mouseX >= x + width - 100 && mouseX < x + width) {
                MainMenuScreen.playClickSound();
                browserSession.setSortColumn(ServerBrowserColumn.PING);
                return true;
            }
        }

        // FX10: rows are shifted by the current scroll offset (mirroring
        // renderBrowser's viewport math) and clicks outside the viewport
        // (scrolled off above/below the header/panel bounds) are ignored.
        int viewportTop = headerY + 16;
        int viewportBottom = y + height;
        int rowY = viewportTop - browseScrollOffset;
        for (ServerBrowserRow row : browserRows) {
            if (rowY + ROW_HEIGHT_COMPACT > viewportTop && rowY < viewportBottom
                    && mouseX >= x && mouseX <= x + width && mouseY >= Math.max(rowY, viewportTop)
                    && mouseY <= Math.min(rowY + ROW_HEIGHT_COMPACT, viewportBottom)) {
                MainMenuScreen.playClickSound();
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

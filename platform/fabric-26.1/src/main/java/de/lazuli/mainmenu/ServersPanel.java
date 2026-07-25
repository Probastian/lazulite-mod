package de.lazuli.mainmenu;

import de.lazuli.api.serverbrowser.ServerBrowserColumn;
import de.lazuli.api.serverbrowser.ServerBrowserFilterState;
import de.lazuli.api.serverbrowser.ServerBrowserRow;
import de.lazuli.api.serverbrowser.ServerBrowserSession;
import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.api.serverbrowser.ServerBrowserSource;
import de.lazuli.api.serverjoinpresence.FriendServerPresenceReader;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.features.mainmenu.services.MainMenuStateMachine;
import de.lazuli.friends.AvatarTextureCache;
import de.lazuli.serverbrowser.ServerBrowserPasswordPromptScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.EventLoopGroupHolder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Servers tab panel (specification FR4): a Saved sub-view (real vanilla
 * {@link ServerList} data, same accordion row pattern as {@link WorldsPanel})
 * and a Browser sub-view rendering {@link ServerBrowserSession}'s live data
 * inside this panel's own layout (not the separate {@code ServerBrowserScreen}).
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

    // FR-B3.5: latency filter options this button cycles through (Any / <50ms
    // / <100ms / <200ms per the design handoff), mapped onto
    // ServerBrowserFilterState.maxPing (0 == no limit == "Any").
    private static final int[] MAX_PING_OPTIONS = { 0, 50, 100, 200 };

    private final MainMenuStateMachine state;
    private final MainMenuScreen owner;
    private final ServerBrowserSessionFactory sessionFactory;
    private final boolean steamAvailable;
    // Batch-2 FR-BB4.1/4.3: friend-on-server counts/identities and the
    // shared avatar-texture cache (same mechanism the friends sidebar
    // already uses, no new texture-loading code) for per-row avatars.
    private final FriendServerPresenceReader friendServerPresenceReader;
    private final AvatarTextureCache avatarTextureCache;
    private final FriendsSidebarFacade friendsSidebarFacade;
    private static final int MAX_SHOWN_AVATARS = 2;
    private static final int AVATAR_SIZE = 14;
    private static final int AVATAR_GAP = 2;

    private final ServerList savedServers = new ServerList(Minecraft.getInstance());
    private final ServerStatusPinger savedServerPinger = new ServerStatusPinger();
    private final IconTextureCache iconCache = new IconTextureCache(de.lazuli.LazuliMod.LOGGER::warn);
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

    private Button subViewToggle;
    private Button refreshButton;
    private Button savedRefreshButton;
    private Button directConnectButton;
    private Button addServerButton;
    private EditBox searchBox;
    private Button hideFullToggle;
    private Button hidePasswordToggle;
    private Button latencyToggle;

    public ServersPanel(MainMenuStateMachine state, MainMenuScreen owner, ServerBrowserSessionFactory sessionFactory, boolean steamAvailable,
                         FriendServerPresenceReader friendServerPresenceReader, AvatarTextureCache avatarTextureCache,
                         FriendsSidebarFacade friendsSidebarFacade) {
        this.state = state;
        this.owner = owner;
        this.sessionFactory = sessionFactory;
        this.steamAvailable = steamAvailable;
        this.friendServerPresenceReader = friendServerPresenceReader;
        this.avatarTextureCache = avatarTextureCache;
        this.friendsSidebarFacade = friendsSidebarFacade;
        try {
            savedServers.load();
        } catch (Exception e) {
            de.lazuli.LazuliMod.LOGGER.warn("Failed to load saved server list: " + e);
        }
    }

    /** Called once, when the tab bar/screen constructs the panel's own buttons. */
    public void init(Consumer<AbstractWidget> addWidget, int x, int y, int width) {
        subViewToggle = Button.builder(Component.literal(subViewLabel()), b -> toggleSubView())
                .bounds(x + CONTENT_LEFT_PAD, y - 24, 110, 20).build();
        addWidget.accept(subViewToggle);

        refreshButton = Button.builder(Component.literal("Refresh"), b -> onRefreshClicked())
                .bounds(x + width - 76, y - 24, 76, 20).build();
        addWidget.accept(refreshButton);

        // FX4.2: a separate, Saved-view-specific refresh control -- distinct
        // from the Browser-only refreshButton above, since it re-pings every
        // saved server via ServerStatusPinger rather than
        // ServerBrowserSession.refresh() (Browser-only).
        savedRefreshButton = Button.builder(Component.literal("Refresh"), b -> pingAllSavedServers())
                .bounds(x + width - 76, y - 24, 76, 20).build();
        addWidget.accept(savedRefreshButton);

        directConnectButton = Button.builder(Component.literal("Direct Connect"), b ->
                Minecraft.getInstance().setScreenAndShow(new DirectConnectModalScreen(owner)))
                .bounds(x + width - 250, y - 24, 110, 20).build();
        addWidget.accept(directConnectButton);

        addServerButton = Button.builder(Component.literal("+ Add Server"), b ->
                Minecraft.getInstance().setScreenAndShow(new AddServerModalScreen(owner, savedServers)))
                .bounds(x + width - 364, y - 24, 100, 20).build();
        addWidget.accept(addServerButton);

        searchBox = new EditBox(Minecraft.getInstance().font, x + CONTENT_LEFT_PAD, y, 160, 18, Component.literal("Search"));
        searchBox.setResponder(text -> {
            filter = filter.withSearchText(text);
            if (browserSession != null) {
                browserSession.setFilter(filter);
            }
        });
        addWidget.accept(searchBox);

        hideFullToggle = Button.builder(Component.literal("Hide Full"), b -> toggleHideFull())
                .bounds(x + CONTENT_LEFT_PAD + 168, y, 100, 18).build();
        addWidget.accept(hideFullToggle);

        hidePasswordToggle = Button.builder(Component.literal("Hide Locked"), b -> toggleHidePassword())
                .bounds(x + 272, y, 110, 18).build();
        addWidget.accept(hidePasswordToggle);

        // FR-B3.5: latency filter -- a select-style cycling control (Any /
        // <50ms / <100ms / <200ms) wired to filter.maxPing, previously
        // entirely absent from this panel.
        latencyToggle = Button.builder(Component.literal(latencyLabel()), b -> cycleMaxPing())
                .bounds(x + 386, y, 100, 18).build();
        addWidget.accept(latencyToggle);

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
        subViewToggle.setMessage(Component.literal(subViewLabel()));
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
        latencyToggle.visible = browser;
    }

    private String latencyLabel() {
        int maxPing = filter.maxPing();
        return maxPing <= 0 ? "Latency: Any" : "Latency: <" + maxPing + "ms";
    }

    private void cycleMaxPing() {
        int currentIndex = 0;
        for (int i = 0; i < MAX_PING_OPTIONS.length; i++) {
            if (MAX_PING_OPTIONS[i] == filter.maxPing()) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex = (currentIndex + 1) % MAX_PING_OPTIONS.length;
        filter = filter.withMaxPing(MAX_PING_OPTIONS[nextIndex]);
        latencyToggle.setMessage(Component.literal(latencyLabel()));
        if (browserSession != null) {
            browserSession.setFilter(filter);
        }
    }

    /** FX4.5: saved servers are pinged once automatically the first time the Saved sub-view becomes visible. */
    private void maybePingSavedOnce() {
        if (savedPingedOnce) {
            return;
        }
        savedPingedOnce = true;
        pingAllSavedServers();
    }

    /** FX4.2/FX4.5: re-pings every saved server via vanilla's own {@link ServerStatusPinger}. */
    private void pingAllSavedServers() {
        for (int i = 0; i < savedServers.size(); i++) {
            pingSavedServer(i);
        }
    }

    private void pingSavedServer(int index) {
        if (!pingingSavedIndices.add(index)) {
            return;
        }
        ServerData server = savedServers.get(index);
        try {
            savedServerPinger.pingServer(server, () -> pingingSavedIndices.remove(index), () -> pingingSavedIndices.remove(index),
                    EventLoopGroupHolder.remote(Minecraft.getInstance().options.useNativeTransport()));
        } catch (Exception e) {
            pingingSavedIndices.remove(index);
            server.setState(ServerData.State.UNREACHABLE);
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
        hideFullToggle.setMessage(Component.literal("Hide Full: " + (filter.hideFull() ? "On" : "Off")));
        if (browserSession != null) {
            browserSession.setFilter(filter);
        }
    }

    private void toggleHidePassword() {
        filter = filter.withHidePasswordProtected(!filter.hidePasswordProtected());
        hidePasswordToggle.setMessage(Component.literal("Hide Locked: " + (filter.hidePasswordProtected() ? "On" : "Off")));
        if (browserSession != null) {
            browserSession.setFilter(filter);
        }
    }

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        if (refreshSpinDegrees > 0) {
            refreshSpinDegrees = Math.max(0, refreshSpinDegrees - 24f); // decays back to 0 over ~15 frames
        }

        if (state.serversSubView() == MainMenuStateMachine.ServersSubView.SAVED) {
            renderSaved(guiGraphics, font, x, y + 24, width, height - 24, mouseX, mouseY);
        } else {
            renderBrowser(guiGraphics, font, x, y + 24, width, height - 24, mouseX, mouseY);
        }
    }

    private static final int CONTENT_LEFT_PAD = 8;

    private void renderSaved(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int leftX = x + CONTENT_LEFT_PAD;
        int count = savedServers.size();
        if (count == 0) {
            guiGraphics.text(font, Component.literal("No saved servers yet."), leftX, y, 0xFF908C7F);
            return;
        }
        int rowY = y;
        for (int i = 0; i < count; i++) {
            ServerData server = savedServers.get(i);
            String rowId = "saved:" + i;
            boolean expanded = rowId.equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight;
            guiGraphics.fill(x, rowY, x + width, rowY + rowHeight, hovered ? 0xFF2A2820 : 0xFF201E17);

            int pingColor = pingStatusColor(server.ping);
            Identifier iconId = iconCache.forServer(rowId, server.getIconBytes());
            int textX;
            if (expanded) {
                // FR-B3.4: expanded row shows the server icon scaled up to fill
                // the larger thumbnail area (single real icon, no repeated tiles).
                int gridSize = rowHeight - IMAGE_MARGIN * 2;
                int gridX = leftX + IMAGE_MARGIN;
                int gridY = rowY + IMAGE_MARGIN;
                guiGraphics.fill(gridX - BORDER_THICKNESS, gridY - BORDER_THICKNESS,
                        gridX + gridSize + BORDER_THICKNESS, gridY + gridSize + BORDER_THICKNESS, pingColor);
                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, iconId, gridX, gridY, 0f, 0f,
                        gridSize, gridSize, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE);
                textX = gridX + gridSize + BORDER_THICKNESS + 6;
            } else {
                // FX12.1/FX12.4: 2/3-row-height 1:1 image (full-height read as
                // oversized against the row's text) with a ping-status colored
                // border replacing the old separate status dot, vertically
                // centered in the leftover space.
                int imageSize = (rowHeight - IMAGE_MARGIN * 2) * 2 / 3;
                int imageX = leftX + IMAGE_MARGIN;
                int imageY = rowY + (rowHeight - imageSize) / 2;
                guiGraphics.fill(imageX - BORDER_THICKNESS, imageY - BORDER_THICKNESS,
                        imageX + imageSize + BORDER_THICKNESS, imageY + imageSize + BORDER_THICKNESS, pingColor);
                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, iconId, imageX, imageY, 0f, 0f,
                        imageSize, imageSize, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE);
                textX = imageX + imageSize + BORDER_THICKNESS + 6;
            }

            int textAvailableWidth = Math.max(20, x + width - textX - 4);
            guiGraphics.text(font, Component.literal(server.name), textX, rowY + 4, 0xFFEAE8E1);

            // Batch-2 FR-BB4.2/4.3: friend avatars for this row, right-aligned
            // in the row's own top-right corner -- empty space on a compact
            // row (no button there); on an expanded row this sits above the
            // Connect button (bottom-right), so the two never overlap.
            renderFriendAvatars(guiGraphics, server.ip, x + width, rowY + 4);

            // FX4.3: pending-state placeholder instead of a blank string.
            String playersText;
            if (server.players != null) {
                playersText = server.players.online() + "/" + server.players.max() + " players";
            } else if (pingingSavedIndices.contains(i) || server.state() == ServerData.State.PINGING) {
                playersText = "Pinging...";
            } else {
                playersText = "—";
            }
            guiGraphics.text(font, Component.literal(playersText), textX, rowY + 15, 0xFF908C7F);

            // FX11: MOTD always shown (not gated on expanded) once available,
            // clipped/truncated to the row's own available width so it never
            // overflows into the tab bar (R5: reuse vanilla's own
            // Font#plainSubstrByWidth truncation helper rather than inventing
            // manual pixel-width math).
            if (server.motd != null) {
                String motdPlain = font.plainSubstrByWidth(server.motd.getString(), textAvailableWidth);
                guiGraphics.text(font, Component.literal(motdPlain), textX, rowY + 26, 0xFF908C7F);
            }

            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                boolean connectHover = mouseX >= x + width - 100 && mouseX <= x + width - 8 && mouseY >= buttonY && mouseY <= buttonY + 18;
                guiGraphics.fill(x + width - 100, buttonY, x + width - 8, buttonY + 18, connectHover ? 0xFF64A066 : 0xFF528A54);
                guiGraphics.centeredText(font, Component.literal("Connect"), x + width - 54, buttonY + 5, 0xFFFFFFFF);
            }
            rowY += rowHeight + 4;
        }
    }

    /**
     * Batch-2 FR-BB4.2/4.3/4.4: renders up to {@link #MAX_SHOWN_AVATARS}
     * friend avatars for {@code hostPort}, right-aligned so their right edge
     * sits at {@code rightEdgeX}, plus a "+N" badge (N = count - shown) when
     * more friends are on the server than fit -- N always derives from
     * {@link FriendServerPresenceReader#friendsOnServer(String)} (the
     * authoritative count), never from the identity list's own size
     * (Decision 2/Risk 4: a benign one-tick race between the two reads must
     * never desync the badge math).
     */
    /** Batch-2-fixes FR-F4.2: package-private so {@code HomePanel} can reuse this exact avatar-row element on Recent server cards. */
    void renderFriendAvatars(GuiGraphicsExtractor guiGraphics, String hostPort, int rightEdgeX, int topY) {
        int count = friendServerPresenceReader.friendsOnServer(hostPort);
        if (count <= 0) {
            return;
        }
        List<Long> identities = friendServerPresenceReader.friendSteamIdsOnServer(hostPort);
        int shown = Math.min(MAX_SHOWN_AVATARS, identities.size());
        int overflow = count - Math.min(MAX_SHOWN_AVATARS, count);

        int cursorX = rightEdgeX;
        if (overflow > 0) {
            String badge = "+" + overflow;
            int badgeWidth = Minecraft.getInstance().font.width(badge) + 4;
            cursorX -= badgeWidth;
            guiGraphics.fill(cursorX, topY, cursorX + badgeWidth, topY + AVATAR_SIZE, 0xFF3A3A3A);
            guiGraphics.text(Minecraft.getInstance().font, badge, cursorX + 2, topY + 3, 0xFFEAE8E1);
            cursorX -= AVATAR_GAP;
        }
        for (int i = shown - 1; i >= 0; i--) {
            long steamId64 = identities.get(i);
            cursorX -= AVATAR_SIZE;
            Identifier avatarTexture = avatarTextureCache.getOrUpload(steamId64,
                    friendsSidebarFacade.avatarRgba(steamId64).orElse(null));
            if (avatarTexture != null) {
                int size = AvatarTextureCache.SIZE;
                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, avatarTexture, cursorX, topY, 0f, 0f,
                        AVATAR_SIZE, AVATAR_SIZE, size, size, size, size);
            } else {
                guiGraphics.fill(cursorX, topY, cursorX + AVATAR_SIZE, topY + AVATAR_SIZE, 0xFF528A54);
            }
            cursorX -= AVATAR_GAP;
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

    private void renderBrowser(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        if (!steamAvailable) {
            guiGraphics.centeredText(font,
                    Component.literal("Steam not available - make sure Steam is running and try again."),
                    x + width / 2, y + height / 2, 0xFFFFFFFF);
            return;
        }

        int leftX = x + CONTENT_LEFT_PAD;
        int headerY = y + 24;
        // FR-B3.6: 4-column sortable header (Lock/Name/Players/Latency) --
        // the lock/password glyph previously had no corresponding header.
        drawColumnHeader(guiGraphics, font, "🔒", leftX + 4, headerY, ServerBrowserColumn.PASSWORD);
        drawColumnHeader(guiGraphics, font, "Name", leftX + 24, headerY, ServerBrowserColumn.NAME);
        drawColumnHeader(guiGraphics, font, "Players", x + width - 180, headerY, ServerBrowserColumn.PLAYERS);
        drawColumnHeader(guiGraphics, font, "Latency", x + width - 100, headerY, ServerBrowserColumn.PING);

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

        guiGraphics.enableScissor(x, viewportTop, x + width, viewportBottom);
        int rowY = viewportTop - browseScrollOffset;
        for (ServerBrowserRow row : browserRows) {
            if (rowY + ROW_HEIGHT_COMPACT > viewportTop && rowY < viewportBottom) {
                boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT_COMPACT
                        && mouseY >= viewportTop && mouseY <= viewportBottom;
                guiGraphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT_COMPACT, hovered ? 0xFF2A2820 : 0xFF201E17);
                if (row.hasPassword()) {
                    guiGraphics.text(font, Component.literal("🔒"), leftX + 4, rowY + 7, 0xFF908C7F);
                }
                guiGraphics.text(font, Component.literal(row.serverName()), leftX + 24, rowY + 7, row.respondedSuccessfully() ? 0xFFEAE8E1 : 0xFF605C50);
                // Batch-2 FR-BB4.2/4.3: right-aligned within the Name column
                // (which has substantial unused width at typical panel
                // sizes) so it never crowds the Players/Latency columns'
                // own fixed-width text (spec UI section's flagged tight-
                // layout concern for this panel).
                renderFriendAvatars(guiGraphics, row.address(), x + width - 184, rowY + 6);
                guiGraphics.text(font, Component.literal(row.players() + "/" + row.maxPlayers()), x + width - 180, rowY + 7, 0xFF908C7F);
                guiGraphics.text(font, Component.literal(row.ping() + "ms"), x + width - 100, rowY + 7, pingStatusColor(row.ping()));
            }
            rowY += rowStride;
        }
        guiGraphics.disableScissor();

        if (browserSession != null && browserSession.isRefreshing()) {
            guiGraphics.text(font, Component.literal("Refreshing..."), x + width - 260, headerY, 0xFF908C7F);
        } else if (browserRefreshEverCompleted && browserRows.isEmpty()) {
            guiGraphics.centeredText(font, Component.literal("No servers found."), x + width / 2, viewportTop + 12, 0xFF908C7F);
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

    private void drawColumnHeader(GuiGraphicsExtractor guiGraphics, Font font, String label, int x, int y, ServerBrowserColumn column) {
        boolean sorted = browserSession != null && browserSession.sortColumn() == column;
        String text = sorted ? label + (browserSession.sortAscending() ? " ▲" : " ▼") : label;
        guiGraphics.text(font, Component.literal(text), x, y, sorted ? 0xFFC9A227 : 0xFFEAE8E1);
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
            ServerData server = savedServers.get(i);
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
        int leftX = x + CONTENT_LEFT_PAD;
        int headerY = y + 24;
        if (mouseY >= headerY - 12 && mouseY < headerY + 4) {
            if (mouseX >= leftX + 4 && mouseX < leftX + 24) {
                MainMenuScreen.playClickSound();
                browserSession.setSortColumn(ServerBrowserColumn.PASSWORD);
                return true;
            } else if (mouseX >= leftX + 24 && mouseX < x + width - 200) {
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
            Minecraft.getInstance().setScreenAndShow(new ServerBrowserPasswordPromptScreen(owner, row));
        } else {
            ServerAddress address = ServerAddress.parseString(row.address());
            ServerData serverData = new ServerData(row.serverName(), row.address(), ServerData.Type.OTHER);
            ConnectScreen.startConnecting(owner, Minecraft.getInstance(), address, serverData, false, null);
        }
    }

    /** Batch-2-fixes FR-F4.2: package-private so {@code HomePanel} can invoke the same real connect action a Servers row click does. */
    void connect(ServerData server) {
        ServerAddress address = ServerAddress.parseString(server.ip);
        ConnectScreen.startConnecting(owner, Minecraft.getInstance(), address, server, false, null);
    }

    /**
     * Batch-2-fixes FR-F4.2/Decision 3: vanilla {@code ServerData} has no
     * last-connected timestamp, so this returns every saved server in the
     * saved list's own existing order (recency of the user's own list
     * management, the documented fallback -- see {@code HomePanel}'s own
     * Javadoc and the batch-2-fixes plan's Decision 3).
     */
    List<ServerData> recentServers() {
        List<ServerData> result = new java.util.ArrayList<>();
        for (int i = 0; i < savedServers.size(); i++) {
            result.add(savedServers.get(i));
        }
        return result;
    }
}

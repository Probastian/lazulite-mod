package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.MainMenuTab;
import de.lazuli.api.richpresence.RichPresenceFacade;
import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.api.serverjoinpresence.FriendServerPresenceReader;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.features.mainmenu.config.WardrobeConfig;
import de.lazuli.features.mainmenu.services.MainMenuStateMachine;
import de.lazuli.features.mainmenu.services.StoreCatalog;
import de.lazuli.api.friends.FriendSummary;
import de.lazuli.friends.AvatarTextureCache;
import de.lazuli.friends.FriendContextMenuWidget;
import de.lazuli.friends.FriendSidebarWidget;
import de.lazuli.services.steamworks.SteamAchievementsGateway;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * The "Stonebound" main menu (specification Overview/FR1-FR2): the client's
 * new title screen, replacing vanilla {@code TitleScreen} outright.
 *
 * <p>Composes {@link MainMenuBackgroundRenderer} (continuous 3D background,
 * FR8), a right-hand vertical tab bar (FR2), the Worlds tab panel (FR3, the
 * only fully-built panel this batch -- Servers/Store/Wardrobe are stubbed
 * "Coming soon" placeholders per this batch's own scope), and the right
 * sidebar (FR7).
 *
 * <p><strong>Sidebar reuse (Decision 2, FR7.6):</strong> rather than
 * extracting a brand-new {@code FriendsSidebarRenderer} helper class,
 * {@link FriendSidebarWidget} (the platform's existing per-frame sidebar
 * rendering, {@code de.lazuli.friends}) already <em>is</em> a self-contained,
 * reusable {@code AbstractWidget} -- it takes its own facade/avatar-cache/
 * click-listener via its constructor and does not depend on any specific host
 * {@code Screen} type. This screen therefore constructs and adds a second
 * {@link FriendSidebarWidget} instance directly (the same class
 * {@code FabricFriendsSidebarInjector} adds to every other allow-listed
 * vanilla screen), which already satisfies Decision 2's "extracted, reusable
 * helper called by both consumers" goal without needing a new file -- see
 * this batch's own report for the full reasoning.
 *
 * <p>All top-level state resets on construction (FR1.3): a fresh
 * {@link MainMenuStateMachine} is always constructed here, never reused
 * across screen instances.
 */
public final class MainMenuScreen extends Screen {

    private static final int TAB_BAR_WIDTH = 108;
    private static final MainMenuTab[] TABS = MainMenuTab.values();

    private final MainMenuStateMachine state = new MainMenuStateMachine(MainMenuTab.HOME);
    private final MainMenuBackgroundRenderer background;
    private final WorldsPanel worldsPanel;
    private final ServersPanel serversPanel;
    private final StorePanel storePanel;
    private final WardrobePanel wardrobePanel;
    private final HomePanel homePanel;
    private final AchievementsPanel achievementsPanel;
    private final StatisticsPanel statisticsPanel;
    private final FriendSidebarWidget sidebar;
    private final FriendsSidebarFacade friendsSidebarFacade;
    private FriendContextMenuWidget openMenu;

    /**
     * Full constructor (Sequencing steps 9-11): wires the Servers/Store/
     * Wardrobe panels in addition to Worlds/sidebar (prior batch).
     *
     * @param onWardrobeEquipChanged write-through persistence hook (spec
     *                               FR6.3), invoked with the full equip-map
     *                               snapshot every time an item is equipped
     */
    public MainMenuScreen(MainMenuBackgroundRenderer background, FriendsSidebarFacade friendsSidebarFacade,
                           ServerBrowserSessionFactory serverBrowserSessionFactory, boolean steamAvailable,
                           StoreCatalog storeCatalog, MainMenuStoreOwnershipChecker ownershipChecker,
                           WardrobeConfig initialWardrobeConfig, RichPresenceFacade richPresenceFacade,
                           FriendServerPresenceReader friendServerPresenceReader,
                           SteamAchievementsGateway steamAchievementsGateway,
                           de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfig joinHistoryConfig,
                           Consumer<java.util.Map<de.lazuli.api.mainmenu.WardrobeSlot, String>> onWardrobeEquipChanged) {
        super(Component.literal("Stonebound"));
        this.friendsSidebarFacade = friendsSidebarFacade;
        this.background = background;
        this.worldsPanel = new WorldsPanel(state, this);
        this.serversPanel = new ServersPanel(state, this, serverBrowserSessionFactory, steamAvailable,
                friendServerPresenceReader, new AvatarTextureCache(msg -> { }), friendsSidebarFacade);
        this.storePanel = new StorePanel(storeCatalog, ownershipChecker);
        state.loadEquipped(initialWardrobeConfig.equipped());
        this.wardrobePanel = new WardrobePanel(state, storeCatalog, (slot, itemId) -> onWardrobeEquipChanged.accept(state.equipSnapshot()));
        this.sidebar = new FriendSidebarWidget(friendsSidebarFacade, new AvatarTextureCache(msg -> { }),
                (friend, mouseX, mouseY, button, isOwnProfile) -> openContextMenu(friend, mouseX, mouseY, isOwnProfile),
                false, false, richPresenceFacade);
        // Batch-2 FR-BB2.1/2.4: a second presentation surface for the same
        // friend list/context menu, not a new interaction model -- reuses
        // this screen's own openContextMenu, exactly like the sidebar does.
        this.homePanel = new HomePanel(friendsSidebarFacade, new AvatarTextureCache(msg -> { }),
                (friend, mouseX, mouseY, button, isOwnProfile) -> openContextMenu(friend, mouseX, mouseY, isOwnProfile),
                worldsPanel, serversPanel, joinHistoryConfig);
        // Batch-2-fixes Item F1: Branch A (v1 data-scope reduction, see
        // AchievementsPanel's own Javadoc) -- real Steam achievement data.
        this.achievementsPanel = new AchievementsPanel(steamAchievementsGateway);
        // Batch-2-fixes Item F5: vanilla Minecraft stats, no Steamworks involvement.
        this.statisticsPanel = new StatisticsPanel();
    }

    /**
     * Opens the friend-row context menu (mirrors
     * {@code FabricFriendsSidebarInjector#openContextMenu}, ported directly
     * since this screen is not on that injector's vanilla-screen allow-list
     * and already has direct {@link #addRenderableWidget}/{@link #removeWidget}
     * access as a {@link Screen} subclass). Steam World Hosting's join/invite
     * operations and the toast sink aren't threaded into this screen's own
     * constructor, so "Invite to game"/"Join game" stay disabled placeholders
     * here (as they already do everywhere those are unavailable); Open chat/
     * Show profile behave identically to every other host screen.
     */
    private void openContextMenu(FriendSummary friend, int mouseX, int mouseY, boolean isOwnProfile) {
        closeContextMenu();
        int menuX = Math.max(0, Math.min(mouseX - FriendContextMenuWidget.WIDTH, width - FriendContextMenuWidget.WIDTH));
        int menuY = Math.min(mouseY, height - FriendContextMenuWidget.HEIGHT);
        FriendContextMenuWidget menu = new FriendContextMenuWidget(menuX, menuY, friend, friendsSidebarFacade, this::closeContextMenu,
                isOwnProfile, null, null, null, null);
        addRenderableWidget(menu);
        openMenu = menu;
        sidebar.notifyContextMenuOpenChanged(true);
    }

    private void closeContextMenu() {
        if (openMenu != null) {
            removeWidget(openMenu);
            openMenu = null;
            sidebar.notifyContextMenuOpenChanged(false);
        }
    }

    @Override
    protected void init() {
        addRenderableWidget(sidebar);
        worldsPanel.init(this::addRenderableWidget, panelX(), panelY(), panelWidth());
        serversPanel.init(this::addRenderableWidget, panelX(), panelY(), panelWidth());
        worldsPanel.setTabActive(state.activeTab() == MainMenuTab.WORLDS);
        serversPanel.setTabActive(state.activeTab() == MainMenuTab.SERVERS);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // FX6.2/R5: the reserved left-third background+character region has
    // width-growth priority *inverted* from the panel's -- the panel is the
    // flexible region that must never go below this floor, so the reserved
    // region shrinks first at small window widths instead.
    private static final int MIN_PANEL_WIDTH = 260;
    private static final int RIGHT_MARGIN = 24;
    // Batch-2 FR-BB1.2: the sidebar now docks to the LEFT edge, no longer
    // adjacent to the tab bar (which keeps its own independent right-edge
    // dock, see barX below) -- this is the margin between the sidebar's
    // collapsed column and the reserved background+character region that
    // now starts after it.
    private static final int LEFT_MARGIN = RIGHT_MARGIN;

    private int reservedWidth() {
        int naive = width / 3;
        // Batch-2 FR-BB1.2: both the sidebar (left) and the tab bar (right)
        // now consume their own independent margin/width off of `width`,
        // since they are no longer stacked adjacently on the same edge.
        int maxAllowed = width - TAB_BAR_WIDTH - RIGHT_MARGIN - sidebarCollapsedWidth() - LEFT_MARGIN - MIN_PANEL_WIDTH;
        return Math.max(0, Math.min(naive, Math.max(0, maxAllowed)));
    }

    private int panelX() {
        // Batch-2 FR-BB1.2: the reserved background+character region (and
        // therefore the panel after it) now starts past the left-docked
        // sidebar's own collapsed width + margin, not at 0.
        return sidebarCollapsedWidth() + LEFT_MARGIN + reservedWidth();
    }

    private int panelY() {
        return RIGHT_MARGIN;
    }

    private int panelWidth() {
        // Batch-2 FR-BB1.2: the tab bar's own right-edge dock is no longer
        // adjacent to the sidebar (that space is already reserved by
        // panelX()), so only the tab bar + its own margin are subtracted here.
        return Math.max(MIN_PANEL_WIDTH, width - panelX() - TAB_BAR_WIDTH - RIGHT_MARGIN);
    }

    private int panelHeight() {
        return height - 2 * panelY();
    }

    private int sidebarCollapsedWidth() {
        // Was a guessed 84px, leaving a large gap before the tab bar since
        // FriendSidebarWidget's real collapsed width is much smaller.
        return FriendSidebarWidget.collapsedWidth();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // The 3D background renders continuously regardless of tab state
        // (FR1.4/FR8.1) -- drawn first so every 2D element below layers on
        // top of it. See MainMenuBackgroundRenderer's own Javadoc for why no
        // manual matrix push/pop is needed around this call.
        background.render(guiGraphics, width, height, sidebarCollapsedWidth() + LEFT_MARGIN, reservedWidth());

        renderTabBar(guiGraphics, mouseX, mouseY);

        MainMenuTab active = state.activeTab();
        if (active != null) {
            int x = panelX();
            int y = panelY();
            int w = panelWidth();
            int h = panelHeight();
            // FX6.5: the panel's translucent fill must not bleed left of
            // panelX() into the reserved background+character region.
            guiGraphics.fill(x, y - 12, x + w + 12, y + h + 12, 0x8C312E22);
            switch (active) {
                case WORLDS -> worldsPanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case SERVERS -> serversPanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case STORE -> storePanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case WARDROBE -> wardrobePanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case HOME -> homePanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case ACHIEVEMENTS -> achievementsPanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case STATISTICS -> statisticsPanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
            }
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);

        // FX14: MainMenuScreen is a custom screen, not on
        // FabricFriendsSidebarInjector's vanilla-screen allow-list, so the
        // injector's ScreenEvents.afterExtract hook never fires for it --
        // renderNow() must be invoked explicitly here instead. Deliberately
        // NOT adding this screen to the injector's allow-list (would double-render).
        sidebar.renderNow(guiGraphics, mouseX, mouseY, delta);
        sidebar.renderDropdownOverlay(guiGraphics, mouseX, mouseY, delta);
        if (openMenu != null) {
            openMenu.renderNow(guiGraphics, mouseX, mouseY, delta);
        }
    }

    /** FX9: the standard vanilla UI click sound for every hand-hit-tested custom control in this class. */
    static void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
    }

    private void renderTabBar(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        // Batch-2 FR-BB1.2: the tab bar keeps its own right-edge dock,
        // independent of the (now left-docked) sidebar.
        int barX = width - TAB_BAR_WIDTH;
        int barY = 0;
        int barHeight = height;
        guiGraphics.fill(barX, barY, barX + TAB_BAR_WIDTH, barY + barHeight, 0xB31D1B12);

        int buttonHeight = 56;
        int spacing = 8;
        int startY = height / 2 - (TABS.length * (buttonHeight + spacing)) / 2;
        for (int i = 0; i < TABS.length; i++) {
            MainMenuTab tab = TABS[i];
            int y = startY + i * (buttonHeight + spacing);
            boolean active = tab == state.activeTab();
            boolean hovered = mouseX >= barX && mouseX <= barX + TAB_BAR_WIDTH && mouseY >= y && mouseY <= y + buttonHeight;
            int bg = active ? 0x8C312E22 : (hovered ? 0x99312E22 : 0x00000000);
            int accent = active ? 0xFF528A54 : 0x00000000;
            guiGraphics.fill(barX, y, barX + TAB_BAR_WIDTH, y + buttonHeight, bg);
            guiGraphics.fill(barX, y, barX + 3, y + buttonHeight, accent);
            int labelColor = active ? 0xFFEAE8E1 : 0xFF938F82;
            guiGraphics.centeredText(font, Component.literal(tabLabel(tab)), barX + TAB_BAR_WIDTH / 2, y + buttonHeight / 2 - 4, labelColor);
        }
    }

    private static String tabLabel(MainMenuTab tab) {
        return switch (tab) {
            case WORLDS -> "Worlds";
            case SERVERS -> "Servers";
            case STORE -> "Store";
            case WARDROBE -> "Wardrobe";
            case HOME -> "Home";
            case ACHIEVEMENTS -> "Achievements";
            case STATISTICS -> "Statistics";
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Mirrors FabricFriendsSidebarInjector#onBeforeMouseClick/onAllowMouseClick:
        // the open context menu always takes priority over every other widget,
        // and a click outside it dismisses it before any other dispatch runs.
        if (openMenu != null) {
            if (!openMenu.containsPoint(event.x(), event.y())) {
                closeContextMenu();
            } else {
                return openMenu.mouseClicked(event, doubleClick);
            }
        }

        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

        // Batch-2 FR-BB1.2: same right-edge-only dock as renderTabBar().
        int barX = width - TAB_BAR_WIDTH;
        int buttonHeight = 56;
        int spacing = 8;
        int startY = height / 2 - (TABS.length * (buttonHeight + spacing)) / 2;
        for (int i = 0; i < TABS.length; i++) {
            int y = startY + i * (buttonHeight + spacing);
            if (event.x() >= barX && event.x() <= barX + TAB_BAR_WIDTH && event.y() >= y && event.y() <= y + buttonHeight) {
                playClickSound();
                state.selectTab(TABS[i]);
                worldsPanel.setTabActive(state.activeTab() == MainMenuTab.WORLDS);
                serversPanel.setTabActive(state.activeTab() == MainMenuTab.SERVERS);
                return true;
            }
        }

        int h = panelHeight();
        MainMenuTab active = state.activeTab();
        if (active == MainMenuTab.WORLDS) {
            if (worldsPanel.mouseClicked(panelX(), panelY(), panelWidth(), h, event.x(), event.y())) {
                return true;
            }
        } else if (active == MainMenuTab.SERVERS) {
            if (serversPanel.mouseClicked(panelX(), panelY(), panelWidth(), h, event.x(), event.y())) {
                return true;
            }
        } else if (active == MainMenuTab.STORE) {
            if (storePanel.mouseClicked(panelX(), panelY(), panelWidth(), h, event.x(), event.y())) {
                return true;
            }
        } else if (active == MainMenuTab.WARDROBE) {
            if (wardrobePanel.mouseClicked(panelX(), panelY(), panelWidth(), h, event.x(), event.y())) {
                return true;
            }
        } else if (active == MainMenuTab.HOME) {
            if (homePanel.mouseClicked(panelX(), panelY(), panelWidth(), h, event.x(), event.y(), event.button())) {
                return true;
            }
        } else if (active == MainMenuTab.ACHIEVEMENTS) {
            if (achievementsPanel.mouseClicked(panelX(), panelY(), panelWidth(), h, event.x(), event.y())) {
                return true;
            }
        } else if (active == MainMenuTab.STATISTICS) {
            if (statisticsPanel.mouseClicked(panelX(), panelY(), panelWidth(), h, event.x(), event.y())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (openMenu != null && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            closeContextMenu();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        // FX10.2: forward scroll input to the Servers panel only -- Worlds/
        // Store/Wardrobe don't need it in this batch (see plan's note on
        // flagging, not silently expanding scope, if that changes).
        int h = panelHeight();
        if (state.activeTab() == MainMenuTab.SERVERS) {
            return serversPanel.mouseScrolled(panelX(), panelY(), panelWidth(), h, mouseX, mouseY, verticalAmount);
        } else if (state.activeTab() == MainMenuTab.STATISTICS) {
            return statisticsPanel.mouseScrolled(panelX(), panelY(), panelWidth(), h, mouseX, mouseY, verticalAmount);
        }
        return false;
    }

    @Override
    public void onClose() {
        serversPanel.deactivateBrowser();
        super.onClose();
    }

    @Override
    public void removed() {
        serversPanel.deactivateBrowser();
        super.removed();
    }
}

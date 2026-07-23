package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.MainMenuTab;
import de.lazuli.api.richpresence.RichPresenceFacade;
import de.lazuli.api.serverbrowser.ServerBrowserSessionFactory;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.features.mainmenu.config.WardrobeConfig;
import de.lazuli.features.mainmenu.services.MainMenuStateMachine;
import de.lazuli.features.mainmenu.services.StoreCatalog;
import de.lazuli.friends.AvatarTextureCache;
import de.lazuli.friends.FriendSidebarWidget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

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

    private final MainMenuStateMachine state = new MainMenuStateMachine();
    private final MainMenuBackgroundRenderer background;
    private final WorldsPanel worldsPanel;
    private final ServersPanel serversPanel;
    private final StorePanel storePanel;
    private final WardrobePanel wardrobePanel;
    private final FriendSidebarWidget sidebar;

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
                           Consumer<java.util.Map<de.lazuli.api.mainmenu.WardrobeSlot, String>> onWardrobeEquipChanged) {
        super(Component.literal("Stonebound"));
        this.background = background;
        this.worldsPanel = new WorldsPanel(state, this);
        this.serversPanel = new ServersPanel(state, this, serverBrowserSessionFactory, steamAvailable);
        this.storePanel = new StorePanel(storeCatalog, ownershipChecker);
        state.loadEquipped(initialWardrobeConfig.equipped());
        this.wardrobePanel = new WardrobePanel(state, storeCatalog, (slot, itemId) -> onWardrobeEquipChanged.accept(state.equipSnapshot()));
        this.sidebar = new FriendSidebarWidget(friendsSidebarFacade, new AvatarTextureCache(msg -> { }),
                (friend, mouseX, mouseY, button, isOwnProfile) -> { }, false, false, richPresenceFacade);
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

    private int reservedWidth() {
        int naive = width / 3;
        int maxAllowed = width - TAB_BAR_WIDTH - sidebarCollapsedWidth() - RIGHT_MARGIN - MIN_PANEL_WIDTH;
        return Math.max(0, Math.min(naive, Math.max(0, maxAllowed)));
    }

    private int panelX() {
        return reservedWidth();
    }

    private int panelY() {
        return (int) (height * 0.22);
    }

    private int panelWidth() {
        return Math.max(MIN_PANEL_WIDTH, width - panelX() - TAB_BAR_WIDTH - sidebarCollapsedWidth() - RIGHT_MARGIN);
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
        background.render(guiGraphics, width, height, reservedWidth());

        renderTabBar(guiGraphics, mouseX, mouseY);

        MainMenuTab active = state.activeTab();
        if (active != null) {
            int x = panelX();
            int y = panelY();
            int w = panelWidth();
            int h = (int) (height * 0.62);
            // FX6.5: the panel's translucent fill must not bleed left of
            // panelX() into the reserved background+character region.
            guiGraphics.fill(x, y - 12, x + w + 12, y + h + 12, 0x8C312E22);
            switch (active) {
                case WORLDS -> worldsPanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case SERVERS -> serversPanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case STORE -> storePanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
                case WARDROBE -> wardrobePanel.render(guiGraphics, font, x, y, w, h, mouseX, mouseY);
            }
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);

        // FX14: MainMenuScreen is a custom screen, not on
        // FabricFriendsSidebarInjector's vanilla-screen allow-list, so the
        // injector's ScreenEvents.afterExtract hook never fires for it --
        // renderNow() must be invoked explicitly here instead. Deliberately
        // NOT adding this screen to the injector's allow-list (would double-render).
        sidebar.renderNow(guiGraphics, mouseX, mouseY, delta);
    }

    /** FX9: the standard vanilla UI click sound for every hand-hit-tested custom control in this class. */
    static void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
    }

    private void renderTabBar(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        int barX = width - TAB_BAR_WIDTH - sidebarCollapsedWidth();
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
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

        int barX = width - TAB_BAR_WIDTH - sidebarCollapsedWidth();
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

        int h = (int) (height * 0.62);
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
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        // FX10.2: forward scroll input to the Servers panel only -- Worlds/
        // Store/Wardrobe don't need it in this batch (see plan's note on
        // flagging, not silently expanding scope, if that changes).
        int h = (int) (height * 0.62);
        if (state.activeTab() == MainMenuTab.SERVERS) {
            return serversPanel.mouseScrolled(panelX(), panelY(), panelWidth(), h, mouseX, mouseY, verticalAmount);
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

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

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * The "Stonebound" main menu (specification Overview/FR1-FR2): the client's
 * new title screen, replacing vanilla {@code TitleScreen} outright --
 * {@code fabric-1.21.11} (Yarn-mapped, obfuscated) port of the
 * {@code fabric-26.1}/{@code fabric-26.2} class of the same name.
 *
 * <p>Composes {@link MainMenuBackgroundRenderer} (continuous 3D background,
 * FR8), a right-hand vertical tab bar (FR2), the four tab panels (FR3-FR6),
 * and the right sidebar (FR7).
 *
 * <p><strong>Sidebar reuse (Decision 2, FR7.6):</strong> {@link FriendSidebarWidget}
 * is this module's existing, already-reusable per-frame sidebar rendering
 * (self-contained {@code ClickableWidget}, takes its own facade/avatar-cache/
 * click-listener) -- this screen adds a second instance directly, the same
 * class {@code FabricFriendsSidebarInjector} adds to every other allow-listed
 * vanilla screen. Its {@code renderWidget(...)} is deliberately empty (drawn
 * manually via {@code renderNow(...)} so it always paints on top); this
 * screen calls {@code renderNow}/{@code renderDropdownOverlay} itself, at the
 * end of its own {@link #render}, then flushes any queued tooltip via
 * {@code context.drawDeferredElements()} -- mirroring
 * {@code FabricFriendsSidebarInjector#onAfterRender}'s own documented reason
 * for that ordering. Consistent with the {@code fabric-26.1}/{@code fabric-26.2}
 * scope for this batch, the friend-row context menu is not wired here (a
 * no-op row-click listener is passed) -- right-click actions on a friend row
 * remain available on every other allow-listed vanilla screen via the
 * existing injector.
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
     * @param onWardrobeEquipChanged write-through persistence hook (spec
     *                               FR6.3), invoked with the full equip-map
     *                               snapshot every time an item is equipped
     */
    public MainMenuScreen(MainMenuBackgroundRenderer background, FriendsSidebarFacade friendsSidebarFacade,
                           ServerBrowserSessionFactory serverBrowserSessionFactory, boolean steamAvailable,
                           StoreCatalog storeCatalog, MainMenuStoreOwnershipChecker ownershipChecker,
                           WardrobeConfig initialWardrobeConfig, RichPresenceFacade richPresenceFacade,
                           Consumer<java.util.Map<de.lazuli.api.mainmenu.WardrobeSlot, String>> onWardrobeEquipChanged) {
        super(Text.literal("Stonebound"));
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
        addDrawableChild(sidebar);
        worldsPanel.init(this::addDrawableChild, panelX(), panelY(), panelWidth());
        serversPanel.init(this::addDrawableChild, panelX(), panelY(), panelWidth());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int panelX() {
        return 24;
    }

    private int panelY() {
        return (int) (height * 0.22);
    }

    private int panelWidth() {
        return width - TAB_BAR_WIDTH - 48 - sidebarCollapsedWidth();
    }

    private int sidebarCollapsedWidth() {
        return 84;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // The 3D background renders continuously regardless of tab state
        // (FR1.4/FR8.1) -- drawn first so every 2D element below layers on
        // top of it.
        background.render(context, width, height);

        renderTitle(context);
        renderTabBar(context, mouseX, mouseY);

        MainMenuTab active = state.activeTab();
        if (active != null) {
            int x = panelX();
            int y = panelY();
            int w = panelWidth();
            int h = (int) (height * 0.62);
            context.fill(x - 12, y - 12, x + w + 12, y + h + 12, 0x8C312E22);
            switch (active) {
                case WORLDS -> worldsPanel.render(context, textRenderer, x, y, w, h, mouseX, mouseY);
                case SERVERS -> serversPanel.render(context, textRenderer, x, y, w, h, mouseX, mouseY);
                case STORE -> storePanel.render(context, textRenderer, x, y, w, h, mouseX, mouseY);
                case WARDROBE -> wardrobePanel.render(context, textRenderer, x, y, w, h, mouseX, mouseY);
            }
        }

        super.render(context, mouseX, mouseY, delta);

        // Sidebar is drawn manually, last, so it always paints on top of every
        // other element above (Decision 2, this class's own Javadoc).
        sidebar.renderNow(context, mouseX, mouseY, delta);
        sidebar.renderDropdownOverlay(context, mouseX, mouseY, delta);
        context.drawDeferredElements();
    }

    private void renderTitle(DrawContext context) {
        double scale = height / 1080.0;
        int titleX = (int) (60 * scale);
        int titleY = (int) (48 * scale);
        context.drawText(textRenderer, Text.literal("STONEBOUND"), titleX, titleY, 0xFFEAE8E1, false);
        context.drawText(textRenderer, Text.literal("OVERHAUL MOD · V2.1"), titleX, titleY + 14, 0xFF908C7F, false);
    }

    private void renderTabBar(DrawContext context, int mouseX, int mouseY) {
        int barX = width - TAB_BAR_WIDTH - sidebarCollapsedWidth();
        int barY = 0;
        int barHeight = height;
        context.fill(barX, barY, barX + TAB_BAR_WIDTH, barY + barHeight, 0xB31D1B12);

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
            context.fill(barX, y, barX + TAB_BAR_WIDTH, y + buttonHeight, bg);
            context.fill(barX, y, barX + 3, y + buttonHeight, accent);
            int labelColor = active ? 0xFFEAE8E1 : 0xFF938F82;
            context.drawCenteredTextWithShadow(textRenderer, tabLabel(tab), barX + TAB_BAR_WIDTH / 2, y + buttonHeight / 2 - 4, labelColor);
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
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) {
            return true;
        }

        int barX = width - TAB_BAR_WIDTH - sidebarCollapsedWidth();
        int buttonHeight = 56;
        int spacing = 8;
        int startY = height / 2 - (TABS.length * (buttonHeight + spacing)) / 2;
        for (int i = 0; i < TABS.length; i++) {
            int y = startY + i * (buttonHeight + spacing);
            if (click.x() >= barX && click.x() <= barX + TAB_BAR_WIDTH && click.y() >= y && click.y() <= y + buttonHeight) {
                state.selectTab(TABS[i]);
                serversPanel.setTabActive(state.activeTab() == MainMenuTab.SERVERS);
                return true;
            }
        }

        int h = (int) (height * 0.62);
        MainMenuTab active = state.activeTab();
        if (active == MainMenuTab.WORLDS) {
            if (worldsPanel.mouseClicked(panelX(), panelY(), panelWidth(), h, click.x(), click.y())) {
                return true;
            }
        } else if (active == MainMenuTab.SERVERS) {
            if (serversPanel.mouseClicked(panelX(), panelY(), panelWidth(), h, click.x(), click.y())) {
                return true;
            }
        } else if (active == MainMenuTab.STORE) {
            if (storePanel.mouseClicked(panelX(), panelY(), panelWidth(), h, click.x(), click.y())) {
                return true;
            }
        } else if (active == MainMenuTab.WARDROBE) {
            if (wardrobePanel.mouseClicked(panelX(), panelY(), panelWidth(), h, click.x(), click.y())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        serversPanel.deactivateBrowser();
        super.close();
    }

    @Override
    public void removed() {
        serversPanel.deactivateBrowser();
        super.removed();
    }
}

package de.lazuli.friends;

import de.lazuli.LazuliMod;
import de.lazuli.api.friends.FriendSummary;
import de.lazuli.api.richpresence.RichPresenceFacade;
import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.api.worldhosting.WorldInviteSender;
import de.lazuli.api.worldhosting.WorldJoinRequester;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.services.ui.ToastService;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.realms.gui.screen.RealmsMainScreen;
import net.minecraft.client.input.KeyInput;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Set;

/**
 * Version Adapter for the Friends Sidebar overlay + context menu on
 * Minecraft 1.21.11 (Yarn-mapped, obfuscated) -- implementation plan
 * Decision 1 (Pattern 1, one injector, no mixin), Decision 2's FR2.2
 * allow-list, Decision 4's context-menu dismissal.
 *
 * <p>The sidebar attaches only to specific {@code Screen}s via
 * {@link #ALLOW_LISTED_SCREENS} -- it is never rendered during ordinary
 * gameplay (no {@code Screen} open). {@code MainMenuScreen} (which replaces
 * {@code TitleScreen} outright, base spec Overview) is deliberately not in
 * this list: it hosts its own dedicated {@link FriendSidebarWidget} instance
 * directly, so adding it here would double-render.
 *
 * <p>{@link RealmsMainScreen} is included per Decision 1/2, but its
 * {@link ScreenEvents#AFTER_INIT} reachability is unconfirmed (Risk 2) -- if
 * it never fires for this screen in practice, the sidebar simply never
 * appears there (an acceptable, logged v1 exclusion, not a crash).
 *
 * <p>Usage example (from {@code FriendsSidebarClientInitializer}):
 * <pre>{@code
 * new FabricFriendsSidebarInjector(facade);
 * }</pre>
 */
public final class FabricFriendsSidebarInjector {

    private final FriendsSidebarFacade facade;
    private final AvatarTextureCache avatarTextureCache;
    private final WorldJoinRequester worldJoinRequester;
    private final FriendHostingStatusReader hostingStatusReader;
    private final WorldInviteSender worldInviteSender;
    private final ToastService toastService;
    private final RichPresenceFacade richPresenceFacade;

    // The sidebar attaches only to these specific screens, never to
    // de.lazuli.mainmenu.MainMenuScreen (which already owns its own
    // dedicated sidebar instance).
    private static final Set<Class<? extends Screen>> ALLOW_LISTED_SCREENS = Set.of(
            SelectWorldScreen.class,
            MultiplayerScreen.class,
            OptionsScreen.class,
            GameMenuScreen.class,
            RealmsMainScreen.class
    );

    private FriendContextMenuWidget openMenu;
    private Screen openMenuScreen;
    private FriendSidebarWidget activeSidebar;
    private Screen activeSidebarScreen;

    /**
     * @param worldJoinRequester  Steam World Hosting's join operation for the
     *                            reused "Join game" context-menu slot
     *                            (Decision 4), threaded into every
     *                            {@link FriendContextMenuWidget}
     * @param hostingStatusReader gate for that slot's enablement (Decision 4)
     * @param worldInviteSender   Steam World Hosting's invite operation for the
     *                            reused "Invite to game" context-menu slot
     *                            (specification-invite-to-game.md D6), threaded
     *                            into every {@link FriendContextMenuWidget}; may
     *                            be a Noop when that feature is disabled
     * @param toastService        failure-feedback sink for a failed invite send
     *                            (specification-invite-to-game.md D6)
     */
    public FabricFriendsSidebarInjector(FriendsSidebarFacade facade, WorldJoinRequester worldJoinRequester,
            FriendHostingStatusReader hostingStatusReader, WorldInviteSender worldInviteSender,
            ToastService toastService, RichPresenceFacade richPresenceFacade) {
        this.facade = facade;
        this.worldJoinRequester = worldJoinRequester;
        this.hostingStatusReader = hostingStatusReader;
        this.worldInviteSender = worldInviteSender;
        this.toastService = toastService;
        this.richPresenceFacade = richPresenceFacade;
        this.avatarTextureCache = new AvatarTextureCache(LazuliMod.LOGGER::warn);
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    private boolean isAllowListed(Screen screen) {
        for (Class<? extends Screen> allowed : ALLOW_LISTED_SCREENS) {
            if (allowed.isInstance(screen)) {
                return true;
            }
        }
        return false;
    }

    private void onScreenInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!isAllowListed(screen)) {
            return;
        }

        // Some screens (e.g. SelectWorldScreen on a list refresh/search) fire
        // AFTER_INIT more than once on the same screen instance without
        // clearing their own widget list first -- drop any sidebar we
        // previously added there so it doesn't end up duplicated/overlapping.
        if (activeSidebar != null && activeSidebarScreen != null) {
            Screens.getButtons(activeSidebarScreen).remove(activeSidebar);
        }

        // Only the main menu and pause menu get the always-visible avatar
        // strip by default; every other allow-listed screen starts as a
        // small click-to-open handle instead (FR4.11).
        boolean handleOnly = !(screen instanceof TitleScreen || screen instanceof GameMenuScreen);
        // Only MultiplayerScreen has its own top-right corner button (the
        // server-browser entry point) the sidebar/handle must avoid covering
        // -- every other handle-only screen (e.g. SelectWorldScreen) has no
        // such button and should sit flush to the top instead of reserving
        // an unused gap there.
        boolean reserveTopInset = screen instanceof MultiplayerScreen;
        FriendSidebarWidget sidebar = new FriendSidebarWidget(facade, avatarTextureCache,
                (friend, mouseX, mouseY, button, isOwnProfile) -> openContextMenu(screen, friend, mouseX, mouseY, isOwnProfile),
                handleOnly, reserveTopInset, richPresenceFacade);
        Screens.getButtons(screen).add(sidebar);
        activeSidebar = sidebar;
        activeSidebarScreen = screen;

        ScreenMouseEvents.beforeMouseClick(screen).register(this::onBeforeMouseClick);
        ScreenMouseEvents.allowMouseClick(screen).register(this::onAllowMouseClick);
        ScreenMouseEvents.allowMouseScroll(screen).register(this::onAllowMouseScroll);
        ScreenKeyboardEvents.allowKeyPress(screen).register(this::onAllowKeyPress);
        ScreenEvents.afterRender(screen).register(this::onAfterRender);
    }

    /**
     * Draws the sidebar and (if open) the context menu last, after the
     * screen's own render pass (including e.g. TitleScreen's logo), menu on
     * top of the sidebar, so neither ever renders behind other screen
     * content or each other, regardless of widget-list order.
     *
     * <p>Root cause of "native tooltip never appears" (Polish pass bug
     * fix): {@code Screen.renderWithTooltipAndSubtitles(...)} -- the method
     * {@code fabric-screen-api-v1}'s {@code GameRendererMixin} wraps to fire
     * {@code ScreenEvents.afterRender} -- itself calls, as its very last
     * step, {@code context.drawDeferredElements()}, which is what actually
     * converts any {@code drawTooltip}/{@code setTooltipForNextFrame} call
     * made so far this frame into a rendered tooltip (Yarn 1.21.11's
     * {@code DrawContext} uses the same extract-then-flush
     * {@code GuiRenderState} architecture as the Mojang-mapped 26.x
     * platforms' {@code GuiGraphicsExtractor}, despite the different method
     * names -- {@code drawTooltip} queues, it does not draw immediately).
     * Because {@code afterRender} fires only after that whole wrapped call
     * (including that last step) returns, any tooltip call made from within
     * this hook (e.g. by
     * {@link FriendSidebarWidget#renderNow}/{@code renderDropdownOverlay}
     * below) sets state that this frame's {@code drawDeferredElements}
     * already consumed -- it is never flushed, so the tooltip silently never
     * renders, every frame. Re-invoking {@code drawDeferredElements}
     * ourselves, once, after every draw call in this method that might have
     * queued a tooltip, flushes it for this same frame instead.
     */
    private void onAfterRender(Screen screen, net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY,
            float delta) {
        if (screen == activeSidebarScreen && activeSidebar != null) {
            activeSidebar.renderNow(context, mouseX, mouseY, delta);
        }
        if (screen == activeSidebarScreen && activeSidebar != null) {
            activeSidebar.renderDropdownOverlay(context, mouseX, mouseY, delta);
        }
        if (openMenu != null && screen == openMenuScreen) {
            openMenu.renderNow(context, mouseX, mouseY, delta);
        }
        context.drawDeferredElements();
    }

    /**
     * Some vanilla screens (e.g. {@code SelectWorldScreen}'s own world list)
     * consume scroll-wheel/click input broadly rather than only when the
     * mouse is exactly over their own widget bounds, so the sidebar's own
     * {@code mouseScrolled}/{@code mouseClicked} (registered as just another
     * child widget) never gets a turn once such a screen is showing.
     * Intercepting here, before vanilla's own dispatch, lets the sidebar (and
     * an open context menu) claim the event first when the mouse is actually
     * over them.
     */
    private boolean onAllowMouseScroll(Screen screen, double mouseX, double mouseY, double horizontalAmount,
            double verticalAmount) {
        if (screen == activeSidebarScreen && activeSidebar != null
                && activeSidebar.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return false;
        }
        return true;
    }

    private boolean onAllowMouseClick(Screen screen, Click click) {
        if (openMenu != null && screen == openMenuScreen && openMenu.mouseClicked(click, false)) {
            return false;
        }
        if (screen == activeSidebarScreen && activeSidebar != null && activeSidebar.mouseClicked(click, false)) {
            return false;
        }
        return true;
    }

    private void openContextMenu(Screen screen, FriendSummary friend, int mouseX, int mouseY, boolean isOwnProfile) {
        closeMenu();
        // Default to opening below-left of the cursor (the sidebar lives on
        // the right edge, so opening to the right would usually run off
        // screen) -- clamped so it never overflows either screen edge.
        int menuX = Math.max(0, Math.min(mouseX - FriendContextMenuWidget.WIDTH, screen.width - FriendContextMenuWidget.WIDTH));
        int menuY = Math.min(mouseY, screen.height - FriendContextMenuWidget.HEIGHT);
        FriendContextMenuWidget menu = new FriendContextMenuWidget(menuX, menuY, friend, facade, this::closeMenu,
                isOwnProfile, worldJoinRequester, hostingStatusReader, worldInviteSender, toastService);
        List<ClickableWidget> widgets = Screens.getButtons(screen);
        widgets.add(menu);
        openMenu = menu;
        openMenuScreen = screen;
        if (activeSidebar != null) {
            activeSidebar.notifyContextMenuOpenChanged(true);
        }
    }

    private void closeMenu() {
        if (openMenu != null && openMenuScreen != null) {
            Screens.getButtons(openMenuScreen).remove(openMenu);
        }
        openMenu = null;
        openMenuScreen = null;
        if (activeSidebar != null) {
            activeSidebar.notifyContextMenuOpenChanged(false);
        }
    }

    private void onBeforeMouseClick(Screen screen, Click click) {
        if (openMenu != null && screen == openMenuScreen && !openMenu.containsPoint(click.x(), click.y())) {
            closeMenu();
        }
    }

    private boolean onAllowKeyPress(Screen screen, KeyInput input) {
        if (openMenu != null && screen == openMenuScreen && input.key() == GLFW.GLFW_KEY_ESCAPE) {
            closeMenu();
            return false;
        }
        return true;
    }
}

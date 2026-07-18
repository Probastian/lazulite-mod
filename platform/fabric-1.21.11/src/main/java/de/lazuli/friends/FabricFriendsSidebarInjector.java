package de.lazuli.friends;

import de.lazuli.LazuliMod;
import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;

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

/**
 * Version Adapter for the Friends Sidebar overlay + context menu on
 * Minecraft 1.21.11 (Yarn-mapped, obfuscated) -- implementation plan
 * Decision 1 (Pattern 1, one injector, no mixin), Decision 2's FR2.2
 * allow-list, Decision 4's context-menu dismissal.
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

    private FriendContextMenuWidget openMenu;
    private Screen openMenuScreen;

    public FabricFriendsSidebarInjector(FriendsSidebarFacade facade) {
        this.facade = facade;
        this.avatarTextureCache = new AvatarTextureCache(LazuliMod.LOGGER::warn);
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    private boolean isAllowListed(Screen screen) {
        return screen instanceof TitleScreen
                || screen instanceof SelectWorldScreen
                || screen instanceof MultiplayerScreen
                || screen instanceof OptionsScreen
                || screen instanceof GameMenuScreen
                || screen instanceof RealmsMainScreen;
    }

    private void onScreenInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!isAllowListed(screen)) {
            return;
        }

        FriendSidebarWidget sidebar = new FriendSidebarWidget(6, 6, facade, avatarTextureCache,
                (friend, mouseX, mouseY, button) -> openContextMenu(screen, friend, mouseX, mouseY));
        Screens.getButtons(screen).add(sidebar);

        ScreenMouseEvents.beforeMouseClick(screen).register(this::onBeforeMouseClick);
        ScreenKeyboardEvents.allowKeyPress(screen).register(this::onAllowKeyPress);
    }

    private void openContextMenu(Screen screen, FriendSummary friend, int mouseX, int mouseY) {
        closeMenu();
        FriendContextMenuWidget menu = new FriendContextMenuWidget(mouseX, mouseY, friend, facade, this::closeMenu);
        List<ClickableWidget> widgets = Screens.getButtons(screen);
        widgets.add(menu);
        openMenu = menu;
        openMenuScreen = screen;
    }

    private void closeMenu() {
        if (openMenu != null && openMenuScreen != null) {
            Screens.getButtons(openMenuScreen).remove(openMenu);
        }
        openMenu = null;
        openMenuScreen = null;
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

package de.lazuli.friends;

import de.lazuli.LazuliMod;
import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import com.mojang.realmsclient.RealmsMainScreen;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Version Adapter for the Friends Sidebar overlay + context menu on
 * Minecraft 26.2 (Mojang-mapped, unobfuscated) -- implementation plan
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
                || screen instanceof JoinMultiplayerScreen
                || screen instanceof OptionsScreen
                || screen instanceof PauseScreen
                || screen instanceof RealmsMainScreen;
    }

    private void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!isAllowListed(screen)) {
            return;
        }

        FriendSidebarWidget sidebar = new FriendSidebarWidget(6, 6, facade, avatarTextureCache,
                (friend, mouseX, mouseY, button) -> openContextMenu(screen, friend, mouseX, mouseY));
        Screens.getWidgets(screen).add(sidebar);

        ScreenMouseEvents.beforeMouseClick(screen).register(this::onBeforeMouseClick);
        ScreenKeyboardEvents.allowKeyPress(screen).register(this::onAllowKeyPress);
    }

    private void openContextMenu(Screen screen, FriendSummary friend, int mouseX, int mouseY) {
        closeMenu();
        FriendContextMenuWidget menu = new FriendContextMenuWidget(mouseX, mouseY, friend, facade, this::closeMenu);
        List<AbstractWidget> widgets = Screens.getWidgets(screen);
        widgets.add(menu);
        openMenu = menu;
        openMenuScreen = screen;
    }

    private void closeMenu() {
        if (openMenu != null && openMenuScreen != null) {
            Screens.getWidgets(openMenuScreen).remove(openMenu);
        }
        openMenu = null;
        openMenuScreen = null;
    }

    private void onBeforeMouseClick(Screen screen, MouseButtonEvent event) {
        if (openMenu != null && screen == openMenuScreen && !openMenu.containsPoint(event.x(), event.y())) {
            closeMenu();
        }
    }

    private boolean onAllowKeyPress(Screen screen, net.minecraft.client.input.KeyEvent event) {
        if (openMenu != null && screen == openMenuScreen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            closeMenu();
            return false;
        }
        return true;
    }
}

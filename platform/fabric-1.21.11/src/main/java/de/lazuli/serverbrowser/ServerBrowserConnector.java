package de.lazuli.serverbrowser;

import de.lazuli.api.serverbrowser.ServerBrowserRow;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * FR4.1's vanilla connect hand-off on Minecraft 1.21.11 (Yarn-mapped) --
 * {@code javap}-confirmed signatures reused verbatim from
 * {@code .claude/context/minecraft.md} row 76, no reimplementation of
 * connection/handshake logic. Used identically for an unprotected row and
 * for a password-protected row after {@link ServerBrowserPasswordPromptScreen}'s
 * "Join" (FR4.3's v1 stub -- no branching by password state at connect time).
 *
 * <p>Usage example:
 * <pre>{@code
 * ServerBrowserConnector.connect(currentScreen, row);
 * }</pre>
 */
final class ServerBrowserConnector {

    private ServerBrowserConnector() {
    }

    static void connect(Screen currentScreen, ServerBrowserRow row) {
        ServerInfo serverInfo = new ServerInfo(row.serverName(), row.address(), ServerInfo.ServerType.OTHER);
        ServerAddress address = ServerAddress.parse(row.address());
        ConnectScreen.connect(currentScreen, MinecraftClient.getInstance(), address, serverInfo, false, null);
    }
}

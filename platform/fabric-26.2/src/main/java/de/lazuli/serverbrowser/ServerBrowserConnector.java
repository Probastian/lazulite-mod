package de.lazuli.serverbrowser;

import de.lazuli.api.serverbrowser.ServerBrowserRow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * FR4.1's vanilla connect hand-off on Minecraft 26.2 (Mojang-mapped) --
 * {@code javap}-confirmed signatures reused verbatim from
 * {@code .claude/context/minecraft.md} row 76 (this repo's own
 * {@code steam-world-hosting} research), no reimplementation of connection/
 * handshake logic. Used identically for an unprotected row and for a
 * password-protected row after {@link ServerBrowserPasswordPromptScreen}'s
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
        ServerData serverData = new ServerData(row.serverName(), row.address(), ServerData.Type.OTHER);
        ServerAddress address = ServerAddress.parseString(row.address());
        ConnectScreen.startConnecting(currentScreen, Minecraft.getInstance(), address, serverData, false, null);
    }
}

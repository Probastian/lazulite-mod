package de.lazuli.serverjoinpresence;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * The client-side "connect to this real multiplayer server" operation (spec
 * FR2.1/FR2.2, plan Decision 5) -- {@code fabric-1.21.11} (Yarn-mapped)
 * equivalent of the 26.1/26.2 class of the same name. Reuses vanilla's own
 * {@code ConnectScreen}/{@code ClientConnection.connect(...)} flow exactly as
 * a normal saved-server/server-browser row click already does
 * ({@code de.lazuli.mainmenu.ServersPanel}'s own reused pattern) -- confirmed
 * class/method shape per {@code .claude/context/minecraft.md}'s
 * "Integrated-server Netty/login networking stack" row.
 *
 * <p>Invoked with no {@code Screen} owner available (a Steam callback
 * context, not a button click) -- {@code null} is passed for the owner
 * screen (see implementation plan Risk 3).
 */
public final class ServerJoinOperation {

    public static final ServerJoinOperation INSTANCE = new ServerJoinOperation();

    private ServerJoinOperation() {
    }

    /**
     * Connects the local client to the given real multiplayer server, exactly
     * as if the player had entered this address via Direct Connect.
     *
     * @param host the server's hostname/IP
     * @param port the server's port
     */
    public void connectToServer(String host, int port) {
        MinecraftClient client = MinecraftClient.getInstance();
        String raw = host + ":" + port;
        ServerAddress address = ServerAddress.parse(raw);
        ServerInfo serverInfo = new ServerInfo(host, raw, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(null, client, address, serverInfo, false, null);
    }
}
